"""Opening prepared card tables from the ordinary browser landing page."""
import os
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main


def check(base, _):
    with sync_playwright() as pw:
        browser=pw.chromium.launch(headless=True, executable_path=os.environ['EUROZONE_BROWSER'])
        page=browser.new_page(viewport={'width':1440,'height':1000})
        errors=[]
        page.on('pageerror',lambda e:errors.append(str(e)))
        page.goto(base)
        page.get_by_text('Card test tables',exact=True).click()
        page.get_by_role('button',name='Open test table',exact=True).click()
        dialog=page.get_by_role('dialog',name='Resolve Swing Plow',exact=True)
        expect(dialog).to_be_visible()
        original=page.url
        page.reload()
        expect(dialog).to_be_visible()
        farm=dialog.get_by_role('group',name="Tester's farm",exact=True)
        farm.get_by_role('button',name='Farm space 2: empty',exact=True).click()
        dialog.get_by_role('button',name='Resolve effect →',exact=True).click()
        expect(dialog).to_have_count(0)
        expect(page.locator('.tt-player[data-player="0"] [aria-label$=": field"]')).to_have_count(2)
        page.get_by_role('button',name='← Tables',exact=True).click()
        page.get_by_text('Card test tables',exact=True).click()
        page.get_by_role('button',name='Open test table',exact=True).click()
        expect(dialog).to_be_visible()
        assert page.url!=original
        page.goto(original)
        expect(page.locator('.tt-player[data-player="0"] [aria-label$=": field"]')).to_have_count(2)
        assert not errors,errors
        browser.close()
        print('PASS: landing-page test table creation, persistence, independent fresh copies and existing table preservation')


if __name__=='__main__':main(check,'test/eurozone/plow_click_browser_fixture.clj')
