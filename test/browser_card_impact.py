"""Card impact is visible from saved games; draft artwork retains its printed ratio."""
import os
from playwright.sync_api import sync_playwright,expect
from browser_smoke import main


def check(base,ids):
    game,draft=ids.split(',')
    with sync_playwright() as pw:
        browser=pw.chromium.launch(headless=True,executable_path=os.environ['EUROZONE_BROWSER'])
        context=browser.new_context(viewport={'width':1440,'height':1000})
        context.add_init_script('sessionStorage.setItem("eurozone-session", "'+'a'*64+'")')
        page=context.new_page();errors=[]
        page.on('pageerror',lambda e:errors.append(str(e)))
        page.goto(base+'/#'+game)
        page.get_by_role('button',name='Ada player view',exact=True).click()
        page.locator('[data-owner="0"][data-deck="improvements"]').get_by_role('button',name='Fish Trap',exact=True).click()
        page.get_by_role('dialog').get_by_role('button',name='Fish Trap',exact=True).click()
        history=page.get_by_role('region',name='Card impact history',exact=True)
        expect(history.locator('.impact-earned')).to_have_text('Received: 2 food')
        expect(history.locator('li')).to_have_count(2)
        expect(history).to_contain_text('Fishing')
        expect(history).to_contain_text('Reed Bank')
        page.screenshot(path='/tmp/eurozone-fish-trap-impact.png')
        page.goto(base+'/#'+draft)
        def proportions(selector):
            art=page.locator(selector)
            assert art.count()>0,selector
            sizes=art.evaluate_all('els=>els.map(e=>{let r=e.getBoundingClientRect();return [r.width,r.height]})')
            for width,height in sizes:
                assert width>50 and abs(width/height-337.5/517.57)<.015,(selector,width,height)
        for width in [1440,900,390]:
            page.set_viewport_size({'width':width,'height':1000})
            expect(page.locator('.draw-choices .card-sheet-art').first).to_be_visible()
            proportions('.draw-choices .card-sheet-art')
            proportions('.draw-choices .hand-card>img')
            assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
            page.screenshot(path='/tmp/eurozone-draft-proportions-'+str(width)+'.png',full_page=True)
        page.locator('.draw-choices').get_by_role('button',name='Mason',exact=True).click()
        page.get_by_role('button',name='Select card',exact=True).click()
        page.get_by_role('button',name='Keep card & pass left →',exact=True).click()
        expect(page.locator('.draw-kept .card-sheet-art')).to_have_count(1)
        for width in [1440,900,390]:
            page.set_viewport_size({'width':width,'height':1000})
            proportions('.draw-kept .card-sheet-art')
        page.reload()
        expect(page.locator('.draw-kept .card-sheet-art')).to_have_count(1)
        proportions('.draw-kept .card-sheet-art')
        assert not errors,errors
        browser.close()
        print('Fish Trap totals and round history; printed draft and kept-card proportions at desktop/tablet/mobile widths passed')

if __name__=='__main__': main(check,'test/eurozone/card_impact_browser_fixture.clj')
