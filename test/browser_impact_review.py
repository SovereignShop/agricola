"""Impact filters must match the same card/player, and never invent data for old logs."""
import json, os
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
ROOT=Path(__file__).resolve().parents[1]

def game(seed, impacts=None):
    g=dict(seed=seed, players=2, mode='random', extra=0, policies=['deployed']*2,
           file=f'game-{seed}.edn', verified=True, status='complete', moves=100,
           scores=[30,25], begging=[0,0], played=[dict(id='trap',name='Fish Trap'),dict(id='hoe',name='Garden Hoe')],dealt=[],effects=[])
    if impacts is not None:g['card-impact']=impacts
    return g

def card(cid,name,reason,player=0):
    return {'id':cid,'name':name,'reason':reason,'player':player,'zero-impact':reason!='measured',
            'played-round':4,'first-move':25,'effects':0,'declined':0,'unmeasured':[]}

games=[game(1,[card('trap','Fish Trap','measured'),card('hoe','Garden Hoe','no-trigger')]),
       game(2,[card('trap','Fish Trap','no-trigger',1)]),game(3),
       game(4,[card('trap','Fish Trap','declined')])]
with sync_playwright() as pw:
    browser=pw.chromium.launch(headless=True,executable_path=os.environ['EUROZONE_BROWSER'])
    page=browser.new_page(viewport={'width':1440,'height':1000});errors=[]
    page.on('pageerror',lambda e:errors.append(str(e)))
    page.route('http://review.test/index.html*',lambda r:r.fulfill(content_type='text/html',body=(ROOT/'scripts/review_index.html').read_text()))
    page.route('http://review.test/manifest.json',lambda r:r.fulfill(json={'games':games,'completed':4,'target':4,'finished':True,'worker_failures':[]}))
    page.goto('http://review.test/index.html?embedded=1')
    expect(page.locator('#games tr')).to_have_count(4)
    page.locator('#impact').select_option('zero')
    expect(page.locator('#games tr')).to_have_count(3)
    page.locator('#search').fill('Fish Trap')
    expect(page.locator('#games tr')).to_have_count(2)
    expect(page.locator('#games')).to_contain_text('Player 2')
    page.locator('#impact').select_option('no-trigger')
    expect(page.locator('#games tr')).to_have_count(1)
    expect(page.locator('#games tr').first.locator('td').first).to_contain_text('2')
    page.locator('#search').fill('Garden Hoe')
    expect(page.locator('#games tr')).to_have_count(1)
    page.locator('#players').select_option('3')
    expect(page.locator('#games tr')).to_have_count(0)
    page.locator('#players').select_option('')
    page.screenshot(path='/tmp/eurozone-impact-review.png')
    page.set_viewport_size({'width':390,'height':844})
    assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
    assert not errors,errors
    browser.close()
print('Per-card/player zero-impact search, missing measurements, combined filters and mobile passed')
