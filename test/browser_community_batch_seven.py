"""Batch-seven harvest/stock engines, graphical payments and choices, web/Discord/mobile."""
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
                def authorize(route):
                    login=route.fetch(max_redirects=0)
                    params=urllib.parse.parse_qs(urllib.parse.urlsplit(login.headers['location']).query)
                    callback=params['redirect_uri'][0]+'?'+urllib.parse.urlencode({'code':'111','state':params['state'][0]})
                    route.fulfill(response=login,headers={**login.headers,'location':callback})
                ctx.route(base+'/discord/login?*',authorize);page.goto(base+'/?seat=new');frame=page
            def review():return frame.get_by_role('region',name='Card review',exact=True)
            def card(name):expect(review().locator('.card-review-heading strong')).to_have_text(name,timeout=30000)
            def next_card(name):review().get_by_role('button',name='Next card →',exact=True).click();card(name)
            def marker(resource):return frame.locator(f'.tt-player[data-player="0"] .tt-inventory [data-goods="{resource}"] .pile-count')
            def amount(resource):return int(marker(resource).inner_text()) if marker(resource).count() else 0
            def restart(name):
                old=frame.locator('.game').get_attribute('data-motion-table')
                review().get_by_role('button',name='Restart test',exact=True).click()
                expect(frame.locator('.game')).not_to_have_attribute('data-motion-table',old,timeout=30000);card(name)
            def resolve(dialog):
                expect(dialog.get_by_role('combobox')).to_have_count(0)
                dialog.get_by_role('button',name='Resolve effect →',exact=True).click();expect(dialog).to_have_count(0)
            def action(space):
                frame.locator(f'.action-card[data-action={space}]').click()
                frame.get_by_role('button',name='Place family member →',exact=True).click()
            def inspect(name):
                frame.locator('[data-owner="0"][data-deck="improvements"]').get_by_role('button',name='Open Played · improvements spread',exact=True).click()
                frame.get_by_role('dialog').get_by_role('button',name=name,exact=True).click()
                dialog=frame.get_by_role('dialog',name=name,exact=True)
                expect(dialog).to_be_visible();expect(dialog.get_by_role('combobox')).to_have_count(0)
                return dialog
            def shape(dialog):
                expect(dialog.get_by_role('combobox')).to_have_count(0);expect(dialog.locator('svg.farm')).to_have_count(0)
                box=dialog.bounding_box();assert box['x']>=0 and box['x']+box['width']<=(390 if mobile else 1440)+1,box
                assert dialog.evaluate('(el)=>el.scrollWidth<=el.clientWidth+1')
            try:
                details=frame.locator('details').filter(has=frame.get_by_text('Card test tables',exact=True))
                details.get_by_text('Card test tables',exact=True).click()
                frame.get_by_label('Card to test').select_option('private-market')
                frame.get_by_role('button',name='Open test table',exact=True).click();card('Private Market')
                dialog=inspect('Private Market');shape(dialog)
                food,stone=amount('food'),amount('stone')
                dialog.get_by_role('button',name='2 Food → Stone',exact=True).click()
                expect(dialog.get_by_role('group',name='Exchange').locator('[data-piece-type=food] b')).to_have_text(['2','2','2','2'])
                page.screenshot(path=f'/tmp/eurozone-private-market-{mobile}-{activity}.png')
                dialog.get_by_role('button',name='Use card ability',exact=True).click()
                expect(marker('food')).to_have_text(str(food-2));expect(marker('stone')).to_have_text(str(stone+1))
                expect(dialog.get_by_role('button',name='Use card ability',exact=True)).to_be_disabled()
                next_card('Grain Market');dialog=frame.get_by_role('dialog',name='Resolve Grain Market',exact=True)
                shape(dialog)
                exchange=dialog.get_by_role('group',name='Card exchange',exact=True)
                expect(exchange.locator('[data-piece-type=grain] b')).to_have_text('1')
                expect(exchange.locator('[data-piece-type=vegetable] b')).to_have_text('1')
                grain,veg=amount('grain'),amount('vegetable')
                old=frame.locator('.game').get_attribute('data-motion-table')
                (page.frames[1] if activity else page).evaluate('location.reload()');card('Grain Market')
                assert frame.locator('.game').get_attribute('data-motion-table')==old
                page.screenshot(path=f'/tmp/eurozone-grain-market-{mobile}-{activity}.png')
                resolve(dialog);expect(marker('grain')).to_have_text(str(grain-1));expect(marker('vegetable')).to_have_text(str(veg+1))
                restart('Grain Market');dialog.get_by_role('button',name='Skip this effect',exact=True).click();expect(dialog).to_have_count(0)
                next_card('Grain Elevator');grain=amount('grain');action('grain')
                expect(marker('grain')).to_have_text(str(grain+3));expect(frame.get_by_role('dialog')).to_have_count(0)
                inspect('Grain Elevator')
                expect(frame.get_by_role('dialog',name='Grain Elevator').locator('.tt-card-markers')).to_have_count(0)
                next_card('Grain Elevator · opponent');grain=amount('grain')
                other=frame.locator('.tt-player[data-player="1"] .tt-inventory [data-goods="grain"] .pile-count')
                before=int(other.inner_text());action('grain')
                expect(marker('grain')).to_have_text(str(grain));expect(other).to_have_text(str(before+1))
                expect(frame.get_by_role('dialog')).to_have_count(0)
                next_card('Farm School');dialog=frame.get_by_role('dialog',name='Resolve Farm School',exact=True)
                shape(dialog)
                expect(dialog.get_by_role('group',name='Occupation payment').locator('[data-piece-type=food] b')).to_have_text('2')
                food=amount('food')
                page.screenshot(path=f'/tmp/eurozone-farm-school-{mobile}-{activity}.png')
                dialog.get_by_role('group',name='Playable occupations',exact=True).get_by_role('button',name='Architect',exact=True).click()
                preview=frame.get_by_role('dialog',name='Architect',exact=True)
                expect(preview.locator('img[src*="original-g005"]')).to_be_visible()
                preview.get_by_role('button',name='Play occupation →',exact=True).click()
                expect(marker('food')).to_have_text(str(food-2))
                expect(frame.get_by_role('dialog',name='Architect',exact=True)).to_have_count(0)
                restart('Farm School');dialog.get_by_role('button',name='Skip this effect',exact=True).click();expect(dialog).to_have_count(0)
                next_card('Brickyard');dialog=inspect('Brickyard')
                group=dialog.get_by_role('group',name='Goods to collect',exact=True)
                expect(group.locator('[data-piece-type=clay] b')).to_have_text('3')
                expect(dialog.get_by_role('button',name='Collect goods →',exact=True)).to_be_disabled()
                dialog.get_by_role('button',name='Close dialog',exact=True).click()
                expect(frame.get_by_role('dialog')).to_have_count(0)
                action('grain');dialog=inspect('Brickyard')
                expect(dialog.get_by_role('group',name='Goods to collect').locator('[data-piece-type=clay] b')).to_have_text('4')
                shape(dialog);page.screenshot(path=f'/tmp/eurozone-brickyard-{mobile}-{activity}.png')
                dialog.get_by_role('button',name='Collect goods →',exact=True).click();expect(marker('clay')).to_have_text('4')
                expect(dialog.get_by_role('button',name='Collect goods →',exact=True)).to_be_disabled()
                (page.frames[1] if activity else page).evaluate('location.reload()');card('Brickyard');expect(marker('clay')).to_have_text('4')
                next_card('Journeyman');dialog=frame.get_by_role('dialog',name='Resolve Journeyman',exact=True)
                shape(dialog);food=amount('food')
                expect(dialog.get_by_role('group',name='Card effect').locator('[data-piece-type=food] b')).to_have_text(['1','1','1'])
                dialog.get_by_role('button',name='Food → Cattle',exact=True).click()
                page.screenshot(path=f'/tmp/eurozone-journeyman-{mobile}-{activity}.png')
                resolve(dialog);expect(marker('food')).to_have_text(str(food-1));expect(marker('cattle')).to_have_text('1')
                restart('Journeyman');dialog.get_by_role('button',name='Skip this effect',exact=True).click();expect(dialog).to_have_count(0)
                next_card('Carrot Farmer')
                assert not errors,errors
                assert (page.frames[1] if activity else page).evaluate('document.documentElement.scrollWidth<=innerWidth')
                print('PASS seven review tables, exchanges, stock thresholds, opponent clearing, occupation/animal choices, declines and reload',mobile,activity,flush=True)
            except Exception:
                page.screenshot(path='/tmp/eurozone-community-seven-failure.png')
                print('FAIL',mobile,activity,frame.locator('body').inner_text()[-7000:],errors,flush=True);raise
            finally:ctx.close()
        browser.close()

if __name__=='__main__':
    browser_discord.check=check
    browser_discord.main()
