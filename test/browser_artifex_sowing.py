"""Sowing prepares gifts before planting and commits only one worker."""
import os
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main, choose_action, confirm_action


def check(base, fixtures):
    seed, accepted, declined, forester = fixtures.split(",")
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ["EUROZONE_BROWSER"])
        context = browser.new_context(viewport={"width": 1600, "height": 1100})
        context.add_init_script('sessionStorage.setItem("eurozone-session", "' + "a" * 64 + '")')
        page = context.new_page()
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))

        def player(name):
            page.locator(".player-tab").filter(has_text=name).click()

        def open_game(game):
            page.goto(base + "/#" + game)
            page.locator(".game").wait_for()
            player("Ada")

        def amount(resource):
            return page.locator('[data-tile="farm"] .inventory .resource.' + resource + ' b')

        def click_decision(label):
            revision = page.locator(".game").get_attribute("data-revision")
            page.get_by_role("button", name=label, exact=True).click()
            expect(page.locator(".game")).not_to_have_attribute("data-revision", revision)
            expect(page.locator("[role=alert]")).to_have_count(0)

        def start_sow():
            choose_action(page, "Grain Utilization")
            confirm_action(page)
            click_decision("Take Sow action")

        open_game(seed)
        start_sow()
        expect(amount("grain")).to_have_text("1")
        page.get_by_label("Sow field 1", exact=True).select_option("grain")
        page.get_by_label("Sow field 2", exact=True).select_option("vegetable")
        click_decision("Resolve effect →")
        player("Ada")
        expect(amount("grain")).to_have_text("0")
        expect(amount("vegetable")).to_have_text("0")
        expect(amount("clay")).to_have_text("1")
        expect(amount("stone")).to_have_text("1")
        expect(page.locator(".player-tab").filter(has_text="Ada")).to_contain_text("1 / 2 workers ready")
        print("Seed Pellets funds sowing before selection; Garden Hoe rewards planted vegetables: passed", flush=True)

        open_game(accepted)
        start_sow()
        player("Ben")
        expect(page.locator(".card-decision")).to_contain_text("Publican")
        click_decision("Resolve effect →")
        page.reload()
        player("Ada")
        expect(amount("grain")).to_have_text("1")
        page.get_by_label("Sow field 1", exact=True).select_option("grain")
        click_decision("Resolve effect →")
        player("Ada")
        expect(amount("grain")).to_have_text("0")
        expect(page.locator(".player-tab").filter(has_text="Ada")).to_contain_text("1 / 2 workers ready")
        player("Ben")
        expect(amount("grain")).to_have_text("0")
        print("Publican can supply the only seed, with reload before the saved sowing decision: passed", flush=True)

        open_game(declined)
        start_sow()
        player("Ben")
        click_decision("Skip this effect")
        player("Ada")
        expect(page.locator(".card-decision")).to_have_count(0)
        expect(page.locator(".player-tab").filter(has_text="Ada")).to_contain_text("2 / 2 workers ready")
        choose_action(page, "Grain Seeds")
        confirm_action(page)
        player("Ada")
        expect(amount("grain")).to_have_text("1")
        print("Declined Publican gift leaves the worker available for another action: passed", flush=True)

        open_game(forester)
        choose_action(page, "Forest")
        confirm_action(page)
        click_decision("Take Sow action")
        expect(amount("grain")).to_have_text("0")
        page.get_by_label("Sow field 1", exact=True).select_option("vegetable")
        expect(page.get_by_role("button", name="Resolve effect →", exact=True)).to_be_enabled()
        page.get_by_label("Sow field 2", exact=True).select_option("vegetable")
        expect(page.locator(".card-decision")).to_contain_text("This Sow action is limited to one field.")
        expect(page.get_by_role("button", name="Resolve effect →", exact=True)).to_be_disabled()
        page.get_by_label("Sow field 2", exact=True).select_option("")
        expect(page.get_by_role("button", name="Resolve effect →", exact=True)).to_be_enabled()
        click_decision("Resolve effect →")
        player("Ada")
        expect(amount("vegetable")).to_have_text("1")
        expect(amount("clay")).to_have_text("0")
        expect(amount("stone")).to_have_text("0")
        print("Chief Forester permits one field and does not trigger unconditional sowing cards: passed", flush=True)
        assert errors == [], errors
        page.screenshot(path="/tmp/eurozone-artifex-sowing.png", full_page=True)
        browser.close()


if __name__ == "__main__":
    main(check, "test/eurozone/artifex_sowing_browser_fixture.clj")
