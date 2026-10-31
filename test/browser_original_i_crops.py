"""English I print faces and multi-stack wood sowing through both real editors."""
import os
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main


def check(base, fixture_ids):
    work, popup, draw = fixture_ids.split(',')
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ['EUROZONE_BROWSER'])
        context = browser.new_context(viewport={'width':1680, 'height':1050})
        context.add_init_script('sessionStorage.setItem("eurozone-session", "'+'a'*64+'")')
        page = context.new_page()
        errors = []
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.goto(base+'/#'+draw)
        page.get_by_role('button', name='Reveal packet', exact=True).click()
        expect(page.locator('.draw-choices .card-sheet-art')).to_have_count(16)
        faces = page.locator('.draw-choices .card-sheet-art').evaluate_all('els=>els.map(e=>({id:e.dataset.cardArt,src:e.style.backgroundImage}))')
        assert all(':catalog/original-base-i-' in f['id'] and 'original-i-' in f['src'] for f in faces), faces
        for sheet in ['minors', 'occupations']:
            assert page.evaluate('async src=>{let i=new Image();i.src=src;await i.decode();return i.naturalWidth>3000}', '/art/agricola/original-i-'+sheet+'.jpg')
        page.goto(base+'/#'+work)
        page.locator('.action-card[data-action="sow-bake"]').click()
        for n in [1, 2]:
            box = page.get_by_role('checkbox', name=f'Sow Copse · stack {n} with wood', exact=True)
            box.click()
            expect(box).to_be_checked()
        page.get_by_role('button', name='Place family member →', exact=True).click()
        expect(page.get_by_role('dialog')).to_have_count(0)
        expect(page.locator('.tt-player[data-player="0"] button.tt-card[aria-label="Copse"] .tt-card-markers b')).to_have_text('8')
        expect(page.locator('.tt-player[data-player="0"] .tt-inventory [data-goods="wood"] .pile-count')).to_have_text('2')
        page.reload()
        expect(page.locator('.tt-player[data-player="0"] .tt-inventory [data-goods="wood"] .pile-count')).to_have_text('2')
        # Phone-sized saved effect: independently editing two stacks must preserve both choices.
        page.set_viewport_size({'width':390, 'height':844})
        page.goto(base+'/#'+popup)
        expect(page.get_by_role('dialog', name='Resolve Copse', exact=True)).to_be_visible()
        page.reload()
        for n in [1, 2]:
            page.get_by_role('combobox', name=f'Sow Copse · stack {n}', exact=True).select_option('wood')
        for n in [1, 2]:
            expect(page.get_by_role('combobox', name=f'Sow Copse · stack {n}', exact=True)).to_have_value('wood')
        page.get_by_role('button', name='Resolve effect →', exact=True).click()
        expect(page.get_by_role('dialog')).to_have_count(0)
        expect(page.locator('.tt-player[data-player="0"] button.tt-card[aria-label="Copse"] .tt-card-markers b')).to_have_text('8')
        expect(page.locator('.tt-player[data-player="0"] .tt-inventory [data-goods="wood"] .pile-count')).to_have_text('2')
        assert not errors, errors
        page.screenshot(path='/tmp/eurozone-original-i-copse-phone.png')
        print('PASS: English I draft art, independent Copse wood stacks in board editor and saved phone popup', flush=True)
        browser.close()


if __name__ == '__main__':
    main(check, 'test/eurozone/original_i_crops_browser_fixture.clj')
