"""Batch-thirteen: thirty native card reviews in web and desktop/mobile Discord Activity."""
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
            def next_card(name):
                button=review().get_by_role('button',name='Next card →',exact=True)
                expect(button).to_be_enabled();button.click();card(name)
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
                page.screenshot(path=f'/tmp/eurozone-thirteen-{name}-{mobile}-{activity}.png')
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
            def screenshot(name):
                page.evaluate('() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')
                page.screenshot(path=f'/tmp/eurozone-thirteen-{name}-{mobile}-{activity}.png')
            def major(name='Fireplace',action_space=None):
                if action_space:frame.locator(f'.action-card[data-action={action_space}]').click()
                frame.get_by_role('group',name='Playable major improvements',exact=True).get_by_role('button',name=name,exact=True).first.click()
                preview=frame.get_by_role('dialog',name=name,exact=True)
                preview.get_by_role('button',name='Place family member & play card →' if action_space else 'Build improvement →',exact=True).click()
                expect(preview).to_have_count(0)
            def pending_occupation(name):
                frame.get_by_role('group',name='Playable occupations',exact=True).get_by_role('button',name=name,exact=True).click()
                preview=frame.get_by_role('dialog',name=name,exact=True)
                preview.get_by_role('button',name='Play occupation →',exact=True).click();expect(preview).to_have_count(0)
            def marks(name):return frame.locator('.tt-player[data-player="0"] svg.farm .room-scoring-food').filter(has=frame.locator('title',has_text=name))
            def refresh(name):
                (page.frames[1] if activity else page).evaluate('location.reload()');card(name)
            def skip_then_harvest(name):skip(name);harvest()
            def click_cell(dialog,n,kind='empty'):
                cell=dialog.get_by_role('button',name=f'Farm space {n}: {kind}',exact=True)
                cell.click();expect(cell).to_have_attribute('aria-pressed','true')
            def bake(name):
                d=pending(name);shape(d)
                expect(d.get_by_role('group',name='Baking exchange',exact=True)).to_be_visible()
                food,grain=amount('food'),amount('grain')
                d.get_by_role('button',name='Bake bread →',exact=True).click()
                qty('grain',grain-1);qty('food',food+5)
            def sow(name):
                d=pending(name);d.get_by_role('button',name='Take Sow action',exact=True).click();shape(d,True)
                expect(d.get_by_role('group',name='Crop to sow',exact=True).get_by_role('button',name='Vegetables',exact=True)).to_have_count(0)
                click_cell(d,1,'field');screenshot(name);resolve(d)
                expect(frame.locator('.tt-player[data-player="0"] [aria-label="Farm space 1: field"] image[data-piece-type="grain"]')).to_have_count(3)
            def discounted_oven(name,baking=False):
                clay,stone,grain,food=amount('clay'),amount('stone'),amount('grain'),amount('food')
                frame.locator('.action-card[data-action=improvement]').click()
                frame.get_by_role('group',name='Playable major improvements',exact=True).get_by_role('button',name='Clay Oven',exact=True).click()
                preview=frame.get_by_role('dialog',name='Clay Oven',exact=True)
                preview.get_by_role('button',name='Select card',exact=True).click();expect(preview).to_have_count(0)
                d=frame.get_by_role('dialog');shape(d)
                group=d.get_by_role('group',name=name+' discount',exact=True)
                group.get_by_role('button',name='Pay 1 less clay',exact=True).click()
                if baking:
                    d.get_by_role('button',name='Add grain to bake',exact=True).click()
                screenshot(name);d.get_by_role('button',name='Place family member →',exact=True).click()
                expect(d).to_have_count(0);qty('clay',clay-2);qty('stone',stone-1)
                qty('grain',grain+(1 if name=='Bargaining Baker' else 0)-(1 if baking else 0))
                if baking:qty('food',food+5)
            def clear_harvest():
                d=frame.get_by_role('dialog',name='Harvest',exact=True)
                if d.count():close(d)
            def fence(dialog):
                for edge in ['h:0:0','h:1:0','v:0:0','v:0:1']:
                    svg=dialog.locator('svg.farm')
                    point=svg.evaluate("(svg, label) => {const root=svg.getBoundingClientRect(), edge=svg.querySelector('[aria-label=\"'+label+'\"]').getBoundingClientRect();return {x:edge.x-root.x+edge.width/2,y:edge.y-root.y+edge.height/2}}",'Fence '+edge)
                    svg.click(position=point)
                screenshot('fences')
            def card_marker(name,resource,occupation=False):
                deck='occupations' if occupation else 'improvements'
                image={'wood':'tree.png','food':'food.png','clay':'clay.png'}[resource]
                return frame.locator(f'[data-owner="0"][data-deck="{deck}"]').get_by_role('button',name=name,exact=True).locator(f'.tt-card-markers > span:has(img[src$="/{image}"]) > b')
            def selected_minor(dialog,name):
                dialog.get_by_role('group',name='Minor improvements to discard',exact=True).get_by_role('button',name=name,exact=True).click()
                preview=frame.get_by_role('dialog',name=name,exact=True)
                preview.get_by_role('button',name='Select card',exact=True).click();expect(preview).to_have_count(0)
            try:
                details=frame.locator('details').filter(has=frame.get_by_text('Card test tables',exact=True))
                details.get_by_text('Card test tables',exact=True).click()
                frame.get_by_label('Card to test').select_option('animal-farmer')
                frame.get_by_role('button',name='Open test table',exact=True).click();card('Animal Farmer')
                action('sheep');qty('sheep',2);qty('boar',2);qty('cattle',1)
                next_card('Artisan');d=frame.get_by_role('dialog',name='Harvest',exact=True);food,clay=amount('food'),amount('clay')
                d.get_by_role('checkbox',name='Use Pottery · 1 clay → 2 food',exact=True).click();d.get_by_role('button',name='Feed family →',exact=True).click()
                qty('clay',clay-1);qty('food',food);clear_harvest()
                next_card('Resource Collector');d=pending('Resource Collector');shape(d)
                for name in ['Wood','Clay','Reed','Stone']:
                    button=d.get_by_role('button',name='Schedule '+name,exact=True);button.click();expect(button).to_be_disabled()
                d.get_by_role('button',name='Remove Clay from delivery 2',exact=True).click()
                expect(d.get_by_role('button',name='Schedule Clay',exact=True)).to_be_enabled()
                d.get_by_role('button',name='Clear delivery order',exact=True).click()
                for name in ['Wood','Clay','Reed','Stone']:d.get_by_role('button',name='Schedule '+name,exact=True).click()
                screenshot('resource-collector');resolve(d)
                for round,resource in [(2,'wood'),(3,'clay'),(4,'reed'),(5,'stone')]:
                    expect(frame.locator(f'.future-round-resources[data-round="{round}"] [data-resource="{resource}"]')).to_have_count(1)
                refresh('Resource Collector');expect(frame.locator('.future-round-resources[data-round="2"] [data-resource="wood"]')).to_have_count(1)
                next_card('Understudy');plow(1)
                next_card('Beehive Oven');d=pending('Beehive Oven');shape(d);food,grain=amount('food'),amount('grain')
                d.get_by_role('button',name='Use other baking improvements · +1 food',exact=True).click()
                expect(d.get_by_label('Receive 6 food',exact=True)).to_be_visible();screenshot('beehive-oven')
                d.get_by_role('button',name='Bake bread →',exact=True).click();qty('food',food+6);qty('grain',grain-1)
                restart('Beehive Oven');d=pending('Beehive Oven');food=amount('food')
                d.get_by_role('button',name='Use Beehive Oven',exact=True).click();expect(d.get_by_label('Receive 4 food',exact=True)).to_be_visible()
                d.get_by_role('button',name='Bake bread →',exact=True).click();qty('food',food+4)
                restart('Beehive Oven');skip('Beehive Oven')
                next_card('Cow Bell');action('cattle');qty('cattle',4);expect(frame.get_by_role('dialog')).to_have_count(0)
                next_card('Hammer');d=pending('Hammer');shape(d,True);wood=amount('wood')
                expect(d.get_by_role('group',name='Hammer payment to opponents',exact=True)).to_be_visible();fence(d);resolve(d);qty('wood',wood-5)
                next_card("Potter's Wheel");d=frame.get_by_role('dialog',name='Harvest',exact=True);food,clay=amount('food'),amount('clay')
                d.get_by_role('checkbox',name='Use Pottery · 1 clay → 2 food',exact=True).click();d.get_by_role('button',name='Feed family →',exact=True).click()
                qty('clay',clay-1);qty('food',food-1);clear_harvest()
                next_card('Remodeling');d=pending('Remodeling');shape(d,True);wood,reed=amount('wood'),amount('reed')
                click_cell(d,1);d.get_by_role('button',name='Resolve effect →',exact=True).click()
                expect(d.get_by_role('group',name='Return stables',exact=True)).to_have_count(0)
                click_cell(d,1);screenshot('remodeling');resolve(d);qty('wood',wood);qty('reed',reed)
                expect(frame.locator('.tt-player[data-player="0"] svg.farm image[href$="/wood_room.png"]')).to_have_count(3)
                next_card('Seasonal Plants');d=pending('Seasonal Plants');shape(d,True);click_cell(d,2,'field');resolve(d)
                expect(frame.locator('.tt-player[data-player="0"] [aria-label="Farm space 2: field"] image[data-piece-type="vegetable"]')).to_have_count(3)
                next_card('Fence Dealer');wood=amount('wood');frame.locator('.action-card[data-action=fences]').click();d=frame.get_by_role('dialog');shape(d,True);fence(d)
                d.get_by_role('button',name='Place family member →',exact=True).click();qty('wood',wood-2)
                next_card('Livestock Keeper');d=pending('Livestock Keeper');shape(d);food=amount('food')
                d.get_by_role('button',name='Food → extra Sheep',exact=True).click();resolve(d);qty('food',food-1)
                d=frame.get_by_role('dialog',name='Harvest',exact=True);d.get_by_role('button',name='Complete breeding →',exact=True).click();qty('sheep',4);clear_harvest()
                restart('Livestock Keeper');skip('Livestock Keeper');clear_harvest()
                next_card('Stable Deliveryman');d=pending('Stable Deliveryman');shape(d)
                expect(d.get_by_role('group',name='Stable arrival payment',exact=True)).to_be_visible()
                for n in [4,6,8,10]:
                    button=d.get_by_role('button',name=f'Reserve stable for round {n}',exact=True);button.click();expect(button).to_have_attribute('aria-pressed','true')
                screenshot('stable-deliveryman');resolve(d)
                for n in [4,6,8,10]:expect(frame.locator(f'.future-round-resources[data-round="{n}"] [data-resource="stable"]')).to_have_count(1)
                next_card('Sunrise Admirer');d=pending('Sunrise Admirer');shape(d,True);food=amount('food')
                expect(d.get_by_role('button',name='Farm space 1: empty',exact=True)).to_have_attribute('aria-disabled','true')
                click_cell(d,5);screenshot('sunrise-admirer');resolve(d);qty('food',food-2)
                expect(frame.locator('.tt-player[data-player="0"] [aria-label="Farm space 5: field"]')).to_have_count(1)
                restart('Sunrise Admirer');skip('Sunrise Admirer')
                expect(frame.locator('[data-owner="0"][data-deck="occupations"]').get_by_role('button',name='Sunrise Admirer',exact=True)).to_have_count(0)
                next_card('Apple Tree');d=pending('Apple Tree');shape(d,True);click_cell(d,1,'field');screenshot('apple-tree');resolve(d)
                mark=frame.locator('.tt-player[data-player="0"] .farm-card-markers[aria-label="Apple Tree farm marker"]');expect(mark).to_have_count(1)
                refresh('Apple Tree');expect(mark).to_have_count(1)
                next_card('Backyard Well');d=open_played('Backyard Well');shape(d,True);food,stone=amount('food'),amount('stone');click_cell(d,1)
                screenshot('backyard-well');d.get_by_role('button',name='Use card ability',exact=True).click();qty('stone',stone);qty('food',food)
                expect(d.get_by_role('button',name='Use card ability',exact=True)).to_be_disabled();close(d)
                expect(frame.locator('.tt-player[data-player="0"] .farm-card-markers[aria-label="Backyard Well farm marker"]')).to_have_count(1)
                for n in [2,3,4]:expect(food_mark(n)).to_have_count(1)
                next_card('Changing Plans');d=pending('Changing Plans');shape(d,True);click_cell(d,1);click_cell(d,2)
                d.get_by_role('button',name='Resolve effect →',exact=True).click();expect(d.get_by_role('group',name='Return stables',exact=True)).to_have_count(0)
                click_cell(d,1);expect(d.get_by_role('button',name='Resolve effect →',exact=True)).to_be_disabled();click_cell(d,2);screenshot('changing-plans');resolve(d)
                for n in [1,2]:expect(frame.locator(f'.tt-player[data-player="0"] [aria-label="Farm space {n}: field"]')).to_have_count(1)
                next_card('Gold Medal');d=pending('Gold Medal');shape(d);d.get_by_role('group',name='Gold Medal scoring category',exact=True).get_by_role('button',name='Grain',exact=True).click();screenshot('gold-medal');resolve(d)
                next_card('Pig Wallow');d=pending('Pig Wallow');shape(d,True);wood=amount('wood');click_cell(d,1,'field')
                d.get_by_role('button',name='Resolve effect →',exact=True).click();click_cell(d,1);screenshot('pig-wallow');resolve(d);qty('wood',wood)
                expect(frame.locator('.tt-player[data-player="0"] [aria-label="Farm space 1: empty, pasture"]')).to_have_count(1)
                next_card('Wild Breeding');d=pending('Wild Breeding');shape(d);sheep=amount('sheep');d.locator('.action-card[data-action=sheep]').click();screenshot('wild-breeding');resolve(d);qty('sheep',sheep+1)
                restart('Wild Breeding');skip('Wild Breeding')
                next_card('Absinthe');d=pending('Absinthe');shape(d);food=amount('food');d.locator('.action-card[data-action=grain]').click();screenshot('absinthe');resolve(d);action('grain');qty('food',food+1)
                next_card('Artists Garden');d=pending('Artists Garden');shape(d,True);grain=amount('grain');click_cell(d,1,'field');resolve(d);qty('grain',grain+1)
                clear_harvest();restart('Artists Garden');skip('Artists Garden');clear_harvest()
                next_card('Chocolade');food=amount('food');frame.locator('.action-card[data-action=lessons]').click();d=frame.get_by_role('dialog');shape(d)
                expect(d.get_by_role('group',name='Chocolade occupation payment',exact=True)).to_be_visible()
                d.get_by_role('button',name='Keep stored food',exact=True).click();screenshot('chocolade')
                d.get_by_role('group',name='Playable occupations',exact=True).get_by_role('button',name='Architect',exact=True).click()
                preview=frame.get_by_role('dialog',name='Architect',exact=True);preview.get_by_role('button',name='Place family member & play card →',exact=True).click();expect(preview).to_have_count(0);qty('food',food-1)
                restart('Chocolade');food=amount('food');play_occupation('Architect');qty('food',food)
                next_card('Straw-Thatched Hut');clay,grain,food,reed=amount('clay'),amount('grain'),amount('food'),amount('reed')
                frame.locator('.action-card[data-action=build]').click();d=frame.get_by_role('dialog');shape(d,True);click_cell(d,1)
                d.get_by_role('button',name='2 Clay + Grain + Food → Clay room',exact=True).click();screenshot('straw-thatched-hut')
                d.get_by_role('button',name='Place family member →',exact=True).click();qty('clay',clay-2);qty('grain',grain-1);qty('food',food-1);qty('reed',reed)
                next_card('Animal Welfarist');action('grain');qty('sheep',1);qty('boar',1);qty('cattle',1);clear_harvest()
                next_card('Benefactor');d=open_played('Benefactor',True);shape(d,True);wood,reed=amount('wood'),amount('reed')
                d.get_by_role('button',name='Sheep → Wooden room',exact=True).click();click_cell(d,1);screenshot('benefactor')
                d.get_by_role('button',name='Use card ability',exact=True).click();qty('sheep',0);qty('wood',wood);qty('reed',reed)
                expect(d.get_by_role('button',name='Use card ability',exact=True)).to_be_disabled();close(d)
                next_card('Cabbage Lover');d=pending('Cabbage Lover');shape(d);clay=amount('clay')
                expect(d.get_by_role('group',name='Clay for Cabbage Lover points',exact=True)).to_be_visible();screenshot('cabbage-lover');resolve(d);qty('clay',clay-1)
                restart('Cabbage Lover');skip('Cabbage Lover')
                next_card('Country Doctor');action('growth');expect(frame.get_by_role('dialog')).to_have_count(0)
                next_card('Martial Artist');d=pending('Martial Artist');shape(d);food=amount('food');selected_minor(d,'Shovel');selected_minor(d,'Clockwork Man');screenshot('martial-artist');resolve(d);qty('food',food+4);clear_harvest()
                restart('Martial Artist');skip('Martial Artist');clear_harvest()
                next_card('Parquet Setter');wood,food=amount('wood'),amount('food');action('grain');qty('wood',wood+1);qty('food',food+1);clear_harvest()
                refresh('Parquet Setter');qty('wood',wood+1)
                assert not errors,errors
                print(f'PASS thirty card reviews; mobile={mobile}, activity={activity}',flush=True)
            except Exception:
                screenshot('failure')
                Path('/tmp/eurozone-thirteen-failure.html').write_text((page.frames[1] if activity else page).content())
                raise
            finally:ctx.close()
        browser.close()

if __name__=='__main__':
    browser_discord.check=check
    browser_discord.main()
