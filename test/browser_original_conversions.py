"""Original animal repayment, simultaneous baking/cooking, and Plane decisions."""
import os
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main


def check(base, fixture_ids):
    loan, bake, feed = fixture_ids.split(',')
    with sync_playwright() as pw:
        options = {'headless': True}
        if os.environ.get('EUROZONE_BROWSER'):
            options['executable_path'] = os.environ['EUROZONE_BROWSER']
        browser = pw.chromium.launch(**options)
        context = browser.new_context(viewport={'width':1680, 'height':1050})
        context.add_init_script('sessionStorage.setItem("eurozone-session", "'+'a'*64+'")')
        page = context.new_page()
        errors = []
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.goto(base+'/#'+loan)
        expect(page.get_by_role('button', name='Settle loan', exact=True)).to_be_visible()
        page.reload()
        expect(page.get_by_role('dialog').get_by_role('spinbutton')).to_have_count(0)
        expect(page.get_by_role('dialog').get_by_role('combobox')).to_have_count(0)
        sheep=page.get_by_role('button', name='Return Sheep', exact=True)
        cattle=page.get_by_role('button', name='Return Cattle', exact=True)
        sheep.click();expect(sheep).to_have_attribute('aria-pressed','true')
        cattle.click();expect(cattle).to_have_attribute('aria-pressed','true')
        page.get_by_role('button', name='Settle loan', exact=True).click()
        try:
            expect(page.get_by_role('button', name='Settle loan', exact=True)).to_have_count(0)
        except Exception:
            print('LOAN FAILURE:', page.locator('body').inner_text(), 'JS:', errors, flush=True)
            raise
        expect(page.locator('.tt-player[data-player="0"] [data-goods="sheep"]')).to_have_count(0)
        expect(page.locator('.tt-player[data-player="0"] [data-goods="cattle"]')).to_have_count(0)
        expect(page.locator('.tt-player[data-player="0"] [data-goods="boar"] .pile-count')).to_have_text('1')
        print('PASS: final loan popup persists and submits selected species, keeping an unreturned boar', flush=True)

        page.goto(base+'/#'+bake)
        expect(page.get_by_role('group', name='Baking exchange', exact=True)).to_be_visible()
        page.get_by_role('button', name='Add Wild boar conversion', exact=True).click()
        page.get_by_role('button', name='Bake bread →', exact=True).click()
        expect(page.get_by_role('button', name='Collect cooked markers', exact=True)).to_be_visible()
        page.reload()
        page.get_by_role('button', name='Collect cooked markers', exact=True).click()
        expect(page.get_by_role('dialog')).to_have_count(0)
        expect(page.locator('.tt-player[data-player="0"] [data-goods="food"] .pile-count')).to_have_text('7')
        expect(page.locator('.tt-player[data-player="0"] [data-goods="boar"]')).to_have_count(0)
        print('PASS: joint baking/cooking earns Crock food and saved Brush Maker decision', flush=True)

        page.set_viewport_size({'width':430, 'height':932})
        page.goto(base+'/#'+feed)
        page.get_by_role('checkbox', name='Use Joinery · 1 wood → 2 food', exact=True).click()
        plane = page.get_by_role('checkbox', name='Plane: use 2 wood for 4 food (otherwise 1 wood for 3 food)', exact=True)
        expect(plane).to_be_visible()
        plane.click()
        expect(plane).to_be_checked()
        page.get_by_role('button', name='Feed family →', exact=True).click()
        expect(page.locator('.tt-player[data-player="0"] [data-goods="wood"]')).to_have_count(0)
        assert not errors, errors
        print('PASS: phone-sized feeding popup submits the Plane second-wood choice', flush=True)
        browser.close()


if __name__ == '__main__':
    main(check, 'test/eurozone/original_conversions_browser_fixture.clj')
