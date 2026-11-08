"""Ten FR native card reviews: web, Discord iframe, and touch-sized Discord."""
import json, os, urllib.parse
from pathlib import Path
from playwright.sync_api import expect, sync_playwright
import browser_discord


def check(base):
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ['EUROZONE_BROWSER'])
        for mobile, activity in [(False, False), (False, True), (True, True)]:
            width = 390 if mobile else 1440
            ctx = browser.new_context(viewport={'width': width, 'height': 844 if mobile else 1000}, has_touch=mobile, is_mobile=mobile)
            page = ctx.new_page(); errors = []; page.on('pageerror', lambda e: errors.append(str(e)))
            if activity:
                parent = browser_discord.PARENT.replace('<style>', '<meta name="viewport" content="width=device-width, initial-scale=1"><style>').replace('e.source.postMessage', 'e.source?.postMessage').replace('USER', json.dumps({'id': '111', 'username': 'Ada'}))
                ctx.route(base+'/discord-test-parent', lambda route: route.fulfill(content_type='text/html', body=parent))
                page.goto(base+'/discord-test-parent'); frame = page.frame_locator('iframe')
            else:
                def authorize(route):
                    login = route.fetch(max_redirects=0)
                    params = urllib.parse.parse_qs(urllib.parse.urlsplit(login.headers['location']).query)
                    callback = params['redirect_uri'][0]+'?'+urllib.parse.urlencode({'code': '111', 'state': params['state'][0]})
                    route.fulfill(response=login, headers={**login.headers, 'location': callback})
                ctx.route(base+'/discord/login?*', authorize); page.goto(base+'/?seat=new'); frame = page
            def review(): return frame.get_by_role('region', name='Card review', exact=True)
            def card(name):
                expect(review().locator('.card-review-heading strong')).to_have_text(name, timeout=30000)
                print(f'FR review {name}: mobile={mobile}, activity={activity}', flush=True)
            def pending(name): return frame.get_by_role('dialog', name='Resolve '+name, exact=True)
            def next_card(name):
                review().get_by_role('button', name='Next card →', exact=True).click(); card(name)
            def shape(dialog, farm=False):
                expect(dialog.get_by_role('combobox')).to_have_count(0)
                expect(dialog.locator('svg.farm')).to_have_count(1 if farm else 0)
                box = dialog.bounding_box(); assert box['x'] >= 0 and box['x']+box['width'] <= width+1, box
                assert dialog.evaluate('(el)=>el.scrollWidth<=el.clientWidth+1')
            def snap(name):
                page.evaluate('() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')
                page.screenshot(path=f'/tmp/eurozone-sixteen-fr-{name}-{mobile}-{activity}.png')
            def marker(resource): return frame.locator(f'.tt-player[data-player="0"] .tt-inventory [data-goods="{resource}"] .pile-count')
            def amount(resource): return int(marker(resource).inner_text()) if marker(resource).count() else 0
            def qty(resource, n):
                if n: expect(marker(resource)).to_have_text(str(n))
                else: expect(marker(resource)).to_have_count(0)
            def resolve(dialog):
                dialog.get_by_role('button', name='Resolve effect →', exact=True).click()
            def cell(dialog, n, kind='empty'):
                button = dialog.get_by_role('button', name=f'Farm space {n}: {kind}', exact=True)
                button.click(); expect(button).to_have_attribute('aria-pressed', 'true')
            def action(space):
                frame.locator(f'.action-card[data-action={space}]').click()
                frame.get_by_role('button', name='Place family member →', exact=True).click()
            def close_harvest():
                d = frame.get_by_role('dialog', name='Harvest', exact=True)
                if d.count(): d.get_by_role('button', name='Close dialog', exact=True).click()
            def restart(name):
                old = frame.locator('.game').get_attribute('data-motion-table')
                review().get_by_role('button', name='Restart test', exact=True).click()
                expect(frame.locator('.game')).not_to_have_attribute('data-motion-table', old, timeout=30000); card(name)
            def choose_hand(name, direct=False):
                frame.get_by_role('button', name=name, exact=True).click()
                face = frame.get_by_role('dialog', name=name, exact=True)
                face.get_by_role('button', name=('Play occupation →' if direct else 'Select card'), exact=True).click()
            try:
                details = frame.locator('details').filter(has=frame.get_by_text('Card test tables', exact=True))
                details.get_by_text('Card test tables', exact=True).click()
                frame.get_by_label('Card to test').select_option('necklace')
                frame.get_by_role('button', name='Open test table', exact=True).click(); card('Necklace')
                food = amount('food'); frame.locator('.action-card[data-action=plow]').click()
                d = frame.get_by_role('dialog'); cell(d, 0); snap('necklace')
                d.get_by_role('button', name='Place family member →', exact=True).click(); qty('food', food+1)

                next_card('Prefect'); d = frame.get_by_role('dialog', name='Harvest', exact=True)
                pottery = d.get_by_role('button', name='Use Pottery', exact=True)
                expect(pottery.get_by_label('Prefect adds 1 food', exact=True)).to_be_visible()
                expect(pottery).to_have_attribute('data-food', '3'); pottery.click(); snap('prefect')
                d.get_by_role('button', name='Feed family →', exact=True).click(); close_harvest()

                next_card('Prosecutor'); frame.locator('.action-card[data-action=meeting]').click()
                choose_hand('Shovel'); d = frame.get_by_role('dialog')
                d.get_by_role('button', name='Pay 1 less wood', exact=True).nth(0).click()
                d.get_by_role('button', name='Pay 1 less clay', exact=True).nth(1).click()
                expect(d.get_by_role('combobox')).to_have_count(0); snap('prosecutor')
                d.get_by_role('button', name='Place family member →', exact=True).click()

                next_card('Tower Builder'); frame.locator('.action-card[data-action=build]').click()
                d = frame.get_by_role('dialog'); cell(d, 0)
                d.get_by_role('button', name='Place family member →', exact=True).click()
                d = pending('Tower Builder'); shape(d, True); cell(d, 1); snap('tower-builder'); resolve(d)
                expect(d).to_have_count(0)
                restart('Tower Builder'); frame.locator('.action-card[data-action=build]').click()
                d = frame.get_by_role('dialog'); cell(d, 0); d.get_by_role('button', name='Place family member →', exact=True).click()
                pending('Tower Builder').get_by_role('button', name='Skip this effect', exact=True).click()

                next_card('Timetable'); frame.locator('.action-card[data-action=meeting]').click(); choose_hand('Shovel')
                d = frame.get_by_role('dialog')
                expect(d.get_by_role('button', name='Use available stored resources first', exact=True)).to_have_attribute('aria-pressed', 'true')
                d.get_by_role('button', name='Keep resources on Timetable', exact=True).click()
                expect(d.get_by_role('button', name='Place family member →', exact=True)).to_be_disabled()
                d.get_by_role('button', name='Use available stored resources first', exact=True).click(); snap('timetable')
                d.get_by_role('button', name='Place family member →', exact=True).click()

                next_card('Cat Lover'); frame.locator('.action-card[data-action=build]').click()
                d = frame.get_by_role('dialog'); cell(d, 6)
                payments = d.get_by_role('group', name='Payment for the highlighted room', exact=True)
                payments.get_by_role('button', name='4 Wood', exact=True).click()
                expect(d.get_by_role('combobox')).to_have_count(0); snap('cat-lover')
                d.get_by_role('button', name='Place family member →', exact=True).click()
                expect(frame.locator('.tt-player[data-player="0"] [aria-label="Farm space 6: room"]')).to_have_count(1)

                next_card('Sunday School'); d = pending('Sunday School'); shape(d)
                choose_hand('Chief', True)
                d = frame.get_by_role('dialog').filter(has=frame.get_by_role('button', name='Skip this effect', exact=True))
                d.get_by_role('button', name='Skip this effect', exact=True).click()
                choose_hand('Tutor', True); snap('sunday-school'); expect(pending('Sunday School')).to_have_count(0)
                restart('Sunday School')
                for _ in range(2): pending('Sunday School').get_by_role('button', name='Skip this effect', exact=True).click()

                next_card('Park Cemetery'); d = pending('Park Cemetery'); shape(d)
                stone = amount('stone'); d.get_by_role('button', name='Take one stone from Park Cemetery', exact=True).click(); snap('park-cemetery'); resolve(d)
                qty('stone', stone+1)
                restart('Park Cemetery'); pending('Park Cemetery').get_by_role('button', name='Skip this effect', exact=True).click()

                next_card('Fencing Master'); d = pending('Fencing Master'); shape(d)
                d.get_by_role('button', name='Store two fences', exact=True).click(); snap('fencing-master'); resolve(d)
                expect(d).to_have_count(0)
                restart('Fencing Master'); pending('Fencing Master').get_by_role('button', name='Skip this effect', exact=True).click()

                next_card('Pear Peeler'); d = pending('Pear Peeler'); shape(d); wood, grain, vegetable = amount('wood'), amount('grain'), amount('vegetable')
                d.get_by_role('button', name='Leave three wood → grain and vegetable', exact=True).click(); snap('pear-peeler'); resolve(d)
                qty('wood', wood-3); qty('grain', grain+1); qty('vegetable', vegetable+1)
                (page.frames[1] if activity else page).evaluate('location.reload()'); card('Pear Peeler'); qty('wood', wood-3)
                restart('Pear Peeler'); pending('Pear Peeler').get_by_role('button', name='Skip this effect', exact=True).click()
                assert not errors, errors
                print(f'PASS ten FR reviews: mobile={mobile}, activity={activity}', flush=True)
            except Exception:
                snap('failure'); Path('/tmp/eurozone-sixteen-fr-failure.html').write_text((page.frames[1] if activity else page).content()); raise
            finally: ctx.close()
        browser.close()

if __name__ == '__main__':
    browser_discord.check = check
    browser_discord.main()
