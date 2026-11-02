"""Shuffled owner permissions, live starting-player tabs and saved impact cleanup."""
import os
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main


def check(base, ids):
    online, seller, five = ids.split(',')
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ['EUROZONE_BROWSER'])
        pages=[]; errors=[]
        for token in ['a','b']:
            context=browser.new_context(viewport={'width':1440,'height':1000})
            context.add_init_script('sessionStorage.setItem("eurozone-session", "'+token*64+'")')
            page=context.new_page(); page.on('pageerror',lambda e:errors.append(str(e)))
            page.goto(base+'/#'+online); pages.append(page)
        host,guest=pages
        for page,seat in [(host,'1'),(guest,'0')]:
            tabs=page.get_by_role('navigation',name='Table views')
            expect(tabs.get_by_role('button')).to_have_text(['Table view','Board view','Ben','Ada'])
            expect(tabs.locator('[data-starting-player="true"]')).to_have_count(1)
            expect(tabs.get_by_role('button',name='Ada player view',exact=True).get_by_role('img',name='Starting player')).to_have_attribute('src','/art/agricola/starting-player.svg')
            expect(page.locator('.tt-player').first).to_have_attribute('data-player',seat)
            positions=page.locator('.tt-player').evaluate_all('(els)=>els.map(e=>({id:e.dataset.player,x:parseFloat(e.style.left),y:parseFloat(e.style.top)}))')
            own=positions[0]
            assert own['y']==min(p['y'] for p in positions)
            assert own['x']==min(p['x'] for p in positions if p['y']==own['y'])
            expect(page.locator('[data-deck="hand-occupation"]')).to_have_count(1)
            expect(page.locator('[data-deck="hand-occupation"]')).to_have_attribute('data-owner',seat)
        for page in pages:
            token=page.locator('.first-player-marker')
            expect(token).to_be_visible()
            assert token.evaluate('(img)=>img.complete && img.naturalWidth>0')
        host.locator('.action-card[data-action="grain"]').click()
        host.get_by_role('button',name='Place family member →',exact=True).click()
        expect(host.get_by_role('dialog')).to_have_count(0)
        guest.locator('.action-card[data-action="meeting"]').click()
        guest.get_by_role('button',name='Place family member →',exact=True).click()
        expect(guest.get_by_role('dialog')).to_have_count(0)
        for page in pages:
            tabs=page.get_by_role('navigation',name='Table views')
            expect(tabs.get_by_role('button',name='Ben player view',exact=True)).to_have_attribute('data-starting-player','true')
            expect(tabs.get_by_role('button',name='Ada player view',exact=True)).to_have_attribute('data-starting-player','false')
            expect(tabs.locator('.first-player-marker')).to_have_count(1)
            page.reload()
            expect(tabs.get_by_role('button',name='Ben player view',exact=True).locator('.first-player-marker')).to_have_attribute('src','/art/agricola/starting-player.svg')
        guest.set_viewport_size({'width':390,'height':844})
        expect(guest.locator('.first-player-marker')).to_be_in_viewport()
        guest.screenshot(path='/tmp/eurozone-first-player-mobile.png')
        host.goto(base+'/#'+seller)
        host.locator('[data-deck="occupations"][data-owner="0"]').get_by_role('button',name='Open Played · occupations spread',exact=True).click()
        host.get_by_role('dialog').get_by_role('button',name='Resource Seller',exact=True).click()
        history=host.get_by_role('region',name='Card impact history',exact=True)
        expect(history.locator('.impact-earned')).to_have_text('Received: 1 wood')
        expect(history.locator('li')).to_have_count(1)
        expect(history).not_to_contain_text('Effect resolved')
        host.screenshot(path='/tmp/eurozone-resource-seller-impact.png')
        for page,seat in [(host,'4'),(guest,'3')]:
            page.goto(base+'/#'+five)
            expect(page.locator('.tt-player')).to_have_count(5)
            expect(page.locator('.tt-player').first).to_have_attribute('data-player',seat)
            expect(page.get_by_role('navigation',name='Table views').get_by_role('button')).to_have_count(7)
            ids=page.locator('.tt-player').evaluate_all('(els)=>els.map(e=>Number(e.dataset.player))')
            assert ids==[(int(seat)+i)%5 for i in range(5)],ids
            positions=page.locator('.tt-player').evaluate_all('(els)=>els.map(e=>({x:parseFloat(e.style.left),y:parseFloat(e.style.top)}))')
            assert positions[0]['y']==min(p['y'] for p in positions)
            assert positions[0]['x']<positions[1]['x']
            name='Ada' if seat=='4' else 'Ben'
            page.get_by_role('button',name=name+' player view',exact=True).click()
            expect(page.locator('.tt-game')).to_have_attribute('data-view','player-'+seat)
            expect(page.locator('.tt-player[data-player="'+seat+'"] .tt-farm')).to_be_in_viewport(ratio=0.8,timeout=10000)
        assert not errors,errors
        browser.close()
        print('PASS: randomized seat ownership/private hands, live 1st marker, reconnect/mobile and Resource Seller history cleanup')

if __name__=='__main__': main(check,'test/eurozone/seating_browser_fixture.clj')
