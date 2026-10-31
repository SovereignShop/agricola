"""Real Plowman play and saved schedules render every kind of future piece."""
import os
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main, choose_action, confirm_action


def check(base, fixture_id):
    with sync_playwright() as pw:
        options = {"headless": True}
        if os.environ.get("EUROZONE_BROWSER"):
            options["executable_path"] = os.environ["EUROZONE_BROWSER"]
        browser = pw.chromium.launch(**options)
        context = browser.new_context(viewport={"width": 1600, "height": 1000})
        context.add_init_script('sessionStorage.setItem("eurozone-session", "' + 'a' * 64 + '")')
        page = context.new_page()
        errors = []
        page.on("pageerror", lambda e: errors.append(str(e)))
        page.goto(base + "/#" + fixture_id)
        board = page.locator(".physical-board")
        expect(board).to_be_visible()

        def piece(round, player, resource):
            return board.locator(f'.future-round-resources[data-round="{round}"] [data-player="{player}"] [data-resource="{resource}"]')

        expect(piece(5, 0, "field")).to_have_count(0)
        choose_action(page, "Lessons")
        page.get_by_role("group", name="Playable occupations", exact=True).get_by_role("button", name="Plowman", exact=True).click()
        page.get_by_role("button", name="Select card", exact=True).click()
        confirm_action(page)

        def check_pieces():
            for round in [5, 8, 11]:
                marker = piece(round, 0, "field")
                expect(marker.locator("b")).to_have_text("1")
                expect(marker).to_have_attribute("title", "Ada · 1 field · Plowman · optional · pay 1 food")
            for round, animal in [(7, "sheep"), (10, "boar"), (14, "cattle")]:
                expect(piece(round, 1, animal).locator("b")).to_have_text("1")
            for round in [7, 11]:
                expect(piece(round, 2, "fence").locator("b")).to_have_text("4")
            expect(piece(2, 3, "guest").locator("b")).to_have_text("1")
            for resource in ["food", "stable", "person"]:
                expect(piece(4, 4, resource).locator("b")).to_have_text("1")
            # No nested scroll areas, hidden pieces, or broken artwork.
            assert board.locator('.future-round-owners').evaluate_all('els => els.every(e => !["auto", "scroll"].includes(getComputedStyle(e).overflowY))')
            assert board.locator('.future-resource').evaluate_all('els => els.every(e => {const r=e.getBoundingClientRect(), p=e.closest(".future-round-resources").getBoundingClientRect(); return r.left >= p.left && r.right <= p.right + 1 && r.bottom <= p.bottom + 1})')
            page.wait_for_function('[...document.querySelectorAll(".future-resource img")].every(e => e.complete && e.naturalWidth > 0)')

        check_pieces()
        page.reload()
        check_pieces()
        board.screenshot(path="/tmp/eurozone-future-pieces.png")
        page.set_viewport_size({"width": 390, "height": 844})
        check_pieces()
        assert page.evaluate('document.documentElement.scrollWidth <= innerWidth')
        assert not errors, errors
        browser.close()
        print("Plowman live play, all future piece kinds, optional costs, reload, and mobile: passed")


if __name__ == "__main__":
    main(check, "test/eurozone/future_pieces_browser_fixture.clj")
