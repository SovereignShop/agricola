"""Final community cards: native prepared-position reviews in web and desktop/mobile Discord Activity."""
import json, os, re, urllib.parse, subprocess
from pathlib import Path
from playwright.sync_api import expect, sync_playwright
import browser_discord


def check(base):
    recordings=json.loads(subprocess.check_output(['clojure','-Sdeps','{:aliases {:fixtures {:extra-paths ["test"]}}}', '-M:fixtures','test/eurozone/community_final_browser_fixture.clj'],text=True).strip().splitlines()[-1])
    with sync_playwright() as pw:
        browser=pw.chromium.launch(headless=True,executable_path=os.environ['EUROZONE_BROWSER'])
        modes=[(False,False),(False,True),(True,True)]
        for mobile,activity in (modes if not os.getenv('FINAL_MODE') else [modes[int(os.environ['FINAL_MODE'])]]):
            ctx=browser.new_context(viewport={'width':390 if mobile else 1440,'height':844 if mobile else 1000},has_touch=mobile,is_mobile=mobile)
            page=ctx.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
            if activity:
                parent=browser_discord.PARENT.replace('<style>','<meta name="viewport" content="width=device-width, initial-scale=1"><style>').replace('e.source.postMessage','e.source?.postMessage').replace('USER',json.dumps({'id':'111','username':'Ada'}))
                ctx.route(base+'/discord-test-parent',lambda route:route.fulfill(content_type='text/html',body=parent))
                manifest={'games':[{'seed':i+1,'players':2,'mode':'random','extra':0,'policies':['deployed','deployed'], 'file':f'game-{i+1}.edn','verified':True,'status':'complete','moves':recording['moves'],'scores':[0,0],'begging':[0,0],'played':[],'dealt':[],'effects':[]} for i,recording in enumerate(recordings)],'completed':32,'target':32,'finished':True,'worker_failures':[]}
                ctx.route('**/recordings/review-*/index.html*',lambda route:route.fulfill(content_type='text/html',body=(Path(__file__).resolve().parents[1]/'scripts/review_index.html').read_text()))
                ctx.route('**/recordings/review-*/manifest.json',lambda route:route.fulfill(json=manifest))
                ctx.route('**/recordings/review-*/game-*.edn',lambda route:route.fulfill(content_type='application/edn',body=recordings[int(re.search(r'game-(\d+)\.edn',route.request.url).group(1))-1]['text']))
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
                expect(dialog.locator('svg.farm:visible')).to_have_count(1 if farm else 0)
                box=dialog.bounding_box();assert box['x']>=0 and box['x']+box['width']<=(390 if mobile else 1440)+1,box
                assert dialog.evaluate('(el)=>el.scrollWidth<=el.clientWidth+1')
                for board in dialog.locator('.board-picker .original-board').all():
                    bounds=board.bounding_box()
                    assert abs(bounds['width']/bounds['height']-4225/1725)<0.02,bounds
                    if mobile:assert bounds['width']>=1100,bounds
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
                page.screenshot(path=f'/tmp/eurozone-final-{name}-{mobile}-{activity}.png')
                dialog.get_by_role('button',name='Use card ability',exact=True).click()
                qty('food',before_food+food_gain);qty(resource,before_resource-1)
                expect(dialog.get_by_role('button',name='Use card ability',exact=True)).to_be_disabled()
                expect(dialog.get_by_text('0 exchanges remaining this harvest.',exact=True)).to_be_visible()
                close(dialog)
            def pending(name):return frame.get_by_role('dialog',name='Resolve '+name.replace('’', "'"),exact=True)
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
                page.screenshot(path=f'/tmp/eurozone-final-{name}-{mobile}-{activity}.png')
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
                frame.locator('.app').evaluate('() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve)))')
                expect(frame.locator('.app')).to_have_attribute('aria-busy','false')
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
                if activity and not os.getenv('FINAL_START'):
                    frame.get_by_role('button',name='Recorded games',exact=True).click()
                    library=frame.frame_locator('iframe[title="Recorded game collections"]')
                    library.get_by_role('link',name='Browse 1,000 games →',exact=True).click()
                    expect(library.locator('#games tr')).to_have_count(32)
                for index,recording in enumerate([] if os.getenv('FINAL_START') else recordings):
                    if activity:
                        library.locator('#games tr').nth(index).get_by_role('link',name='Watch',exact=True).click()
                    else:
                        frame.get_by_label('Open game recording').set_input_files({'name':recording['id']+'.edn','mimeType':'application/edn','buffer':recording['text'].encode()})
                    expect(frame.locator('.replay-view')).to_be_visible(timeout=30000)
                    expect(frame.locator('.replay-controls')).to_contain_text('Move 0 / '+str(recording['moves']))
                    frame.get_by_role('button',name='Last',exact=True).click()
                    expect(frame.get_by_label('Replay move')).to_have_value(str(recording['moves']))
                    expect(frame.locator('.replay-view .action-card:not([disabled])')).to_have_count(0)
                    frame.get_by_role('button',name='First',exact=True).click()
                    expect(frame.get_by_label('Replay move')).to_have_value('0')
                    frame.get_by_role('button',name='Close playback',exact=True).click()
                if activity and not os.getenv('FINAL_START'):frame.get_by_role('button',name='Back to Eurozone',exact=True).click()
                if not os.getenv('FINAL_START'):print(f"PASS all 32 native recording playbacks: mobile={mobile}, activity={activity}",flush=True)
                details=frame.locator('details').filter(has=frame.get_by_text('Card test tables',exact=True))
                details.get_by_text('Card test tables',exact=True).click()
                names=['Astronomer', 'Food Critic', 'Badger', 'Bathroom', 'Agrarian', 'Card Player', 'Late Bloomer', 'Shared Wall', 'Master Cook', 'Early Riser', 'Almanac', 'Stone Crane', 'Baking Field', 'Kitchen Table', 'Downsizing', 'Expert Builder', 'Musketeer', 'Mailman', 'Resource Lender', 'Ground Pickaxe Plow', 'Winter Garden', 'Permaculturist', 'Irrigation Canal', 'Sunday Worker', 'Debt Collector', 'Remodeler', 'Auctioneer', 'Writing Maniac', 'Unicycle Driver', 'Mooch', 'Village School', 'Witches’ Dance Ground']
                ids=['astronomer', 'food-critic', 'badger', 'bathroom', 'agrarian', 'card-player', 'late-bloomer', 'shared-wall', 'master-cook', 'early-riser', 'almanac', 'stone-crane', 'baking-field', 'kitchen-table', 'downsizing', 'expert-builder', 'musketeer', 'mailman', 'resource-lender', 'ground-pickaxe-plow', 'winter-garden', 'permaculturist', 'irrigation-canal', 'sunday-worker', 'debt-collector', 'remodeler', 'auctioneer', 'writing-maniac', 'unicycle-driver', 'mooch', 'village-school', 'witches-dance-ground']
                start_index=ids.index(os.environ.get('FINAL_START','astronomer'))
                frame.get_by_label('Card to test').select_option(ids[start_index])
                frame.get_by_role('button',name='Open test table',exact=True).click();card(names[start_index])
                if start_index <= 0:
                    play_occupation('Tutor')
                if start_index <= 1:
                    if start_index < 1:next_card('Food Critic')
                    d=pending('Food Critic');shape(d)
                    d.get_by_role('group',name='Store Sheep',exact=True).get_by_role('button',name='+',exact=True).click();resolve(d)
                    restart('Food Critic');skip('Food Critic')
                if start_index <= 2:
                    if start_index < 2:next_card('Badger')
                    d=pending('Badger');shape(d)
                    d.locator('.action-card[data-action=grain]').click();resolve(d)
                    food=amount('food');action('grain');qty('food',food+1)
                    expect(frame.get_by_label('Badger · receive one food',exact=True)).to_be_visible()
                if start_index <= 3:
                    if start_index < 3:next_card('Bathroom')
                    d=pending('Bathroom');shape(d)
                    d.get_by_role('group',name='Store Grain',exact=True).get_by_role('button',name='+',exact=True).click()
                    d.get_by_role('group',name='Store Vegetables',exact=True).get_by_role('button',name='+',exact=True).click()
                    expect(d.get_by_role('group',name='Store Vegetables',exact=True).get_by_role('button',name='+',exact=True)).to_be_disabled()
                    resolve(d)
                    refresh('Bathroom')
                    frame.locator('.action-card[data-action=sow-bake]').click();d=frame.get_by_role('dialog')
                    shape(d,True)
                    d.get_by_role('button',name='Sow grain',exact=True).click()
                    d.get_by_role('button',name='Farm space 1: field',exact=True).click()
                    d.get_by_role('button',name='Sow vegetables',exact=True).click()
                    d.get_by_role('button',name='Farm space 2: field',exact=True).click()
                    d.get_by_role('button',name='Place family member →',exact=True).click()
                    d=pending('Bathroom');shape(d,True)
                    d.get_by_role('button',name='Farm space 1: field',exact=True).click()
                    d.get_by_role('button',name='Farm space 2: field',exact=True).click();screenshot('bathroom');resolve(d)
                    expect(frame.locator('.tt-player[data-player="0"] [aria-label="Farm space 1: field"] image[data-piece-type="grain"]')).to_have_count(4)
                    expect(frame.locator('.tt-player[data-player="0"] [aria-label="Farm space 2: field"] image[data-piece-type="vegetable"]')).to_have_count(3)
                    restart('Bathroom');skip('Bathroom')
                if start_index <= 4:
                    if start_index < 4:next_card('Agrarian')
                    d=pending('Agrarian');shape(d);food=amount('food');resolve(d);qty('food',food-1)
                    restart('Agrarian');skip('Agrarian')
                if start_index <= 5:
                    if start_index < 5:next_card('Card Player')
                    wood=amount('wood');action('wood');qty('wood',wood+4)
                if start_index <= 6:
                    if start_index < 6:next_card('Late Bloomer')
                    d=pending('Late Bloomer');shape(d);resolve(d)
                    restart('Late Bloomer');skip('Late Bloomer')
                if start_index <= 7:
                    if start_index < 7:next_card('Shared Wall')
                    frame.locator('.action-card[data-action=fences]').click();d=frame.get_by_role('dialog');shape(d,True)
                    wood=amount('wood');fence(d,['h:0:0','v:0:0','v:0:1'])
                    d.get_by_role('button',name='Place family member →',exact=True).click();qty('wood',wood-3)
                    refresh('Shared Wall')
                if start_index <= 8:
                    if start_index < 8:next_card('Master Cook')
                    food=amount('food');d=open_played('Master Cook',True);shape(d)
                    d.get_by_role('button',name='Use card ability',exact=True).click();qty('food',food+2)
                    expect(d.get_by_role('button',name='Use card ability',exact=True)).to_be_disabled();close(d)
                    refresh('Master Cook')
                if start_index <= 9:
                    if start_index < 9:next_card('Early Riser')
                    action('grain');d=pending('Early Riser');shape(d)
                    d.get_by_label('Cook newborns that cannot be housed').click();expect(d.get_by_label('Cook newborns that cannot be housed')).to_be_checked();resolve(d);harvest()
                if start_index <= 10:
                    if start_index < 10:next_card('Almanac')
                    play_minor('Almanac');d=pending('Almanac');shape(d);resolve(d)
                if start_index <= 11:
                    if start_index < 11:next_card('Stone Crane')
                    d=pending('Stone Crane');shape(d,True)
                    click_cell(d,1);d.get_by_role('group',name='Room discount · 1',exact=True).get_by_role('button',name='Reed',exact=True).click();resolve(d)
                    restart('Stone Crane');skip('Stone Crane')
                if start_index <= 12:
                    if start_index < 12:next_card('Baking Field')
                    food=amount('food');action('grain');harvest();qty('food',food+2)
                if start_index <= 13:
                    if start_index < 13:next_card('Kitchen Table')
                    frame.locator('.action-card[data-action=sow-bake]').click();d=frame.get_by_role('dialog')
                    d.get_by_role('button',name='Store one food on Kitchen Table',exact=True).click()
                    d.get_by_role('button',name='Place family member →',exact=True).click()
                    d=open_played('Kitchen Table');d.get_by_role('button',name='Use card ability',exact=True).click()
                    if d.count():close(d)
                    d=pending('Kitchen Table')
                    if not d.count():frame.get_by_role('button',name='Go to my decision',exact=True).click();d=pending('Kitchen Table')
                    d.get_by_role('button',name='Bake bread →',exact=True).click();refresh('Kitchen Table')
                if start_index <= 14:
                    if start_index < 14:next_card('Downsizing')
                    d=pending('Downsizing');shape(d,True);click_cell(d,1,'room')
                    d.get_by_role('button',name='Resolve effect →',exact=True).click()
                    expect(d.get_by_role('button',name='Fence h:0:0',exact=True)).to_have_count(1)
                    fence(d,['h:0:0','h:1:0','v:0:0']);resolve(d)
                    restart('Downsizing');skip('Downsizing')
                if start_index <= 15:
                    if start_index < 15:next_card('Expert Builder')
                    d=pending('Expert Builder');shape(d)
                    d.get_by_role('group',name='Paid building resource',exact=True).get_by_role('button',name='Reed',exact=True).click();resolve(d)
                if start_index <= 16:
                    if start_index < 16:next_card('Musketeer')
                    d=pending('Musketeer');shape(d)
                    d.get_by_role('button',name='Musketeer intersection 3, 3',exact=True).click();screenshot('musketeer');resolve(d)
                if start_index <= 17:
                    if start_index < 17:next_card('Mailman')
                    for space in ['grain','wood','build','plow']:
                        d=pending('Mailman');shape(d)
                        d.locator('.action-card[data-action='+space+']').click()
                        revision=frame.locator('.game').get_attribute('data-revision')
                        d.get_by_role('button',name='Resolve effect →',exact=True).click()
                        expect(frame.locator('.game')).not_to_have_attribute('data-revision',revision)
                    action('grain');d=pending('Mailman');shape(d);resolve(d);refresh('Mailman')
                if start_index <= 18:
                    if start_index < 18:next_card('Resource Lender')
                    d=pending('Resource Lender');shape(d)
                    d.get_by_role('group',name='Extra resource',exact=True).get_by_role('button',name='Wood',exact=True).click()
                    for _ in range(4):d.get_by_role('button',name='Schedule Wood',exact=True).click()
                    expect(d.get_by_role('button',name='Schedule Wood',exact=True)).to_be_disabled();resolve(d)
                    restart('Resource Lender');skip('Resource Lender')
                if start_index <= 19:
                    if start_index < 19:next_card('Ground Pickaxe Plow')
                    d=pending('Ground Pickaxe Plow');shape(d)
                    d.locator('.action-card[data-action=grain]').click();d.locator('.action-card[data-action=clay]').click()
                    expect(d.locator('.action-card[aria-pressed=true]')).to_have_count(2)
                    d.get_by_role('button',name='Resolve effect →',exact=True).click();shape(d,True)
                    click_cell(d,2);click_cell(d,3);screenshot('pickaxe');resolve(d)
                    restart('Ground Pickaxe Plow');skip('Ground Pickaxe Plow')
                if start_index <= 20:
                    if start_index < 20:next_card('Winter Garden')
                    frame.locator('.action-card[data-action=sow-bake]').click();d=frame.get_by_role('dialog');shape(d,True)
                    d.get_by_role('button',name='Sow grain',exact=True).click();click_cell(d,6,'room')
                    d.get_by_role('button',name='Place family member →',exact=True).click();expect(d).to_have_count(0);refresh('Winter Garden')
                    expect(frame.locator('.tt-player[data-player="0"] [aria-label="Farm space 6: room"] image[data-piece-type="grain"]')).to_have_count(3)
                if start_index <= 21:
                    if start_index < 21:next_card('Permaculturist')
                    frame.locator('.action-card[data-action=sow-bake]').click();d=frame.get_by_role('dialog');shape(d,True)
                    d.get_by_role('button',name='Sow grain',exact=True).click();click_cell(d,1);click_cell(d,15)
                    d.get_by_role('button',name='Place family member →',exact=True).click()
                    d=open_played('Permaculturist',True);shape(d,True);click_cell(d,15,'field')
                    d.get_by_role('button',name='Use card ability',exact=True).click();close(d);refresh('Permaculturist')
                    expect(frame.locator('.tt-player[data-player="0"] [aria-label="Farm space 15: empty"]')).to_have_count(1)
                if start_index <= 22:
                    if start_index < 22:next_card('Irrigation Canal')
                    d=pending('Irrigation Canal');shape(d,True)
                    d.get_by_role('button',name='Farm space 2: field',exact=True).click()
                    d.get_by_role('button',name='Farm space 1: field',exact=True).click();screenshot('irrigation');resolve(d)
                    expect(frame.locator('.tt-player[data-player="0"] [aria-label="Farm space 2: field"] image[data-piece-type="grain"]')).to_have_count(2)
                    restart('Irrigation Canal');skip('Irrigation Canal')
                if start_index <= 23:
                    if start_index < 23:next_card('Sunday Worker')
                    d=pending('Sunday Worker');shape(d);resolve(d)
                    restart('Sunday Worker');skip('Sunday Worker')
                if start_index <= 24:
                    if start_index < 24:next_card('Debt Collector')
                    d=pending('Debt Collector');shape(d)
                    d.get_by_role('button',name='Guest in round 4',exact=True).click();resolve(d)
                if start_index <= 25:
                    if start_index < 25:next_card('Remodeler')
                    d=pending('Remodeler');shape(d,True)
                    d.get_by_role('button',name='Farm space 1: empty',exact=True).click()
                    expect(d.get_by_role('button',name='Build free room',exact=True)).to_have_attribute('aria-pressed','true')
                    click_cell(d,1);screenshot('remodeler');resolve(d)
                    restart('Remodeler');skip('Remodeler')
                if start_index <= 26:
                    if start_index < 26:next_card('Auctioneer')
                    for bid in [3,4,4,0]:
                        d=pending('Auctioneer');shape(d)
                        for _ in range(bid):d.get_by_role('group',name='Sealed food bid',exact=True).get_by_role('button',name='+',exact=True).click()
                        revision=frame.locator('.game').get_attribute('data-revision')
                        d.get_by_role('button',name='Resolve effect →',exact=True).click()
                        expect(frame.locator('.game')).not_to_have_attribute('data-revision',revision)
                    expect(pending('Auctioneer')).to_have_count(0);refresh('Auctioneer')
                if start_index <= 27:
                    if start_index < 27:next_card('Writing Maniac')
                    for round_no,name in [(5,'Tutor'),(7,'Woodcutter'),(9,'Clay Deliveryman'),(11,'Architect')]:
                        d=pending('Writing Maniac');shape(d)
                        d.get_by_role('group',name='Occupation to schedule',exact=True).get_by_role('button',name=name,exact=True).click()
                        preview=frame.get_by_role('dialog',name=name,exact=True);preview.get_by_role('button',name='Select card',exact=True).click()
                        d.get_by_role('button',name='Occupation in round '+str(round_no),exact=True).click()
                        revision=frame.locator('.game').get_attribute('data-revision')
                        d.get_by_role('button',name='Resolve effect →',exact=True).click()
                        expect(frame.locator('.game')).not_to_have_attribute('data-revision',revision)
                    refresh('Writing Maniac')
                    for space in ['grain','wood','clay','reed','fishing','labor']:
                        revision=frame.locator('.game').get_attribute('data-revision')
                        action(space);expect(frame.locator('.game')).not_to_have_attribute('data-revision',revision)
                    for _ in range(3):
                        revision=frame.locator('.game').get_attribute('data-revision')
                        feed();expect(frame.locator('.game')).not_to_have_attribute('data-revision',revision)
                    for _ in range(3):
                        revision=frame.locator('.game').get_attribute('data-revision')
                        frame.get_by_role('button',name='Complete breeding →',exact=True).click()
                        expect(frame.locator('.game')).not_to_have_attribute('data-revision',revision)
                    d=pending('Writing Maniac');shape(d)
                    expect(d.get_by_role('img',name='Tutor · English card print',exact=True)).to_be_visible()
                    d.get_by_role('button',name='Pay two food and play',exact=True).click();screenshot('writing-arrival');resolve(d)
                    expect(frame.locator('[data-owner="0"][data-deck="occupations"]').get_by_role('button',name='Tutor',exact=True)).to_have_count(1)
                if start_index <= 28:
                    if start_index < 28:next_card('Unicycle Driver')
                    d=pending('Unicycle Driver');shape(d)
                    d.locator('.action-card[data-action=grain]').click();d.locator('.action-card[data-action=plow]').click();screenshot('road');resolve(d)
                    action('grain');d=frame.get_by_role('dialog');shape(d)
                    d.locator('.action-card[data-action=grain]').click()
                    revision=frame.locator('.game').get_attribute('data-revision')
                    d.get_by_role('button',name='Resolve effect →',exact=True).click()
                    expect(frame.locator('.game')).not_to_have_attribute('data-revision',revision)
                    d=frame.get_by_role('dialog');d.locator('.action-card[data-action=plow]').click();click_cell(d,1)
                    d.get_by_role('button',name='Place family member →',exact=True).click()
                    expect(frame.locator('.tt-player[data-player="0"] [aria-label="Farm space 1: field"]')).to_have_count(1)
                    restart('Unicycle Driver');skip('Unicycle Driver')
                if start_index <= 29:
                    if start_index < 29:next_card('Mooch')
                    d=pending('Mooch');shape(d)
                    d.get_by_role('button',name='Partner',exact=True).click();resolve(d)
                    frame.locator('.action-card[data-action=sow-bake]').click();d=frame.get_by_role('dialog');shape(d,True)
                    food=amount('food');d.get_by_role('button',name='Add grain to bake',exact=True).click()
                    d.get_by_role('button',name='Place family member →',exact=True).click();qty('food',food+5)
                if start_index <= 30:
                    if start_index < 30:next_card('Village School')
                    frame.locator('.action-card[data-action=build]').click();d=frame.get_by_role('dialog');shape(d,True)
                    click_cell(d,1);d.locator('summary',has_text='Village School').click()
                    d.get_by_role('group',name='Card to discard for Village School',exact=True).get_by_role('button',name='Woodcutter',exact=True).click()
                    preview=frame.get_by_role('dialog',name='Woodcutter',exact=True);preview.get_by_role('button',name='Select card',exact=True).click()
                    d.get_by_role('group',name='Village School discount',exact=True).get_by_role('button',name='Wood',exact=True).click();screenshot('village-school')
                    d.get_by_role('button',name='Place family member →',exact=True).click();expect(d).to_have_count(0);refresh('Village School')
                    expect(frame.locator('.tt-player[data-player="0"] [aria-label="Farm space 1: room"]')).to_have_count(1)
                if start_index <= 31:
                    if start_index < 31:next_card('Witches’ Dance Ground')
                    d=pending('Witches’ Dance Ground');shape(d,True)
                    d.get_by_role('button',name='Cover two unused spaces',exact=True).click();click_cell(d,1);click_cell(d,2);screenshot('witches-cover');resolve(d)
                    expect(frame.locator('.tt-player[data-player="0"] [aria-label="Farm space 1: removed"]')).to_have_count(1)
                    restart('Witches’ Dance Ground');d=pending('Witches’ Dance Ground')
                    # The farm geometry gives each candidate a stable coordinate-derived identity.
                    candidates=d.locator('svg.farm [role="button"][aria-label$=": candidate"]:not([aria-disabled=true])')
                    first=candidates.first;label=first.get_attribute('aria-label');first.click()
                    expect(d.get_by_role('button',name=label,exact=True)).to_have_attribute('aria-pressed','true')
                    candidates.locator('xpath=self::*[@aria-pressed="false"]').first.click()
                    expect(d.locator('svg.farm [aria-pressed=true]')).to_have_count(2)
                    screenshot('witches-extend');resolve(d);refresh('Witches’ Dance Ground')
                    expect(frame.locator('.tt-player[data-player="0"] svg.farm > g[aria-label^="Farm space"]')).to_have_count(17)
                assert not errors,errors
                print(f'PASS {32-start_index} final-card reviews; mobile={mobile}, activity={activity}',flush=True)
            except Exception:
                print("BROWSER ERRORS",errors,flush=True)
                screenshot('failure')
                Path('/tmp/eurozone-final-failure.html').write_text((page.frames[1] if activity else page).content())
                raise
            finally:ctx.close()
        browser.close()

if __name__=='__main__':
    browser_discord.check=check
    browser_discord.main()
