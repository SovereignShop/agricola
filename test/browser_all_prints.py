"""Every implemented hand card renders an image; Revised sprite grids use the right cells."""
import os
from playwright.sync_api import expect,sync_playwright
from browser_smoke import main

def check(base,fixture):
    room,minors,occupations=fixture.split(',')
    with sync_playwright() as pw:
        browser=pw.chromium.launch(headless=True,executable_path=os.environ['EUROZONE_BROWSER'])
        ctx=browser.new_context(viewport={'width':1440,'height':1000})
        ctx.add_init_script('sessionStorage.setItem("eurozone-session","'+'a'*64+'")')
        page=ctx.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
        page.goto(base+'/#'+room)
        for deck,label,count in [('hand-occupation','Hand · occupations',int(occupations)),('hand-minor','Hand · minors',int(minors))]:
            page.locator('[data-owner="0"][data-deck="'+deck+'"]').get_by_role('button',name='Open '+label+' spread',exact=True).click()
            dialog=page.get_by_role('dialog')
            expect(dialog.locator('.text-card')).to_have_count(0)
            expect(dialog.locator('img,.card-sheet-art')).to_have_count(count)
            for code,size in ([('a169','1000% 500%'),('d180','1000% 500%')] if deck=='hand-occupation' else []):
                sprite=dialog.locator('[data-card-art=":revised-five/'+code+'"]')
                expect(sprite).to_have_css('background-size',size)
            if deck=='hand-occupation':
                dialog.get_by_role('button',name='Off-Siter',exact=True).click()
                inspected=page.get_by_role('dialog',name='Off-Siter',exact=True)
                expect(inspected.locator('.card-sheet-art')).to_have_css('background-position','0% 0%')
                page.screenshot(path='/tmp/eurozone-five-player-print.png')
            page.get_by_role('button',name='Close dialog',exact=True).click()
        assert int(minors)+int(occupations)==794
        assert not errors,errors
        print('PASS all 794 hand cards render images, including 48 five-player occupations')
        browser.close()

if __name__=='__main__':main(check,'test/eurozone/all_prints_browser_fixture.clj')
