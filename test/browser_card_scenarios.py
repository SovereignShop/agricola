"""User-facing prepared tables through the real Discord SDK and peer host saves."""
import json
import os
from playwright.sync_api import expect, sync_playwright
import browser_discord


def check(base):
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ['EUROZONE_BROWSER'])
        errors = []
        for uid, name in [('111', 'Ada'), ('222', 'Ben')]:
            context = browser.new_context(viewport={'width':1440, 'height':1000})
            parent = browser_discord.PARENT.replace('USER', json.dumps({'id':uid,'username':name}))
            context.route(base+'/discord-test-parent', lambda route,request,parent=parent: route.fulfill(content_type='text/html', body=parent))
            page = context.new_page()
            page.on('pageerror', lambda e: errors.append(str(e)))
            page.goto(base+'/discord-test-parent')
            frame = page.frame_locator('iframe')
            expect(frame.get_by_role('heading',name='Activity tables',exact=True)).to_be_visible()
            frame.get_by_text('Card test tables',exact=True).click()
            frame.get_by_label('Card to test').select_option('swing-plow')
            frame.get_by_role('button',name='Open test table',exact=True).click()
            dialog = frame.get_by_role('dialog',name='Resolve Swing Plow',exact=True)
            expect(dialog).to_be_visible(timeout=20000)
            # The creator controls the decision even for the second Discord user.
            expect(dialog.get_by_role('group',name=name+"'s farm",exact=True)).to_be_visible()
            expect(frame.locator('.tt-player').first).to_have_attribute('data-player','0')
            page.frames[1].evaluate('location.reload()')
            expect(dialog).to_be_visible(timeout=20000)
            farm = dialog.get_by_role('group',name=name+"'s farm",exact=True)
            farm.get_by_role('button',name='Farm space 2: empty',exact=True).click()
            farm.get_by_role('button',name='Farm space 3: empty',exact=True).click()
            dialog.get_by_role('button',name='Resolve effect →',exact=True).click()
            expect(dialog).to_have_count(0)
            expect(frame.locator('.tt-player[data-player="0"] [aria-label$=": field"]')).to_have_count(3)
            page.frames[1].evaluate('location.reload()')
            expect(frame.locator('.tt-player[data-player="0"] [aria-label$=": field"]')).to_have_count(3)
            frame.get_by_role('button',name='← Tables',exact=True).click()
            expect(frame.get_by_role('heading',name='Activity tables',exact=True)).to_be_visible()
            own_table = frame.locator('.activity-table').filter(has=frame.get_by_text('Your table',exact=True))
            expect(own_table).to_have_count(1)
            own_table.get_by_role('button',name='Open table',exact=True).click()
            expect(frame.locator('.tt-player[data-player="0"] [aria-label$=": field"]')).to_have_count(3)
            page.screenshot(path='/tmp/eurozone-card-test-'+uid+'.png')
            context.close()
        assert not errors, errors
        browser.close()
        print('PASS: both Discord users independently host test tables; native farm clicks, reload and lobby reopening retain results')


if __name__ == '__main__':
    browser_discord.check = check
    browser_discord.main()
