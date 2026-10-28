"""Scheduled goods appear on the board, survive reload, and leave on delivery."""
import os
from playwright.sync_api import expect, sync_playwright
from browser_smoke import takeback, main, choose_action, confirm_action


def check_future_rounds(base, fixture_id):
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
        board = page.locator(".physical-board")
        expect(board.locator('.round-card-slot')).to_have_count(14)
        expect(board.locator('.round-card-slot[data-revealed="true"]')).to_have_count(1)
        hidden = board.locator('.round-card-slot[data-revealed="false"]')
        expect(hidden.locator('button, .round-card-front .round-card-art')).to_have_count(0)
        expect(board.locator('.round-action strong')).to_have_text("Sheep Market")
        # Both unmodified sprite sheets load locally, without remote image requests.
        for side in ["front", "back"]:
            assert page.evaluate("""src => new Promise(resolve => {
              const img = new Image(); img.onload = () => resolve(img.naturalWidth > 0);
              img.onerror = () => resolve(false); img.src = src;
            })""", f"/art/agricola/round-{side}.jpg")
        round2 = board.locator('.future-round-resources[data-round="2"]')
        ada = round2.locator('[data-player="0"] [data-resource="food"]')
        ben = round2.locator('[data-player="1"] [data-resource="food"]')
        expect(ada.locator("b")).to_have_text("1")
        expect(ben.locator("b")).to_have_text("2")
        choose_action(page, "Meeting Place")
        page.get_by_label("Minor improvement", exact=True).select_option(":revised-base/a044")
        confirm_action(page)
        expect(ada.locator("b")).to_have_text("2")
        expect(ben.locator("b")).to_have_text("2")
        expect(round2).to_be_visible()
        page.reload()
        expect(ada.locator("b")).to_have_text("2")
        page.wait_for_function("[...document.querySelectorAll('.future-resource img')].every(e => e.complete && e.naturalWidth > 0)")
        expect(board.locator('[data-resource="field"] svg')).to_have_count(1)
        board.screenshot(path="/tmp/eurozone-future-rounds.png")
        for width in [1440, 390]:
            page.set_viewport_size({"width": width, "height": 1000})
            assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
            # Every marker stays within the physical board and its goods are readable.
            assert board.evaluate("""board => {
              const b = board.getBoundingClientRect();
              return [...board.querySelectorAll('.future-round-resources')].every(e => {
                const r = e.getBoundingClientRect();
                return r.left >= b.left && r.right <= b.right + 1 && r.top >= b.top && r.bottom <= b.bottom + 1;
              });
            }""")
        page.set_viewport_size({"width": 1440, "height": 1000})
        # The same mounted slot receives the round-start flip, not a replacement node.
        page.evaluate("""() => {
          window.roundSlot = document.querySelector('.round-card-slot[data-round="2"]');
          window.flips = 0;
          window.roundSlot.addEventListener('transitionend', e => {
            if (e.propertyName === 'transform') window.flips++;
          });
        }""")
        for action in ["Grain Seeds", "Forest", "Clay Pit"]:
            choose_action(page, action)
            confirm_action(page)
        expect(round2).to_have_count(0)
        slot = board.locator('.round-card-slot[data-round="2"]')
        expect(slot).to_have_attribute("data-revealed", "true")
        expect(slot.locator(".round-action strong")).to_have_text("Major or Minor Improvement")
        page.wait_for_function("window.flips === 1")
        assert slot.evaluate("e => e === window.roundSlot")
        board.screenshot(path="/tmp/eurozone-round-card-revealed.png")
        expect(board.locator('.future-round-resources[data-round="3"] [data-player="0"] b')).to_have_text("2")
        page.reload()
        expect(slot).to_have_attribute("data-revealed", "true")
        # Undo crosses the round boundary and removes the now-private front.
        takeback(page)
        expect(slot).to_have_attribute("data-revealed", "false")
        expect(slot.locator("button, .round-card-front .round-card-art")).to_have_count(0)
        page.emulate_media(reduced_motion="reduce")
        assert slot.locator('.round-card-flipper').evaluate("e => getComputedStyle(e).transitionDuration") == "0s"
        choose_action(page, "Clay Pit")
        confirm_action(page)
        expect(slot).to_have_attribute("data-revealed", "true")
        assert errors == [], errors
        browser.close()
        print("Round-card artwork, hidden fronts, animated reveal, reload, undo, reduced motion; future goods, mobile placement, and delivery: passed")


if __name__ == "__main__":
    main(check_future_rounds, "test/eurozone/future_round_fixture.clj")
