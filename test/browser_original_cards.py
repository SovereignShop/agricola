"""Original E prints in the real draw/table UI and saved native card choices."""
import os
import re
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main


def check(base, fixture_ids):
    work, optional, draw = fixture_ids.split(',')
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ['EUROZONE_BROWSER'])
        context = browser.new_context(viewport={'width':1680,'height':1050})
        context.add_init_script('sessionStorage.setItem("eurozone-session", "'+'a'*64+'")')
        page = context.new_page()
        errors = []
        page.on('pageerror', lambda e: errors.append(str(e)))
        page.goto(base+'/#'+draw)
        page.get_by_role('button',name='Reveal packet',exact=True).click()
        expect(page.locator('.draw-choices .card-sheet-art')).to_have_count(16)
        expect(page.locator('.draw-choices .text-card')).to_have_count(0)
        faces = page.locator('.draw-choices .card-sheet-art').evaluate_all('els=>els.map(e=>({id:e.dataset.cardArt,src:e.style.backgroundImage,w:e.offsetWidth,h:e.offsetHeight}))')
        assert all(':catalog/original-base-e-' in f['id'] and 'original-e-' in f['src'] and f['w']>100 and f['h']>=170 for f in faces),faces
        picked=page.locator('.draw-choices .hand-card').first.get_attribute('aria-label')
        page.locator('.draw-choices .hand-card').first.click()
        page.get_by_role('button',name='Keep card & pass left →',exact=True).click()
        page.get_by_role('button',name='Reveal packet',exact=True).click()
        page.locator('.draw-choices .hand-card').first.click()
        page.get_by_role('button',name='Keep card & pass left →',exact=True).click()
        page.get_by_role('button',name='Reveal packet',exact=True).click()
        expect(page.get_by_role('button',name='Drafted '+picked,exact=True).locator('.card-sheet-art')).to_have_count(1)
        page.screenshot(path='/tmp/eurozone-original-draw.png')

        page.goto(base+'/#'+work)
        expect(page.locator('.tt-player')).to_have_count(2)
        page.get_by_role('button',name='Ada player view',exact=True).click()
        deck=page.locator('[data-owner="0"][data-deck="hand-minor"]')
        expect(deck.locator('.card-sheet-art')).to_have_count(1)
        deck.get_by_role('button',name='Field',exact=True).click()
        spread=page.get_by_role('dialog',name='Hand · minor improvements',exact=True)
        # The spread and inspection both use the same English atlas face.
        expect(page.get_by_role('dialog').locator('.card-sheet-art')).to_have_count(1)
        page.get_by_role('dialog').get_by_role('button',name='Field',exact=True).click()
        expect(page.get_by_role('dialog',name='Field',exact=True).locator('.card-sheet-art')).to_have_count(1)
        page.get_by_role('button',name='Close dialog',exact=True).click()
        page.locator('.tt-shared .action-card').filter(has=page.locator('strong',has_text=re.compile('^Meeting Place$'))).click()
        page.get_by_role('group',name='Playable minor improvements',exact=True).get_by_role('button',name='Field',exact=True).click()
        page.get_by_role('button',name='Place family member →',exact=True).click()
        expect(page.get_by_role('dialog',name='Resolve Field',exact=True)).to_be_visible()
        page.reload()
        expect(page.get_by_role('dialog',name='Resolve Field',exact=True)).to_be_visible()
        page.get_by_role('combobox',name='Farm space',exact=True).select_option('0')
        page.get_by_role('button',name='Resolve effect →',exact=True).click()
        expect(page.get_by_role('dialog')).to_have_count(0)
        expect(page.locator('.tt-player[data-player="0"] [aria-label="Farm space 1: field"]')).to_have_count(1)
        expect(page.locator('.tt-player[data-player="0"] [data-goods="food"] .pile-count')).to_have_text('1')
        expect(page.locator('[data-owner="1"][data-deck="hand-minor"] .card-sheet-art')).to_have_count(1)

        page.goto(base+'/#'+optional)
        expect(page.get_by_role('dialog',name='Resolve Master Brewer',exact=True)).to_be_visible()
        expect(page.locator('.tt-player[data-player="0"] [data-goods="grain"] .pile-count')).to_have_text('1')
        page.reload()
        expect(page.get_by_role('dialog',name='Resolve Master Brewer',exact=True)).to_be_visible()
        page.get_by_role('button',name='Resolve effect →',exact=True).click()
        expect(page.get_by_role('dialog')).to_have_count(0)
        expect(page.locator('.tt-player[data-player="0"] [data-goods="grain"]')).to_have_count(0)
        expect(page.locator('.tt-player[data-player="0"] [data-goods="food"] .pile-count')).to_have_text('5')
        assert not errors,errors
        print('PASS: original English draw/kept/stack/spread/inspection art, saved mandatory Field placement/passing, saved optional Master Brewer payment',flush=True)
        browser.close()

if __name__=='__main__':
    main(check,'test/eurozone/original_browser_fixture.clj')
