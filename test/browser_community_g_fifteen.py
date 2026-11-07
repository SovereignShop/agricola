"""Batch-fourteen: thirty native card reviews in web and desktop/mobile Discord Activity."""
import json, os, re, urllib.parse
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
                page.screenshot(path=f'/tmp/eurozone-g-fifteen-{name}-{mobile}-{activity}.png')
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
                page.screenshot(path=f'/tmp/eurozone-g-fifteen-{name}-{mobile}-{activity}.png')
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
            def fence(dialog,edges=None):
                for edge in edges or ['h:0:0','h:1:0','v:0:0','v:0:1']:
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
            def convert(resources):
                frame.get_by_role('button',name='Go to my decision',exact=True).click()
                d=frame.get_by_role('dialog');d.get_by_text('Convert goods to food',exact=True).click()
                for resource in resources:
                    d.get_by_role('button',name='Add '+resource+' conversion',exact=True).click()
                    expect(d.get_by_role('group',name=resource+' to convert',exact=True).locator('b')).to_have_text('1')
                d.get_by_role('button',name='Convert to food',exact=True).click();close(d)
            try:
                details=frame.locator('details').filter(has=frame.get_by_text('Card test tables',exact=True))
                details.get_by_text('Card test tables',exact=True).click()
                frame.get_by_label('Card to test').select_option('headmaster')
                frame.get_by_role('button',name='Open test table',exact=True).click();card('Headmaster')
                play_occupation('Chief');qty('food',0)
                next_card('Mathematician');d=pending('Mathematician');shape(d)
                d.locator('.action-card[data-action=wood]').click();screenshot('mathematician');resolve(d)
                restart('Mathematician');skip('Mathematician')
                next_card('Miner');d=pending('Miner');shape(d)
                expect(d.get_by_label('Miner claim · Ada',exact=True)).to_have_count(1)
                d.locator('.action-card[data-action=stone-west]').click();resolve(d)
                if frame.get_by_role('dialog',name='Harvest',exact=True).count():close(frame.get_by_role('dialog',name='Harvest',exact=True))
                next_card('Opportunist');action('clay');d=pending('Opportunist');shape(d)
                d.get_by_role('button',name='Clay',exact=True).click();resolve(d)
                next_card('Pilgrim');d=pending('Pilgrim');shape(d)
                d.get_by_role('button',name='Wood',exact=True).click();resolve(d);qty('food',1);qty('wood',1)
                next_card('Pond Watchman');d=frame.get_by_role('dialog');shape(d)
                d.locator('.action-card[data-action=fishing]').click()
                d.get_by_role('button',name='Resolve effect →',exact=True).click()
                d=pending('Pond Watchman');shape(d)
                d.locator('.action-card[data-action=wood]').click()
                d.get_by_role('button',name='Place family member →',exact=True).click()
                expect(d).to_have_count(0)
                next_card('Animal Farm');d=pending('Animal Farm');shape(d)
                d.get_by_role('button',name='Sheep',exact=True).click();resolve(d)
                action('wood');expect(frame.get_by_role('dialog')).to_have_count(0)
                next_card('Bountiful Harvest')
                d=frame.get_by_role('dialog',name='Harvest',exact=True)
                d.get_by_role('button',name='Feed family →',exact=True).click()
                expect(d.get_by_text("Partner's farm",exact=True)).to_be_visible()
                d.get_by_role('button',name='Feed family →',exact=True).click()
                expect(d.get_by_text("Ada's farm",exact=True)).to_be_visible()
                d.get_by_role('button',name='Complete breeding →',exact=True).click()
                expect(d.get_by_text("Partner's farm",exact=True)).to_be_visible()
                d.get_by_role('button',name='Complete breeding →',exact=True).click()
                d=frame.get_by_role('dialog')
                d.get_by_role('button',name='Finish harvest decisions →',exact=True).click()
                expect(d).to_have_count(0)
                d=open_minor('Bountiful Harvest');shape(d)
                expect(d.get_by_role('group',name='Goods to collect',exact=True)).to_be_visible();screenshot('bountiful-stock');close(d)
                next_card('Chronicle')
                expect(frame.get_by_label('Chronicle food for Ada',exact=True)).to_have_count(10)
                major('Clay Oven','improvement')
                next_card('Dunghill');d=pending('Dunghill');shape(d,True)
                click_cell(d,2,'field');click_cell(d,3,'field');screenshot('dunghill');resolve(d)
                if frame.get_by_role('dialog',name='Harvest',exact=True).count():close(frame.get_by_role('dialog',name='Harvest',exact=True))
                restart('Dunghill');skip('Dunghill')
                assert not errors,errors
                print(f'PASS ten G-deck reviews; mobile={mobile}, activity={activity}',flush=True)
            except Exception:
                print("BROWSER ERRORS",errors,flush=True)
                screenshot('failure')
                Path('/tmp/eurozone-g-fifteen-failure.html').write_text((page.frames[1] if activity else page).content())
                raise
            finally:ctx.close()
        browser.close()

if __name__=='__main__':
    browser_discord.check=check
    browser_discord.main()
