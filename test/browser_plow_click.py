"""Native pending plow decisions can be completed with farm clicks and taps."""
import os
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main


def check(base, ids):
    swing,wheel=ids.split(',')
    with sync_playwright() as pw:
        browser=pw.chromium.launch(headless=True,executable_path=os.environ['EUROZONE_BROWSER'])
        context=browser.new_context(viewport={'width':1440,'height':1000},has_touch=True)
        context.add_init_script('sessionStorage.setItem("eurozone-session", "'+'a'*64+'")')
        page=context.new_page();errors=[]
        page.on('pageerror',lambda e:errors.append(str(e)))
        page.goto(base+'/#'+swing)
        expect(page.get_by_role('dialog',name='Resolve Swing Plow',exact=True)).to_be_visible()
        page.reload()
        dialog=page.get_by_role('dialog',name='Resolve Swing Plow',exact=True)
        farm=dialog.get_by_role('group',name="Ada's farm",exact=True)
        cell=lambda i: farm.get_by_role('button',name='Farm space '+str(i)+': empty',exact=True)
        confirm=dialog.get_by_role('button',name='Resolve effect →',exact=True)
        expect(confirm).to_be_disabled()
        expect(cell(1)).to_have_attribute('aria-disabled','true')
        expect(cell(15)).to_have_attribute('aria-disabled','true')
        expect(farm.get_by_role('button',name='Farm space 6: room',exact=True)).to_have_attribute('aria-disabled','true')
        cell(2).click(); cell(3).click()
        expect(cell(2)).to_have_attribute('aria-pressed','true')
        expect(cell(3)).to_have_attribute('aria-pressed','true')
        expect(cell(4)).to_have_attribute('aria-disabled','true')
        cell(2).click()
        expect(cell(2)).to_have_attribute('aria-pressed','false')
        expect(cell(3)).to_have_attribute('aria-pressed','false')
        expect(confirm).to_be_disabled()
        cell(2).click(); cell(3).click()
        expect(confirm).to_be_enabled()
        page.screenshot(path='/tmp/eurozone-swing-plow-click.png')
        confirm.click()
        expect(page.get_by_role('dialog')).to_have_count(0)
        for i in [1,2,3]:
            expect(page.locator('.tt-player[data-player="0"] [aria-label="Farm space '+str(i)+': field"]')).to_have_count(1)
        page.set_viewport_size({'width':390,'height':844})
        page.goto(base+'/#'+wheel)
        dialog=page.get_by_role('dialog',name='Resolve Wheel Plow',exact=True)
        farm=dialog.get_by_role('group',name="Ada's farm",exact=True)
        farm.get_by_role('button',name='Farm space 1: empty',exact=True).tap()
        farm.get_by_role('button',name='Farm space 2: empty',exact=True).tap()
        expect(farm.get_by_role('button',name='Farm space 2: empty',exact=True)).to_have_attribute('aria-pressed','true')
        dialog.get_by_role('button',name='Clear selected fields',exact=True).tap()
        expect(dialog.get_by_role('button',name='Resolve effect →',exact=True)).to_be_disabled()
        farm.get_by_role('button',name='Farm space 1: empty',exact=True).tap()
        farm.get_by_role('button',name='Farm space 2: empty',exact=True).tap()
        dialog.get_by_role('button',name='Resolve effect →',exact=True).tap()
        expect(page.get_by_role('dialog')).to_have_count(0)
        for i in [1,2]:
            expect(page.locator('.tt-player[data-player="0"] [aria-label="Farm space '+str(i)+': field"]')).to_have_count(1)
        assert not errors,errors
        browser.close()
        print('PASS: Swing Plow and immediate multi-field plows by click/tap, limits, adjacency, undo/clear and saved decisions')

if __name__=='__main__':main(check,'test/eurozone/plow_click_browser_fixture.clj')
