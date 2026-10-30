"""Occupation payment, occupied spaces and saved nested placements in the browser."""
import os
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main, choose_action, confirm_action


def check(base, fixtures):
    bassinet, forest, mummy, lazy, swag, minstrel = fixtures.split(",")
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ["EUROZONE_BROWSER"])
        context = browser.new_context(viewport={"width": 1600, "height": 1100})
        context.add_init_script('sessionStorage.setItem("eurozone-session", "' + "a" * 64 + '")')
        page = context.new_page()
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))

        def ada():
            page.locator(".player-tab").filter(has_text="Ada").click()

        def open_game(game):
            page.goto(base + "/#" + game)
            page.locator(".game").wait_for()
            ada()

        def click_decision(label):
            revision = page.locator(".game").get_attribute("data-revision")
            page.get_by_role("button", name=label, exact=True).click()
            expect(page.locator(".game")).not_to_have_attribute("data-revision", revision)
            expect(page.locator("[role=alert]")).to_have_count(0)

        def goods(resource, n):
            ada()
            expect(page.locator('[data-tile="farm"] .inventory .resource.' + resource + ' b')).to_have_text(str(n))

        open_game(bassinet)
        choose_action(page, "Grain Seeds")
        confirm_action(page)
        goods("grain", 1)
        print("Bassinet exposes its shared occupied action on the board: passed", flush=True)

        open_game(forest)
        choose_action(page, "Lessons")
        page.get_by_label("Wood for occupation cost", exact=True).fill("1")
        page.get_by_label("Occupation", exact=True).select_option(str(":revised-base/a116"))
        confirm_action(page)
        goods("wood", 0)
        print("Forest School shares Lessons and pays its occupation cost in wood: passed", flush=True)

        open_game(mummy)
        choose_action(page, "Day Laborer")
        confirm_action(page)
        goods("food", 6)
        print("Mummy's Boy exposes the occupied second-worker space: passed", flush=True)

        open_game(lazy)
        expect(page.locator(".card-decision")).to_contain_text("Lazy Sowman")
        page.reload()
        ada()
        page.get_by_label("Action for card", exact=True).select_option("{:space :grain}")
        click_decision("Place family member →")
        goods("grain", 1)
        expect(page.locator(".card-decision")).to_have_count(0)
        print("Lazy Sowman resumes a saved immediate placement: passed", flush=True)

        open_game(swag)
        expect(page.locator(".card-decision")).to_contain_text("Swagman")
        page.reload()
        ada()
        click_decision("Move family member →")
        goods("grain", 1)
        expect(page.locator(".player-tab").filter(has_text="Ada")).to_contain_text("1 / 2 workers ready")
        print("Swagman moves one person without spending another worker: passed", flush=True)

        open_game(minstrel)
        expect(page.locator(".card-decision")).to_contain_text("Minstrel")
        page.reload()
        ada()
        click_decision("Take card action →")
        goods("sheep", 1)
        expect(page.locator(".player-tab").filter(has_text="Ada")).to_contain_text("0 / 2 workers ready")
        print("Minstrel resolves its saved return-home action without a worker: passed", flush=True)
        assert errors == [], errors
        page.screenshot(path="/tmp/eurozone-placement-cards.png", full_page=True)
        browser.close()


if __name__ == "__main__":
    main(check, "test/eurozone/placement_cards_browser_fixture.clj")
