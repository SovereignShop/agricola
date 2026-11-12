"""Offline protocol tests; no real credentials or plan usage."""
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import tempfile
import threading
import time
from types import SimpleNamespace
import unittest
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit

from cryptography.hazmat.primitives.asymmetric import rsa
import jwt
import requests

from connect import Connections, ConnectionError, OpenAI, ISSUER, SCOPES, handler_for


class Provider(OpenAI):
    def __init__(self):
        self.key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        self.jwks = SimpleNamespace(get_signing_key_from_jwt=lambda _: SimpleNamespace(key=self.key.public_key()))
        self.claims = {}
        self.calls = []
        self.probe_calls = 0
        self.scopes = SCOPES
        self.revoked = True

    def discovery(self):
        return {"authorization_endpoint": ISSUER + "/api/accounts/authorize"}

    def token(self, fields):
        self.calls.append(dict(fields))
        if fields["grant_type"] == "refresh_token":
            return {"access_token": "renewed-secret", "refresh_token": "rotated-secret", "expires_in": 3600}
        return {"access_token": "access-secret", "refresh_token": "refresh-secret", "expires_in": 3600,
                "scope": self.scopes, "id_token": jwt.encode(self.claims, self.key, algorithm="RS256")}

    def models(self, token):
        return [{"slug": "test-model", "name": "Test model"}]

    def probe(self, token, model):
        self.probe_calls += 1
        return "Connection works."

    def revoke(self, profile):
        return self.revoked


class ConnectionTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.provider = Provider()
        self.connections = Connections(self.directory.name, self.provider)

    def tearDown(self):
        self.connections.close()
        self.directory.cleanup()

    def start(self, profile=None, browser="browser"):
        url = self.connections.begin(browser, "http://127.0.0.1:1455/auth/callback", profile)
        params = {k: v[0] for k, v in parse_qs(urlsplit(url).query).items()}
        self.provider.claims = {"iss": ISSUER, "sub": "account-1", "aud": "oaiapp_test",
                                "iat": int(time.time()), "exp": int(time.time()) + 3600,
                                "nonce": params["nonce"], "email": "test@example.invalid"}
        return {"state": params["state"], "code": "one-time-code", "client_id": "oaiapp_test"}, params

    def connect(self):
        callback, _ = self.start()
        return self.connections.finish("browser", callback)

    def test_pkce_registration_and_no_credentials_in_ui(self):
        callback, params = self.start()
        self.assertEqual("dynamic_agent_client", params["client_id"])
        self.assertEqual("S256", params["code_challenge_method"])
        profile = self.connections.finish("browser", callback)
        token_call = self.provider.calls[0]
        self.assertEqual("oaiapp_test", token_call["client_id"])
        self.assertEqual(params["redirect_uri"], token_call["redirect_uri"])
        self.assertNotIn("client_secret", token_call)
        self.assertEqual("account-1", self.connections.profile(profile)["subject"])
        self.assertEqual(0o600, self.connections.file.stat().st_mode & 0o777)
        self.assertEqual(0o700, Path(self.directory.name).stat().st_mode & 0o777)
        with self.assertRaises(ConnectionError):
            self.connections.finish("browser", callback)

    def test_wrong_browser_state_expiry_and_decline(self):
        callback, _ = self.start()
        with self.assertRaises(ConnectionError):
            self.connections.finish("another-browser", callback)
        with self.assertRaises(ConnectionError):
            self.connections.finish("browser", dict(callback, state="wrong"))
        callback, _ = self.start()
        self.connections.pending["browser"]["expires"] = 0
        with self.assertRaises(ConnectionError):
            self.connections.finish("browser", callback)
        callback, _ = self.start()
        with self.assertRaisesRegex(ConnectionError, "cancelled"):
            self.connections.finish("browser", dict(callback, error="access_denied"))
        self.assertEqual([], self.provider.calls)
        self.assertEqual({}, self.connections.data["profiles"])

    def test_jwt_rejects_issuer_audience_nonce_expiry_and_subject(self):
        for key, value in [("iss", "https://wrong.invalid"), ("aud", "other-client"),
                           ("nonce", "wrong"), ("exp", 1), ("sub", "")]:
            with self.subTest(key=key):
                callback, _ = self.start()
                self.provider.claims[key] = value
                with self.assertRaises(ConnectionError):
                    self.connections.finish("browser", callback)
                self.assertFalse(any(p.get("access_token") for p in self.connections.data["profiles"].values()))

    def test_invalid_signature_and_unsigned_token(self):
        _, params = self.start()
        wrong_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        for token in [jwt.encode(self.provider.claims, wrong_key, algorithm="RS256"),
                      jwt.encode(self.provider.claims, "", algorithm="none")]:
            with self.assertRaises(ConnectionError):
                self.provider.identity(token, "oaiapp_test", params["nonce"])

    def test_reconnect_preserves_client_and_rejects_different_identity(self):
        profile = self.connect()
        callback, params = self.start(profile)
        self.assertEqual("oaiapp_test", params["client_id"])
        self.assertIn("id_token_hint", params)
        self.assertNotIn("agent_name_hint", params)
        self.provider.claims["sub"] = "another-account"
        with self.assertRaises(ConnectionError):
            self.connections.finish("browser", callback)
        self.assertEqual("account-1", self.connections.profile(profile)["subject"])
        callback, _ = self.start(profile)
        with self.assertRaises(ConnectionError):
            self.connections.finish("browser", dict(callback, client_id="oaiapp_other"))

    def test_refresh_is_serialized_and_rotated_tokens_persist(self):
        profile = self.connect()
        self.connections.profile(profile)["expires_at"] = 0
        with ThreadPoolExecutor(max_workers=4) as pool:
            list(pool.map(lambda _: self.connections.models(profile), range(4)))
        refreshes = [c for c in self.provider.calls if c["grant_type"] == "refresh_token"]
        self.assertEqual(1, len(refreshes))
        self.assertNotIn("scope", refreshes[0])
        saved = json.loads(self.connections.file.read_text())["profiles"][profile]
        self.assertEqual("rotated-secret", saved["refresh_token"])
        self.assertEqual("renewed-secret", saved["access_token"])

    def test_identity_sign_in_does_not_enable_usage(self):
        self.provider.scopes = "openid profile email"
        profile = self.connect()
        with self.assertRaisesRegex(ConnectionError, "not granted"):
            self.connections.models(profile)

    def test_signout_retains_registration_but_removes_secrets(self):
        profile = self.connect()
        host = self.connections.data["host_id"]
        self.provider.revoked = False
        self.assertFalse(self.connections.sign_out(profile))
        saved = self.connections.profile(profile)
        self.assertEqual("oaiapp_test", saved["client_id"])
        self.assertFalse(any(k in saved for k in ["access_token", "refresh_token", "id_token"]))
        _, params = self.start(profile)
        self.assertNotIn("id_token_hint", params)
        self.assertEqual(host, params["ext_agent_host_id"])

    def test_restart_and_exclusive_writer(self):
        profile = self.connect()
        host = self.connections.data["host_id"]
        with self.assertRaises(ConnectionError):
            Connections(self.directory.name, self.provider)
        self.connections.close()
        self.connections = Connections(self.directory.name, self.provider)
        self.assertEqual(host, self.connections.data["host_id"])
        self.assertEqual("account-1", self.connections.profile(profile)["subject"])
        self.assertEqual({}, self.connections.pending)

    def test_unknown_model_does_not_run_inference(self):
        profile = self.connect()
        with self.assertRaisesRegex(ConnectionError, "available"):
            self.connections.probe(profile, "made-up-model")

    def test_http_roundtrip_and_cross_site_protection(self):
        server = ThreadingHTTPServer(("127.0.0.1", 0), BaseHTTPRequestHandler)
        origin = f"http://127.0.0.1:{server.server_port}"
        server.RequestHandlerClass = handler_for(self.connections, origin)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        browser = requests.Session()
        try:
            page = browser.get(origin).text
            csrf = re.search(r'name="csrf" value="([^"]+)"', page)[1]
            fields = {"csrf": csrf, "profile": ""}
            self.assertEqual(403, browser.post(origin + "/connect", data=fields).status_code)
            self.assertEqual(403, browser.get(origin, headers={"Host": "evil.invalid"}).status_code)
            self.assertEqual(403, browser.post(origin + "/connect", data=fields,
                                              headers={"Origin": "https://evil.invalid"}).status_code)
            start = browser.post(origin + "/connect", data=fields, headers={"Origin": origin}, allow_redirects=False)
            self.assertEqual(303, start.status_code)
            params = {k: v[0] for k, v in parse_qs(urlsplit(start.headers["Location"]).query).items()}
            self.provider.claims = {"iss": ISSUER, "aud": "oaiapp_test", "sub": "user", "nonce": params["nonce"],
                                    "iat": int(time.time()), "exp": int(time.time()) + 3600, "email": "<script>test</script>"}
            response = browser.get(origin + "/auth/callback", params={"state": params["state"], "code": "code", "client_id": "oaiapp_test"})
            self.assertEqual(origin + "/", response.url)
            self.assertIn("Connected", response.text)
            self.assertNotIn("access-secret", response.text)
            self.assertNotIn("refresh-secret", response.text)
            self.assertNotIn("<script>", response.text)
            profile = next(iter(self.connections.data["profiles"]))
            fields.update(profile=profile)
            models = browser.post(origin + "/models", data=fields, headers={"Origin": origin})
            self.assertIn("Test with Test model", models.text)
            fields["model"] = "test-model"
            probe = browser.post(origin + "/probe", data=fields, headers={"Origin": origin})
            self.assertIn("Connection test completed", probe.text)
            browser.get(probe.url)
            self.assertEqual(1, self.provider.probe_calls)
            signed_out = browser.post(origin + "/disconnect", data=fields, headers={"Origin": origin})
            self.assertIn("Signed out", signed_out.text)
        finally:
            browser.close()
            server.shutdown()
            server.server_close()
            thread.join()

    def test_stream_requires_completed_event(self):
        def response(events):
            result = SimpleNamespace(status_code=200, iter_lines=lambda: iter(events))
            context = unittest.mock.MagicMock()
            context.__enter__.return_value = result
            return context
        api = OpenAI()
        with patch("connect.requests.post", return_value=response([b'data: {"type":"response.output_text.delta","delta":"partial"}', b''])):
            with self.assertRaisesRegex(ConnectionError, "before completion"):
                api.probe("fake-token", "test-model")
        completed = {"type": "response.completed", "response": {"output": [{"content": [{"type": "output_text", "text": "Works"}]}]}}
        with patch("connect.requests.post", return_value=response([("data: " + json.dumps(completed)).encode(), b''])):
            self.assertEqual("Works", api.probe("fake-token", "test-model"))


if __name__ == "__main__":
    unittest.main()
