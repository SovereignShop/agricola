"""The portable replay verifier and read-only board playback agree with JVM logs."""
import os
from pathlib import Path
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main, ROOT, choose_action, confirm_action


def check_replay(base, fixture_id):
    recording = Path(os.environ.get("EUROZONE_REPLAY_FILE", str(ROOT / "data/simulations/validation-solo-final/game-501.edn")))
    with sync_playwright() as pw:
        opts = {"headless": True}
        if os.environ.get("EUROZONE_BROWSER"):
            opts["executable_path"] = os.environ["EUROZONE_BROWSER"]
        browser = pw.chromium.launch(**opts)
        context = browser.new_context(viewport={"width": 1440, "height": 1000})
        context.add_init_script('if (location.origin === ' + repr(base) + ') sessionStorage.setItem("eurozone-session", "' + "a" * 64 + '")')
        page = context.new_page()
        errors = []
        page.on("pageerror", lambda e: errors.append(str(e)))
        page.goto(base)
        page.get_by_label("Open game recording").set_input_files(recording)
        expect(page.locator(".replay-view")).to_be_visible(timeout=30000)
        expect(page.locator(".replay-controls")).to_contain_text("Move 0 /")
        page.get_by_role("button", name="Play", exact=True).click()
        expect(page.get_by_label("Replay move")).not_to_have_value("0")
        page.get_by_role("button", name="Pause", exact=True).click()
        page.get_by_role("button", name="Last", exact=True).click()
        expect(page.locator(".score-table")).to_be_visible()
        expect(page.locator(".action-card:not([disabled])")).to_have_count(0)
        page.get_by_role("button", name="Previous", exact=True).click()
        expect(page.locator(".score-table")).to_have_count(0)
        page.get_by_role("button", name="First", exact=True).click()
        expect(page.get_by_label("Replay move")).to_have_value("0")
        page.screenshot(path="/tmp/agricola-replay.png", full_page=True)
        page.get_by_role("button", name="Close playback", exact=True).click()
        # Hash corruption is rejected before displaying any replay state.
        text = recording.read_text()
        import re
        bad = re.sub(r':hash "[a-f0-9]+"', ':hash "corrupt"', text, count=1)
        page.get_by_label("Open game recording").set_input_files({"name": "corrupt.edn", "mimeType": "text/plain", "buffer": bad.encode()})
        expect(page.get_by_role("alert")).to_contain_text("Initial-state hash mismatch")
        expect(page.locator(".replay-view")).to_have_count(0)
        page.goto(base + "/#" + fixture_id)
        choose_action(page, "Forest")
        confirm_action(page)
        with page.expect_download() as download:
            page.get_by_role("button", name="Save recording", exact=True).click()
        saved = download.value.path()
        page.get_by_label("Open game recording").set_input_files(saved)
        expect(page.locator(".replay-view")).to_be_visible(timeout=30000)
        expect(page.locator(".replay-controls")).to_contain_text("Move 0 / 1")
        page.get_by_role("button", name="Last", exact=True).click()
        expect(page.locator(".replay-controls")).to_contain_text("Move 1 / 1")
        assert not errors, errors
        browser.close()
        print("Cross-runtime hashes, visual playback, seeking, read-only controls, corruption rejection, live-game export/import: passed")


if __name__ == "__main__":
    main(check_replay)
