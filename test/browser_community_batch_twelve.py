"""Batch-twelve: thirty native card reviews in web and desktop/mobile Discord Activity."""
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
                page.screenshot(path=f'/tmp/eurozone-twelve-{name}-{mobile}-{activity}.png')
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
                page.screenshot(path=f'/tmp/eurozone-twelve-{name}-{mobile}-{activity}.png')
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
            try:
                details=frame.locator('details').filter(has=frame.get_by_text('Card test tables',exact=True))
                details.get_by_text('Card test tables',exact=True).click()
                frame.get_by_label('Card to test').select_option('bakers-daughter')
                frame.get_by_role('button',name='Open test table',exact=True).click();card("Baker's Daughter")
                d=frame.get_by_role('dialog',name='Resolve card effect',exact=True);shape(d)
                d.get_by_role('button',name="Play Baker's Daughter",exact=True).click();bake("Baker's Daughter")
                if frame.get_by_role('dialog',name='Resolve card effect',exact=True).count():
                    frame.get_by_role('dialog',name='Resolve card effect',exact=True).get_by_role('button',name='Continue',exact=True).click()
                    expect(frame.get_by_role('dialog',name='Resolve card effect',exact=True)).to_have_count(0)
                next_card('Carrot Cake Baker');bake('Carrot Cake Baker')
                restart('Carrot Cake Baker');skip('Carrot Cake Baker')
                next_card('Freeloader');plow(0)
                next_card('Grain Lover');d=pending('Grain Lover');shape(d,True)
                click_cell(d,1);d.get_by_role('button',name='Resolve effect →',exact=True).click();sow('Grain Lover')
                restart('Grain Lover');skip('Grain Lover')
                next_card('Inventor');discounted_oven('Inventor')
                next_card('Pearl Seeker');d=pending('Pearl Seeker');shape(d);food,stone=amount('food'),amount('stone')
                count=d.get_by_role('group',name='Stone to buy',exact=True)
                count.get_by_role('button',name='+',exact=True).click();expect(count.get_by_role('button',name='+',exact=True)).to_be_disabled()
                count.get_by_role('button',name='−',exact=True).click();screenshot('pearl-seeker');resolve(d)
                qty('food',food-2);qty('stone',stone+1)
                refresh('Pearl Seeker');qty('stone',stone+1)
                restart('Pearl Seeker');skip('Pearl Seeker')
                next_card('Stone Trader');d=pending('Stone Trader');shape(d);food,stone,wood=amount('food'),amount('stone'),amount('wood')
                expect(d.get_by_role('group',name='Playable major improvements',exact=True).get_by_role('button',name='Fireplace',exact=True)).to_have_count(0)
                major('Well');qty('food',food-1);qty('stone',stone-3);qty('wood',wood-1)
                restart('Stone Trader');skip('Stone Trader')
                next_card('Birthstone');d=pending('Birthstone');shape(d);stone=amount('stone')
                expect(d.get_by_role('group',name='Resources for points',exact=True)).to_be_visible();resolve(d);qty('stone',stone-1)
                restart('Birthstone');skip('Birthstone')
                next_card('Meat Pie');d=pending('Meat Pie');shape(d);grain,sheep=amount('grain'),amount('sheep')
                d.get_by_role('button',name='Grain + Sheep → 3 Points',exact=True).click();resolve(d);qty('grain',grain-1);qty('sheep',sheep-1)
                restart('Meat Pie');skip('Meat Pie')
                next_card('Pine Forest');d=pending('Pine Forest');shape(d);qty('wood',0)
                expect(d.get_by_role('group',name='Wood on Pine Forest',exact=True)).to_be_visible();screenshot('pine-forest');resolve(d);qty('wood',5)
                restart('Pine Forest');skip('Pine Forest');qty('wood',0)
                next_card('Bargaining Baker');discounted_oven('Bargaining Baker',True)
                next_card('Covetous Farmer');qty('reed',0);d=open_played('Covetous Farmer',True);shape(d)
                d.get_by_role('button',name='Reed',exact=True).click();d.get_by_role('button',name='Use card ability',exact=True).click();qty('reed',1)
                expect(d.get_by_role('button',name='Use card ability',exact=True)).to_be_disabled();close(d)
                next_card('Diplomat');plow(0)
                next_card('Farm School Graduate');d=pending('Farm School Graduate');shape(d);food=amount('food');pending_occupation('Architect');qty('food',food-2);harvest()
                restart('Farm School Graduate');skip_then_harvest('Farm School Graduate')
                next_card('Gem Hunter');d=pending('Gem Hunter');shape(d);clay,stone=amount('clay'),amount('stone')
                expect(d.get_by_role('group',name='Gem Hunter stone',exact=True)).to_be_visible();resolve(d);qty('clay',clay-1);qty('stone',stone+1)
                restart('Gem Hunter');skip('Gem Hunter')
                next_card('Wheelbarrow Pusher');d=pending('Wheelbarrow Pusher');shape(d);food,wood=amount('food'),amount('wood')
                d.get_by_role('group',name='Wheelbarrow resource',exact=True).get_by_role('button',name='Wood + Food',exact=True).click();screenshot('wheelbarrow');resolve(d)
                qty('food',food+1);qty('wood',wood+1)
                next_card('Wood Worker');d=pending('Wood Worker');shape(d);food,wood=amount('food'),amount('wood')
                expect(d.get_by_role('group',name='Food on action space for wood',exact=True)).to_be_visible();resolve(d);qty('food',food-1);qty('wood',wood+1)
                restart('Wood Worker');skip('Wood Worker')
                next_card('Attic');d=pending('Attic');shape(d,True);wood=amount('wood')
                expect(d.get_by_role('group',name='Scoring resources in rooms',exact=True)).to_be_visible()
                expect(d.get_by_role('button',name='Farm space 6: room',exact=True)).to_have_attribute('aria-disabled','true')
                click_cell(d,1,'room');screenshot('attic');resolve(d);qty('wood',wood-1)
                mark=frame.locator('.tt-player[data-player="0"] svg.farm .room-scoring-resource').filter(has=frame.locator('title',has_text='Attic'))
                expect(mark).to_have_count(1);refresh('Attic');expect(mark).to_have_count(1)
                restart('Attic');skip('Attic');expect(mark).to_have_count(0)
                next_card('Clockwork Man');play_minor('Clockwork Man')
                expect(frame.locator('[data-owner="0"][data-deck="improvements"]').get_by_role('button',name='Clockwork Man',exact=True)).to_have_count(1)
                next_card('Honeymoon');grain=amount('grain');action('grain');qty('grain',grain+1)
                next_card('Chameleon');play_minor('Chameleon');qty('sheep',2);qty('boar',1);qty('cattle',1)
                next_card('Goblet');major('Well','meeting')
                for n in range(3,8):expect(food_mark(n).locator('b')).to_have_text('2')
                next_card('Hay Rake');plow(1)
                next_card('Rock Pyramid');d=pending('Rock Pyramid');shape(d,True)
                click_cell(d,2);click_cell(d,3);screenshot('rock-pyramid');resolve(d)
                for n in [2,3]:expect(frame.locator(f'.tt-player[data-player="0"] [aria-label="Farm space {n}: field"]')).to_have_count(1)
                restart('Rock Pyramid');skip('Rock Pyramid')
                next_card('Tuileries Garden');d=pending('Tuileries Garden');shape(d);grain=amount('grain')
                d.get_by_role('button',name='Grain → Sow',exact=True).click();d.get_by_role('button',name='Resolve effect →',exact=True).click();sow('Tuileries Garden');qty('grain',grain)
                restart('Tuileries Garden');skip('Tuileries Garden')
                next_card('Winter Deadwood');d=pending('Winter Deadwood');shape(d);wood=amount('wood')
                d.get_by_role('button',name='Wood → 1 Point',exact=True).click();resolve(d);qty('wood',wood-1)
                expect(frame.locator('[data-owner="0"][data-deck="improvements"]').get_by_role('button',name='Winter Deadwood',exact=True)).to_have_count(1)
                restart('Winter Deadwood');d=pending('Winter Deadwood');wood=amount('wood')
                d.get_by_role('button',name='Receive Wood · pass left',exact=True).click();screenshot('winter-deadwood');resolve(d);qty('wood',wood+1)
                expect(frame.locator('[data-owner="0"][data-deck="improvements"]').get_by_role('button',name='Winter Deadwood',exact=True)).to_have_count(0)
                next_card('Award Winner');d=pending('Award Winner');shape(d);wood=amount('wood')
                group=d.get_by_role('group',name='Additional resource for one point',exact=True);expect(group.get_by_role('button')).to_have_count(2)
                expect(group.get_by_role('button',name='Stone → 1 Point',exact=True)).to_have_count(0)
                group.get_by_role('button',name='Wood → 1 Point',exact=True).click();screenshot('award-winner');resolve(d);qty('wood',wood-1)
                restart('Award Winner');skip('Award Winner')
                next_card('Cattle Dealer');d=pending('Cattle Dealer');shape(d);reed,cattle=amount('reed'),amount('cattle')
                expect(d.get_by_role('group',name='Card exchange',exact=True)).to_be_visible();resolve(d);qty('reed',reed-1);qty('cattle',cattle+2)
                restart('Cattle Dealer');skip('Cattle Dealer')
                next_card('Gardening Enthusiast');d=pending('Gardening Enthusiast');shape(d);grain,veg=amount('grain'),amount('vegetable')
                for label in ['Return Grain','Return Vegetables']:
                    button=d.get_by_role('button',name=label,exact=True);button.click();expect(button).to_have_attribute('aria-pressed','true')
                expect(d.get_by_text('Final penalty: 0 points.',exact=True)).to_be_visible();screenshot('gardening-enthusiast')
                d.get_by_role('button',name='Settle loan',exact=True).click();expect(d).to_have_count(0);qty('grain',grain-1);qty('vegetable',veg-1)
                if frame.get_by_role('dialog').count():close(frame.get_by_role('dialog'))
                next_card('Sculptors Son');wood=amount('wood');action('wood');qty('wood',wood+5)
                refresh('Sculptors Son');qty('wood',wood+5)
                assert not errors,errors
                print(f'PASS thirty card reviews; mobile={mobile}, activity={activity}',flush=True)
            except Exception:
                screenshot('failure')
                Path('/tmp/eurozone-twelve-failure.html').write_text((page.frames[1] if activity else page).content())
                raise
            finally:ctx.close()
        browser.close()

if __name__=='__main__':
    browser_discord.check=check
    browser_discord.main()
