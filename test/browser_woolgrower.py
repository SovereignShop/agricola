"""Card housing displays actual quantities on the card that holds them."""
import os
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main


def check(base, fixture):
    housing, baking = fixture.split(",")
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ["EUROZONE_BROWSER"])
        context = browser.new_context(viewport={"width": 1600, "height": 1100})
        context.add_init_script('sessionStorage.setItem("eurozone-session", "' + "a" * 64 + '")')
        page = context.new_page()
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.goto(base + "/#" + housing)
        page.locator(".game").wait_for()
        page.locator(".player-tab").filter(has_text="Ada").click()
        for _ in range(2):
            wool = page.get_by_role("button", name="Woolgrower", exact=True)
            expect(wool.locator('[aria-label="Animals on card"] [aria-label="3 sheep"]')).to_have_count(1)
            # The older compartment card must not display Woolgrower's sheep too.
            expect(page.locator('.played-card:not([aria-label="Woolgrower"]) [aria-label="Animals on card"] [aria-label="3 sheep"]')).to_have_count(0)
            page.reload()
            page.locator(".game").wait_for()
            page.locator(".player-tab").filter(has_text="Ada").click()
        print("Woolgrower shows all three sheep on its own card, including after reload: passed", flush=True)
        page.goto(base + "/#" + baking)
        page.locator(".game").wait_for()
        expect(page.locator(".card-decision")).to_contain_text("Baking Sheet")
        page.reload()
        page.locator(".player-tab").filter(has_text="Ada").click()
        revision = page.locator(".game").get_attribute("data-revision")
        page.get_by_role("button", name="Resolve effect →", exact=True).click()
        expect(page.locator(".game")).not_to_have_attribute("data-revision", revision)
        page.locator(".player-tab").filter(has_text="Ada").click()
        expect(page.locator('[data-tile="farm"] .inventory .resource.grain b')).to_have_text("0")
        expect(page.locator('[data-tile="farm"] .inventory .resource.food b')).to_have_text("6")
        expect(page.locator(".card-decision")).to_have_count(0)
        expect(page.locator("[role=alert]")).to_have_count(0)
        print("Baking Sheet restores its optional grain exchange and completes it once: passed", flush=True)
        assert errors == [], errors
        browser.close()


if __name__ == "__main__":
    main(check, "test/eurozone/woolgrower_browser_fixture.clj")
