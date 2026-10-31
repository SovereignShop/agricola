"""Original I occupation choices through the real browser and saved server state."""
import os
import re
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main


def check(base, fixture_ids):
    pig, fence, cabinet, taster = fixture_ids.split(',')
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
        page.goto(base+'/#'+pig)
        page.locator('.action-card[data-action="wood"]').click()
        choice = page.get_by_role('checkbox', name='Pig Catcher: leave 2 wood on this space and take 1 wild boar', exact=True)
        choice.click()
        expect(choice).to_be_checked()
        page.get_by_role('button', name='Place family member →', exact=True).click()
        expect(page.get_by_role('dialog')).to_have_count(0)
        expect(page.locator('.tt-player[data-player="0"] [data-goods="boar"] .pile-count')).to_have_text('1')
        expect(page.locator('.tt-player[data-player="0"] [data-goods="wood"]')).to_have_count(0)
        expect(page.locator('.tt-player[data-player="0"] [data-goods="food"] .pile-count')).to_have_text('3')
        print('PASS: Pig Catcher leaves actual pile wood and triggers Berry Picker', flush=True)

        page.goto(base+'/#'+fence)
        page.get_by_role('combobox', name='Action space for Fence Builder', exact=True).select_option(':wood')
        page.get_by_role('button', name='Resolve effect →', exact=True).click()
        expect(page.get_by_role('dialog')).to_have_count(0)
        page.reload()
        expect(page.get_by_label("Ada's Fence Builder marker", exact=True)).to_have_count(1)
        page.locator('.action-card[data-action="wood"]').click()
        page.get_by_role('button', name='Place family member →', exact=True).click()
        for edge in ['h:0:0', 'h:1:0', 'v:0:0', 'v:0:1']:
            page.locator('.card-decision').get_by_label('Fence '+edge, exact=True).click(force=True)
        resolve = page.get_by_role('button', name='Resolve effect →', exact=True)
        expect(resolve).to_be_enabled()
        resolve.click()
        expect(page.get_by_role('dialog')).to_have_count(0)
        expect(page.locator('.tt-player[data-player="0"] [aria-label^="Fence "][aria-label$=", built"]')).to_have_count(4)
        print('PASS: Fence Builder marker survives reload and grants a real fencing popup', flush=True)

        page.set_viewport_size({'width':430, 'height':932})
        page.goto(base+'/#'+cabinet)
        expect(page.get_by_role('dialog')).to_have_count(1)
        page.get_by_role('combobox', name=re.compile(r'^Card effect')).select_option('1')
        page.get_by_role('button', name='Resolve effect →', exact=True).click()
        expect(page.locator('.tt-player[data-player="0"] [data-goods="food"] .pile-count')).to_have_text('4')
        expect(page.locator('.tt-player[data-player="0"] [data-goods="wood"]')).to_have_count(0)
        print('PASS: mobile Cabinetmaker popup offers Plane second-wood conversion', flush=True)

        page.set_viewport_size({'width':1680, 'height':1050})
        page.goto(base+'/#'+taster)
        page.get_by_role('button', name='Resolve effect →', exact=True).click()
        expect(page.get_by_role('dialog')).to_have_count(0)
        expect(page.get_by_text('Your turn · Dee', exact=True)).to_be_visible()
        expect(page.locator('.tt-player[data-player="3"] [data-goods="food"] .pile-count')).to_have_text('4')
        assert not errors, errors
        print('PASS: Taster pays one food and gives its owner the first placement', flush=True)
        browser.close()


if __name__ == '__main__':
    main(check, 'test/eurozone/original_i_occupations_b_browser_fixture.clj')
