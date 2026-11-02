"""New card review tables and Powerhouse combination: graphical exchanges and direct field removal, web/Discord/mobile."""
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
                frame.get_by_label('Card to test').select_option('sheep-market')
                frame.get_by_role('button',name='Open test table',exact=True).click();card('Sheep Market')
                dialog=frame.get_by_role('dialog',name='Resolve Sheep Market',exact=True)
                expect(dialog).to_be_visible();expect(dialog.locator('svg.farm')).to_have_count(0)
                expect(dialog.get_by_role('group',name='Card effect').get_by_role('button')).to_have_count(4)
                food,grain,vegetable=amount('food'),amount('grain'),amount('vegetable')
                dialog.get_by_role('button',name='Sheep → Vegetable',exact=True).click();resolve(dialog)
                expect(marker('vegetable')).to_have_text(str(vegetable+1));assert amount('food')==food
                restart('Sheep Market')
                frame.get_by_role('dialog',name='Resolve Sheep Market').get_by_role('button',name='Skip this effect',exact=True).click()
                expect(frame.get_by_role('dialog',name='Resolve Sheep Market')).to_have_count(0)
                next_card('Wooden Bridge');food=amount('food');action('reed');expect(marker('food')).to_have_text(str(food+1))
                restart('Wooden Bridge');reed=amount('reed');action('labor');expect(marker('reed')).to_have_text(str(reed+1))
                next_card('Edible Roots');food=amount('food');action('grain');expect(marker('food')).to_have_text(str(food+2))
                next_card('Festival Hall');dialog=frame.get_by_role('dialog',name='Resolve Festival Hall',exact=True)
                expect(dialog.locator('svg.farm')).to_have_count(0);expect(dialog.get_by_role('group',name='Card effect').get_by_role('button')).to_have_count(4)
                food,stone,grain=amount('food'),amount('stone'),amount('grain')
                dialog.get_by_role('button',name='Grain → Stone + Food',exact=True).click()
                expect(dialog.locator('.choice-effect-pieces')).to_have_count(4)
                page.screenshot(path=f'/tmp/eurozone-festival-hall-{mobile}-{activity}.png')
                resolve(dialog);expect(marker('stone')).to_have_text(str(stone+1));assert amount('food')==food+2;assert amount('grain')==grain-1
                restart('Festival Hall');frame.get_by_role('dialog',name='Resolve Festival Hall').get_by_role('button',name='Skip this effect',exact=True).click()
                expect(frame.get_by_role('dialog',name='Resolve Festival Hall')).to_have_count(0)
                next_card('Abandoned Willow');dialog=frame.get_by_role('dialog',name='Resolve Abandoned Willow',exact=True)
                old=frame.locator('.game').get_attribute('data-motion-table')
                (page.frames[1] if activity else page).evaluate('location.reload()');card('Abandoned Willow')
                assert frame.locator('.game').get_attribute('data-motion-table')==old
                expect(dialog.locator('.choice-recipe:has([data-piece-type=wood])')).to_be_visible()
                expect(dialog.get_by_role('button',name='Farm space 2: field',exact=True)).to_be_disabled()
                expect(dialog.get_by_role('button',name='Skip this effect',exact=True)).to_have_count(0)
                wood=amount('wood');dialog.get_by_role('button',name='Farm space 1: field',exact=True).click()
                box=dialog.bounding_box();assert box['x']>=0 and box['x']+box['width']<=(390 if mobile else 1440)+1,box
                assert dialog.evaluate('(el)=>el.scrollWidth<=el.clientWidth+1')
                page.screenshot(path=f'/tmp/eurozone-abandoned-willow-{mobile}-{activity}.png')
                resolve(dialog);expect(marker('wood')).to_have_text(str(wood+4))
                next_card('Powerhouse');food=amount('food');action('grain');expect(marker('food')).to_have_text(str(food+2))
                next_card('Powerhouse + Politician');dialog=frame.get_by_role('dialog',name='Resolve Powerhouse',exact=True)
                expect(dialog.locator('svg.farm')).to_have_count(0);expect(dialog.get_by_role('combobox')).to_have_count(0)
                food,stone=amount('food'),amount('stone')
                dialog.locator('summary').filter(has_text='Politician').click()
                dialog.get_by_role('group',name='Exchange').get_by_role('button',name='1 reed → 1 stone',exact=True).click()
                dialog.get_by_role('button',name='Use card ability',exact=True).click();expect(marker('stone')).to_have_text(str(stone+1))
                page.screenshot(path=f'/tmp/eurozone-powerhouse-politician-{mobile}-{activity}.png')
                dialog.get_by_role('button',name='Begin work →',exact=True).click();expect(dialog).to_have_count(0)
                expect(marker('food')).to_have_text(str(food+2))
                restart('Powerhouse + Politician');food=amount('food')
                frame.get_by_role('button',name='Begin work →',exact=True).click();expect(marker('food')).to_have_text(str(food+1))
                next_card('Seed Spreader')
                assert not errors,errors
                assert (page.frames[1] if activity else page).evaluate('document.documentElement.scrollWidth<=innerWidth')
                print('PASS six cards + combination table, graphical choices, automatic effects, declines, navigation and reload',mobile,activity,flush=True)
            except Exception:
                page.screenshot(path='/tmp/eurozone-community-five-failure.png')
                print('FAIL',mobile,activity,frame.locator('body').inner_text()[-7000:],errors,flush=True);raise
            finally:ctx.close()
        browser.close()

if __name__=='__main__':
    browser_discord.check=check
    browser_discord.main()
