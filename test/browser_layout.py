"""Real pointer/keyboard checks for the full-width table workspace."""
import os
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main


def check_layout(base, fixture_id):
    with sync_playwright() as pw:
        opts = {"headless": True}
        if os.environ.get("EUROZONE_BROWSER"):
            opts["executable_path"] = os.environ["EUROZONE_BROWSER"]
        browser = pw.chromium.launch(**opts)
        context = browser.new_context(viewport={"width": 2560, "height": 1200})
        context.add_init_script('if (location.origin === ' + repr(base) + ') sessionStorage.setItem("eurozone-session", "' + "a" * 64 + '")')
        page = context.new_page()
        errors = []
        page.on("pageerror", lambda e: errors.append(str(e)))
        page.goto(base + "/#" + fixture_id)
        page.get_by_role("heading", name="Make your move.").wait_for()
        expect(page.locator(".tile-row")).to_have_count(3)
        assert page.locator(".game").bounding_box()["width"] > 2500
        assert page.locator(".tile-row").first.bounding_box()["width"] > 2500
        page.screenshot(path="/tmp/eurozone-workspace-initial.png")
        # Includes the turn banner above the table (production baseline is about 343px).
        assert page.locator(".tile-grid").bounding_box()["y"] < 380
        board = page.locator('[data-tile="board"]')
        farm = page.locator('[data-tile="farm"]')
        assert abs(board.bounding_box()["y"] - farm.bounding_box()["y"]) < 2
        def assert_full_width():
            assert page.evaluate("Math.abs(document.querySelector('.game').getBoundingClientRect().width - document.documentElement.clientWidth) < 2")
            assert board.locator(".action-board").bounding_box()["width"] >= board.bounding_box()["width"] - 20
            assert board.locator(".physical-board").bounding_box()["width"] >= board.bounding_box()["width"] - 40
            assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")

        for width, height in [(2560, 1200), (3440, 900), (1440, 700)]:
            page.set_viewport_size({"width": width, "height": height})
            assert_full_width()
        page.set_viewport_size({"width": 2560, "height": 1200})
        print("Viewport-width workspace and board filling its panel across screen sizes: passed")

        def rows():
            return page.locator(".tile-row").evaluate_all("rs=>rs.map(r=>[...r.querySelectorAll('.table-tile')].map(t=>t.dataset.tile))")

        def drag_to_edge(source, target, edge, cancel=False):
            handle = page.get_by_role("button", name="Move " + source, exact=True)
            handle.scroll_into_view_if_needed()
            start = handle.bounding_box()
            dest = page.locator('[data-tile="' + target + '"]').bounding_box()
            x = dest["x"] + dest["width"] * ({"left": .15, "right": .85}.get(edge, .5))
            y = dest["y"] + dest["height"] * ({"top": .05, "bottom": .9}.get(edge, .45))
            page.mouse.move(start["x"] + 25, start["y"] + start["height"] / 2)
            page.mouse.down()
            page.mouse.move(x, y, steps=12)
            expect(page.locator(".dock-preview." + edge)).to_be_visible()
            if cancel:
                page.keyboard.press("Escape")
                expect(page.locator(".dock-preview")).to_have_count(0)
            page.mouse.up()

        before = rows()
        drag_to_edge("Table chat", "farm", "left", cancel=True)
        assert rows() == before
        drag_to_edge("Table chat", "farm", "left")
        expect(page.locator(".tile-row").first.locator(".table-tile").nth(1)).to_have_attribute("data-tile", "chat")
        drag_to_edge("Table chat", "farm", "top")
        expect(page.locator(".tile-row").first.locator(".table-tile")).to_have_count(1)
        expect(page.locator(".tile-row").first.locator(".table-tile")).to_have_attribute("data-tile", "chat")
        page.get_by_role("button", name="Table view", exact=True).click()
        expect(page.locator(".tile-row")).to_have_count(3)
        print("Dock left, dock above, visible previews, Escape cancellation, and preset restore: passed")

        separator = page.get_by_role("separator", name="Resize panels board and farm", exact=True)
        before_width = board.bounding_box()["width"]
        chat_width = page.locator('[data-tile="chat"]').bounding_box()["width"]
        separator.focus()
        separator.press("ArrowRight")
        page.wait_for_function("w => document.querySelector('[data-tile=board]').getBoundingClientRect().width > w+20", arg=before_width)
        assert abs(page.locator('[data-tile="chat"]').bounding_box()["width"] - chat_width) < 2
        handle = separator.bounding_box()
        page.mouse.move(handle["x"] + 5, handle["y"] + 100)
        page.mouse.down()
        page.mouse.move(handle["x"] + 105, handle["y"] + 100, steps=8)
        page.mouse.up()
        widths = [board.bounding_box()["width"], farm.bounding_box()["width"]]
        page.reload()
        page.get_by_role("heading", name="Make your move.").wait_for()
        assert abs(board.bounding_box()["width"] - widths[0]) < 3
        assert abs(farm.bounding_box()["width"] - widths[1]) < 3
        print("Pointer/keyboard split resizing and saved proportions after reload: passed")

        y = page.locator(".tile-grid").bounding_box()["y"]
        page.get_by_role("button", name="Focus table", exact=True).click()
        expect(page.locator(".site-header")).to_be_hidden()
        assert page.locator(".tile-grid").bounding_box()["y"] < y - 30
        page.get_by_role("button", name="Maximize Shared board", exact=True).click()
        expect(farm).to_be_hidden()
        assert board.bounding_box()["width"] > 2500
        assert_full_width()
        page.keyboard.press("Escape")
        expect(farm).to_be_visible()
        page.get_by_role("button", name="Hide Table chat", exact=True).click()
        expect(page.locator('[data-tile="chat"]')).to_have_count(0)
        page.get_by_role("button", name="Panels", exact=True).click()
        page.get_by_label("Table chat", exact=True).click()
        expect(page.locator('[data-tile="chat"]')).to_have_count(1)
        page.get_by_role("button", name="Panels", exact=True).click()
        page.get_by_role("button", name="Show all farms", exact=True).click()
        expect(page.locator('[data-tile="farm-0"]')).to_be_visible()
        expect(page.locator('[data-tile="farm-1"]')).to_be_visible()
        assert page.locator('[data-tile="farm-0"]').bounding_box()["y"] < page.locator('[data-tile="hand"]').bounding_box()["y"]
        page.reload()
        page.get_by_role("heading", name="Make your move.").wait_for()
        expect(page.locator('[data-tile="farm-1"]')).to_be_visible()
        page.get_by_role("button", name="Table view", exact=True).click()
        page.get_by_role("button", name="Fullscreen", exact=True).click()
        page.wait_for_function("!!document.fullscreenElement")
        page.get_by_role("button", name="Exit fullscreen", exact=True).click()
        page.wait_for_function("!document.fullscreenElement")
        page.screenshot(path="/tmp/eurozone-workspace-wide.png", full_page=True)
        page.set_viewport_size({"width": 390, "height": 844})
        page.screenshot(path="/tmp/eurozone-workspace-mobile.png", full_page=True)
        assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
        expect(page.get_by_role("button", name="Panels", exact=True)).to_be_visible()
        page.screenshot(path="/tmp/eurozone-workspace-mobile.png", full_page=True)
        assert errors == [], errors
        print("Focus, maximize/restore, visibility, all-farms placement, persistence, and mobile: passed")
        browser.close()


if __name__ == "__main__":
    main(check_layout, "test/eurozone/card_browser_fixture.clj")
