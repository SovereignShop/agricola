"""WizKids White occupation choices against the compiled browser and real server."""
import os
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main


def check(base, fixture_ids):
    stable, puppet, bell, plow = fixture_ids.split(',')
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ['EUROZONE_BROWSER'])
        context = browser.new_context(viewport={'width':1680, 'height':1050})
        context.add_init_script('sessionStorage.setItem("eurozone-session", "'+'a'*64+'")')
        page = context.new_page(); errors = []
        page.on('pageerror', lambda e: errors.append(str(e)))
        def table(room, title):
            print('Checking '+title, flush=True)
            page.goto(base+'/#'+room)
            expect(page.get_by_role('dialog', name='Resolve '+title, exact=True)).to_be_visible()
            page.reload()
        def resolve(): page.get_by_role('button', name='Resolve effect →', exact=True).click()
        def goods(pid, resource, count):
            loc=page.locator('.tt-player[data-player="'+str(pid)+'"] .tt-inventory [data-goods="'+resource+'"] .pile-count')
            expect(loc).to_have_text(str(count)) if count else expect(loc).to_have_count(0)
        table(stable, 'Stable Master')
        page.get_by_role('combobox', name='Farm space', exact=True).select_option('0')
        resolve();expect(page.get_by_role('dialog')).to_have_count(0);goods(0,'wood',0)
        expect(page.locator('.tt-player[data-player="0"] .tt-farm-caption')).to_contain_text('1 / 4 stables')
        table(puppet, 'Puppeteer')
        page.get_by_role('group',name='Playable occupations',exact=True).get_by_role('button',name='Wood Collector',exact=True).click()
        resolve();expect(page.get_by_role('dialog')).to_have_count(0)
        goods(0,'food',0);goods(1,'food',2)
        expect(page.locator('[data-owner="0"][data-deck="occupations"] .tt-card')).to_have_count(2)
        page.set_viewport_size({'width':390, 'height':844})
        table(bell, 'Bellfounder')
        page.get_by_role('combobox',name='Card effect',exact=True).select_option('0')
        resolve();expect(page.get_by_role('dialog')).to_have_count(0);goods(0,'clay',0);goods(0,'food',5)
        assert page.locator('body').evaluate('e=>e.scrollWidth<=innerWidth')
        page.set_viewport_size({'width':1680,'height':1050})
        table(plow, 'Plow Maker')
        checkbox=page.get_by_role('checkbox',name='Plow Maker: pay 1 food for one extra field',exact=True)
        checkbox.click();expect(checkbox).to_be_checked()
        page.get_by_role('combobox',name='Plow field 1',exact=True).select_option('1')
        resolve();expect(page.get_by_role('dialog')).to_have_count(0)
        for i in [1,2]: expect(page.locator('.tt-player[data-player="0"] [aria-label="Farm space '+str(i)+': field"]')).to_have_count(1)
        assert not errors, errors
        print('PASS: saved White Stable Master, Puppeteer transfer, mobile Bellfounder, and Plow Maker workflows',flush=True)
        browser.close()


if __name__=='__main__': main(check,'test/eurozone/wizkids_white_occupations_browser_fixture.clj')
