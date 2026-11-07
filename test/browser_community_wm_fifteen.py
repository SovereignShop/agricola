"""Batch-fifteen WM: ten native card reviews in web and desktop/mobile Discord Activity."""
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
                page.screenshot(path=f'/tmp/eurozone-wm-fifteen-{name}-{mobile}-{activity}.png')
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
                expect(h).to_be_visible();h.get_by_role('button',name=re.compile(r'^Feed (family|& take)')).click()
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
                page.screenshot(path=f'/tmp/eurozone-wm-fifteen-{name}-{mobile}-{activity}.png')
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
            def clear_harvest():
                d=frame.get_by_role('dialog',name='Harvest',exact=True)
                if d.count():close(d)
            try:
                details=frame.locator('details').filter(has=frame.get_by_text('Card test tables',exact=True))
                details.get_by_text('Card test tables',exact=True).click()
                frame.get_by_label('Card to test').select_option('borrower')
                frame.get_by_role('button',name='Open test table',exact=True).click();card('Borrower')
                before=amount('food');d=open_played('Borrower',True);shape(d);screenshot('borrower')
                d.get_by_role('button',name='Use card ability',exact=True).click();qty('food',before-3);close(d)
                next_card('Chemist');d=pending('Chemist');shape(d);food,clay=amount('food'),amount('clay')
                d.locator('.action-card[data-action=reed]').click();expect(d.get_by_role('button',name='Resolve effect →',exact=True)).to_be_enabled()
                screenshot('chemist');resolve(d);qty('food',food+2);qty('clay',clay-1)
                next_card('Family Planner');d=pending('Family Planner');shape(d)
                for n in [5,8]:
                    b=d.get_by_role('button',name=f'Reserve person for round {n}',exact=True);b.click();expect(b).to_have_attribute('aria-pressed','true')
                screenshot('family-planner');resolve(d)
                for n in [5,8]:expect(frame.locator(f'.future-round-resources[data-round="{n}"] [data-resource="person"]')).to_have_count(1)
                refresh('Family Planner')
                next_card('Lady in Waiting');d=pending('Lady in Waiting');shape(d);food=amount('food');screenshot('lady');resolve(d);qty('food',food-2)
                next_card('Storehouse Minder');d=pending('Storehouse Minder');shape(d);wood,clay=amount('wood'),amount('clay')
                for r in ['Wood','Clay']:
                    b=d.get_by_role('group',name='Resources to store',exact=True).get_by_role('button',name=r,exact=True);b.click();expect(b).to_have_attribute('aria-pressed','true')
                screenshot('minder-deposit');resolve(d);qty('wood',wood-1);qty('clay',clay-1)
                d=open_played('Storehouse Minder',True);shape(d)
                b=d.get_by_role('group',name='Stored resource to collect',exact=True).get_by_role('button',name='Collect Clay',exact=True)
                b.click();expect(b).to_have_attribute('aria-pressed','true');d.get_by_role('button',name='Use card ability',exact=True).click();qty('clay',clay);close(d)
                next_card('Almshouse');feed();d=pending('Almshouse');shape(d);screenshot('almshouse');resolve(d);clear_harvest()
                next_card('Cash Crop');grain=amount('grain');action('grain');qty('grain',grain+1);harvest();screenshot('cash-crop')
                next_card('Fish Pond');d=pending('Fish Pond');shape(d,True);click_cell(d,1);screenshot('fish-pond-place');resolve(d)
                expect(frame.locator('.tt-player[data-player="0"] [aria-label="Farm space 1: feature"]')).to_have_count(1)
                food=amount('food');action('fishing');d=pending('Fish Pond');shape(d);screenshot('fish-pond-store');resolve(d);qty('food',food)
                next_card('Rotherham Plow');d=pending('Rotherham Plow');shape(d,True);click_cell(d,8);screenshot('rotherham');resolve(d)
                expect(frame.locator('.tt-player[data-player="0"] [aria-label="Farm space 8: field"]')).to_have_count(1)
                next_card('Slapdash Renovation');d=pending('Slapdash Renovation');shape(d);screenshot('slapdash');resolve(d)
                expect(frame.locator('[data-piece-key^="worker::renovate:"]')).to_have_count(0)
                assert not errors,errors
                print(f'PASS ten WM card reviews; mobile={mobile}, activity={activity}',flush=True)
            except Exception:
                screenshot('failure')
                Path('/tmp/eurozone-wm-fifteen-failure.html').write_text((page.frames[1] if activity else page).content())
                raise
            finally:ctx.close()
        browser.close()

if __name__=='__main__':
    browser_discord.check=check
    browser_discord.main()
