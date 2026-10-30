"""Text scaling keeps board geometry and works inside the real Activity SDK iframe."""
import json
import os
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main, create_table
import browser_discord as discord


def font(locator):
    return locator.evaluate('e => parseFloat(getComputedStyle(e).fontSize)')


def check(base, fixture_id):
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ['EUROZONE_BROWSER'])
        context = browser.new_context(viewport={'width': 1600, 'height': 1100})
        context.add_init_script('sessionStorage.setItem("eurozone-session", "' + 'a' * 64 + '")')
        page = context.new_page(); errors = []
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.goto(base)
        create_table(page, 'Ada', 'Starting food')
        expect(page.locator('.starting-food')).to_contain_text('starting player gets 2 food; everyone else gets 3')
        page.goto(base + '/#' + fixture_id)
        expect(page.locator('.game')).to_be_visible()
        reset = page.get_by_role('button', name='Reset text size', exact=True)
        expect(reset).to_have_text('100%')
        heading = page.get_by_role('heading', name='Make your move.', exact=True)
        initial = font(heading)
        board = page.locator('svg.farm').first
        width = board.bounding_box()['width']
        revision = page.locator('.game').get_attribute('data-revision')
        page.get_by_role('button', name='Increase text size', exact=True).click()
        expect(reset).to_have_text('110%')
        assert abs(font(heading) / initial - 1.1) < .01
        page.keyboard.press('Control+=')
        expect(reset).to_have_text('120%')
        page.keyboard.press('Control+Shift+=')
        expect(reset).to_have_text('130%')
        page.keyboard.press('Control+-')
        expect(reset).to_have_text('120%')
        assert abs(board.bounding_box()['width'] - width) < 1
        assert page.locator('.game').get_attribute('data-revision') == revision
        page.reload()
        expect(reset).to_have_text('120%')
        page.get_by_role('button', name='How to play', exact=True).click()
        expect(reset).to_be_visible()
        page.get_by_role('button', name='Village & farm', exact=True).click()
        page.get_by_role('button', name='Focus table', exact=True).click()
        expect(page.locator('.site-header')).to_be_hidden()
        expect(reset).to_be_visible()
        page.keyboard.press('Control+0')
        expect(reset).to_have_text('100%')
        for _ in range(10): page.get_by_role('button', name='Increase text size', exact=True).click()
        expect(reset).to_have_text('200%')
        expect(page.get_by_role('button', name='Increase text size', exact=True)).to_be_disabled()
        assert abs(font(heading) / initial - 2) < .01
        assert abs(board.bounding_box()['width'] - width) < 1
        page.screenshot(path='/tmp/eurozone-text-size-200.png', full_page=True)
        reset.click()
        expect(reset).to_have_text('100%')
        page.set_viewport_size({'width': 390, 'height': 844})
        for _ in range(5): page.get_by_role('button', name='Increase text size', exact=True).click()
        expect(reset).to_have_text('150%')
        page.screenshot(path='/tmp/eurozone-text-size-mobile.png', full_page=True)
        page.wait_for_function('document.documentElement.scrollWidth <= innerWidth')
        expect(reset).to_be_visible()
        page.screenshot(path='/tmp/eurozone-text-size-mobile.png', full_page=True)
        for _ in range(7): page.get_by_role('button', name='Decrease text size', exact=True).click()
        expect(reset).to_have_text('80%')
        expect(page.get_by_role('button', name='Decrease text size', exact=True)).to_be_disabled()
        # A malformed preference cannot produce tiny or infinite text on startup.
        page.evaluate('localStorage.setItem("eurozone-text-size-v1", "NaN")')
        page.reload(); expect(reset).to_have_text('100%')
        assert errors == [], errors
        browser.close()
        print('Starting food, text buttons/shortcuts, bounds, persistence, focus, geometry and mobile: passed', flush=True)


def check_activity(base):
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ['EUROZONE_BROWSER'])
        ctx = browser.new_context(viewport={'width': 1440, 'height': 1100})
        parent = discord.PARENT.replace('USER', json.dumps({'id': '111', 'username': 'Ada'}))
        ctx.route(base + '/discord-test-parent', lambda route: route.fulfill(content_type='text/html', body=parent))
        page = ctx.new_page(); errors = []
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.goto(base + '/discord-test-parent')
        frame = page.frame_locator('iframe')
        discord.enter_activity(frame, True)
        expect(frame.locator('.starting-food')).to_contain_text('starting player gets 2 food')
        frame.get_by_role('button', name='Increase text size', exact=True).click()
        reset = frame.get_by_role('button', name='Reset text size', exact=True)
        expect(reset).to_have_text('110%')
        page.keyboard.press('Control+='); expect(reset).to_have_text('120%')
        child = page.frames[1]
        with child.expect_navigation(wait_until='load'): child.evaluate('location.reload()')
        expect(reset).to_have_text('120%')
        reset.click(); expect(reset).to_have_text('100%')
        assert errors == [], errors
        browser.close()
        print('Discord Activity iframe: text controls, keyboard shortcuts and reload persistence passed', flush=True)


if __name__ == '__main__':
    main(check, 'test/eurozone/card_browser_fixture.clj')
    discord.check = check_activity
    discord.main()
