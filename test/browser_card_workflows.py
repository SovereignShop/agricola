"""Alternative payments, optional branches and preparation/post-harvest windows."""
import os
import re
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main, choose_action, confirm_action


def check(base, fixtures):
    payment, choice, preparation, harvest = fixtures.split(",")
    with sync_playwright() as pw:
        options = {"headless": True}
        if os.environ.get("EUROZONE_BROWSER"):
            options["executable_path"] = os.environ["EUROZONE_BROWSER"]
        browser = pw.chromium.launch(**options)
        context = browser.new_context(viewport={"width": 1600, "height": 1100})
        context.add_init_script('sessionStorage.setItem("eurozone-session", "' + "a" * 64 + '")')
        page = context.new_page()
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))

        def open_game(game):
            page.goto(base + "/#" + game)
            try:
                page.locator(".game").wait_for()
            except Exception:
                print(page.locator("body").inner_text(), errors, flush=True)
                raise
            page.locator(".player-tab").filter(has_text="Ada").click()

        def amount(resource):
            return page.locator('[data-tile="farm"] .inventory .resource.' + resource + ' b')

        def resolve():
            revision = page.locator(".game").get_attribute("data-revision")
            page.get_by_role("button", name="Resolve effect →", exact=True).click()
            expect(page.locator(".game")).not_to_have_attribute("data-revision", revision)

        open_game(payment)
        choose_action(page, "Meeting Place")
        page.get_by_label("Minor improvement", exact=True).select_option(":catalog/revised-artifex-expansion-a-4-baseboards")
        page.get_by_label("Card payment", exact=True).select_option("1")
        confirm_action(page)
        page.locator(".player-tab").filter(has_text="Ada").click()
        expect(amount("grain")).to_have_text("0")
        expect(amount("wood")).to_have_text("2")
        expect(amount("food")).to_have_text("2")
        print("Alternative printed payment and automatic goods: passed", flush=True)

        open_game(choice)
        page.locator(".card-decision").get_by_role("heading", name="Treegardener", exact=True).wait_for()
        page.get_by_label(re.compile("^Card effect")).select_option("1")
        expect(page.get_by_role("button", name="Resolve effect →", exact=True)).to_be_disabled()
        page.reload()
        page.locator(".card-decision").get_by_role("heading", name="Treegardener", exact=True).wait_for()
        page.get_by_label(re.compile("^Card effect")).select_option("0")
        resolve()
        expect(amount("wood")).to_have_text("2")
        expect(amount("food")).to_have_text("0")
        print("Optional branch rejects unaffordable payment and survives reload: passed", flush=True)

        open_game(preparation)
        page.locator(".card-decision").get_by_role("heading", name="Plow Driver", exact=True).wait_for()
        page.get_by_label("Farm space", exact=True).select_option("4")
        expect(page.get_by_role("button", name="Resolve effect →", exact=True)).to_be_disabled()
        page.get_by_text("Convert goods to food", exact=True).click()
        page.locator(".card-decision").get_by_role("spinbutton", name=re.compile("Grain")).fill("1")
        page.get_by_role("button", name="Convert to food", exact=True).click()
        expect(amount("food")).to_have_text("1")
        page.get_by_label("Farm space", exact=True).select_option("4")
        resolve()
        expect(page.get_by_role("button", name="Farm space 5: field", exact=True)).to_be_visible()
        expect(amount("food")).to_have_text("0")
        print("Preparation conversion funds Plow Driver: passed", flush=True)

        open_game(harvest)
        page.locator(".card-decision").get_by_role("heading", name="After the harvest", exact=True).wait_for()
        page.locator(".card-ability summary").filter(has_text="Sheep Walker").click()
        page.get_by_label(re.compile("^Exchange one sheep for")).select_option("vegetable")
        page.get_by_role("button", name="Use card ability", exact=True).click()
        expect(amount("vegetable")).to_have_text("1")
        page.reload()
        page.locator(".card-decision").get_by_role("heading", name="After the harvest", exact=True).wait_for()
        page.get_by_role("button", name="Finish harvest decisions →", exact=True).click()
        expect(page.locator(".card-decision")).to_have_count(0)
        expect(page.locator(".game")).to_contain_text("THE FINAL HARVEST")
        expect(page.get_by_role("row", name="Vegetables 1 -1", exact=True)).to_be_visible()
        page.screenshot(path="/tmp/eurozone-card-workflows.png", full_page=True)
        assert errors == [], errors
        print("Post-breeding ability precedes final scoring and survives reload: passed", flush=True)
        page.get_by_role("button", name="← All tables", exact=True).click()
        page.get_by_label("Pre-game hand setup").select_option("random")
        pack = page.get_by_label("Include Artifex Expansion", exact=True)
        expect(pack).to_be_checked()
        expect(page.locator(".pack-picker")).to_contain_text("98/120 cards")
        page.get_by_text("Card coverage", exact=True).click()
        expect(page.get_by_text("Artifex Expansion: 98 / 120", exact=True)).to_be_visible()
        pack.click()
        expect(pack).not_to_be_checked()
        expect(page.locator(".pack-picker")).to_contain_text("48 minor improvements in the eligible pool")
        print("Partial-pack counts and base-only pool selection: passed", flush=True)
        assert errors == [], errors
        browser.close()


if __name__ == "__main__":
    main(check, "test/eurozone/card_workflows_fixture.clj")
