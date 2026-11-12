"""Optional browser check with simulated OpenAI approval; no real account access."""
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import tempfile
import threading
import time
from urllib.parse import parse_qs, urlencode, urlsplit

from playwright.sync_api import sync_playwright, expect
from connect import Connections, ISSUER, handler_for
from test_connect import Provider


def main():
    with tempfile.TemporaryDirectory() as directory:
        provider = Provider()
        connections = Connections(directory, provider)
        server = ThreadingHTTPServer(("127.0.0.1", 0), BaseHTTPRequestHandler)
        origin = f"http://127.0.0.1:{server.server_port}"
        provider.discovery = lambda: {"authorization_endpoint": origin + "/simulated-approval"}

        class TestHandler(handler_for(connections, origin)):
            def do_GET(self):
                parsed = urlsplit(self.path)
                if parsed.path != "/simulated-approval":
                    return super().do_GET()
                params = {k: v[0] for k, v in parse_qs(parsed.query).items()}
                provider.claims = {"iss": ISSUER, "sub": "browser-test", "aud": "oaiapp_test",
                                   "nonce": params["nonce"], "iat": int(time.time()),
                                   "exp": int(time.time()) + 3600, "email": "review@example.invalid"}
                callback = origin + "/auth/callback?" + urlencode({"state": params["state"],
                                                                  "code": "simulated", "client_id": "oaiapp_test"})
                self.reply(200, f'<p>Simulated OpenAI approval</p><a href="{callback}">Approve test connection</a>')

        server.RequestHandlerClass = TestHandler
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            with sync_playwright() as pw:
                options = {"headless": True}
                if os.environ.get("EUROZONE_BROWSER"):
                    options["executable_path"] = os.environ["EUROZONE_BROWSER"]
                browser = pw.chromium.launch(**options)
                page = browser.new_page(viewport={"width": 1100, "height": 900})
                errors = []
                page.on("pageerror", lambda error: errors.append(str(error)))

                page.route("https://**/*", lambda route: route.abort())
                page.goto(origin)
                page.get_by_role("button", name="Continue with ChatGPT", exact=True).click()
                if urlsplit(page.url).path != "/simulated-approval":
                    raise AssertionError("Sign-in navigation failed: " + page.locator("body").inner_text())
                page.get_by_role("link", name="Approve test connection").click()
                expect(page.get_by_text("Connected · Plan usage enabled", exact=True)).to_be_visible()
                page.get_by_role("button", name="Check available models").click()
                page.get_by_role("button", name="Test with Test model").click()
                expect(page.get_by_role("status")).to_contain_text("Connection test completed")
                page.screenshot(path="/tmp/eurozone-chatgpt-desktop.png", full_page=True)
                page.set_viewport_size({"width": 390, "height": 844})
                assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
                page.screenshot(path="/tmp/eurozone-chatgpt-mobile.png", full_page=True)
                page.reload()
                assert provider.probe_calls == 1, "Reload must not repeat a paid test request"
                expect(page.get_by_text("Connected · Plan usage enabled", exact=True)).to_be_visible()
                page.get_by_role("button", name="Sign out", exact=True).click()
                expect(page.get_by_text("Signed out · Plan usage not enabled", exact=True)).to_be_visible()
                page.get_by_role("button", name="Continue with ChatGPT", exact=True).first.click()
                page.get_by_role("link", name="Approve test connection").click()
                expect(page.get_by_text("Connected · Plan usage enabled", exact=True)).to_be_visible()
                assert not errors, errors
                assert "access-secret" not in page.content()
                browser.close()
            print("Browser check passed: simulated sign-in, model test, reload, sign-out, reconnect; desktop and mobile.")
        finally:
            server.shutdown()
            server.server_close()
            thread.join()
            connections.close()


if __name__ == "__main__":
    main()
