"""Normal browser observers enter from a table list or invitation, then may take a seat."""
import os
from playwright.sync_api import expect,sync_playwright
from browser_smoke import main,create_table


def check(base,_):
    with sync_playwright() as pw:
        browser=pw.chromium.launch(headless=True,executable_path=os.environ['EUROZONE_BROWSER'])
        host=browser.new_context().new_page();watch=browser.new_context().new_page()
        errors=[]
        for page in [host,watch]:page.on('pageerror',lambda e:errors.append(str(e)))
        host.goto(base);create_table(host,'Ada','Observe this farm',online=True)
        link=host.url
        watch.goto(base)
        row=watch.locator('.table-list-row').filter(has=watch.get_by_text('Observe this farm',exact=True))
        row.get_by_role('button',name='Join as observer',exact=True).click()
        expect(watch.locator('.observer-status')).to_have_text('Observing')
        expect(host.locator('.seats .seat')).to_have_count(1)
        expect(watch.get_by_role('button',name='Take a seat',exact=True)).to_have_count(0)
        watch.reload()
        expect(watch.get_by_role('button',name='Join as a player',exact=True)).to_be_visible()
        watch.get_by_role('button',name='Join as a player',exact=True).click()
        watch.get_by_placeholder('Your name').fill('Ben')
        watch.get_by_role('button',name='Take a seat',exact=True).click()
        expect(watch.locator('.observer-status')).to_have_count(0)
        expect(host.locator('.seats .seat')).to_have_count(2)
        third=browser.new_context().new_page();third.on('pageerror',lambda e:errors.append(str(e)))
        third.goto(link)
        third.get_by_role('button',name='Join as observer',exact=True).click()
        host.get_by_role('button',name='Start the game →',exact=True).click()
        expect(third.locator('.tt-player')).to_have_count(2)
        expect(third.locator('.observer-status')).to_have_text('Observing')
        expect(third.locator('.action-card:not([disabled])')).to_have_count(0)
        third.reload()
        expect(third.locator('.tt-player')).to_have_count(2)
        assert not errors,errors
        browser.close()
        print('PASS: browser table-list and invitation observers, full table, reload, optional seat join before start')


if __name__=='__main__':main(check)
