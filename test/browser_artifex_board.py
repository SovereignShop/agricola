"""Shared card action spaces, pile choices, and stored goods in a browser."""
import os
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main, confirm_action


def check(base, fixtures):
    certificate, shepherd, chapel, stored, released, locked, tally = fixtures.split(",")
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ["EUROZONE_BROWSER"])
        context = browser.new_context(viewport={"width": 1600, "height": 1100})
        context.add_init_script('sessionStorage.setItem("eurozone-session", "' + "a" * 64 + '")')
        page = context.new_page()
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))

        def select_player(name):
            page.locator(".player-tab").filter(has_text=name).click()

        def open_game(game, name="Ada"):
            page.goto(base + "/#" + game)
            page.locator(".game").wait_for()
            select_player(name)

        def amount(resource):
            return page.locator('[data-tile="farm"] .inventory .resource.' + resource + ' b')

        def resolve():
            revision = page.locator(".game").get_attribute("data-revision")
            page.get_by_role("button", name="Resolve effect →", exact=True).click()
            expect(page.locator(".game")).not_to_have_attribute("data-revision", revision)
            expect(page.locator("[role=alert]")).to_have_count(0)

        open_game(certificate)
        expect(page.locator(".card-decision")).to_contain_text("Work Certificate")
        page.reload()
        page.get_by_label("Accumulation space", exact=True).select_option("[:wood :wood]")
        resolve()
        select_player("Ada")
        expect(amount("wood")).to_have_text("1")
        expect(page.locator(".player-tab").filter(has_text="Ada")).to_contain_text("1 / 2 workers ready")
        print("Work Certificate triggers on play and preserves its saved pile choice: passed", flush=True)

        open_game(shepherd)
        expect(page.locator(".card-decision")).to_contain_text("Riverine Shepherd")
        resolve()
        select_player("Ada")
        expect(amount("reed")).to_have_text("1")
        expect(amount("sheep")).to_have_text("1")
        print("Riverine Shepherd takes the counterpart good without another placement: passed", flush=True)

        open_game(chapel, "Ben")
        page.get_by_role("button", name="3 bonus points", exact=True).click()
        confirm_action(page)
        select_player("Ben")
        expect(amount("grain")).to_have_text("0")
        expect(page.locator(".player-tab").filter(has_text="Ben")).to_contain_text("1 / 2 workers ready")
        select_player("Ada")
        expect(amount("grain")).to_have_text("1")
        expect(page.get_by_role("button", name="3 bonus points", exact=True)).to_be_disabled()
        print("Chapel charges its visitor, pays its owner, and becomes occupied: passed", flush=True)

        open_game(stored)
        expect(amount("wood")).to_have_text("0")
        expect(page.get_by_role("button", name="Interim Storage", exact=True).locator(".card-stored-goods .resource.wood b")).to_have_text("1")
        open_game(released)
        expect(amount("wood")).to_have_text("1")
        expect(page.get_by_role("button", name="Interim Storage", exact=True).locator(".card-stored-goods .resource.wood")).to_have_count(0)
        print("Interim Storage keeps goods off the inventory until round seven: passed", flush=True)

        open_game(locked)
        expect(page.get_by_role("button", name="2 clay and 3 wood", exact=True)).to_be_disabled()
        open_game(tally)
        page.get_by_role("button", name="2 clay and 3 wood", exact=True).click()
        confirm_action(page)
        select_player("Ada")
        expect(amount("wood")).to_have_text("3")
        expect(amount("clay")).to_have_text("2")
        print("Forest Tallyman checks both occupied spaces before allowing placement: passed", flush=True)
        assert errors == [], errors
        browser.close()


if __name__ == "__main__":
    main(check, "test/eurozone/artifex_board_browser_fixture.clj")
