"""Multi-field card decisions survive reload and precede Cultivation sowing."""
import os
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main, choose_action, confirm_action


def check(base, fixtures):
    double, wheel, cultivation = fixtures.split(",")
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

        def click_decision(label="Resolve effect →"):
            revision = page.locator(".game").get_attribute("data-revision")
            page.get_by_role("button", name=label, exact=True).click()
            expect(page.locator(".game")).not_to_have_attribute("data-revision", revision)
            expect(page.locator("[role=alert]")).to_have_count(0)

        def choose_fields(first, second):
            page.get_by_label("Plow field 1", exact=True).select_option(str(first))
            expect(page.get_by_role("button", name="Resolve effect →", exact=True)).to_be_enabled()
            page.get_by_label("Plow field 2", exact=True).select_option(str(second))
            expect(page.get_by_role("button", name="Resolve effect →", exact=True)).to_be_enabled()

        def fields(count):
            ada()
            for number in range(1, count + 1):
                expect(page.locator('[data-tile="farm"] [aria-label="Farm space ' + str(number) + ': field"]')).to_have_count(1)
            expect(page.locator(".player-tab").filter(has_text="Ada")).to_contain_text("1 / 2 workers ready")

        open_game(double)
        expect(page.locator(".card-decision")).to_contain_text("Double-Turn Plow")
        page.reload()
        ada()
        choose_fields(0, 1)
        click_decision()
        fields(2)
        print("Double-Turn Plow saved choice builds two adjacent fields with one placement: passed", flush=True)

        open_game(wheel)
        choose_action(page, "Farmland")
        page.get_by_role("button", name="Farm space 1: empty", exact=True).click()
        confirm_action(page)
        expect(page.locator(".card-decision")).to_contain_text("Wheel Plow")
        page.reload()
        ada()
        choose_fields(1, 2)
        click_decision()
        fields(3)
        print("Wheel Plow reload keeps base field and chooses two additional fields: passed", flush=True)

        open_game(cultivation)
        choose_action(page, "Cultivation")
        page.get_by_role("button", name="Farm space 1: empty", exact=True).click()
        confirm_action(page)
        choose_fields(1, 2)
        click_decision()
        click_decision("Take Sow action")
        page.reload()
        ada()
        for number in range(1, 4):
            page.get_by_label("Sow field " + str(number), exact=True).select_option("grain")
            expect(page.get_by_role("button", name="Resolve effect →", exact=True)).to_be_enabled()
        # Let the controlled selects render, then check all edits survived.
        page.evaluate("() => new Promise(done => requestAnimationFrame(() => requestAnimationFrame(done)))")
        for number in range(1, 4):
            expect(page.get_by_label("Sow field " + str(number), exact=True)).to_have_value("grain")
        click_decision()
        fields(3)
        expect(page.locator('[data-tile="farm"] svg.farm [data-piece-type="grain"]')).to_have_count(9)
        print("Cultivation can sow all three newly plowed fields after saved decisions: passed", flush=True)
        assert errors == [], errors
        page.screenshot(path="/tmp/eurozone-artifex-plows.png", full_page=True)
        browser.close()


if __name__ == "__main__":
    main(check, "test/eurozone/artifex_plow_browser_fixture.clj")
