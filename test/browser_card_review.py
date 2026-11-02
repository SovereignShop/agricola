"""Fast card review navigation and batch-four graphical flows in web/Discord, desktop/mobile."""
import json, os, urllib.parse
from playwright.sync_api import expect, sync_playwright
import browser_discord


def check(base):
    with sync_playwright() as pw:
        browser=pw.chromium.launch(headless=True,executable_path=os.environ['EUROZONE_BROWSER'])
        for mobile,activity in [(False,False),(False,True),(True,True)]:
            ctx=browser.new_context(viewport={'width':390 if mobile else 1440,'height':844 if mobile else 1000},has_touch=mobile,is_mobile=mobile)
            page=ctx.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
            if activity:
                parent=browser_discord.PARENT.replace('<style>','<meta name="viewport" content="width=device-width, initial-scale=1"><style>').replace('e.source.postMessage','e.source?.postMessage').replace('USER',json.dumps({'id':'111','username':'Ada'}))
                ctx.route(base+'/discord-test-parent',lambda route:route.fulfill(content_type='text/html',body=parent))
                page.goto(base+'/discord-test-parent');frame=page.frame_locator('iframe')
            else:
                # This harness server requires Discord login; log in through its OAuth stub.
                def authorize(route):
                    login=route.fetch(max_redirects=0)
                    params=urllib.parse.parse_qs(urllib.parse.urlsplit(login.headers['location']).query)
                    callback=params['redirect_uri'][0]+'?'+urllib.parse.urlencode({'code':'111','state':params['state'][0]})
                    route.fulfill(response=login,headers={**login.headers,'location':callback})
                ctx.route(base+'/discord/login?*',authorize)
                page.goto(base+'/?seat=new')
                frame=page
            def review():return frame.get_by_role('region',name='Card review',exact=True)
            def card(name):expect(review().locator('.card-review-heading strong')).to_have_text(name,timeout=30000)
            def next_card(name):review().get_by_role('button',name='Next card →',exact=True).click();card(name)
            def amount(resource):
                loc=frame.locator(f'.tt-player[data-player="0"] .tt-inventory [data-goods="{resource}"] .pile-count')
                return int(loc.inner_text()) if loc.count() else 0
            try:
                details=frame.locator('details').filter(has=frame.get_by_text('Card test tables',exact=True))
                details.get_by_text('Card test tables',exact=True).click()
                frame.get_by_label('Card to test').select_option('seed-spreader')
                frame.get_by_role('button',name='Open test table',exact=True).click();card('Seed Spreader')
                original=frame.locator('.game').get_attribute('data-motion-table')
                dialog=frame.get_by_role('dialog',name='Resolve Seed Spreader',exact=True)
                expect(dialog).to_be_visible();expect(dialog.get_by_role('combobox')).to_have_count(0)
                # Next remains reachable with a pending resolution. Going back stages a fresh copy.
                next_card('Vegetable Scoop')
                review().get_by_role('button',name='Previous card',exact=True).click();card('Seed Spreader')
                assert frame.locator('.game').get_attribute('data-motion-table')!=original
                dialog=frame.get_by_role('dialog',name='Resolve Seed Spreader',exact=True)
                dialog.get_by_role('button',name='Take Sow action',exact=True).click()
                dialog.get_by_role('group',name='Crop to sow').get_by_role('button',name='Vegetables',exact=True).click()
                expect(dialog.get_by_role('group',name='Crop to sow').get_by_role('button',name='Wood',exact=True)).to_have_count(0)
                dialog.get_by_role('button',name='Farm space 2: field',exact=True).click()
                dialog.get_by_role('button',name='Resolve effect →',exact=True).click();expect(dialog).to_have_count(0)
                next_card('Vegetable Scoop');before=amount('vegetable')
                frame.locator('.action-card[data-action=vegetable]').click()
                frame.get_by_role('button',name='Place family member →',exact=True).click()
                expect(frame.get_by_role('dialog')).to_have_count(0)
                assert amount('vegetable')==before+2
                next_card('Diamond Mine')
                frame.locator('.action-card[data-action=meeting]').click()
                frame.get_by_role('group',name='Playable minor improvements',exact=True).get_by_role('button',name='Diamond Mine',exact=True).click()
                preview=frame.get_by_role('dialog',name='Diamond Mine',exact=True)
                expect(preview.locator('img[src*="original-wm073"]')).to_be_visible()
                preview.get_by_role('button',name='Select card',exact=True).click()
                frame.get_by_role('button',name='Place family member →',exact=True).click()
                expect(frame.get_by_role('dialog')).to_have_count(0)
                for n in [5,6]:expect(frame.locator(f'.future-round-resources[data-round="{n}"] [data-resource=stone]')).to_have_count(1)
                next_card('Encircling Wall')
                current=frame.locator('.game').get_attribute('data-motion-table')
                (page.frames[1] if activity else page).evaluate('location.reload()')
                card('Encircling Wall')
                assert frame.locator('.game').get_attribute('data-motion-table')==current
                dialog=frame.get_by_role('dialog',name='Resolve Encircling Wall',exact=True)
                before=amount('wood');expect(dialog.get_by_role('combobox')).to_have_count(0)
                dialog.get_by_role('button',name='Farm space 2: empty',exact=True).click()
                dialog.get_by_role('button',name='Resolve effect →',exact=True).click();expect(dialog).to_have_count(0)
                assert amount('wood')==before
                next_card('Stone House Reconstruction')
                frame.locator('[data-owner="0"][data-deck="improvements"]').get_by_role('button',name='Open Played · improvements spread',exact=True).click()
                frame.get_by_role('dialog').get_by_role('button',name='Stone House Reconstruction',exact=True).click()
                inspection=frame.get_by_role('dialog',name='Stone House Reconstruction',exact=True)
                expect(inspection.locator('.choice-recipe')).to_be_visible()
                expect(inspection.get_by_role('combobox')).to_have_count(0)
                before=amount('stone');inspection.get_by_role('button',name='Use card ability',exact=True).click()
                expect(inspection.get_by_role('button',name='Use card ability',exact=True)).to_be_disabled()
                expect(frame.locator('.tt-player[data-player="0"] .tt-inventory [data-goods="stone"] .pile-count')).to_have_text(str(before-2))
                expect(review().get_by_role('button',name='Next card →',exact=True)).to_be_in_viewport()
                page.screenshot(path=f'/tmp/eurozone-review-{mobile}-{activity}.png')
                # Next is also reachable from a card inspection, and restart does not overwrite it.
                next_card('Swing Plow')
                previous=frame.locator('.game').get_attribute('data-motion-table')
                review().get_by_role('button',name='Restart test',exact=True).click()
                expect(frame.locator('.game')).not_to_have_attribute('data-motion-table',previous,timeout=30000)
                card('Swing Plow')
                assert not errors,errors
                assert (page.frames[1] if activity else page).evaluate('document.documentElement.scrollWidth<=innerWidth')
                print('PASS card review',mobile,activity,flush=True)
            except Exception:
                page.screenshot(path='/tmp/eurozone-review-failure.png')
                print('FAIL',mobile,activity,frame.locator('body').inner_text()[-6000:],errors,flush=True)
                raise
            finally:ctx.close()
        browser.close()

if __name__=='__main__':
    browser_discord.check=check
    browser_discord.main()
