"""Batch-nine: fifteen native card reviews in web and desktop/mobile Discord Activity."""
import json, os, urllib.parse
from pathlib import Path
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
            def qty(resource,n):
                expect(marker(resource)).to_have_text(str(n)) if n else expect(marker(resource)).to_have_count(0)
            def close(dialog):
                dialog.get_by_role('button',name='Close dialog',exact=True).click();expect(dialog).to_have_count(0)
            def play_minor(name):
                frame.locator('.action-card[data-action=meeting]').click()
                frame.get_by_role('group',name='Playable minor improvements',exact=True).get_by_role('button',name=name,exact=True).click()
                preview=frame.get_by_role('dialog',name=name,exact=True)
                preview.get_by_role('button',name='Place family member & play card →',exact=True).click()
                expect(preview).to_have_count(0)
            def plow(cell):
                frame.locator('.action-card[data-action=plow]').click()
                frame.get_by_role('dialog').get_by_role('button',name=f'Farm space {cell+1}: empty',exact=True).click()
                frame.get_by_role('button',name='Place family member →',exact=True).click()
                expect(frame.get_by_role('dialog')).to_have_count(0)
                expect(frame.locator(f'.tt-player[data-player="0"] [aria-label="Farm space {cell+1}: field"]')).to_have_count(1)
            def open_minor(name):
                frame.locator('[data-owner="0"][data-deck="improvements"]').get_by_role('button',name='Open Played · improvements spread',exact=True).click()
                frame.get_by_role('dialog').get_by_role('button',name=name,exact=True).click()
                return frame.get_by_role('dialog',name=name,exact=True)
            def harvest():
                dialog=frame.get_by_role('dialog',name='Harvest',exact=True)
                expect(dialog).to_be_visible();close(dialog)
            def feeding_exchange(name,resource,food_gain,label):
                harvest();before_food,before_resource=amount('food'),amount(resource)
                dialog=open_minor(name);shape(dialog)
                expect(dialog.get_by_label('Times',exact=True)).to_have_count(0)
                dialog.get_by_role('button',name=label,exact=True).click()
                page.screenshot(path=f'/tmp/eurozone-nine-{name}-{mobile}-{activity}.png')
                dialog.get_by_role('button',name='Use card ability',exact=True).click()
                qty('food',before_food+food_gain);qty(resource,before_resource-1)
                expect(dialog.get_by_role('button',name='Use card ability',exact=True)).to_be_disabled()
                expect(dialog.get_by_text('0 exchanges remaining this harvest.',exact=True)).to_be_visible()
                close(dialog)
            try:
                details=frame.locator('details').filter(has=frame.get_by_text('Card test tables',exact=True))
                details.get_by_text('Card test tables',exact=True).click()
                frame.get_by_label('Card to test').select_option('reed-sale')
                frame.get_by_role('button',name='Open test table',exact=True).click();card('Reed Sale')
                food,reed=amount('food'),amount('reed');play_minor('Reed Sale');qty('food',food+3);qty('reed',reed-1)
                expect(frame.locator('[data-owner="0"][data-deck="improvements"]').get_by_role('button',name='Reed Sale',exact=True)).to_have_count(0)
                next_card('Sheepskin Rug');play_minor('Sheepskin Rug');qty('sheep',0)
                next_card('Sickle');plow(4)
                next_card("Stonecutter's Hall");feeding_exchange("Stonecutter's Hall",'stone',3,'Stone → 3 Food')
                next_card('Tree House');action('growth')
                expect(frame.locator('.tt-player[data-player="0"]')).to_contain_text('1 / 3 workers ready')
                expect(frame.locator('.tt-player[data-player="0"] svg.farm image[href*="soil_room"]')).to_have_count(2)
                next_card('Boundary Stones');plow(6)
                next_card('Brazier');feeding_exchange('Brazier','sheep',2,'Sheep → 2 Food')
                next_card('Glassblowers Shop');feeding_exchange('Glassblowers Shop','clay',3,'Clay → 3 Food')
                next_card('Lamp Oil');qty('food',0);play_occupation('Architect');qty('food',0)
                next_card('Worm Bait')
                worm=frame.locator('[data-owner="0"][data-deck="improvements"]').get_by_role('button',name='Worm Bait',exact=True)
                expect(worm.locator('.tt-card-markers > span:has(img[src$="/food.png"]) > b')).to_have_text('2')
                action('grain');harvest()
                expect(worm.locator('.tt-card-markers > span:has(img[src$="/food.png"]) > b')).to_have_text('1')
                next_card('Flat Hill');clay,reed=amount('clay'),amount('reed');play_minor('Flat Hill')
                qty('clay',clay);qty('reed',reed)
                expect(frame.locator('.tt-player[data-player="0"] svg.farm image[href*="soil_room"]')).to_have_count(2)
                next_card('Lighthouse');action('growth')
                expect(frame.locator('.tt-player[data-player="0"]')).to_contain_text('1 / 3 workers ready')
                next_card('Drinker of Absinthe');play_occupation('Drinker of Absinthe')
                for n in range(7,15):expect(food_mark(n)).to_have_count(1)
                next_card('Immigrants Son');dialog=frame.get_by_role('dialog',name='Resolve Immigrants Son',exact=True)
                shape(dialog,True)
                dialog.get_by_role('button',name='Farm space 1: empty',exact=True).click()
                page.screenshot(path=f'/tmp/eurozone-nine-immigrants-son-{mobile}-{activity}.png')
                resolve(dialog)
                expect(frame.locator('.tt-player[data-player="0"] [aria-label="Farm space 1: field"]')).to_have_count(1)
                restart('Immigrants Son');dialog=frame.get_by_role('dialog',name='Resolve Immigrants Son',exact=True)
                dialog.get_by_role('button',name='Skip this effect',exact=True).click();expect(dialog).to_have_count(0)
                expect(frame.locator('.tt-player[data-player="0"] [aria-label="Farm space 1: field"]')).to_have_count(0)
                next_card('Village Druid');qty('sheep',0);play_occupation('Architect');qty('sheep',1)
                (page.frames[1] if activity else page).evaluate('location.reload()')
                card('Village Druid');qty('sheep',1)
                assert not errors,errors
                print(f'PASS fifteen card reviews; mobile={mobile}, activity={activity}',flush=True)
            except Exception:
                page.screenshot(path=f'/tmp/eurozone-nine-failure-{mobile}-{activity}.png')
                Path('/tmp/eurozone-nine-failure.html').write_text((page.frames[1] if activity else page).content())
                raise
            finally:ctx.close()
        browser.close()

if __name__=='__main__':
    browser_discord.check=check
    browser_discord.main()
