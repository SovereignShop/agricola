"""Saved animal, mixed crop, improvement exchange and baking decisions."""
import os
import re
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main, choose_action, confirm_action


def check(base, fixtures):
    reclamation, silage, full, oriental, winnowing, heresy, knife, freshman = fixtures.split(",")
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

        def click_decision():
            revision = page.locator(".game").get_attribute("data-revision")
            page.get_by_role("button", name="Resolve effect →", exact=True).click()
            expect(page.locator(".game")).not_to_have_attribute("data-revision", revision)
            expect(page.locator("[role=alert]")).to_have_count(0)

        def amount(resource):
            return page.locator('[data-tile="farm"] .inventory .resource.' + resource + ' b')

        def choose_card(label, name):
            selector = page.get_by_label(label, exact=True)
            option = selector.locator("option").filter(has_text=re.compile("^" + re.escape(name) + "$")).first
            selector.select_option(option.get_attribute("value"))

        open_game(reclamation)
        expect(page.locator(".card-decision")).to_contain_text("Reclamation Plow")
        expect(page.get_by_label(re.compile("^Keep all newly taken animals"))).to_be_checked()
        click_decision()
        page.reload()
        ada()
        page.get_by_label("Farm space", exact=True).select_option("2")
        click_decision()
        ada()
        expect(page.locator('[data-tile="farm"] [aria-label="Farm space 3: field"]')).to_have_count(1)
        expect(page.locator(".player-tab").filter(has_text="Ada")).to_contain_text("1 / 2 workers ready")
        print("Reclamation Plow waits for accommodation and saves its later plow choice: passed", flush=True)

        open_game(silage)
        page.get_by_label("Grain source", exact=True).select_option("2")
        page.get_by_label("Species to breed", exact=True).select_option("sheep")
        click_decision()
        expect(amount("sheep")).to_have_text("3")
        expect(page.locator('[data-tile="farm"] [data-piece-key="crop:0:2:0"][data-piece-type="vegetable"]')).to_have_count(1)
        print("Silage takes grain from a mixed field and breeds exactly one sheep: passed", flush=True)

        open_game(full)
        page.get_by_role("button", name="Scoring", exact=True).click()
        expect(page.locator(".score-table tr").filter(has=page.get_by_text("Bonus", exact=True)).locator("td").nth(1)).to_have_text("2")
        print("Full Farmer and Organic Farmer share one optimal housing arrangement: passed", flush=True)

        open_game(oriental)
        choose_action(page, "Meeting Place")
        choose_card("Minor improvement", "Oriental Fireplace")
        expect(page.get_by_label("Return cooking improvement", exact=True)).to_have_value("hearth-4")
        confirm_action(page)
        ada()
        expect(page.locator(".played-cards")).to_contain_text("Oriental Fireplace")
        print("Oriental Fireplace exchanges an owned Cooking Hearth through the normal action UI: passed", flush=True)

        open_game(winnowing)
        page.get_by_label(re.compile("^Baking improvement")).select_option("catalog/revised-artifex-expansion-a-60-oriental-fireplace")
        click_decision()
        expect(amount("grain")).to_have_text("0")
        expect(amount("food")).to_have_text("4")
        print("Winnowing Fan recognizes Oriental Fireplace as a baking improvement: passed", flush=True)

        open_game(heresy)
        choose_action(page, "Lessons")
        choose_card("Occupation", "Heresy Teacher")
        confirm_action(page)
        ada()
        expect(page.locator('[data-tile="farm"] svg.farm [data-piece-type="vegetable"]')).to_have_count(2)
        expect(page.locator('[data-tile="farm"] svg.farm [data-piece-type="grain"]')).to_have_count(6)
        print("Heresy Teacher displays vegetables beneath existing grain stacks: passed", flush=True)

        open_game(knife)
        page.reload()
        ada()
        page.get_by_label(re.compile("^Mixed fields counted as grain")).fill("1")
        click_decision()
        expect(amount("food")).to_have_text("3")
        expect(amount("grain")).to_have_text("1")
        print("Saved mixed-field classification prevents counting each field twice: passed", flush=True)

        open_game(freshman)
        choose_action(page, "Grain Utilization")
        confirm_action(page)
        expect(page.locator(".card-decision")).to_contain_text("Freshman")
        page.reload()
        ada()
        choose_card("Occupation", "Wood Cutter")
        click_decision()
        ada()
        expect(page.locator(".played-cards")).to_contain_text("Wood Cutter")
        expect(amount("food")).to_have_text("2")
        expect(page.locator(".player-tab").filter(has_text="Ada")).to_contain_text("1 / 2 workers ready")
        print("Freshman substitutes a free occupation with no grain or oven, using one worker: passed", flush=True)
        assert errors == [], errors
        page.screenshot(path="/tmp/eurozone-artifex-harvest.png", full_page=True)
        browser.close()


if __name__ == "__main__":
    main(check, "test/eurozone/artifex_harvest_browser_fixture.clj")
