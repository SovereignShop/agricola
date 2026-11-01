"""Grocer's physical markers follow actual purchases for both editions."""
import os
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main


def check(base, fixtures):
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ['EUROZONE_BROWSER'])
        context = browser.new_context(viewport={'width': 1440, 'height': 1000})
        context.add_init_script('sessionStorage.setItem("eurozone-session", "'+'a'*64+'")')
        page = context.new_page()
        errors = []
        page.on('pageerror', lambda e: errors.append(str(e)))
        sequences = [ ['reed', 'grain', 'stone', 'vegetable', 'wood', 'clay', 'reed', 'vegetable'],
                      ['wood', 'grain', 'reed', 'stone', 'vegetable', 'clay', 'reed', 'vegetable'] ]
        for room, sequence in zip(fixtures.split(','), sequences):
            page.goto(base+'/#'+room)
            deck = page.locator('[data-owner="0"][data-deck="occupations"]')
            expect(deck.locator('[data-next-resource]')).to_have_attribute('data-next-resource', sequence[0])
            assert sum(map(int, deck.locator('.tt-card-markers b').all_text_contents())) == 8
            deck.locator('.tt-card').click()
            spread = page.get_by_role('dialog')
            expect(spread.locator('[data-next-resource]')).to_have_attribute('data-next-resource', sequence[0])
            spread.get_by_role('button', name='Grocer', exact=True).click()
            dialog = page.get_by_role('dialog', name='Grocer', exact=True)
            for i, resource in enumerate(sequence):
                expect(dialog.locator('[data-next-resource]')).to_have_attribute('data-next-resource', resource)
                assert sum(map(int, dialog.locator('.tt-card-markers b').all_text_contents())) == 8-i
                revision = page.locator('.game').get_attribute('data-revision')
                dialog.get_by_role('button', name='Use card ability', exact=True).click()
                expect(page.locator('.game')).not_to_have_attribute('data-revision', revision)
                expect(page.get_by_role('alert')).to_have_count(0)
            expect(dialog.locator('[data-next-resource]')).to_have_count(0)
            expect(dialog.get_by_role('button', name='Use card ability', exact=True)).to_be_disabled()
            expect(deck.locator('.tt-card-markers')).to_have_count(0)
        assert not errors, errors
        browser.close()
        print('Original/revised Grocer: table and spread markers, correct next resource through all 16 purchases, depleted stock: passed')


if __name__ == '__main__':
    main(check, 'test/eurozone/grocer_browser_fixture.clj')
