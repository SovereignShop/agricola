"""Native card prerequisites, automatic effects, and saved decisions through the UI."""
import os
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main, choose_action, confirm_action


def check_cards(base, fixture_id):
    with sync_playwright() as pw:
        options = {"headless": True}
        if os.environ.get("EUROZONE_BROWSER"):
            options["executable_path"] = os.environ["EUROZONE_BROWSER"]
        browser = pw.chromium.launch(**options)
        context = browser.new_context(viewport={"width": 1440, "height": 1000})
        context.add_init_script('if (location.origin === ' + repr(base) + ') sessionStorage.setItem("eurozone-session", "' + "a" * 64 + '")')
        page = context.new_page()
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.goto(base + "/#" + fixture_id)
        page.get_by_role("heading", name="Make your move.").wait_for()
        choose_action(page, "Meeting Place")
        minor = page.get_by_role("combobox", name="Minor improvement", exact=True)
        expect(minor.locator('option[value=":revised-base/b068"]')).to_have_attribute("disabled", "")
        expect(minor.locator('option[value=":revised-base/b068"]')).to_contain_text("Requires: 2 Occupations")
        expect(minor.locator('option[value=":revised-base/b056"]')).to_have_attribute("disabled", "")
        expect(minor.locator('option[value=":revised-base/b056"]')).to_contain_text("Fishing")
        page.get_by_role("button", name="Cancel", exact=True).click()
        choose_action(page, "Lessons")
        expect(page.get_by_role("button", name="Place family member →")).to_be_disabled()
        page.get_by_label("Occupation", exact=True).select_option(":revised-base/a116")
        confirm_action(page)
        choose_action(page, "Day Laborer")
        confirm_action(page)
        choose_action(page, "Forest")
        confirm_action(page)
        page.locator(".player-tab").filter(has_text="Ada").click()
        expect(page.locator('[data-tile="farm"] .inventory .resource.wood b')).to_have_text("14")
        expect(page.locator(".card-decision")).to_have_count(0)
        expect(page.get_by_role("button", name="Wood Cutter", exact=True).first).to_be_visible()
        page.locator(".player-tab").filter(has_text="Ben").click()
        choose_action(page, "Grain Seeds")
        confirm_action(page)
        print("Printed/special prerequisites disabled; Lessons and Wood Cutter auto-trigger: passed")

        choose_action(page, "Lessons")
        page.get_by_label("Occupation", exact=True).select_option(":revised-base/a112")
        confirm_action(page)
        page.locator(".player-tab").filter(has_text="Ada").click()
        expect(page.locator('[data-tile="farm"] .inventory .resource.grain b')).to_have_text("1")
        page.locator(".player-tab").filter(has_text="Ben").click()
        choose_action(page, "Forest")
        confirm_action(page)
        choose_action(page, "Meeting Place")
        expect(page.get_by_label("Minor improvement", exact=True).locator('option[value=":revised-base/b068"]')).not_to_have_attribute("disabled", "")
        page.get_by_label("Minor improvement", exact=True).select_option(":revised-base/b068")
        confirm_action(page)
        choose_action(page, "Clay Pit")
        confirm_action(page)
        print("Occupation effects and newly satisfied Beanfield prerequisite: passed")

        choose_action(page, "Meeting Place")
        page.get_by_label("Minor improvement", exact=True).select_option(":revised-base/a002")
        # This action pauses for a field placement, so the action editor is replaced.
        confirm_action(page)
        expect(page.locator(".card-decision")).to_be_visible()
        expect(page.locator(".turn-banner")).to_have_attribute("data-your-turn", "true")
        expect(page.locator(".turn-banner")).to_contain_text("Resolve Shifting Cultivation")
        expect(page.get_by_role("button", name="Resolve effect →")).to_be_disabled()
        page.reload()
        page.get_by_role("heading", name="Shifting Cultivation", exact=True).wait_for()
        page.get_by_label("Farm space", exact=True).select_option("4")
        revision = page.locator(".game").get_attribute("data-revision")
        page.get_by_role("button", name="Resolve effect →").click()
        expect(page.locator(".game")).not_to_have_attribute("data-revision", revision)
        expect(page.locator(".card-decision")).to_have_count(0)
        page.get_by_role("button", name="Minors · 1", exact=True).click()
        expect(page.locator(".hand-panel").get_by_role("button", name="Shifting Cultivation", exact=True)).to_be_visible()
        page.locator(".player-tab").filter(has_text="Ada").click()
        expect(page.locator('[aria-label="Farm space 5: field"]')).to_be_visible()
        page.locator(".hand-panel").scroll_into_view_if_needed()
        page.wait_for_function("[...document.querySelectorAll('.hand-panel img')].every(e => e.complete && e.naturalWidth > 0)")
        page.screenshot(path="/tmp/eurozone-native-cards.png", full_page=True)
        assert errors == [], errors
        print("Field choice persists through reload; travelling card passes after resolution: passed")
        browser.close()


if __name__ == "__main__":
    main(check_cards, "test/eurozone/card_browser_fixture.clj")
