"""Local, opt-in ChatGPT account connection and paired game assistant."""
import argparse
import base64
import fcntl
import hashlib
import html
import json
import os
from pathlib import Path
import secrets
import tempfile
import threading
import time
import uuid
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlencode, urlsplit

import jwt
import requests
from game_bridge import GameBridge, INSTRUCTIONS, validated_request, validate_result

ISSUER = "https://auth.openai.com"
RESOURCE = "https://api.openai.com/v1"
SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct"


class ConnectionError(Exception):
    """An intentionally credential-free message safe to show in the browser."""


class OpenAI:
    def __init__(self):
        self.metadata = None
        self.jwks = None

    def discovery(self):
        if self.metadata is None:
            response = requests.get(ISSUER + "/.well-known/openid-configuration",
                                    timeout=15, allow_redirects=False)
            if response.status_code != 200:
                raise ConnectionError("Could not load OpenAI sign-in configuration. Try again.")
            metadata = response.json()
            if metadata.get("issuer") != ISSUER:
                raise ConnectionError("Unexpected OpenAI issuer.")
            for name in ("authorization_endpoint", "token_endpoint", "jwks_uri", "revocation_endpoint"):
                url = urlsplit(metadata.get(name, ""))
                if url.scheme != "https" or url.netloc != "auth.openai.com":
                    raise ConnectionError("Unexpected OpenAI authentication endpoint.")
            self.metadata = metadata
            self.jwks = jwt.PyJWKClient(metadata["jwks_uri"], timeout=15)
        return self.metadata

    def token(self, fields):
        response = requests.post(self.discovery()["token_endpoint"], data=fields,
                                 timeout=30, allow_redirects=False)
        if response.status_code != 200:
            # Never return provider bodies: they may echo credentials or user data.
            try:
                invalid = response.json().get("error") == "invalid_grant"
            except ValueError:
                invalid = False
            if invalid:
                raise ConnectionError("Sign-in expired or was revoked. Connect this account again.")
            raise ConnectionError(f"OpenAI token request failed (HTTP {response.status_code}). Try again.")
        return response.json()

    def identity(self, token, client_id, nonce=None):
        self.discovery()
        try:
            key = self.jwks.get_signing_key_from_jwt(token)
            identity = jwt.decode(token, key.key, algorithms=["RS256"], audience=client_id,
                                  issuer=ISSUER, leeway=5,
                                  options={"require": ["sub", "exp", "iat"]})
            if not isinstance(identity["sub"], str) or not identity["sub"]:
                raise ValueError("Missing subject")
            if nonce is not None and identity.get("nonce") != nonce:
                raise ValueError("Invalid nonce")
            if identity.get("azp", client_id) != client_id:
                raise ValueError("Invalid authorized party")
            return identity
        except (jwt.PyJWTError, ValueError, TypeError):
            raise ConnectionError("OpenAI identity verification failed. Connect again.") from None

    def revoke(self, profile):
        for delay in (0, 0.25, 0.5):
            time.sleep(delay)
            try:
                response = requests.post(self.discovery()["revocation_endpoint"],
                                         data={"token": profile["refresh_token"],
                                               "token_type_hint": "refresh_token",
                                               "client_id": profile["client_id"]},
                                         timeout=10, allow_redirects=False)
                if response.status_code == 200:
                    return True
                if response.status_code < 500:
                    return False
            except requests.RequestException:
                pass
        return False

    def models(self, token):
        response = requests.get(RESOURCE + "/models", headers={"Authorization": "Bearer " + token},
                                timeout=30, allow_redirects=False)
        if response.status_code != 200:
            raise ConnectionError(f"Model access failed (HTTP {response.status_code}). Check plan access or reconnect.")
        return [{"slug": m["slug"], "name": m.get("display_name", m["slug"])}
                for m in response.json().get("models", [])
                if m.get("visibility") == "list" and isinstance(m.get("slug"), str)]

    def probe(self, token, model):
        # Explicit user action only; does not consume plan usage during sign-in.
        return self.respond(token, model, "Reply with one short sentence confirming the connection works.",
                            "Test my Agricola ChatGPT connection.")

    def interpret(self, token, model, packet):
        validated_request(packet)
        schema = {'type': 'object', 'additionalProperties': False,
                  'properties': {'kind': {'type': 'string', 'enum': ['action', 'answer']},
                                 'space': {'type': ['string', 'null'], 'enum': [a['space'] for a in packet['context'].get('actions', [])] + [None]},
                                 'value': {'type': ['string', 'null']},
                                 'confidence': {'type': 'string', 'enum': ['high', 'low']},
                                 'continue': {'type': 'boolean'},
                                 'message': {'type': 'string'}},
                  'required': ['kind', 'space', 'message', 'value', 'confidence', 'continue']}
        return validate_result(self.respond(token, model, INSTRUCTIONS,
                                             json.dumps({'utterance': packet['text'], 'context': packet['context']}), schema), packet)

    def respond(self, token, model, instructions, content, schema=None):
        payload = {"model": model, "store": False, "stream": True, "instructions": instructions,
                   "input": [{"role": "user", "content": content}]}
        if schema:
            payload['text'] = {'format': {'type': 'json_schema', 'name': 'agricola_proposal', 'strict': True, 'schema': schema}}
        with requests.post(RESOURCE + "/responses", headers={"Authorization": "Bearer " + token},
                           json=payload,
                           stream=True, timeout=(10, 30), allow_redirects=False) as response:
            if response.status_code != 200:
                raise ConnectionError(f"Test request failed (HTTP {response.status_code}). Check your plan limits.")
            data, size, started = [], 0, time.monotonic()
            for raw in response.iter_lines():
                size += len(raw)
                if size > 1_000_000 or time.monotonic() - started > 90:
                    raise ConnectionError("Test response exceeded the prototype limit.")
                line = raw.decode("utf-8")
                if line.startswith("data:"):
                    data.append(line[5:].strip())
                elif not line and data:
                    event = json.loads("\n".join(data))
                    data = []
                    if event.get("type") in ("response.failed", "response.incomplete", "error"):
                        raise ConnectionError("OpenAI did not complete the test request.")
                    if event.get("type") == "response.completed":
                        text = "\n".join(part.get("text", "")
                                         for item in event.get("response", {}).get("output", [])
                                         for part in item.get("content", []) if part.get("type") == "output_text")
                        if not text:
                            raise ConnectionError("The test completed without a text response.")
                        return text
            raise ConnectionError("The test stream ended before completion. No success was recorded.")


