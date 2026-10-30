"""Off-turn construction, physical reservations, and temporary family choices."""
import os
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main


def check(base, fixtures):
    stable, fence, riparian, planner, arrival, lodger, telegram = fixtures.split(",")
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

        def resolve():
            revision = page.locator(".game").get_attribute("data-revision")
            page.get_by_role("button", name="Resolve effect →", exact=True).click()
            expect(page.locator(".game")).not_to_have_attribute("data-revision", revision)
            expect(page.locator("[role=alert]")).to_have_count(0)

        open_game(stable)
        page.locator(".card-decision select").first.select_option("1")
        resolve()
        page.get_by_label("Build stable 1", exact=True).check()
        page.get_by_label("Build stable 2", exact=True).check()
        expect(page.get_by_label("Build stable 2", exact=True)).to_be_checked()
        resolve()
        ada()
        expect(amount("wood")).to_have_text("16")
        expect(page.locator(".player-tab").filter(has_text="Ada")).to_contain_text("2 / 2 workers ready")
        print("Stagehand builds two stables off turn without spending a worker: passed", flush=True)

        open_game(fence)
        page.locator(".card-decision select").first.select_option("2")
        resolve()
        for edge in ["h:0:0", "h:1:0", "v:0:0", "v:0:1"]:
            page.locator(".card-decision").get_by_label("Fence " + edge, exact=True).click(force=True)
        expect(page.get_by_role("button", name="Resolve effect →", exact=True)).to_be_enabled()
        resolve()
        ada()
        expect(amount("wood")).to_have_text("16")
        print("Stagehand exposes an interactive fence workflow: passed", flush=True)

        open_game(riparian)
        page.reload()
        expect(page.locator(".card-decision")).to_contain_text("Riparian Builder")
        page.get_by_label("Build room 1", exact=True).check()
        resolve()
        ada()
        expect(amount("clay")).to_have_text("16")
        expect(amount("reed")).to_have_text("8")
        expect(page.locator(".player-tab").filter(has_text="Ada")).to_contain_text("2 / 2 workers ready")
        print("Riparian Builder preserves its off-turn discounted room choice: passed", flush=True)

        open_game(planner)
        for round in [4, 7, 10]:
            page.get_by_label("Reserve stable for round " + str(round), exact=True).check()
        resolve()
        ada()
        expect(page.locator('[data-tile="farm"] .farm-stats')).to_contain_text("3 reserved")
        expect(page.locator('.future-round-resources[data-round="4"] [data-resource="stable"]')).to_have_count(1)
        print("Stable Planner reserves and displays actual stable pieces: passed", flush=True)

        open_game(arrival)
        page.reload()
        page.get_by_label("Farm space", exact=True).select_option("0")
        resolve()
        ada()
        expect(page.locator('[data-tile="farm"] .farm-stats')).to_contain_text("1/4 stables")
        expect(page.locator('[data-tile="farm"] .farm-stats')).to_contain_text("2 reserved")
        expect(amount("wood")).to_have_text("0")
        print("Stable Planner's arriving piece is free and survives reload: passed", flush=True)

        open_game(lodger)
        page.reload()
        page.get_by_label("Person to remove", exact=True).select_option("newborn")
        resolve()
        ada()
        expect(page.locator('[data-tile="farm"] .farm-info')).to_contain_text("2 family members")
        expect(page.locator(".card-decision")).to_have_count(0)
        print("Lodger saves the required adult/newborn removal choice: passed", flush=True)

        open_game(telegram)
        expect(page.locator(".card-decision")).to_contain_text("Telegram")
        resolve()
        ada()
        expect(page.locator(".player-tab").filter(has_text="Ada")).to_contain_text("3 / 3 workers ready")
        print("Telegram offers its temporary worker in the scheduled round: passed", flush=True)
        assert errors == [], errors
        browser.close()


if __name__ == "__main__":
    main(check, "test/eurozone/artifex_temporary_browser_fixture.clj")
