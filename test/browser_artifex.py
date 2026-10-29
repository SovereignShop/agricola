"""Artifex action restrictions, spatial decisions, transfers and animal housing."""
import os
import re
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main, choose_action, confirm_action


def check(base, fixtures):
    shed, shelter, company, payment, mud = fixtures.split(",")
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ["EUROZONE_BROWSER"])
        context = browser.new_context(viewport={"width": 1600, "height": 1100})
        context.add_init_script('sessionStorage.setItem("eurozone-session", "' + "a" * 64 + '")')
        page = context.new_page()
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))

        def open_game(game, player="Ada"):
            page.goto(base + "/#" + game)
            page.locator(".game").wait_for()
            page.locator(".player-tab").filter(has_text=player).click()

        def minor(cid):
            page.get_by_label("Minor improvement", exact=True).select_option(":catalog/revised-artifex-expansion-a-" + cid)

        def amount(resource):
            return page.locator('[data-tile="farm"] .inventory .resource.' + resource + ' b')

        def resolve():
            revision = page.locator(".game").get_attribute("data-revision")
            page.get_by_role("button", name="Resolve effect →", exact=True).click()
            expect(page.locator(".game")).not_to_have_attribute("data-revision", revision)

        open_game(shed)
        choose_action(page, "Meeting Place")
        option = page.get_by_label("Minor improvement", exact=True).locator('option[value=":catalog/revised-artifex-expansion-a-10-wooden-shed"]')
        expect(option).to_have_attribute("disabled", "")
        expect(option).to_contain_text("Major Improvement")
        page.get_by_role("button", name="Cancel", exact=True).click()
        choose_action(page, "Major or Minor Improvement")
        minor("10-wooden-shed")
        confirm_action(page)
        page.locator(".player-tab").filter(has_text="Ada").click()
        expect(amount("wood")).to_have_text("0")
        expect(amount("reed")).to_have_text("0")
        print("Wooden Shed requires a major action and pays printed costs: passed", flush=True)

        open_game(shelter)
        choose_action(page, "Meeting Place")
        minor("1-shelter")
        confirm_action(page)
        page.get_by_label("Farm space", exact=True).select_option("3")
        expect(page.get_by_role("button", name="Resolve effect →", exact=True)).to_be_disabled()
        page.get_by_label("Farm space", exact=True).select_option("4")
        resolve()
        page.locator(".player-tab").filter(has_text="Ada").click()
        expect(page.locator(".farm-stats")).to_contain_text("1/4 stables")
        print("Shelter restricts stable placement to a single-space pasture: passed", flush=True)

        open_game(company)
        choose_action(page, "Meeting Place")
        minor("13-renovation-company")
        confirm_action(page)
        page.reload()
        page.locator(".card-decision").get_by_role("heading", name="Renovation Company", exact=True).wait_for()
        page.get_by_label(re.compile("^Renovate to")).select_option("stone")
        resolve()
        page.locator(".player-tab").filter(has_text="Ada").click()
        expect(page.locator(".farm-info")).to_contain_text("2 stone rooms")
        expect(amount("clay")).to_have_text("3")
        print("Free renovation composes with Conservator and survives reload: passed", flush=True)

        open_game(payment, "Ben")
        choose_action(page, "Fishing")
        confirm_action(page)
        page.locator(".card-decision").get_by_role("heading", name="Paymaster", exact=True).wait_for()
        resolve()
        page.locator(".player-tab").filter(has_text="Ada").click()
        expect(amount("grain")).to_have_text("0")
        page.locator(".player-tab").filter(has_text="Ben").click()
        expect(amount("grain")).to_have_text("1")
        print("Other-player payment pauses for its owner and reaches the acting player: passed", flush=True)

        open_game(mud)
        expect(page.locator('[aria-label="Farm space 5: field"] [data-piece-type="boar"]')).to_have_count(1)
        choose_action(page, "Grain Utilization")
        page.get_by_role("button", name="Farm space 5: field", exact=True).click()
        confirm_action(page)
        expect(page.locator(".card-decision")).to_contain_text("Make room for your animals")
        expect(page.get_by_role("button", name="Resolve effect →", exact=True)).to_be_disabled()
        page.screenshot(path="/tmp/eurozone-artifex-housing.png", full_page=True)
        page.get_by_label("Sheep to keep", exact=True).fill("0")
        resolve()
        expect(page.locator(".card-decision")).to_have_count(0)
        page.locator(".player-tab").filter(has_text="Ada").click()
        expect(amount("boar")).to_have_text("1")
        expect(amount("sheep")).to_have_text("0")
        expect(page.locator('[aria-label="Farm space 5: field"] [data-piece-type="boar"]')).to_have_count(0)
        expect(page.locator(".farm-stats")).to_contain_text("Pet: Wild boar")
        print("Mud Patch displays boars on empty fields and completes rehousing after sowing: passed", flush=True)
        assert not errors, errors
        browser.close()


if __name__ == "__main__":
    main(check, "test/eurozone/artifex_browser_fixture.clj")
