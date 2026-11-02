"""Batch-six stocks, room building and exchanges: graphical exchanges and direct field removal, web/Discord/mobile."""
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
            try:
                details=frame.locator('details').filter(has=frame.get_by_text('Card test tables',exact=True))
                details.get_by_text('Card test tables',exact=True).click()
                frame.get_by_label('Card to test').select_option('carrot-farmer')
                frame.get_by_role('button',name='Open test table',exact=True).click();card('Carrot Farmer')
                dialog=frame.get_by_role('dialog',name='Resolve Carrot Farmer',exact=True)
                expect(dialog.get_by_role('combobox')).to_have_count(0);expect(dialog.locator('svg.farm')).to_have_count(0)
                expect(dialog.locator('.stock-offer .tt-card-markers')).to_have_css('position','static')
                expect(dialog.locator('[data-next-resource=vegetable]')).to_be_visible()
                expect(dialog.locator('.stock-offer .choice-recipe [data-piece-type=food] b')).to_have_text('1')
                expect(dialog.locator('.stock-offer .choice-recipe [data-piece-type=vegetable] b')).to_have_text('1')
                food,veg=amount('food'),amount('vegetable')
                page.screenshot(path=f'/tmp/eurozone-carrot-farmer-{mobile}-{activity}.png')
                dialog.get_by_role('button',name='Take resource →',exact=True).click();expect(dialog).to_have_count(0)
                expect(marker('food')).to_have_text(str(food-1));expect(marker('vegetable')).to_have_text(str(veg+1))
                restart('Carrot Farmer');frame.get_by_role('dialog',name='Resolve Carrot Farmer').get_by_role('button',name='Skip this effect',exact=True).click()
                expect(frame.get_by_role('dialog',name='Resolve Carrot Farmer')).to_have_count(0)
                next_card('Pulley');dialog=frame.get_by_role('dialog',name='Resolve Pulley',exact=True)
                first=dialog.get_by_role('button',name='Farm space 1: empty',exact=True)
                second=dialog.get_by_role('button',name='Farm space 7: empty',exact=True)
                expect(dialog.get_by_role('button',name='Farm space 15: empty',exact=True)).to_be_disabled()
                first.click();expect(first).to_have_attribute('aria-pressed','true')
                second.click();expect(first).to_have_attribute('aria-pressed','false');expect(second).to_have_attribute('aria-pressed','true')
                first.click();expect(second).to_have_attribute('aria-pressed','false')
                expect(dialog.locator('[aria-label="Room payment"] [data-piece-type=wood] b')).to_have_text('5')
                expect(dialog.locator('[aria-label="Room payment"] [data-piece-type=reed] b')).to_have_text('2')
                old=frame.locator('.game').get_attribute('data-motion-table')
                (page.frames[1] if activity else page).evaluate('location.reload()');card('Pulley')
                assert frame.locator('.game').get_attribute('data-motion-table')==old
                first.click();expect(dialog.locator('[aria-label="Room payment"]')).to_be_visible();expect(dialog.get_by_role('combobox')).to_have_count(0)
                box=dialog.bounding_box();assert box['x']>=0 and box['x']+box['width']<=(390 if mobile else 1440)+1,box
                assert dialog.evaluate('(el)=>el.scrollWidth<=el.clientWidth+1')
                page.screenshot(path=f'/tmp/eurozone-pulley-{mobile}-{activity}.png')
                wood,reed=amount('wood'),amount('reed');resolve(dialog)
                expect(marker('wood')).to_have_text(str(wood-5));expect(marker('reed')).to_have_text(str(reed-2))
                restart('Pulley');frame.get_by_role('dialog',name='Resolve Pulley').get_by_role('button',name='Skip this effect',exact=True).click()
                expect(frame.get_by_role('dialog',name='Resolve Pulley')).to_have_count(0)
                next_card('Food Connoisseur')
                expect(frame.locator('.tt-player[data-player="0"] [aria-label="Next resource on Food Connoisseur"]')).to_have_attribute('data-next-resource','reed')
                reed=amount('reed');action('fishing');expect(marker('reed')).to_have_text(str(reed+1))
                expect(frame.locator('.tt-player[data-player="0"] [aria-label="Next resource on Food Connoisseur"]')).to_have_attribute('data-next-resource','wood')
                expect(frame.get_by_role('dialog')).to_have_count(0)
                restart('Food Connoisseur');reed=amount('reed');action('traveling');expect(marker('reed')).to_have_text(str(reed+1))
                next_card('Oak Cask');food=amount('food');action('grain')
                # Feeding may open a modal; close its view to inspect the cask without resolving feeding.
                harvest=frame.get_by_role('dialog',name='Harvest',exact=True)
                expect(harvest).to_be_visible();harvest.get_by_role('button',name='Close dialog',exact=True).click();expect(harvest).to_have_count(0)
                frame.locator('[data-owner="0"][data-deck="improvements"]').get_by_role('button',name='Open Played · improvements spread',exact=True).click()
                frame.get_by_role('dialog').get_by_role('button',name='Oak Cask',exact=True).click()
                inspected=frame.get_by_role('dialog',name='Oak Cask',exact=True)
                expect(inspected.get_by_role('combobox')).to_have_count(0)
                expect(inspected.get_by_role('group',name='Goods to collect').locator('[data-piece-type=food] b')).to_have_text('2')
                page.screenshot(path=f'/tmp/eurozone-oak-cask-{mobile}-{activity}.png')
                inspected.get_by_role('button',name='Collect goods →',exact=True).click();expect(marker('food')).to_have_text(str(food+2))
                expect(inspected.get_by_role('button',name='Collect goods →',exact=True)).to_be_disabled()
                expect(inspected.locator('.tt-card-back')).to_have_count(0)
                (page.frames[1] if activity else page).evaluate('location.reload()');card('Oak Cask');expect(marker('food')).to_have_text(str(food+2))
                next_card('Child Care Worker');dialog=frame.get_by_role('dialog',name='Resolve Child Care Worker',exact=True)
                expect(dialog.locator('svg.farm')).to_have_count(0)
                food,wood,reed=amount('food'),amount('wood'),amount('reed')
                dialog.get_by_role('button',name='Wood + Reed → 5 Food',exact=True).click();resolve(dialog)
                expect(marker('food')).to_have_text(str(food+5));expect(marker('wood')).to_have_text(str(wood-1));expect(marker('reed')).to_have_text(str(reed-1))
                restart('Child Care Worker');frame.get_by_role('dialog',name='Resolve Child Care Worker').get_by_role('button',name='Skip this effect',exact=True).click()
                expect(frame.get_by_role('dialog',name='Resolve Child Care Worker')).to_have_count(0)
                next_card('Oceanographer');dialog=frame.get_by_role('dialog',name='Resolve Oceanographer',exact=True)
                expect(dialog.locator('svg.farm')).to_have_count(0);expect(dialog.get_by_role('combobox')).to_have_count(0)
                expect(dialog.locator('[data-next-resource=wood]')).to_be_visible();wood=amount('wood')
                dialog.get_by_role('button',name='Take resource →',exact=True).click();expect(dialog).to_have_count(0)
                expect(marker('wood')).to_have_text(str(wood+1))
                expect(frame.locator('.tt-player[data-player="0"] [aria-label="Next resource on Oceanographer"]')).to_have_attribute('data-next-resource','clay')
                restart('Oceanographer');frame.get_by_role('dialog',name='Resolve Oceanographer').get_by_role('button',name='Skip this effect',exact=True).click()
                expect(frame.get_by_role('dialog',name='Resolve Oceanographer')).to_have_count(0)
                next_card('Sheep Market')
                assert not errors,errors
                assert (page.frames[1] if activity else page).evaluate('document.documentElement.scrollWidth<=innerWidth')
                print('PASS six card tables, stock markers, room payment, exchanges, declines, navigation and reload',mobile,activity,flush=True)
            except Exception:
                page.screenshot(path='/tmp/eurozone-community-six-failure.png')
                print('FAIL',mobile,activity,frame.locator('body').inner_text()[-7000:],errors,flush=True);raise
            finally:ctx.close()
        browser.close()

if __name__=='__main__':
    browser_discord.check=check
    browser_discord.main()
