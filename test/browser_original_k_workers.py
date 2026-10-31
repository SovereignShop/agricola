"""Persisted Broom replacement hand and Reed Hut worker/adoption choices."""
import os
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main


def check(base, fixture_ids):
    broom, grow, last = fixture_ids.split(',')
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ['EUROZONE_BROWSER'])
        context = browser.new_context(viewport={'width':1680, 'height':1050})
        context.add_init_script('sessionStorage.setItem("eurozone-session", "'+'a'*64+'")')
        page = context.new_page()
        errors = []
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.goto(base+'/#'+broom)
        expect(page.get_by_role('dialog', name='Resolve Broom', exact=True)).to_be_visible()
        page.reload()
        cards = page.get_by_role('group', name='Playable minor improvements', exact=True)
        expect(cards.get_by_role('button', name='Horse', exact=True).locator('.card-sheet-art')).to_have_count(1)
        cards.get_by_role('button', name='Horse', exact=True).click()
        page.get_by_role('button', name='Resolve effect →', exact=True).click()
        expect(page.get_by_role('dialog')).to_have_count(0)
        expect(page.locator('[data-owner="0"][data-deck="improvements"] button[aria-label="Horse"]')).to_have_count(1)
        expect(page.locator('.tt-player[data-player="0"] [data-goods="wood"] .pile-count')).to_have_text('9')
        page.set_viewport_size({'width':390, 'height':844})
        page.goto(base+'/#'+grow)
        expect(page.get_by_role('dialog', name='Resolve Reed Hut', exact=True)).to_be_visible()
        page.reload()
        box = page.get_by_role('checkbox', name='Bring the Reed Hut occupant into your home', exact=True)
        expect(box).not_to_be_checked()
        box.click()
        expect(box).to_be_checked()
        page.screenshot(path='/tmp/eurozone-k-reed-hut-phone.png')
        page.get_by_role('button', name='Resolve effect →', exact=True).click()
        expect(page.get_by_role('dialog')).to_have_count(0)
        expect(page.locator('.tt-player[data-player="0"]')).to_contain_text('2 / 3 workers ready')
        expect(page.get_by_alt_text('Reed Hut occupant', exact=True)).to_have_count(0)
        page.set_viewport_size({'width':1680, 'height':1050})
        page.goto(base+'/#'+last)
        expect(page.get_by_alt_text('Reed Hut occupant', exact=True)).to_have_count(1)
        page.locator('.action-card[data-action="urgent-growth"]').click()
        expect(page.get_by_role('button', name='Place family member →', exact=True)).to_be_disabled()
        page.get_by_role('button', name='Choose another space', exact=True).click()
        page.locator('.action-card[data-action="grain"]').click()
        page.get_by_role('button', name='Place family member →', exact=True).click()
        expect(page.get_by_role('dialog')).to_have_count(0)
        expect(page.get_by_alt_text('Reed Hut occupant', exact=True)).to_have_count(0)
        expect(page.locator('.tt-player[data-player="0"]')).to_contain_text('0 / 5 workers ready')
        assert not errors, errors
        print('PASS: saved Broom English replacement cards, paid followup, phone Reed Hut adoption and last-worker restriction', flush=True)
        browser.close()


if __name__ == '__main__':
    main(check, 'test/eurozone/original_k_workers_browser_fixture.clj')
