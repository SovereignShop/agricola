"""Configure optional rules inside Discord and retain them after host reconnect."""
import json
import os
from playwright.sync_api import expect, sync_playwright
import browser_discord as discord

SWITCHES = ['Use card banlist', 'Harder Braggart', 'Adjust starting food']


def check(base):
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ['EUROZONE_BROWSER'])
        contexts, pages, apps, errors = [], [], [], []
        for uid, name in [('111', 'Ada'), ('222', 'Ben'), ('444', 'Cy'), ('555', 'Dee')]:
            context = browser.new_context(viewport={'width': 1440, 'height': 1000})
            contexts.append(context)
            parent = discord.PARENT.replace('USER', json.dumps({'id': uid, 'username': name}))
            context.route(base+'/discord-test-parent', lambda route, request, parent=parent: route.fulfill(content_type='text/html', body=parent))
            page = context.new_page(); pages.append(page)
            page.on('pageerror', lambda e: errors.append(str(e)))
            page.goto(base+'/discord-test-parent')
            app = page.frame_locator('iframe').first; apps.append(app)
            discord.enter_activity(app, uid == '111')
            if uid == '111':
                app.get_by_label('Players expected').select_option('4')
                expect(app.locator('.app')).to_have_attribute('aria-busy', 'false')
                for label in SWITCHES:
                    expect(app.get_by_role('checkbox', name=label, exact=True)).not_to_be_checked()
                    app.get_by_role('checkbox', name=label, exact=True).click()
                    expect(app.get_by_role('checkbox', name=label, exact=True)).to_be_checked()
                    expect(app.locator('.app')).to_have_attribute('aria-busy', 'false')
            else:
                app.get_by_placeholder('Your name').fill(name)
                app.get_by_role('button', name='Take a seat', exact=True).click()
                for label in SWITCHES:
                    expect(app.get_by_role('checkbox', name=label, exact=True)).to_be_checked()
                    expect(app.get_by_role('checkbox', name=label, exact=True)).to_be_disabled()
        apps[0].get_by_role('button', name='Start the game →').click()
        for app in apps:
            expect(app.locator('.tt-game')).to_be_visible()
            assert app.locator('.tt-inventory [data-goods="food"]').evaluate_all(
                'els=>els.map(e=>Number(e.dataset.pieceCount)).sort()') == [2, 3, 3, 4]
        pages[0].frames[1].evaluate('location.reload()')
        expect(apps[0].locator('.tt-game')).to_be_visible(timeout=20000)
        for app in apps:
            app.get_by_role('button', name='Table menu', exact=True).click()
            app.get_by_role('button', name='Rules', exact=True).click()
            for label in SWITCHES:
                expect(app.get_by_role('checkbox', name=label, exact=True)).to_be_checked()
        assert not errors, errors
        browser.close()
        print('Discord: host configuration, guest read-only settings, four-player food, and host reconnect passed')


if __name__ == '__main__':
    discord.check = check
    discord.main()
