"""Draw, same-browser seats, readiness, and personal layout integration tests."""
import os
import re
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main


def check_setup(base, fixture_id):
    with sync_playwright() as pw:
        options = {"headless": True}
        if os.environ.get("EUROZONE_BROWSER"):
            options["executable_path"] = os.environ["EUROZONE_BROWSER"]
        browser = pw.chromium.launch(**options)
        context = browser.new_context(viewport={"width": 1440, "height": 1000})
        errors = []
        joins = []
        def observe(page):
            page.on("pageerror", lambda e: errors.append(str(e)))
            page.on("websocket", lambda ws: ws.on("framesent", lambda data: joins.append(data) if ":event :join" in str(data) else None))
        context.on("page", observe)
        host = context.new_page()
        host.goto(base)
        host.get_by_text("Connected", exact=True).wait_for()
        host.get_by_label("Your name", exact=True).fill("Tab host")
        host.get_by_label("Table name").fill("Draw and layout test")
        host.get_by_role("button", name="Online table", exact=True).click()
        host.get_by_label("Players expected").select_option("2")
        host.get_by_label("Pre-game hand setup").select_option("draw")
        host.get_by_label("Extra cards per packet (N)").fill("2")
        host.get_by_role("button", name="Create table →").click()
        start = host.get_by_role("button", name="Start draw →")
        expect(start).to_be_disabled()
        with host.expect_popup() as popup:
            host.get_by_role("link", name="Open another player in a new tab ↗").click()
        guest = popup.value
        guest.bring_to_front()
        guest.get_by_placeholder("Your name").click()
        guest.get_by_placeholder("Your name").press("ControlOrMeta+A")
        guest.get_by_placeholder("Your name").press_sequentially("Tab guest", delay=30)
        expect(guest.get_by_placeholder("Your name")).to_have_value("Tab guest")
        guest.get_by_role("button", name="Take a seat", exact=True).click()
        guest.get_by_text("The host will start once everyone is ready.").wait_for()
        host_token = host.evaluate('sessionStorage.getItem("eurozone-session")')
        guest_token = guest.evaluate('sessionStorage.getItem("eurozone-session")')
        assert host_token != guest_token
        assert guest.evaluate('localStorage.getItem("eurozone-session")') == host_token
        expect(guest.locator(".app")).to_have_attribute("aria-busy", "false")
        try:
            expect(guest.get_by_role("button", name="Mark not ready", exact=True)).to_be_visible(timeout=10000)
        except AssertionError:
            raise AssertionError(guest.locator("body").inner_text() + " ERRORS=" + str(errors) + " JOINS=" + str(joins))
        guest.get_by_role("button", name="Mark not ready", exact=True).click()
        expect(host.get_by_role("button", name="Start draw →")).to_be_disabled()
        guest.get_by_role("button", name="I'm ready", exact=True).click()
        expect(host.get_by_role("button", name="Start draw →")).to_be_enabled()
        guest.reload()
        guest.get_by_text("The host will start once everyone is ready.").wait_for()
        assert guest.evaluate('sessionStorage.getItem("eurozone-session")') == guest_token
        host.get_by_role("button", name="Start draw →").click()
        for page in (host, guest):
            page.get_by_role("heading", name="The draw", exact=True).wait_for()
            expect(page.locator(".draw-packet .hand-card")).to_have_count(16)
        host_names = set(host.locator(".draw-packet .hand-card > strong").all_text_contents())
        guest_names = set(guest.locator(".draw-packet .hand-card > strong").all_text_contents())
        assert not (host_names & guest_names)
        print("Independent seats in one browser, readiness gating, reload, and private packets: passed")
        picks = 0
        for _ in range(50):
            if host.get_by_role("heading", name="Agricola", exact=True).count():
                break
            progressed = False
            for page in (host, guest):
                page.bring_to_front()
                expect(page.locator(".app")).to_have_attribute("aria-busy", "false")
                cards = page.locator(".draw-packet > div .hand-grid").first.locator(".hand-card:not([disabled])")
                if cards.count():
                    cards.first.click()
                    revision = page.locator(".game").get_attribute("data-revision")
                    page.get_by_role("button", name="Keep card & pass left →").click()
                    expect(page.locator(".game")).not_to_have_attribute("data-revision", revision)
                    expect(page.locator(".app")).to_have_attribute("aria-busy", "false")
                    expect(page.locator(".toast")).to_have_count(0)
                    picks += 1
                    progressed = True
            if not progressed:
                # Wait for the other tab's fact/ack event to render the next wave.
                host.wait_for_timeout(100)
        assert picks == 28, picks
        for page in (host, guest):
            page.get_by_role("heading", name="Make your move.").wait_for()
        print("Mixed 14+N draw completes with exactly 28 picks and enters round one: passed")
        host.get_by_role("button", name="Show all farms", exact=True).click()
        expect(host.locator('[data-tile="farm-0"]')).to_be_visible()
        expect(host.locator('[data-tile="farm-1"]')).to_be_visible()
        expect(host.locator('[data-tile="farm"]')).to_have_count(0)
        expect(guest.locator('[data-tile="farm-0"]')).to_have_count(0)
        # Detailed docking/resize coverage is in browser_layout.py.
        host.get_by_role("button", name="Hide Game log", exact=True).click()
        expect(host.locator('[data-tile="log"]')).to_have_count(0)
        host.get_by_role("button", name="Panels", exact=True).click()
        host.get_by_label("Game log", exact=True).click()
        expect(host.locator('[data-tile="log"]')).to_have_count(1)
        host.reload()
        host.get_by_role("heading", name="Make your move.").wait_for()
        expect(host.locator('[data-tile="farm-0"]')).to_be_visible()
        guest.reload()
        guest.get_by_role("heading", name="Make your move.").wait_for()
        expect(guest.locator('[data-tile="farm-0"]')).to_have_count(0)
        host.screenshot(path="/tmp/eurozone-all-farms.png", full_page=True)
        assert errors == [], errors
        print("Show all farms, independent layouts, visibility, and persistence: passed")
        browser.close()


if __name__ == "__main__":
    main(check_setup)