class Connections:
    def __init__(self, directory, provider=None):
        self.directory = Path(directory)
        self.directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        os.chmod(self.directory, 0o700)
        self.file = self.directory / "connections.json"
        self.lockfile = open(self.directory / "writer.lock", "a", encoding="utf-8")
        os.chmod(self.directory / "writer.lock", 0o600)
        try:
            fcntl.flock(self.lockfile, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            self.lockfile.close()
            raise ConnectionError("Another connection prototype is using this credential directory.") from None
        self.lock = threading.RLock()
        self.provider = provider or OpenAI()
        self.pending = {}
        self.bridge = GameBridge(self)
        self.data = (json.loads(self.file.read_text()) if self.file.exists()
                     else {"host_id": "urn:uuid:" + str(uuid.uuid4()), "profiles": {}})
        self.save()

    def close(self):
        self.bridge.close()
        self.lockfile.close()

    def save(self):
        fd, name = tempfile.mkstemp(dir=self.directory)
        try:
            with os.fdopen(fd, "w") as output:
                json.dump(self.data, output)
                output.flush()
                os.fsync(output.fileno())
            os.replace(name, self.file)
        finally:
            if os.path.exists(name):
                os.unlink(name)

    def begin(self, browser, redirect_uri, profile_id=None):
        with self.lock:
            profile = self.profile(profile_id) if profile_id else None
            verifier, state, nonce = (secrets.token_urlsafe(32) for _ in range(3))
            self.pending[browser] = {"state": state, "nonce": nonce, "verifier": verifier,
                                     "redirect_uri": redirect_uri, "profile": profile_id,
                                     "expires": time.time() + 600}
            params = {"client_id": profile["client_id"] if profile else "dynamic_agent_client",
                      "ext_agent_host_id": self.data["host_id"], "response_type": "code",
                      "redirect_uri": redirect_uri, "scope": SCOPES, "resource": RESOURCE,
                      "state": state, "nonce": nonce, "code_challenge_method": "S256",
                      "code_challenge": base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).decode().rstrip("=")}
            if profile:
                if profile.get("id_token"):
                    params["id_token_hint"] = profile["id_token"]
                if profile.get("email"):
                    params["login_hint"] = profile["email"]
            else:
                params["agent_name_hint"] = "Eurozone Agricola"
            return self.provider.discovery()["authorization_endpoint"] + "?" + urlencode(params)

    def profile(self, profile_id):
        if profile_id not in self.data["profiles"]:
            raise ConnectionError("Choose a saved connection or add an account.")
        return self.data["profiles"][profile_id]

    def finish(self, browser, params):
        with self.lock:
            pending = self.pending.pop(browser, None)
            if (not pending or pending["expires"] < time.time()
                    or not secrets.compare_digest(pending["state"], params.get("state", ""))):
                raise ConnectionError("Sign-in attempt expired or did not match this browser. Start again.")
            if params.get("error"):
                raise ConnectionError("Sign-in was cancelled or declined. Your saved connections are unchanged.")
            profile_id = pending["profile"] or secrets.token_urlsafe(12)
            previous = self.data["profiles"].get(profile_id, {})
            client_id = params.get("client_id", previous.get("client_id", ""))
            if (not client_id.startswith("oaiapp_")
                    or (previous and client_id != previous["client_id"]) or not params.get("code")):
                raise ConnectionError("OpenAI did not return the expected registration. Start again.")
            # Retain a new registration even if its one-time code cannot be exchanged.
            if not previous:
                self.data["profiles"][profile_id] = {"client_id": client_id}
                self.save()
            tokens = self.provider.token({"grant_type": "authorization_code", "client_id": client_id,
                                          "code": params["code"], "code_verifier": pending["verifier"],
                                          "redirect_uri": pending["redirect_uri"], "resource": RESOURCE})
            identity = self.provider.identity(tokens.get("id_token"), client_id, pending["nonce"])
            if previous.get("subject") and identity["sub"] != previous["subject"]:
                raise ConnectionError("This sign-in belongs to a different account. Add it as a new connection.")
            profile = {"client_id": client_id, "subject": identity["sub"], "issuer": identity["iss"],
                       "email": identity.get("email"), "name": identity.get("name")}
            self.data["profiles"][profile_id] = self.with_tokens(profile, tokens)
            self.save()
            return profile_id

    @staticmethod
    def with_tokens(profile, tokens):
        result = dict(profile)
        for key in ("access_token", "refresh_token", "id_token"):
            if key in tokens:
                result[key] = tokens[key]
        if "scope" in tokens:
            result["scopes"] = tokens["scope"].split()
        result["expires_at"] = time.time() + max(0, float(tokens.get("expires_in", 0)))
        return result

    def access(self, profile_id):
        # Caller holds the same lock for the entire request. Sign-out cannot race refresh/inference.
        profile = self.profile(profile_id)
        if "chatgpt.tokens.use.direct" not in profile.get("scopes", []):
            raise ConnectionError("ChatGPT plan usage was not granted. Reconnect and allow plan usage.")
        if not profile.get("access_token"):
            raise ConnectionError("Connect this account again before testing.")
        if profile.get("expires_at", 0) < time.time() + 60:
            if not profile.get("refresh_token"):
                raise ConnectionError("This connection needs a new sign-in.")
            tokens = self.provider.token({"grant_type": "refresh_token", "client_id": profile["client_id"],
                                          "refresh_token": profile["refresh_token"], "resource": RESOURCE})
            if not tokens.get("access_token"):
                raise ConnectionError("Token renewal did not return access. Reconnect this account.")
            if tokens.get("id_token"):
                identity = self.provider.identity(tokens["id_token"], profile["client_id"])
                if identity["sub"] != profile["subject"]:
                    raise ConnectionError("Token renewal returned a different identity.")
            profile = self.with_tokens(profile, tokens)
            self.data["profiles"][profile_id] = profile
            self.save()
            if "chatgpt.tokens.use.direct" not in profile.get("scopes", []):
                raise ConnectionError("ChatGPT plan usage is no longer enabled. Reconnect this account.")
        return profile["access_token"]

    def models(self, profile_id):
        with self.lock:
            return self.provider.models(self.access(profile_id))

    def probe(self, profile_id, model):
        with self.lock:
            token = self.access(profile_id)
            if model not in {m["slug"] for m in self.provider.models(token)}:
                raise ConnectionError("Choose a model available to this connection.")
            return self.provider.probe(token, model)

    def interpret(self, profile_id, model, packet):
        with self.lock:
            token = self.access(profile_id)
            return self.provider.interpret(token, model, validated_request(packet))

    def sign_out(self, profile_id):
        if self.bridge.profile == profile_id:
            self.bridge.close()
        with self.lock:
            profile = self.profile(profile_id)
            confirmed = not profile.get("refresh_token")
            try:
                if profile.get("refresh_token"):
                    confirmed = self.provider.revoke(profile)
            except (ConnectionError, requests.RequestException):
                confirmed = False
            for key in ("access_token", "refresh_token", "id_token", "scopes", "expires_at"):
                profile.pop(key, None)
            self.pending.clear()
            self.save()
            return confirmed


