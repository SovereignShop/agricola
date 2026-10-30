"""Random hand selection, physical supplies, and card markers in the browser."""
import os
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main, choose_action, confirm_action


def check(base, fixtures):
    knife, yard, loppers, optional, mandatory, race = fixtures.split(",")
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

        def amount(resource):
            return page.locator('[data-tile="farm"] .inventory .resource.' + resource + ' b')

        def resolve(skip=False):
            revision = page.locator(".game").get_attribute("data-revision")
            page.get_by_role("button", name="Skip this effect" if skip else "Resolve effect →", exact=True).click()
            expect(page.locator(".game")).not_to_have_attribute("data-revision", revision)
            expect(page.locator("[role=alert]")).to_have_count(0)

        open_game(knife)
        expect(page.get_by_role("button", name="Resolve effect →", exact=True)).to_be_disabled()
        page.reload()
        for name in ["Portmonger", "Wood Harvester", "Wood Cutter"]:
            page.get_by_label("Offer " + name, exact=True).click()
            expect(page.get_by_label("Offer " + name, exact=True)).to_be_checked()
        resolve()
        ada()
        expect(page.locator(".card-decision")).to_have_count(0)
        expect(page.locator(".player-tab").filter(has_text="Ada")).to_contain_text("1 / 2 workers ready")
        print("Paper Knife requires three cards and resumes its random free occupation after reload: passed", flush=True)

        open_game(yard)
        expect(page.locator('[aria-label="Potter\'s Yard clay"]')).to_have_count(7)
        choose_action(page, "Farmland")
        page.get_by_role("button", name="Farm space 8: empty", exact=True).click()
        confirm_action(page)
        expect(page.locator(".card-decision")).to_contain_text("Potter's Yard")
        expect(amount("clay")).to_have_text("1")
        page.reload()
        resolve()
        ada()
        expect(amount("clay")).to_have_text("0")
        expect(amount("food")).to_have_text("4")
        expect(page.locator('[aria-label="Potter\'s Yard clay"]')).to_have_count(6)
        print("Potter's Yard shows clay markers and offers one exchange for the newly used space: passed", flush=True)

        open_game(loppers)
        expect(page.locator(".card-decision")).to_contain_text("Loppers")
        resolve()
        ada()
        expect(amount("wood")).to_have_text("5")
        expect(amount("food")).to_have_text("4")
        print("Loppers pays wood, removes a supply fence, and grants its food and point: passed", flush=True)

        open_game(optional)
        expect(page.locator(".card-decision")).to_contain_text("Shaving Horse")
        resolve()
        ada()
        expect(amount("wood")).to_have_text("4")
        expect(amount("food")).to_have_text("5")
        open_game(mandatory)
        expect(page.locator(".card-decision")).to_have_count(0)
        expect(amount("wood")).to_have_text("6")
        expect(amount("food")).to_have_text("5")
        print("Shaving Horse offers the five-wood exchange and automatically resolves the seven-wood exchange: passed", flush=True)

        open_game(race)
        choose_action(page, "Farmland")
        page.get_by_role("button", name="Farm space 5: empty", exact=True).click()
        confirm_action(page)
        ada()
        expect(amount("clay")).to_have_text("4")
        expect(page.get_by_role("button", name="Sequestrator", exact=True).locator(".card-stored-goods .resource.clay")).to_have_count(0)
        print("Sequestrator transfers its clay to the first new five-field farm: passed", flush=True)
        assert errors == [], errors
        browser.close()


if __name__ == "__main__":
    main(check, "test/eurozone/artifex_supply_browser_fixture.clj")
