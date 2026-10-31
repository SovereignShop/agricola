"""Original K occupation choices through a production browser bundle and saved state."""
import os
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main


def check(base, fixture_ids):
    trainer, fence, foreman, breeder, seller = fixture_ids.split(',')
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
        page.goto(base+'/#'+trainer)
        page.get_by_role('spinbutton', name='Buy Sheep', exact=True).fill('1')
        page.get_by_role('spinbutton', name='Buy Cattle', exact=True).fill('1')
        page.get_by_role('button', name='Resolve effect →', exact=True).click()
        expect(page.get_by_role('dialog')).to_have_count(0)
        expect(page.locator('.tt-player[data-player="0"] [data-goods="sheep"] .pile-count')).to_have_text('1')
        expect(page.locator('.tt-player[data-player="0"] [data-goods="cattle"] .pile-count')).to_have_text('1')
        expect(page.locator('.tt-player[data-player="0"] [data-goods="food"] .pile-count')).to_have_text('10')
        print('PASS: rapid Animal Trainer choices retain both species and spend only pile food', flush=True)

        page.goto(base+'/#'+fence)
        page.get_by_role('combobox', name='Farm space', exact=True).select_option('0')
        page.get_by_role('button', name='Resolve effect →', exact=True).click()
        expect(page.get_by_role('dialog')).to_have_count(0)
        page.reload()
        expect(page.locator('.tt-player[data-player="0"] [aria-label^="Fence "][aria-label$=", built"]')).to_have_count(4)
        expect(page.locator('.tt-player[data-player="0"] [data-goods="food"] .pile-count')).to_have_text('9')
        print('PASS: Fence Overseer fences the new stable and survives reload', flush=True)

        page.goto(base+'/#'+foreman)
        page.get_by_role('combobox', name='Action space for Foreman', exact=True).select_option(':grain')
        page.get_by_role('button', name='Resolve effect →', exact=True).click()
        expect(page.get_by_role('dialog')).to_have_count(0)
        expect(page.locator('.action-card[data-action="grain"] [data-piece-type="food"]')).to_have_count(1)
        page.reload()
        page.locator('.action-card[data-action="grain"]').click()
        page.get_by_role('button', name='Place family member →', exact=True).click()
        expect(page.locator('.tt-player[data-player="0"] [data-goods="food"] .pile-count')).to_have_text('11')
        expect(page.locator('.action-card[data-action="grain"] [data-piece-type="food"]')).to_have_count(0)
        print('PASS: Foreman food is visible, persisted, and collected from a fixed space', flush=True)

        page.set_viewport_size({'width':430, 'height':932})
        page.goto(base+'/#'+breeder)
        page.get_by_role('combobox', name='Pair of animals', exact=True).select_option('boar')
        page.get_by_role('button', name='Resolve effect →', exact=True).click()
        expect(page.get_by_role('dialog')).to_have_count(0)
        expect(page.locator('.tt-player[data-player="0"] [data-goods="boar"] .pile-count')).to_have_text('2')
        expect(page.locator('.tt-player[data-player="0"] [data-goods="food"] .pile-count')).to_have_text('8')
        print('PASS: mobile Animal Breeder choice buys and houses a pair', flush=True)

        page.goto(base+'/#'+seller)
        page.reload()
        page.get_by_role('combobox', name='Resource receipt order', exact=True).select_option('1')
        page.get_by_role('button', name='Resolve effect →', exact=True).click()
        expect(page.get_by_role('dialog')).to_have_count(0)
        expect(page.locator('.tt-player[data-player="0"] [data-goods="wood"] .pile-count')).to_have_text('22')
        expect(page.locator('.tt-player[data-player="0"] [data-goods="clay"] .pile-count')).to_have_text('1')
        assert not errors, errors
        print('PASS: saved Resource Seller order allows either legal receipt sequence', flush=True)
        browser.close()


if __name__ == '__main__':
    main(check, 'test/eurozone/original_k_occupations_b_browser_fixture.clj')
