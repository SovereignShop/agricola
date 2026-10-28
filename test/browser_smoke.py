"""Optional browser integration test. Run `make build` first.

Install Playwright in your preferred Python environment, then install Chromium:
  pip install playwright
  playwright install chromium
  python test/browser_smoke.py

EUROZONE_BROWSER may point at an existing Chromium executable. The test starts
an isolated server with temporary saves and never touches data/eurozone.edn.
"""
import os
import re
import socket
import subprocess
import tempfile
import time
import urllib.request
from pathlib import Path

from playwright.sync_api import expect, sync_playwright

ROOT = Path(__file__).resolve().parents[1]


def create_table(page, name, title, online=False):
    page.get_by_text("Connected", exact=True).wait_for()
    page.get_by_label("Your name", exact=True).fill(name)
    page.get_by_label("Table name").fill(title)
    if online:
        page.get_by_role("button", name="Online table", exact=True).click()
    page.get_by_role("button", name="Create table →").click()
    page.get_by_role("button", name=re.compile("^(Start the game|Begin solo game)")).wait_for()


def choose_action(page, name):
    page.locator(".action-card").filter(has=page.locator("strong", has_text=re.compile("^" + name + "$"))).click()


def confirm_action(page):
    revision = page.locator(".game").get_attribute("data-revision")
    page.get_by_role("button", name="Place family member →").click()
    expect(page.locator(".game")).not_to_have_attribute("data-revision", revision)
    expect(page.locator(".action-editor")).to_have_count(0)
    expect(page.locator(".app")).to_have_attribute("aria-busy", "false")
    expect(page.locator("[role=alert]")).to_have_count(0)


def takeback(page):
    page.get_by_role("button", name="Request takeback", exact=True).click()
    page.get_by_role("button", name="Ask for takeback", exact=True).click()
    page.wait_for_function("document.querySelector('[role=dialog]')?.textContent.includes('Takeback approved') || document.querySelector('[role=dialog]')?.textContent.includes('Takeback vote')")
    for button in page.get_by_role("button", name="Approve takeback", exact=True).all():
        button.click()
    page.get_by_role("dialog", name="Takeback approved", exact=True).get_by_role("button", name="Continue", exact=True).click()


