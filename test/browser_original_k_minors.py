"""Original K minor decisions, saved state, printed return and mixed roof payments."""
import os
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main


def check(base, fixture_ids):
    greenhouse, ox, pelts, build, hearth = fixture_ids.split(',')
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

        def goods(resource, n):
            locator = page.locator('.tt-player[data-player="0"] [data-goods="'+resource+'"]')
            if n:
                expect(locator.locator('.pile-count')).to_have_text(str(n))
            else:
                expect(locator).to_have_count(0)

        def resolve():
            page.get_by_role('button', name='Resolve effect →', exact=True).click()

        table(greenhouse)
        expect(page.get_by_role('dialog', name='Resolve Greenhouse', exact=True)).to_be_visible()
        page.reload()
        resolve()
        expect(page.get_by_role('dialog')).to_have_count(0)
        goods('food', 0)
        goods('vegetable', 1)

        table(ox)
        expect(page.get_by_role('dialog', name='Resolve Ox Team', exact=True)).to_be_visible()
        page.reload()
        for i, cell in enumerate([0,1,2], 1):
            page.get_by_role('combobox', name='Plow field '+str(i), exact=True).select_option(str(cell))
        resolve()
        expect(page.get_by_role('dialog')).to_have_count(0)
        for cell in [1,2,3]:
            expect(page.locator('.tt-player[data-player="0"] [aria-label="Farm space '+str(cell)+': field"]')).to_have_count(1)

        table(pelts)
        expect(page.get_by_role('dialog', name='Resolve Pelts', exact=True)).to_be_visible()
        page.reload()
        page.get_by_role('spinbutton', name='Food to store as pelts', exact=True).fill('2')
        resolve()
        expect(page.get_by_role('dialog')).to_have_count(0)
        goods('food', 2)

        page.set_viewport_size({'width':430, 'height':932})
        table(build)
        page.get_by_label('Build room 1', exact=True).click()
        expect(page.get_by_label('Build room 1', exact=True)).to_be_checked()
        page.get_by_role('combobox', name='Brushwood Roof: reed replaced with wood', exact=True).select_option('1')
        page.get_by_role('combobox', name='Clay Roof: reed replaced with clay', exact=True).select_option('1')
        page.screenshot(path='/tmp/eurozone-original-k-roof-mobile.png')
        resolve()
        expect(page.get_by_role('dialog')).to_have_count(0)
        goods('wood', 0)
        goods('clay', 0)
        expect(page.locator('.tt-player[data-player="0"] [aria-label="Farm space 1: room"]')).to_have_count(1)

        page.set_viewport_size({'width':1680, 'height':1050})
        table(hearth)
        page.locator('.action-card[data-action="meeting"]').click()
        page.get_by_role('group', name='Playable minor improvements', exact=True).get_by_role('button', name='Cooking Hearth', exact=True).click()
        expect(page.get_by_role('group', name='Return improvement', exact=True).get_by_role('button', name='Fireplace', exact=True)).to_have_attribute('aria-pressed', 'true')
        page.get_by_role('button', name='Place family member →', exact=True).click()
        expect(page.get_by_role('dialog')).to_have_count(0)
        assert not errors, errors
        print('PASS: K Greenhouse paid arrival, Ox Team three-field decision, Pelts saved allocation, phone mixed roof payment, original Hearth return', flush=True)
        browser.close()


if __name__ == '__main__':
    main(check, 'test/eurozone/original_k_minors_browser_fixture.clj')
