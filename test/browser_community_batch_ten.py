"""Batch-ten: thirty native card reviews in web and desktop/mobile Discord Activity."""
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
            def card(name):
                expect(review().locator('.card-review-heading strong')).to_have_text(name,timeout=30000)
                print(f'Review {name}: mobile={mobile}, activity={activity}',flush=True)
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
            def pending(name):return frame.get_by_role('dialog',name='Resolve '+name,exact=True)
            def open_played(name,occupation=False):
                deck='occupations' if occupation else 'improvements'
                frame.locator(f'[data-owner="0"][data-deck="{deck}"]').get_by_role('button',name=f'Open Played · {deck} spread',exact=True).click()
                frame.get_by_role('dialog').get_by_role('button',name=name,exact=True).click()
                return frame.get_by_role('dialog',name=name,exact=True)
            def feed():
                h=frame.get_by_role('dialog',name='Harvest',exact=True)
                expect(h).to_be_visible();h.get_by_role('button',name='Feed family →',exact=True).click()
            def exchange_card(name,resource,n,reward,label,occupation=False):
                harvest();food,before=amount('food'),amount(resource)
                d=open_played(name,occupation);shape(d)
                expect(d.get_by_label('Times',exact=True)).to_have_count(0)
                d.get_by_role('button',name=label,exact=True).click()
                d.get_by_role('button',name='Use card ability',exact=True).click()
                qty('food',food+reward);qty(resource,before-n)
                expect(d.get_by_role('button',name='Use card ability',exact=True)).to_be_disabled();close(d)
            def skip(name):
                d=pending(name);d.get_by_role('button',name='Skip this effect',exact=True).click();expect(d).to_have_count(0)
            def screenshot(name):page.screenshot(path=f'/tmp/eurozone-ten-{name}-{mobile}-{activity}.png')
            try:
                details=frame.locator('details').filter(has=frame.get_by_text('Card test tables',exact=True))
                details.get_by_text('Card test tables',exact=True).click()
                frame.get_by_label('Card to test').select_option('fishermans-apprentice')
                frame.get_by_role('button',name='Open test table',exact=True).click();card("Fisherman's Apprentice")
                d=pending("Fisherman's Apprentice");shape(d);food=amount('food')
                partner=frame.locator('.tt-player[data-player="1"] .tt-inventory [data-goods="food"] .pile-count');partner_before=int(partner.inner_text())
                d.get_by_role('group',name='Playable occupations',exact=True).get_by_role('button',name='Architect',exact=True).click()
                preview=frame.get_by_role('dialog',name='Architect',exact=True)
                preview.get_by_role('button',name='Play occupation →',exact=True).click();qty('food',food-1)
                expect(partner).to_have_text(str(partner_before+1));expect(d).to_have_count(0)
                restart("Fisherman's Apprentice");skip("Fisherman's Apprentice")
                next_card('Glass Blower');exchange_card('Glass Blower','clay',2,5,'2 Clay → 5 Food',True)
                next_card('Grafter');grain,veg=amount('grain'),amount('vegetable');feed();qty('grain',grain+1);qty('vegetable',veg+1);harvest()
                next_card('Grain Farmer');food=amount('food');action('grain');qty('food',food+3);harvest()
                next_card('Grump');wood=amount('wood');play_occupation('Grump');qty('wood',wood+3)
                next_card('Helper');frame.locator('.action-card[data-action=build]').click()
                d=frame.get_by_role('dialog');shape(d,True);d.get_by_role('button',name='Farm space 1: empty',exact=True).click()
                d.get_by_role('button',name='Place family member →',exact=True).click();expect(d).to_have_count(0)
                expect(frame.locator('.tt-player[data-player="0"] svg.farm image[href*="wood_room"]')).to_have_count(3)
                next_card('Truffle Digger');food=amount('food');action('wood');qty('food',food+2)
                next_card('Village Farmer');wood=amount('wood');play_occupation('Village Farmer');qty('wood',wood+4)
                next_card('Wheedler');food=amount('food');action('labor');qty('food',food+3)
                next_card('Sausage Grinder');d=frame.get_by_role('dialog',name='Harvest',exact=True);expect(d).to_be_visible()
                food=amount('food');d.get_by_role('button',name='Add Sheep conversion',exact=True).click();feed();qty('sheep',0);qty('food',food-1);harvest()
                next_card('Animal Buyer');d=pending('Animal Buyer');shape(d);food=amount('food')
                d.get_by_role('button',name='2 Sheep for 4 Food',exact=True).click();screenshot('animal-buyer');resolve(d)
                qty('sheep',2);qty('food',food-4);harvest()
                restart('Animal Buyer');skip('Animal Buyer');harvest()
                next_card('Cloister Dweller');play_occupation('Cloister Dweller')
                expect(frame.locator('[data-owner="0"][data-deck="occupations"]').get_by_role('button',name='Cloister Dweller',exact=True)).to_have_count(1)
                next_card('Humble Farmboy');wood=amount('wood');play_occupation('Humble Farmboy');qty('wood',wood+4)
                next_card('Silo Girl');action('renovate')
                for n in range(7,15):
                    resource='grain' if n%2==0 else 'vegetable'
                    expect(frame.locator(f'.future-round-resources[data-round="{n}"] [data-resource="{resource}"]')).to_have_count(1)
                next_card('Stone Curator');food=amount('food');action('stone-west');qty('food',food+1)
                next_card('Tile Maker');clay=amount('clay');play_occupation('Tile Maker');qty('clay',clay+3)
                next_card('Birdcage');d=pending('Birdcage');shape(d);food=amount('food')
                expect(d.get_by_role('group',name='Store food for points',exact=True)).to_be_visible();screenshot('birdcage');resolve(d);qty('food',food-1);harvest()
                restart('Birdcage');skip('Birdcage');harvest()
                next_card('Free-Range Chickens');food=amount('food');d=open_played('Free-Range Chickens');shape(d)
                control=d.get_by_role('group',name='Food to collect',exact=True)
                for _ in range(4):control.get_by_role('button',name='−',exact=True).click()
                screenshot('partial-food');d.get_by_role('button',name='Collect goods →',exact=True).click();qty('food',food+2);close(d)
                chicken=frame.locator('[data-owner="0"][data-deck="improvements"]').get_by_role('button',name='Free-Range Chickens',exact=True)
                expect(chicken.locator('.tt-card-markers > span:has(img[src$="/food.png"]) > b')).to_have_text('4')
                next_card('Fruitcake');action('grain');d=frame.get_by_role('dialog',name='Harvest',exact=True)
                expect(d).to_contain_text('Ada needs 2 food.');harvest()
                next_card('Iron Plow');d=pending('Iron Plow');shape(d,True)
                d.get_by_role('button',name='Farm space 2: empty',exact=True).click()
                d.get_by_role('button',name='Resolve effect →',exact=True).click()
                d.get_by_role('button',name='Take Sow action',exact=True).click()
                d.get_by_role('button',name='Farm space 1: field',exact=True).click()
                d.get_by_role('group',name='Crop to sow',exact=True).get_by_role('button',name='Vegetables',exact=True).click()
                d.get_by_role('button',name='Farm space 2: field',exact=True).click();screenshot('iron-plow');resolve(d)
                expect(frame.locator('.tt-player[data-player="0"] [aria-label="Farm space 2: field"]')).to_have_count(1)
                restart('Iron Plow');skip('Iron Plow')
                expect(frame.locator('.tt-player[data-player="0"] [aria-label="Farm space 2: field"]')).to_have_count(0)
                next_card('Apple Garden');play_minor('Apple Garden')
                expect(frame.locator('[data-owner="0"][data-deck="improvements"]').get_by_role('button',name='Apple Garden',exact=True)).to_have_count(1)
                next_card('Peasants Boutique');exchange_card('Peasants Boutique','reed',1,4,'Reed → 4 Food')
                next_card('The Port Le Havre');d=pending('The Port Le Havre');shape(d);clay,stone=amount('clay'),amount('stone')
                d.get_by_role('button',name='2 Clay → 2 Stone',exact=True).click();resolve(d);qty('clay',clay-2);qty('stone',stone+2)
                restart('The Port Le Havre');skip('The Port Le Havre')
                next_card('Watering Can');food=amount('food');play_minor('Watering Can');qty('food',food-1)
                expect(frame.locator('[data-owner="0"][data-deck="improvements"]').get_by_role('button',name='Watering Can',exact=True)).to_have_count(0)
                next_card('Art Director');d=pending('Art Director');shape(d);stone=amount('stone')
                d.get_by_role('button',name='Add Stone',exact=True).click();d.get_by_role('button',name='Take 1 resource →',exact=True).click();qty('stone',stone+1);expect(d).to_have_count(0)
                next_card('Cocotte');d=pending('Cocotte');shape(d);food=amount('food')
                expect(d.get_by_role('group',name='Additional major improvement fee',exact=True)).to_be_visible()
                d.get_by_role('button',name='Fireplace',exact=True).first.click()
                preview=frame.get_by_role('dialog',name='Fireplace',exact=True)
                screenshot('cocotte');preview.get_by_role('button',name='Build improvement →',exact=True).click();qty('food',food-1);expect(d).to_have_count(0)
                restart('Cocotte');skip('Cocotte')
                next_card('Dove Hunter');exchange_card('Dove Hunter','sheep',1,4,'Sheep → 4 Food',True)
                next_card('Landscape Gardener');d=pending('Landscape Gardener');shape(d)
                d.get_by_role('button',name='Take Sow action',exact=True).click();shape(d)
                d.get_by_role('button',name='Field on Landscape Gardener · stack 1',exact=True).click()
                d.get_by_role('group',name='Crop to sow',exact=True).get_by_role('button',name='Vegetables',exact=True).click()
                d.get_by_role('button',name='Field on Landscape Gardener · stack 2',exact=True).click();screenshot('landscape-gardener');resolve(d)
                restart('Landscape Gardener');skip('Landscape Gardener')
                next_card('Stage Star');wood=amount('wood');action('traveling');qty('wood',wood-1)
                next_card('Wealthiest European');d=pending('Wealthiest European');shape(d)
                before={r:amount(r) for r in ['wood','clay','stone']}
                for name in ['Wood','Clay','Stone']:d.get_by_role('button',name='Add '+name,exact=True).click()
                screenshot('wealthiest');d.get_by_role('button',name='Take 3 resources →',exact=True).click()
                for r,n in before.items():qty(r,n+1)
                expect(d).to_have_count(0)
                (page.frames[1] if activity else page).evaluate('location.reload()');card('Wealthiest European')
                for r,n in before.items():qty(r,n+1)
                assert not errors,errors
                print(f'PASS thirty card reviews; mobile={mobile}, activity={activity}',flush=True)
            except Exception:
                screenshot('failure')
                Path('/tmp/eurozone-ten-failure.html').write_text((page.frames[1] if activity else page).content())
                raise
            finally:ctx.close()
        browser.close()

if __name__=='__main__':
    browser_discord.check=check
    browser_discord.main()
