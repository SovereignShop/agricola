"""Pinned chat over real RTC channels, local persistence, isolation, and mobile sizing."""
import os
import re
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main


def check(base, fixtures):
    _, online, _, _, _, other = fixtures.split(',')
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ['EUROZONE_BROWSER'])
        contexts = [browser.new_context(viewport={'width': 1440, 'height': 1000}) for _ in range(2)]
        errors = []
        pages = []
        for context, token in zip(contexts, ['a', 'b']):
            context.add_init_script('sessionStorage.setItem("eurozone-session", "'+token*64+'")')
            page = context.new_page()
            page.on('pageerror', lambda error: errors.append(str(error)))
            page.goto(base+'/#'+online)
            page.get_by_role('button', name='Open table chat', exact=True).click()
            pages.append(page)
        h, g = pages
        for page in pages:
            expect(page.get_by_label('Chat message', exact=True)).to_be_enabled(timeout=30000)
        revision = h.locator('.game').get_attribute('data-revision')
        def send(page, text):
            page.get_by_label('Chat message', exact=True).fill(text)
            page.get_by_role('button', name='Send', exact=True).click()
        send(h, '<script>hello</script> from Ada')
        expect(g.locator('.chat-message')).to_contain_text('<script>hello</script> from Ada')
        assert g.locator('.chat-message script').count() == 0
        g.get_by_role('button', name='Collapse chat', exact=True).click()
        send(h, 'Unread conversation')
        expect(g.locator('.chat-unread')).to_have_text('1')
        g.reload()
        expect(g.locator('.chat-unread')).to_have_text('1')
        g.get_by_role('button', name='Open table chat', exact=True).click()
        expect(g.locator('.chat-unread')).to_have_count(0)
        expect(g.locator('.chat-message')).to_have_count(2)
        send(g, 'Hello back!')
        expect(h.locator('.chat-message').last).to_contain_text('Hello back!')
        expect(h.locator('.game')).to_have_attribute('data-revision', revision)
        expect(h.get_by_role('dialog')).to_have_count(0)
        # Pin does not block camera buttons or dismiss the conversation.
        h.get_by_role('button', name='Ada player view', exact=True).click()
        expect(h.locator('.chat-dock-surface')).to_be_visible()
        h.get_by_role('button', name='Unpin chat', exact=True).click()
        h.get_by_role('button', name='Ben player view', exact=True).click()
        expect(h.locator('.chat-dock-surface')).to_have_count(0)
        h.get_by_role('button', name='Open table chat', exact=True).click()
        h.get_by_role('button', name='Pin chat', exact=True).click()
        small = h.locator('.chat-dock-surface').bounding_box()
        h.get_by_role('button', name='Expand chat', exact=True).click()
        expect(h.locator('.chat-dock')).to_have_class(re.compile(r'\bexpanded\b'))
        large = h.locator('.chat-dock-surface').bounding_box()
        assert large['width'] > small['width'] and large['height'] > small['height']
        h.get_by_role('button', name='Move chat to left corner', exact=True).click()
        expect(h.locator('.chat-dock')).to_have_class(re.compile(r'\bcorner-left\b'))
        assert h.locator('.chat-dock').bounding_box()['x'] < 20
        h.reload()
        expect(h.get_by_role('button', name='Restore chat size', exact=True)).to_be_visible()
        expect(h.get_by_role('button', name='Unpin chat', exact=True)).to_be_visible()
        expect(h.locator('.chat-message')).to_have_count(3)
        expect(h.locator('.chat-dock')).to_have_class(re.compile(r'\bcorner-left\b'))
        assert h.locator('.chat-dock').bounding_box()['x'] < 20
        h.get_by_role('button', name='Move chat to right corner', exact=True).click()
        h.get_by_role('button', name='Restore chat size', exact=True).click()
        expect(h.locator('.chat-dock')).to_have_class(re.compile(r'\bopen\b'))
        h.screenshot(path='/tmp/eurozone-chat-desktop.png')
        # Messages and preferences are separate for every table.
        h.goto(base+'/#'+other)
        h.get_by_role('button', name='Open table chat', exact=True).click()
        expect(h.locator('.chat-message')).to_have_count(0)
        h.goto(base+'/#'+online)
        expect(h.locator('.chat-message')).to_have_count(3)
        # Same localStorage, a different signed-in seat, no Ada history leaked.
        isolated = contexts[0].new_page()
        isolated.add_init_script('sessionStorage.setItem("eurozone-session", "'+'b'*64+'")')
        isolated.goto(base+'/#'+online)
        isolated.get_by_role('button', name='Open table chat', exact=True).click()
        expect(isolated.locator('.chat-message')).to_have_count(0)
        isolated.close()
        # Expanded chat remains within portrait and landscape phone viewports.
        h.get_by_role('button', name='Expand chat', exact=True).click()
        expect(h.locator('.chat-dock')).to_have_class(re.compile(r'\bexpanded\b'))
        for width, height in [(390, 844), (844, 390)]:
            h.set_viewport_size({'width': width, 'height': height})
            box = h.locator('.chat-dock-surface').bounding_box()
            assert box['x'] >= 0 and box['y'] >= 0
            assert box['x']+box['width'] <= width and box['y']+box['height'] <= height
            expect(h.get_by_label('Chat message', exact=True)).to_be_in_viewport()
        h.set_viewport_size({'width': 390, 'height': 844})
        h.screenshot(path='/tmp/eurozone-chat-mobile.png')
        h.get_by_role('button', name='Clear history', exact=True).click()
        expect(h.locator('.chat-message')).to_have_count(0)
        h.reload()
        expect(h.locator('.chat-message')).to_have_count(0)
        expect(g.locator('.chat-message')).to_have_count(3)
        assert not errors, errors
        print('RTC delivery, unread count, pin/collapse/expand, reload persistence, user/table isolation, local clear, and mobile bounds: passed')
        browser.close()


if __name__ == '__main__':
    main(check, 'test/eurozone/tabletop_browser_fixture.clj')
