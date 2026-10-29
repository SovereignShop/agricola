"""Granted card actions and crop decisions survive real browser reloads."""
import os
import re
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main


def check(base, fixtures):
    oven, teacher, angler, knife, ridger, fan = fixtures.split(",")
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ["EUROZONE_BROWSER"])
        context = browser.new_context(viewport={"width": 1600, "height": 1100})
        context.add_init_script('sessionStorage.setItem("eurozone-session", "' + "a" * 64 + '")')
        page = context.new_page()
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))

        def open_game(game, heading):
            page.goto(base + "/#" + game)
            page.locator(".game").wait_for()
            page.locator(".player-tab").filter(has_text="Ada").click()
            expect(page.locator(".card-decision").get_by_role("heading", name=heading, exact=True)).to_be_visible()

        def amount(resource):
            return page.locator('[data-tile="farm"] .inventory .resource.' + resource + ' b')

        def resolve():
            revision = page.locator(".game").get_attribute("data-revision")
            page.get_by_role("button", name="Resolve effect →", exact=True).click()
            expect(page.locator(".game")).not_to_have_attribute("data-revision", revision)
            expect(page.locator("[role=alert]")).to_have_count(0)

        def ada():
            page.locator(".player-tab").filter(has_text="Ada").click()

        open_game(oven, "Oven Site")
        expect(amount("wood")).to_have_text("2")
        major = page.get_by_label(re.compile("^Major improvement"))
        expect(major.locator('option[value="joinery"]')).to_have_count(0)
        major.select_option("clay-oven")
        page.get_by_label("Grain to bake", exact=True).fill("1")
        resolve()
        ada()
        expect(amount("clay")).to_have_text("0")
        expect(amount("stone")).to_have_text("0")
        expect(amount("grain")).to_have_text("0")
        expect(amount("food")).to_have_text("7")
        expect(page.locator(".player-tab").filter(has_text="Ada")).to_contain_text("1 / 2 workers ready")
        print("Oven Site filters majors, substitutes its cost, and bakes without spending another worker: passed", flush=True)

        open_game(teacher, "Craft Teacher")
        page.get_by_label("Occupation", exact=True).select_option(":catalog/revised-artifex-expansion-a-103-portmonger")
        resolve()
        page.reload()
        expect(page.locator(".card-decision").get_by_role("heading", name="Craft Teacher", exact=True)).to_be_visible()
        page.get_by_label("Occupation", exact=True).select_option(":catalog/revised-artifex-expansion-a-104-wood-harvester")
        resolve()
        ada()
        expect(page.locator(".card-decision")).to_have_count(0)
        expect(amount("food")).to_have_text("2")
        for name in ("Craft Teacher", "Portmonger", "Wood Harvester"):
            expect(page.locator(".played-hand-lane").get_by_role("button", name=name, exact=True)).to_be_visible()
        print("Craft Teacher saves the second free occupation choice across reload: passed", flush=True)

        open_game(angler, "Angler")
        page.get_by_label("Minor improvement", exact=True).select_option(":catalog/revised-artifex-expansion-a-10-wooden-shed")
        resolve()
        ada()
        expect(amount("food")).to_have_text("7")
        expect(amount("wood")).to_have_text("0")
        expect(amount("reed")).to_have_text("0")
        expect(page.locator(".player-tab").filter(has_text="Ada")).to_contain_text("1 / 2 workers ready")
        print("Angler grants major permission to Wooden Shed and triggers Small Trader: passed", flush=True)

        open_game(knife, "Asparagus Knife")
        field = page.get_by_label(re.compile("^Vegetable field"))
        expect(field.locator('option[value="1"]')).to_have_count(0)
        page.reload()
        page.get_by_label(re.compile("^Vegetable field")).select_option("0")
        exchange = page.get_by_label("Exchange this vegetable for 3 food and 1 bonus point", exact=True)
        expect(exchange).not_to_be_checked()
        exchange.click()
        expect(exchange).to_be_checked()
        resolve()
        expect(amount("vegetable")).to_have_text("0")
        expect(amount("food")).to_have_text("5")
        print("Asparagus Knife selects a vegetable field and saves its optional exchange: passed", flush=True)

        open_game(ridger, "Potato Ridger")
        expect(amount("vegetable")).to_have_text("3")
        expect(page.get_by_role("button", name="Skip this effect", exact=True)).to_be_visible()
        resolve()
        expect(amount("vegetable")).to_have_text("2")
        expect(amount("food")).to_have_text("8")
        print("Potato Ridger offers the three-vegetable harvest exchange: passed", flush=True)

        open_game(fan, "Winnowing Fan")
        page.get_by_label(re.compile("^Baking improvement")).select_option("clay-oven")
        resolve()
        expect(amount("grain")).to_have_text("0")
        expect(amount("food")).to_have_text("7")
        page.screenshot(path="/tmp/eurozone-artifex-actions.png", full_page=True)
        print("Winnowing Fan chooses a baking improvement after harvest: passed", flush=True)
        assert errors == [], errors
        browser.close()


if __name__ == "__main__":
    main(check, "test/eurozone/artifex_actions_browser_fixture.clj")
