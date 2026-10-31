"""Original I minor browser choices against the compiled app and isolated saves."""
import os
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main


def check(base, fixture_ids):
    kitchen, plow, corn, tavern, deposit, lasso = fixture_ids.split(',')
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ['EUROZONE_BROWSER'])
        context = browser.new_context(viewport={'width':1680, 'height':1050})
        context.add_init_script('sessionStorage.setItem("eurozone-session", "'+'a'*64+'")')
        page = context.new_page()
        errors = []
        page.on('pageerror', lambda error: errors.append(str(error)))

        def table(room):
            page.goto(base+'/#'+room)
            expect(page.locator('.tt-player')).to_have_count(4)

        def goods(pid, resource, n):
            locator = page.locator('.tt-player[data-player="'+str(pid)+'"] [data-goods="'+resource+'"]')
            if n:
                expect(locator.locator('.pile-count')).to_have_text(str(n))
            else:
                expect(locator).to_have_count(0)

        def confirm():
            page.get_by_role('button', name='Place family member →', exact=True).click()

        def resolve():
            page.get_by_role('button', name='Resolve effect →', exact=True).click()

        table(kitchen)
        page.locator('.action-card[data-action="meeting"]').click()
        page.get_by_role('group', name='Playable minor improvements', exact=True).get_by_role('button', name="Baker's Kitchen", exact=True).click()
        expect(page.get_by_role('group', name='Return improvement', exact=True).get_by_role('button', name='Clay Oven', exact=True)).to_have_attribute('aria-pressed', 'true')
        confirm()
        expect(page.get_by_role('dialog', name="Resolve Baker's Kitchen", exact=True)).to_be_visible()
        page.reload()
        page.get_by_role('spinbutton', name='Grain to bake', exact=True).fill('1')
        resolve()
        expect(page.get_by_role('dialog')).to_have_count(0)
        goods(0, 'stone', 0)
        goods(0, 'food', 7)

        table(plow)
        page.reload()
        page.get_by_role('combobox', name='Plow', exact=True).select_option(':catalog/original-base-i-68-harrow')
        expect(page.get_by_role('combobox', name='Plow field 2', exact=True)).to_have_count(0)
        page.get_by_role('combobox', name='Plow field 1', exact=True).select_option('1')
        resolve()
        expect(page.get_by_role('dialog')).to_have_count(0)
        goods(0, 'food', 0)
        goods(1, 'food', 5)
        for cell in [1, 2]:
            expect(page.locator('.tt-player[data-player="0"] [aria-label="Farm space '+str(cell)+': field"]')).to_have_count(1)

        table(corn)
        expect(page.get_by_role('dialog', name='Resolve Corn Storehouse', exact=True)).to_be_visible()
        page.get_by_role('button', name='Take Sow action', exact=True).click()
        page.get_by_role('combobox', name='Sow field 1', exact=True).select_option('grain')
        resolve()
        expect(page.get_by_role('dialog', name='Resolve Water Mill', exact=True)).to_be_visible()
        page.reload()
        resolve()
        expect(page.get_by_role('dialog')).to_have_count(0)
        goods(0, 'food', 4)
        goods(1, 'food', 4)
        goods(0, 'grain', 0)

        table(tavern)
        page.get_by_role('button', name='Score 2 bonus points', exact=True).click()
        expect(page.locator('.tt-action-preview .tt-card-face')).to_be_visible()
        confirm()
        expect(page.get_by_role('dialog')).to_have_count(0)
        goods(0, 'food', 2)

        table(deposit)
        page.get_by_role('button', name='Take 5 clay', exact=True).click()
        expect(page.locator('.tt-action-preview .tt-card-face')).to_be_visible()
        confirm()
        expect(page.get_by_role('dialog')).to_have_count(0)
        goods(0, 'clay', 5)
        goods(0, 'food', 2)

        table(lasso)
        page.locator('.action-card[data-action="meeting"]').click()
        page.get_by_role('group', name='Playable minor improvements', exact=True).get_by_role('button', name='Lasso', exact=True).click()
        confirm()
        expect(page.get_by_role('dialog', name='Resolve Lasso', exact=True)).to_be_visible()
        page.reload()
        resolve()
        expect(page.get_by_role('dialog')).to_have_count(0)
        page.locator('.action-card[data-action="sheep"]').click()
        confirm()
        expect(page.get_by_role('dialog')).to_have_count(0)
        goods(0, 'sheep', 1)
        assert not errors, errors
        page.screenshot(path='/tmp/eurozone-original-i-minors.png')
        print('PASS: original I Kitchen oven return and baking; paid public Harrow; Corn/Water Mill saved harvest decisions; Tavern/Clay Deposit choices; Lasso immediate second placement', flush=True)
        browser.close()


if __name__ == '__main__':
    main(check, 'test/eurozone/original_i_minors_browser_fixture.clj')
