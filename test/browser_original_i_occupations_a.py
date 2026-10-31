"""Saved original I-deck multiplayer and final-scoring decision workflows."""
import os
import re
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main


def check(base, fixtures):
    harvest, chamberlain, hide, corn, juggler, businessman = fixtures.split(',')
    with sync_playwright() as pw:
        options = {'headless': True}
        if os.environ.get('EUROZONE_BROWSER'):
            options['executable_path'] = os.environ['EUROZONE_BROWSER']
        browser = pw.chromium.launch(**options)
        context = browser.new_context(viewport={'width': 1680, 'height': 1050})
        context.add_init_script('sessionStorage.setItem("eurozone-session", "'+'a'*64+'")')
        page = context.new_page()
        errors = []
        page.on('pageerror', lambda error: errors.append(str(error)))

        def open_saved(id, name):
            print('Checking '+name, flush=True)
            page.goto(base+'/#'+id)
            expect(page.get_by_role('dialog', name='Resolve '+name, exact=True)).to_be_visible()
            page.reload()
            expect(page.get_by_role('dialog', name='Resolve '+name, exact=True)).to_be_visible()

        def resolve():
            page.get_by_role('button', name='Resolve effect →', exact=True).click()

        def food(pid, amount):
            expect(page.locator(f'.tt-player[data-player="{pid}"] [data-goods="food"] .pile-count')).to_have_text(str(amount))

        open_saved(harvest, 'Harvest Helper')
        page.get_by_role('button', name='Ben · field 1', exact=True).click()
        resolve()
        expect(page.get_by_role('dialog')).to_have_count(0)
        food(1, 5)

        open_saved(chamberlain, 'Chamberlain')
        page.get_by_label(re.compile(r'^Round 12 action')).select_option('cultivation')
        resolve()
        expect(page.get_by_role('dialog')).to_have_count(0)

        open_saved(hide, 'Hide Farmer')
        page.get_by_role('spinbutton', name='Unused spaces to cover (1 food each)', exact=True).fill('13')
        resolve()
        expect(page.get_by_role('dialog')).to_have_count(0)
        food(0, 7)

        open_saved(corn, 'Corn Profiteer')
        page.get_by_role('button', name='Cora', exact=True).click()
        resolve()
        expect(page.get_by_role('dialog')).to_have_count(0)
        food(0, 4)
        food(1, 3)
        food(2, 1)

        open_saved(juggler, 'Juggler')
        resolve()
        expect(page.get_by_role('dialog')).to_have_count(0)
        food(0, 3)
        food(1, 4)

        open_saved(businessman, 'Businessman')
        for name, following in [('Clogs', 'Merchant'), ('Raft', 'Businessman'), ('Plane', None)]:
            page.get_by_role('group', name='Playable minor improvements', exact=True).get_by_role('button', name=name, exact=True).click()
            resolve()
            if following:
                expect(page.get_by_role('dialog', name='Resolve '+following, exact=True)).to_be_visible()
        expect(page.get_by_role('dialog')).to_have_count(0)
        food(0, 21)
        assert not errors, errors
        print('PASS: saved Harvest Helper, Chamberlain, Hide Farmer, Corn Profiteer auction, Juggler and Businessman/Merchant', flush=True)
        browser.close()


if __name__ == '__main__':
    main(check, 'test/eurozone/original_i_occupations_a_browser_fixture.clj')
