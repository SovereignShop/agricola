"""Saved room construction and family growth choices through the browser."""
import os
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main


def check(base, fixtures):
    artist, bed, home = fixtures.split(",")
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

        open_game(artist)
        expect(page.locator(".card-decision")).to_contain_text("House Artist")
        page.reload()
        page.get_by_label("Build room 1", exact=True).click()
        expect(page.get_by_label("Build room 1", exact=True)).to_be_checked()
        expect(page.get_by_role("button", name="Resolve effect →", exact=True)).to_be_enabled()
        page.get_by_label("Build room 2", exact=True).click()
        expect(page.get_by_label("Build room 2", exact=True)).to_be_checked()
        resolve()
        expect(page.locator(".card-decision")).to_contain_text("Bed Maker")
        expect(amount("reed")).to_have_text("8")
        expect(amount("wood")).to_have_text("10")
        page.reload()
        expect(page.locator(".card-decision")).to_contain_text("Bed Maker")
        resolve()
        ada()
        expect(amount("wood")).to_have_text("9")
        expect(amount("grain")).to_have_text("2")
        expect(page.locator(".player-tab").filter(has_text="Ada")).to_contain_text("1 / 3 workers ready")
        print("House Artist builds two discounted rooms, then Bed Maker grows after reload: passed", flush=True)

        open_game(bed)
        expect(page.locator(".card-decision")).to_contain_text("Bed Maker")
        resolve(skip=True)
        ada()
        expect(amount("grain")).to_have_text("3")
        expect(amount("wood")).to_have_text("15")
        expect(page.locator(".player-tab").filter(has_text="Ada")).to_contain_text("1 / 2 workers ready")
        print("Skipping Bed Maker leaves its payment and family unchanged: passed", flush=True)

        open_game(home)
        expect(page.locator(".card-decision")).to_contain_text("Family Friend Home")
        expect(amount("food")).to_have_text("3")
        resolve()
        ada()
        expect(page.locator(".player-tab").filter(has_text="Ada")).to_contain_text("1 / 3 workers ready")
        expect(page.locator(".card-decision")).to_have_count(0)
        print("Family Friend Home grants food and a newborn without another placement: passed", flush=True)
        assert errors == [], errors
        browser.close()


if __name__ == "__main__":
    main(check, "test/eurozone/artifex_construction_browser_fixture.clj")
