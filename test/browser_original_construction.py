"""Saved original plow and per-room payment choices, desktop and phone viewport."""
import os
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main


def check(base, fixture_ids):
    plow, build = fixture_ids.split(',')
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ['EUROZONE_BROWSER'])
        context = browser.new_context(viewport={'width':1680, 'height':1050})
        context.add_init_script('sessionStorage.setItem("eurozone-session", "'+'a'*64+'")')
        page = context.new_page()
        errors = []
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.goto(base+'/#'+plow)
        expect(page.get_by_role('combobox', name='Plow', exact=True)).to_be_visible()
        page.reload()
        page.get_by_role('combobox', name='Plow', exact=True).select_option(':catalog/original-base-e-62-turnwrest-plow')
        maker = page.get_by_role('checkbox', name='Plow Maker: pay 1 food for one extra field', exact=True)
        maker.click()
        expect(maker).to_be_checked()
        for i, cell in enumerate([1,2,3], 1):
            page.get_by_role('combobox', name='Plow field '+str(i), exact=True).select_option(str(cell))
        page.get_by_role('button', name='Resolve effect →', exact=True).click()
        expect(page.get_by_role('dialog')).to_have_count(0)
        expect(page.locator('.tt-player[data-player="0"] [data-goods="food"] .pile-count')).to_have_text('1')
        for cell in range(1,5):
            expect(page.locator('.tt-player[data-player="0"] [aria-label="Farm space '+str(cell)+': field"]')).to_have_count(1)
        print('PASS: select one of two original plows and combine with Plow Maker for four fields after reload', flush=True)

        page.set_viewport_size({'width':430, 'height':932})
        page.goto(base+'/#'+build)
        page.evaluate("""() => {
          document.documentElement.classList.add('discord-activity');
          document.documentElement.style.setProperty('--discord-safe-area-inset-top', '32px');
          document.documentElement.style.setProperty('--discord-safe-area-inset-bottom', '18px');
        }""")
        expect(page.locator('.app')).to_have_css('padding-top', '32px')
        expect(page.locator('.app')).to_have_css('padding-bottom', '18px')
        page.get_by_label('Build room 1', exact=True).click()
        expect(page.get_by_label('Build room 1', exact=True)).to_be_checked()
        page.get_by_label('Build room 2', exact=True).click()
        expect(page.get_by_label('Build room 2', exact=True)).to_be_checked()
        # Mixed bases are the only affordable way to build these two rooms.
        for cell, expected in [(1, {'clay':2, 'wood':1, 'reed':1}), (2, {'clay':3, 'reed':2})]:
            select = page.get_by_role('combobox', name='Room '+str(cell)+' payment', exact=True)
            options = select.locator('option').evaluate_all('(xs) => xs.map(x => ({value:x.value, text:x.textContent.toLowerCase()}))')
            wanted = next(x['value'] for x in options if x['value'] and all((str(n)+' '+resource) in x['text'] for resource,n in expected.items()) and (cell==1 or 'wood' not in x['text']))
            select.select_option(wanted)
        page.screenshot(path='/tmp/eurozone-original-construction-mobile.png')
        page.get_by_role('button', name='Resolve effect →', exact=True).click()
        expect(page.get_by_role('dialog')).to_have_count(0)
        for resource in ['wood','clay','reed']:
            expect(page.locator('.tt-player[data-player="0"] [data-goods="'+resource+'"]')).to_have_count(0)
        assert not errors, errors
        print('PASS: phone-sized dialog offers distinct per-room construction payments', flush=True)
        browser.close()


if __name__ == '__main__':
    main(check, 'test/eurozone/original_construction_browser_fixture.clj')