def esc(value):
    return html.escape(str(value), quote=True)


def handler_for(connections, origin):
    # One local browser session; startup invalidates previous cookies and form tokens.
    session, csrf = secrets.token_urlsafe(32), secrets.token_urlsafe(32)
    notice = {}

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass  # Callback URLs and authorization hints must never enter logs.

        def reply(self, status, body="", location=None, cookie=False, content_type="text/html; charset=utf-8"):
            self.send_response(status)
            # no-referrer on the form page makes Chromium send Origin: null on POST.
            # Keep same-origin forms verifiable; suppress callback URL referrers entirely.
            policy = "no-referrer" if urlsplit(self.path).path == "/auth/callback" else "same-origin"
            for name, value in {"Cache-Control": "no-store", "Referrer-Policy": policy,
                                "X-Content-Type-Options": "nosniff",
                                "Content-Type": content_type,
                                "Content-Security-Policy": "default-src 'none'; script-src 'self'; style-src 'unsafe-inline'; form-action 'self' https://auth.openai.com; frame-ancestors 'none'; base-uri 'none'"}.items():
                self.send_header(name, value)
            if location:
                self.send_header("Location", location)
            if cookie:
                self.send_header("Set-Cookie", f"eurozone_chatgpt={session}; HttpOnly; SameSite=Lax; Path=/")
            self.end_headers()
            self.wfile.write(body.encode())

        def valid_host(self):
            return self.headers.get("Host") == urlsplit(origin).netloc

        def authenticated(self):
            cookie = SimpleCookie()
            try:
                cookie.load(self.headers.get("Cookie", ""))
                return secrets.compare_digest(cookie["eurozone_chatgpt"].value, session)
            except (KeyError, ValueError):
                return False

        def form(self, route, label, profile="", model="", brand=False):
            return (f'<form action="{route}" method="post"><input type="hidden" name="csrf" value="{csrf}">'
                    f'<input type="hidden" name="profile" value="{esc(profile)}">'
                    f'<input type="hidden" name="model" value="{esc(model)}">'
                    + ('<input type="hidden" name="link" value="">' if route == '/pair-game' else '')
                    + f'<button {"data-oai-siwc-button" if brand else ""}>{esc(label)}</button></form>')

        def page(self, message="", models=None, profile=""):
            cards = []
            with connections.lock:
                for index, (key, p) in enumerate(connections.data["profiles"].items(), 1):
                    connected = bool(p.get("id_token"))
                    usage = bool(p.get("access_token")) and "chatgpt.tokens.use.direct" in p.get("scopes", [])
                    cards.append(f'<article><h2>Connection {index}</h2><p>{esc(p.get("email") or p.get("name") or "Registration pending")}</p>'
                                 f'<p>{"Connected" if connected else "Signed out"} · Plan usage {"enabled" if usage else "not enabled"}</p>'
                                 + self.form("/connect", "Continue with ChatGPT", key, brand=True)
                                 + (self.form("/models", "Check available models", key) if usage else "")
                                 + (self.form("/disconnect", "Sign out", key) if connected else "") + '</article>')
            if models is not None:
                cards.append('<article><h2>Test your connection</h2><p>Each test sends a short request and consumes ChatGPT plan usage.</p>'
                             + ("".join('<div>' + self.form("/pair-game", "Use " + m["name"] + " with my game", profile, m["slug"])
                                        + self.form("/probe", "Test with " + m["name"], profile, m["slug"]) + '</div>' for m in models)
                                or '<p>No available models were returned.</p>') + '</article>')
            return ('<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">'
                    '<title>Connect ChatGPT · Eurozone</title><style>body{font:17px system-ui;background:#f4f0e7;color:#202b25;max-width:680px;margin:5vh auto;padding:20px}'
                    'h1{font-size:2rem}article,.notice{padding:22px;background:white;border:1px solid #d6ded5;border-radius:14px;margin:20px 0;overflow-wrap:anywhere}'
                    'button{font:inherit;min-height:46px;padding:10px 18px;border:1px solid #526557;border-radius:9px;background:#fff;color:#173d2a;cursor:pointer;text-align:left}'
                    'form{display:inline-block;margin:5px 8px 5px 0}p{line-height:1.5}a{color:#195d43}</style>'
                    '<main><p>EUROZONE · LOCAL PROTOTYPE</p><h1>Connect your ChatGPT account</h1>'
                    '<p>Connect your own ChatGPT account, then pair it with your game. Credentials stay on this computer.</p>'
                    + (f'<div class="notice" role="status">{esc(message)}</div>' if message else '')
                    + '<article><h2>Pair your game</h2><p>In the game, open Voice commands → Connect my ChatGPT. '
                    'Open its local companion link or paste the pairing link here.</p>'
                    '<label for="pair-link">Game pairing link</label><input id="pair-link" required autocomplete="off" '
                    'style="box-sizing:border-box;width:100%;min-height:46px" placeholder="wss://your-game/assistant#…">'
                    '<p>Choose your account’s available models below, then choose Use with my game. '
                    'Commands and questions submitted in that paired game will use your selected ChatGPT plan. '
                    'Current game controls, visible dialog/card text and recent conversation are sent to OpenAI. Clear commands run immediately; uncertain choices need clarification. Multi-step commands may use several model calls.</p>'
                    f'<p id="pair-status">{esc(connections.bridge.status)}</p>'
                    + self.form('/unpair-game', 'Disconnect game') + '<p><a href="/">Refresh connection status</a></p></article>'
                    + "".join(cards) + self.form("/connect", "Continue with ChatGPT", brand=True)
                    + '<p>Use the button above to add a connection. Sign-in and pairing do not run inference.</p>'
                    '<p><a href="https://chatgpt.com/#settings" rel="noreferrer">Manage access and usage in ChatGPT settings</a></p>'
                    '<p>Keep pairing links private. Each link controls only its originating game window. '
                    'Pairing ends on disconnect, after one hour, or when this companion closes.</p></main><script src="/local.js"></script></html>')

        def do_GET(self):
            if not self.valid_host():
                return self.reply(403, "Open this prototype using its 127.0.0.1 address.")
            parsed = urlsplit(self.path)
            if parsed.path == "/":
                return self.reply(200, self.page(notice.pop("message", "")), cookie=True)
            if parsed.path == "/local.js":
                return self.reply(200, Path(__file__).with_name('local.js').read_text(), content_type='application/javascript')
            if parsed.path != "/auth/callback":
                return self.reply(404, "Not found")
            if not self.authenticated():
                return self.reply(403, "Return to the prototype and start sign-in in this browser.")
            try:
                params = self.params(parsed.query)
                connections.finish(session, params)
                # Remove the authorization code from the displayed URL immediately.
                self.reply(303, location="/")
            except ConnectionError as error:
                notice["message"] = str(error)
                self.reply(303, location="/")
            except Exception:
                notice["message"] = "Could not complete sign-in. Check your connection and try again."
                self.reply(303, location="/")

        @staticmethod
        def params(body):
            fields = parse_qs(body, keep_blank_values=True, max_num_fields=20)
            if any(len(values) != 1 for values in fields.values()):
                raise ConnectionError("Duplicate request fields are not allowed.")
            return {key: values[0] for key, values in fields.items()}

        def do_POST(self):
            if not self.valid_host() or not self.authenticated() or self.headers.get("Origin") != origin:
                return self.reply(403, "Start from the local connection page.")
            try:
                length = int(self.headers.get("Content-Length", "0"))
                if not 0 < length <= 8192:
                    return self.reply(413, "Request too large or empty")
                fields = self.params(self.rfile.read(length).decode())
                if not secrets.compare_digest(fields.get("csrf", ""), csrf):
                    return self.reply(403, "Reload the connection page and try again.")
                profile = fields.get("profile")
                if self.path == "/connect":
                    location = connections.begin(session, origin + "/auth/callback", profile or None)
                    return self.reply(303, location=location)
                if self.path == "/disconnect":
                    confirmed = connections.sign_out(profile)
                    notice["message"] = ("Signed out. Registration is saved for next time." if confirmed else
                                         "Signed out locally. Remote revocation was not confirmed; disconnect the app in ChatGPT settings.")
                    return self.reply(303, location="/")
                if self.path == "/models":
                    return self.reply(200, self.page(models=connections.models(profile), profile=profile))
                if self.path == "/pair-game":
                    connections.bridge.pair(fields.get('link', ''), profile, fields.get('model'))
                    notice['message'] = 'Connecting your selected account to the game. Return to Voice commands to speak or type.'
                    return self.reply(303, location='/')
                if self.path == "/unpair-game":
                    connections.bridge.close()
                    notice['message'] = 'Game disconnected.'
                    return self.reply(303, location='/')
                if self.path == "/probe":
                    notice["message"] = "Connection test completed: " + connections.probe(profile, fields.get("model"))
                    return self.reply(303, location="/")
                self.reply(404, "Not found")
            except (ConnectionError, ValueError) as error:
                notice["message"] = str(error)
                self.reply(303, location="/")
            except Exception:
                notice["message"] = "The request could not finish. Check your connection and try again."
                self.reply(303, location="/")

    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=1455)
    parser.add_argument("--state-dir", type=Path, default=Path.home() / ".local/state/eurozone-chatgpt")
    args = parser.parse_args()
    connections = Connections(args.state_dir)
    try:
        server = ThreadingHTTPServer(("127.0.0.1", args.port), BaseHTTPRequestHandler)
        origin = f"http://127.0.0.1:{server.server_port}"
        server.RequestHandlerClass = handler_for(connections, origin)
        print(f"Connect ChatGPT → {origin}", flush=True)
        try:
            server.serve_forever()
        except KeyboardInterrupt:
            pass
        finally:
            server.server_close()
    finally:
        connections.close()


if __name__ == "__main__":
    main()
