"""Ten FR native card reviews: web, Discord iframe, and touch-sized Discord."""
import json, os, urllib.parse
from pathlib import Path
from playwright.sync_api import expect, sync_playwright
import browser_discord


def check(base):
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ['EUROZONE_BROWSER'])
        for mobile, activity in [(False, False), (False, True), (True, True)]:
            width = 390 if mobile else 1440
            ctx = browser.new_context(viewport={'width': width, 'height': 844 if mobile else 1000}, has_touch=mobile, is_mobile=mobile)
            page = ctx.new_page(); errors = []; page.on('pageerror', lambda e: errors.append(str(e)))
            if activity:
                parent = browser_discord.PARENT.replace('<style>', '<meta name="viewport" content="width=device-width, initial-scale=1"><style>').replace('e.source.postMessage', 'e.source?.postMessage').replace('USER', json.dumps({'id': '111', 'username': 'Ada'}))
                ctx.route(base+'/discord-test-parent', lambda route: route.fulfill(content_type='text/html', body=parent))
                page.goto(base+'/discord-test-parent'); frame = page.frame_locator('iframe')
            else:
                def authorize(route):
                    login = route.fetch(max_redirects=0)
                    params = urllib.parse.parse_qs(urllib.parse.urlsplit(login.headers['location']).query)
                    callback = params['redirect_uri'][0]+'?'+urllib.parse.urlencode({'code': '111', 'state': params['state'][0]})
                    route.fulfill(response=login, headers={**login.headers, 'location': callback})
                ctx.route(base+'/discord/login?*', authorize); page.goto(base+'/?seat=new'); frame = page
            def review(): return frame.get_by_role('region', name='Card review', exact=True)
            def card(name):
                expect(review().locator('.card-review-heading strong')).to_have_text(name, timeout=30000)
                print(f'FR review {name}: mobile={mobile}, activity={activity}', flush=True)
            def pending(name): return frame.get_by_role('dialog', name='Resolve '+name, exact=True)
            def next_card(name):
                review().get_by_role('button', name='Next card →', exact=True).click(); card(name)
            def shape(dialog, farm=False):
                expect(dialog.get_by_role('combobox')).to_have_count(0)
                expect(dialog.locator('svg.farm')).to_have_count(1 if farm else 0)
                box = dialog.bounding_box(); assert box['x'] >= 0 and box['x']+box['width'] <= width+1, box
                assert dialog.evaluate('(el)=>el.scrollWidth<=el.clientWidth+1')
            def snap(name): page.screenshot(path=f'/tmp/eurozone-fifteen-fr-{name}-{mobile}-{activity}.png')
            def marker(resource): return frame.locator(f'.tt-player[data-player="0"] .tt-inventory [data-goods="{resource}"] .pile-count')
            def amount(resource): return int(marker(resource).inner_text()) if marker(resource).count() else 0
            def qty(resource, n):
                if n: expect(marker(resource)).to_have_text(str(n))
                else: expect(marker(resource)).to_have_count(0)
            def resolve(dialog):
                dialog.get_by_role('button', name='Resolve effect →', exact=True).click()
            def cell(dialog, n, kind='empty'):
                button = dialog.get_by_role('button', name=f'Farm space {n}: {kind}', exact=True)
                button.click(); expect(button).to_have_attribute('aria-pressed', 'true')
            def action(space):
                frame.locator(f'.action-card[data-action={space}]').click()
                frame.get_by_role('button', name='Place family member →', exact=True).click()
            def close_harvest():
                d = frame.get_by_role('dialog', name='Harvest', exact=True)
                if d.count(): d.get_by_role('button', name='Close dialog', exact=True).click()
            def restart(name):
                old = frame.locator('.game').get_attribute('data-motion-table')
                review().get_by_role('button', name='Restart test', exact=True).click()
                expect(frame.locator('.game')).not_to_have_attribute('data-motion-table', old, timeout=30000); card(name)
            try:
                details = frame.locator('details').filter(has=frame.get_by_text('Card test tables', exact=True))
                details.get_by_text('Card test tables', exact=True).click()
                frame.get_by_label('Card to test').select_option('grotto')
                frame.get_by_role('button', name='Open test table', exact=True).click(); card('Grotto')
                d = pending('Grotto'); shape(d); food, clay = amount('food'), amount('clay')
                d.get_by_role('button', name='2 food → 1 clay', exact=True).click(); snap('grotto'); resolve(d)
                expect(d).to_have_count(0); qty('food', food-2); qty('clay', clay+1)
                restart('Grotto'); pending('Grotto').get_by_role('button', name='Skip this effect', exact=True).click()
                expect(pending('Grotto')).to_have_count(0)

                next_card('Threshing Machine Plow'); d = pending('Threshing Machine Plow'); shape(d, True)
                cell(d, 8); cell(d, 9); snap('threshing-machine-plow'); resolve(d); expect(d).to_have_count(0)
                for n in [7, 8, 9]: expect(frame.locator(f'.tt-player[data-player="0"] [aria-label="Farm space {n}: field"]')).to_have_count(1)

                next_card('Wood Saw'); d = pending('Wood Saw'); shape(d, True); wood, reed = amount('wood'), amount('reed')
                cell(d, 1); expect(d.get_by_label('Room payment', exact=True)).to_be_visible(); snap('wood-saw'); resolve(d)
                expect(d).to_have_count(0); qty('wood', wood-5); qty('reed', reed-2)

                next_card('Convict Number 24601')
                expect(frame.locator('.tt-player[data-player="0"]').get_by_text('1 / 2 workers ready', exact=True)).to_be_visible()
                action('grain'); close_harvest(); snap('convict')

                next_card('Cowboy And Mother'); d = pending('Cowboy And Mother'); shape(d)
                d.get_by_role('button', name='Plow one field', exact=True).click(); resolve(d)
                d = pending('Cowboy And Mother'); shape(d, True); cell(d, 1); snap('cowboy-and-mother'); resolve(d)
                expect(d).to_have_count(0); expect(frame.locator('.tt-player[data-player="0"] [aria-label="Farm space 1: field"]')).to_have_count(1)

                next_card('Good Friend'); d = pending('Good Friend'); shape(d); wood = amount('wood')
                d.get_by_role('button', name='Receive one wood', exact=True).click(); snap('good-friend'); resolve(d)
                expect(d).to_have_count(0); qty('wood', wood+1)

                next_card('Miser'); frame.locator('.action-card[data-action=build]').click(); d = frame.get_by_role('dialog')
                cell(d, 1); shape(d, True)
                payment = d.get_by_role('group', name='Miser room payment', exact=True)
                expect(payment.get_by_role('button', name='Use Miser discount', exact=True)).to_have_attribute('aria-pressed', 'true')
                payment.get_by_role('button', name='Pay the full room price', exact=True).click()
                expect(d.get_by_role('button', name='Place family member →', exact=True)).to_be_disabled()
                payment.get_by_role('button', name='Use Miser discount', exact=True).click(); snap('miser')
                d.get_by_role('button', name='Place family member →', exact=True).click(); expect(d).to_have_count(0); qty('wood', 0); qty('reed', 0)

                next_card('Turkey Breeder'); d = pending('Turkey Breeder'); shape(d, True); wood = amount('wood')
                for edge in ['h:0:0', 'h:1:0', 'v:0:0', 'v:0:1']:
                    svg = d.locator('svg.farm')
                    point = svg.evaluate("(svg,label)=>{const root=svg.getBoundingClientRect(),edge=svg.querySelector('[aria-label=\"'+label+'\"]').getBoundingClientRect();return {x:edge.x-root.x+edge.width/2,y:edge.y-root.y+edge.height/2}}", 'Fence '+edge)
                    svg.click(position=point)
                snap('turkey-fences'); resolve(d)
                d = pending('Turkey Breeder'); shape(d, True); cell(d, 1); snap('turkey-stable'); resolve(d)
                expect(d).to_have_count(0); qty('wood', wood)
                restart('Turkey Breeder'); pending('Turkey Breeder').get_by_role('button', name='Skip this effect', exact=True).click()
                pending('Turkey Breeder').get_by_role('button', name='Skip this effect', exact=True).click(); expect(pending('Turkey Breeder')).to_have_count(0)

                next_card('Wood Gatherers'); wood = amount('wood'); action('grain'); qty('wood', wood+2); snap('wood-gatherers')
                next_card('Workaholic'); d = pending('Workaholic'); shape(d); wood = amount('wood')
                d.get_by_role('button', name='Collect 5 wood', exact=True).click(); snap('workaholic'); resolve(d)
                expect(d).to_have_count(0); qty('wood', wood+5)
                (page.frames[1] if activity else page).evaluate('location.reload()'); card('Workaholic'); qty('wood', wood+5)
                assert not errors, errors
                print(f'PASS ten FR reviews: mobile={mobile}, activity={activity}', flush=True)
            except Exception:
                snap('failure'); Path('/tmp/eurozone-fifteen-fr-failure.html').write_text((page.frames[1] if activity else page).content()); raise
            finally: ctx.close()
        browser.close()

if __name__ == '__main__':
    browser_discord.check = check
    browser_discord.main()
