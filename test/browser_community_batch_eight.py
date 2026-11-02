"""Batch-eight native card tables: direct board food assignment, fencing, animals, occupations and future deliveries."""
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
            def shape(dialog,farm=False):
                expect(dialog.get_by_role('combobox')).to_have_count(0)
                expect(dialog.locator('svg.farm')).to_have_count(1 if farm else 0)
                box=dialog.bounding_box();assert box['x']>=0 and box['x']+box['width']<=(390 if mobile else 1440)+1,box
                assert dialog.evaluate('(el)=>el.scrollWidth<=el.clientWidth+1')
            def play_occupation(name):
                frame.locator('.action-card[data-action=lessons]').click()
                frame.get_by_role('group',name='Playable occupations',exact=True).get_by_role('button',name=name,exact=True).click()
                preview=frame.get_by_role('dialog',name=name,exact=True)
                preview.get_by_role('button',name='Place family member & play card →',exact=True).click()
                expect(preview).to_have_count(0)
            def food_mark(round):
                return frame.locator(f'.future-round-resources[data-round="{round}"] [data-resource="food"]')
            try:
                details=frame.locator('details').filter(has=frame.get_by_text('Card test tables',exact=True))
                details.get_by_text('Card test tables',exact=True).click()
                frame.get_by_label('Card to test').select_option('clay-starter')
                frame.get_by_role('button',name='Open test table',exact=True).click();card('Clay Starter')
                food,clay,reed=amount('food'),amount('clay'),amount('reed')
                play_occupation('Clay Starter')
                expect(marker('food')).to_have_text(str(food-1))
                expect(marker('clay')).to_have_text(str(clay));expect(marker('reed')).to_have_text(str(reed))
                expect(frame.locator('.tt-player[data-player="0"] svg.farm image[href*="soil_room"]')).to_have_count(2)
                next_card('Fence Helper');dialog=frame.get_by_role('dialog',name='Resolve Fence Helper',exact=True)
                shape(dialog,True)
                expect(dialog.get_by_role('group',name='Payment to fencing player').locator('[data-piece-type=food] b')).to_have_text('1')
                expect(dialog.get_by_role('group',name='Payment to fencing player')).to_contain_text('Partner')
                confirm=dialog.get_by_role('button',name='Resolve effect →',exact=True)
                expect(confirm).to_be_disabled()
                for edge in ['h:0:0','h:1:0','v:0:0','v:0:1']:
                    dialog.locator('svg.farm').scroll_into_view_if_needed()
                    box=dialog.get_by_role('button',name='Fence '+edge,exact=True).locator('line').first.bounding_box()
                    x,y=box['x']+box['width']/2,box['y']+box['height']/2
                    (page.touchscreen.tap if mobile else page.mouse.click)(x,y)
                expect(dialog.get_by_role('group',name='Fence payment').locator('[data-piece-type=wood] b')).to_have_text('4')
                expect(confirm).to_be_enabled()
                food,wood=amount('food'),amount('wood')
                partner=frame.locator('.tt-player[data-player="1"] .tt-inventory [data-goods=food] .pile-count')
                partner_food=int(partner.inner_text())
                page.screenshot(path=f'/tmp/eurozone-fence-helper-{mobile}-{activity}.png')
                resolve(dialog);expect(marker('food')).to_have_text(str(food-1));expect(marker('wood')).to_have_text(str(wood-4))
                expect(partner).to_have_text(str(partner_food+1))
                restart('Fence Helper');dialog.get_by_role('button',name='Skip this effect',exact=True).click();expect(dialog).to_have_count(0)
                next_card('Amazon');action('fishing');expect(marker('sheep')).to_have_text('1')
                expect(frame.get_by_role('dialog')).to_have_count(0)
                next_card('Amazon · animal choice');dialog=frame.get_by_role('dialog',name='Resolve Amazon',exact=True)
                shape(dialog)
                expect(dialog.get_by_role('button',name='Skip this effect',exact=True)).to_have_count(0)
                dialog.get_by_role('button',name='Add Cattle',exact=True).click()
                expect(dialog.get_by_role('button',name='Take 1 animal →',exact=True)).to_be_enabled()
                page.screenshot(path=f'/tmp/eurozone-amazon-{mobile}-{activity}.png')
                dialog.get_by_role('button',name='Take 1 animal →',exact=True).click()
                expect(marker('cattle')).to_have_text('1');expect(dialog).to_have_count(0)
                next_card('Apprenticeship');dialog=frame.get_by_role('dialog',name='Resolve Apprenticeship',exact=True)
                shape(dialog);food=amount('food')
                dialog.get_by_role('group',name='Playable occupations',exact=True).get_by_role('button',name='Architect',exact=True).click()
                preview=frame.get_by_role('dialog',name='Architect',exact=True)
                preview.get_by_role('button',name='Play occupation →',exact=True).click()
                expect(preview).to_have_count(0);expect(marker('food')).to_have_text(str(food))
                restart('Apprenticeship');dialog.get_by_role('button',name='Skip this effect',exact=True).click();expect(dialog).to_have_count(0)
                next_card('Camembert');dialog=frame.get_by_role('dialog',name='Resolve Camembert',exact=True)
                shape(dialog)
                board=dialog.locator('.board-picker')
                confirm=dialog.get_by_role('button',name='Resolve effect →',exact=True)
                expect(confirm).to_be_disabled()
                def space(name):return board.locator(f'[data-action="{name}"]')
                space('meeting').click();expect(space('meeting')).to_have_attribute('aria-pressed','true')
                space('grain').click();space('wood').click()
                expect(dialog.get_by_role('status').filter(has_text='3 / 3')).to_be_visible()
                expect(space('clay')).to_be_disabled();expect(confirm).to_be_enabled()
                expect(space('meeting').locator('[data-piece-type=food]')).to_have_attribute('data-piece-count','1')
                space('grain').click();expect(space('grain')).to_have_attribute('aria-pressed','false')
                expect(confirm).to_be_disabled();space('reed').click();expect(confirm).to_be_enabled()
                # The unresolved native effect survives reload; tentative picks are edited locally.
                old=frame.locator('.game').get_attribute('data-motion-table')
                (page.frames[1] if activity else page).evaluate('location.reload()');card('Camembert')
                assert frame.locator('.game').get_attribute('data-motion-table')==old
                expect(dialog).to_be_visible()
                for name in ['meeting','grain','wood']:space(name).click()
                expect(confirm).to_be_enabled();shape(dialog)
                page.screenshot(path=f'/tmp/eurozone-camembert-{mobile}-{activity}.png')
                resolve(dialog)
                expect(frame.locator('.action-card[data-action=meeting] [data-piece-type=food]')).to_have_attribute('data-piece-count','1')
                partner=frame.locator('.tt-player[data-player="1"] .tt-inventory [data-goods=food] .pile-count')
                before=int(partner.inner_text());action('grain')
                expect(partner).to_have_text(str(before+1))
                expect(frame.locator('.action-card[data-action=grain] [data-piece-type=food]')).to_have_count(0)
                next_card('Coffee Break')
                for n in [3,4,5,6]:expect(food_mark(n)).to_have_count(1)
                vegetable=frame.locator('.future-round-resources[data-round="8"] [data-resource="vegetable"]')
                expect(vegetable).to_have_count(1)
                play_occupation('Architect')
                for n in [3,4,5,6]:expect(food_mark(n)).to_have_count(0)
                expect(vegetable).to_have_count(1)
                (page.frames[1] if activity else page).evaluate('location.reload()');card('Coffee Break')
                for n in [3,4,5,6]:expect(food_mark(n)).to_have_count(0)
                expect(vegetable).to_have_count(1)
                next_card('Private Market')
                assert not errors,errors
                assert (page.frames[1] if activity else page).evaluate('document.documentElement.scrollWidth<=innerWidth')
                print('PASS seven batch-eight tables, farm fences, editable board food, animal/occupation choices, future markers, declines and reload',mobile,activity,flush=True)
            except Exception:
                page.screenshot(path='/tmp/eurozone-community-eight-failure.png')
                print('FAIL',mobile,activity,frame.locator('body').inner_text()[-7000:],errors,flush=True);raise
            finally:ctx.close()
        browser.close()

if __name__=='__main__':
    browser_discord.check=check
    browser_discord.main()