def check_browser(base, fixture_id):
    with sync_playwright() as playwright:
        options = {"headless": True}
        if os.environ.get("EUROZONE_BROWSER"):
            options["executable_path"] = os.environ["EUROZONE_BROWSER"]
        browser = playwright.chromium.launch(**options)
        errors = []
        host = browser.new_context(viewport={"width": 1440, "height": 1000})
        guest = browser.new_context(viewport={"width": 1440, "height": 1000})
        h, g = host.new_page(), guest.new_page()
        for page in (h, g):
            page.on("pageerror", lambda error: errors.append(str(error)))
        h.goto(base)
        create_table(h, "Host", "Online integration", online=True)
        g.goto(h.url)
        g.get_by_placeholder("Your name").fill("Guest")
        g.get_by_role("button", name="Take a seat").click()
        g.get_by_text("The host will start once everyone is ready.").wait_for()
        h.get_by_role("button", name="Start the game →").click()
        for page in (h, g):
            try:
                page.get_by_role("heading", name="Make your move.").wait_for()
            except Exception:
                print("PAGE:",page.locator("body").inner_text(),"ERRORS:",errors,flush=True)
                raise
        # Signaling establishes actual RTCDataChannels; game revisions are not
        # touched by private chat, and text is rendered safely.
        for page in (h, g):
            expect(page.get_by_label("Chat message", exact=True)).to_be_enabled(timeout=30000)
        revision = h.locator(".game").get_attribute("data-revision")
        h.get_by_label("Chat message", exact=True).fill("<script>hello</script> from Host")
        h.get_by_role("button", name="Send", exact=True).click()
        expect(g.locator(".chat-message")).to_contain_text("<script>hello</script> from Host")
        expect(g.locator(".chat-message strong")).to_have_text("Host")
        expect(h.locator(".game")).to_have_attribute("data-revision", revision)
        g.get_by_label("Chat message", exact=True).fill("Hello back!")
        g.get_by_role("button", name="Send", exact=True).click()
        expect(h.locator(".chat-message").last).to_contain_text("Hello back!")
        assert h.locator(".chat-message script").count() == 0
        print("Direct WebRTC chat, identities, escaped text, and revision isolation: passed")
        active, passive = (h, g) if h.locator(".turn-label").inner_text() == "YOUR TURN" else (g, h)
        assert passive.locator(".action-card:not([disabled])").count() == 0
        choose_action(active, "Forest")
        confirm_action(active)
        expect(passive.locator(".action-card.occupied")).to_have_count(1)
        assert h.locator(".action-card.occupied").inner_text() == g.locator(".action-card.occupied").inner_text()
        active.reload()
        active.get_by_text("Connected", exact=True).wait_for()
        expect(active.locator(".action-card.occupied")).to_have_count(1)
        assert len(active.evaluate('localStorage.getItem("eurozone-session")')) == 64
        expect(active.get_by_label("Chat message", exact=True)).to_be_enabled(timeout=30000)
        expect(active.locator(".chat-message")).to_have_count(0)
        active.get_by_label("Chat message", exact=True).fill("Back after reload")
        active.get_by_role("button", name="Send", exact=True).click()
        expect(passive.locator(".chat-message").last).to_contain_text("Back after reload")
        assert h.locator(".piece-pile .piece").count() > 0
        assert h.locator(".placed-worker").count() == 1
        assert h.locator("svg.farm image").count() >= 3
        assert h.locator('.table-tile[data-tile="farm"]').bounding_box()["x"] > h.locator(".action-board").bounding_box()["x"]
        assert h.locator(".chat-panel").bounding_box()["x"] > h.locator(".action-board").bounding_box()["x"]
        assert h.locator("img").evaluate_all("els => els.every(e => e.complete && e.naturalWidth > 0)")
        h.screenshot(path="/tmp/eurozone-tabletop.png", full_page=True)
        print("Online seating, authorization, broadcasts, and reconnect: passed")

        # A separate pass-and-play table exercises UI -> server -> facts for a
        # spatial action, undo, and a complete 14-round game.
        local = browser.new_context()
        play = local.new_page()
        play.on("pageerror", lambda error: errors.append(str(error)))
        play.goto(base)
        create_table(play, "Ada", "Full-game integration")
        play.get_by_role("button", name="Start the game →").click()
        play.get_by_role("heading", name="Make your move.").wait_for()
        choose_action(play, "Farmland")
        play.get_by_role("button", name="Farm space 5: empty", exact=True).click()
        confirm_action(play)
        takeback(play)
        expect(play.locator(".action-card.occupied")).to_have_count(0)
        play.get_by_role("button", name="Improvements", exact=True).click()
        expect(play.locator(".improvement-card")).to_have_count(10)
        expect(play.locator(".improvement-art")).to_have_count(10)
        assert play.locator(".improvement-art").evaluate_all("els => els.every(e => e.complete && e.naturalWidth > 0)")
        play.get_by_role("button", name="Village & farm").click()
        play.get_by_role("heading", name="Make your move.").wait_for()
        preferences = ["Forest", "Clay Pit", "Reed Bank", "Grain Seeds", "Day Laborer", "Fishing", "Meeting Place",
                       "Grove", "Hollow", "Copse", "Traveling Players", "Western Quarry", "Eastern Quarry", "Vegetable Seeds"]
        for _ in range(200):
            expect(play.locator(".app")).to_have_attribute("aria-busy", "false")
            if play.get_by_text("THE FINAL HARVEST", exact=True).count():
                break
            revision = play.locator(".game").get_attribute("data-revision")
            if play.get_by_text("HARVEST · FEEDING", exact=True).count():
                play.locator(".harvest-panel").get_by_role("button", name=re.compile("^Feed")).click()
            elif play.get_by_text("HARVEST · BREEDING", exact=True).count():
                play.get_by_role("button", name="Complete breeding →").click()
            else:
                available = play.locator(".action-card:not([disabled]) strong").all_text_contents()
                selected = next((name for name in preferences if name in available), None)
                if not selected:
                    raise AssertionError("No available action: " + play.locator("body").inner_text())
                choose_action(play, selected)
                confirm_action(play)
            expect(play.locator("[role=alert]")).to_have_count(0)
            expect(play.locator(".game")).not_to_have_attribute("data-revision", revision)
            expect(play.locator(".app")).to_have_attribute("aria-busy", "false")
        else:
            raise AssertionError("Game failed to finish")
        expect(play.locator(".score-table tfoot tr").first).to_contain_text("Total")
        print("Spatial action, undo, improvements, 14 rounds, six harvests, and final scoring: passed")
        play.get_by_role("button", name="Village & farm").click()
        play.get_by_role("heading", name="Make your move.").wait_for()
        expect(play.locator('.round-card-slot[data-revealed="true"]')).to_have_count(14)
        expect(play.locator('.round-card-front .round-card-art')).to_have_count(14)
        # Every action maps to a distinct cell in the original scan sheet.
        positions = play.locator('.round-card-front .round-card-art').evaluate_all(
            "els => els.map(e => e.style.backgroundPosition)")
        assert len(set(positions)) == 14
        play.locator('.physical-board').screenshot(path="/tmp/eurozone-all-round-cards.png")
        play.set_viewport_size({"width": 390, "height": 844})
        assert play.evaluate("document.documentElement.scrollWidth <= innerWidth")
        for tab in ["Scoring", "How to play", "Improvements"]:
            play.get_by_role("button", name=tab, exact=True).click()
            expect(play.locator(".tab.active")).to_have_text(tab)
            assert play.evaluate("document.documentElement.scrollWidth <= innerWidth")
        fixture = browser.new_context(viewport={"width": 1440, "height": 1000})
        fixture.add_init_script('if (location.origin === ' + repr(base) + ') localStorage.setItem("eurozone-session", "' + "a" * 64 + '")')
        art = fixture.new_page()
        art.on("pageerror", lambda error: errors.append(str(error)))
        art.goto(base + "/#" + fixture_id)
        art.get_by_role("heading", name="Played cards", exact=True).wait_for()
        cards = art.locator(".played-card")
        expect(cards).to_have_count(3)
        expect(art.locator(".card-stack.expanded")).to_have_count(0)
        cards.first.click(position={"x": 30, "y": 10})
        expect(cards.first).to_have_attribute("aria-pressed", "true")
        expect(art.locator(".played-card.exposed")).to_have_count(1)
        cards.nth(1).click(position={"x": 30, "y": 10})
        expect(cards.nth(1)).to_have_attribute("aria-pressed", "true")
        expect(art.locator(".played-card.exposed")).to_have_count(1)
        art.get_by_role("button", name="Show all", exact=True).click()
        expect(art.locator(".card-stack.expanded")).to_have_count(1)
        art.screenshot(path="/tmp/eurozone-played-cards.png", full_page=True)
        art.get_by_role("button", name="Stack cards", exact=True).click()
        expect(art.locator(".card-stack.expanded")).to_have_count(0)
        assert art.locator('svg.farm image[href$="sheep.png"]').count() == 2
        assert art.locator('svg.farm image[href$="grain2.png"]').count() == 3
        art.set_viewport_size({"width": 390, "height": 844})
        cards.nth(2).click(position={"x": 30, "y": 10})
        expect(cards.nth(2)).to_have_attribute("aria-pressed", "true")
        assert art.evaluate("document.documentElement.scrollWidth <= innerWidth")
        art.screenshot(path="/tmp/eurozone-mobile.png", full_page=True)
        print("Played card stacking, single-card exposure, show-all, farm pieces, and mobile overflow: passed")
        assert errors == [], errors
        print("Mobile layout and JavaScript errors: passed")
        browser.close()


def main(check=check_browser, fixture="test/eurozone/browser_fixture.clj"):
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    base = f"http://127.0.0.1:{port}"
    with tempfile.TemporaryDirectory(prefix="eurozone-smoke-") as temp:
        env = dict(os.environ, HOST="127.0.0.1", EUROZONE_DATA=str(Path(temp) / "games.edn"))
        seed = subprocess.run(["clojure", "-M", fixture], cwd=ROOT, env=env,
                              text=True, capture_output=True, check=True)
        fixture_id = seed.stdout.strip().splitlines()[-1]
        with open(Path(temp) / "server.log", "w+") as log:
            server = subprocess.Popen(["clojure", "-M:run", str(port)], cwd=ROOT, env=env, stdout=log, stderr=log)
            try:
                for _ in range(100):
                    try:
                        urllib.request.urlopen(base + "/health", timeout=1).close()
                        break
                    except OSError:
                        if server.poll() is not None:
                            log.seek(0)
                            raise RuntimeError(log.read())
                        time.sleep(.2)
                else:
                    raise RuntimeError("Test server did not start")
                check(base, fixture_id)
            finally:
                server.terminate()
                server.wait(timeout=10)


if __name__ == "__main__":
    main()
