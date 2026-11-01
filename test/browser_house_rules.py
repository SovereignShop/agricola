"""House-rule setup and persistence in the actual optimized browser bundle."""
import os
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main

SWITCHES = ['Use card banlist', 'Harder Braggart', 'Adjust starting food']


def check(base, _):
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ['EUROZONE_BROWSER'])
        errors = []
        for players, enabled in [(4, False), (3, True), (4, True), (5, True)]:
            context = browser.new_context(viewport={'width': 1440, 'height': 1000})
            page = context.new_page()
            page.on('pageerror', lambda e: errors.append(str(e)))
            page.goto(base)
            page.get_by_text('Connected', exact=True).wait_for()
            page.get_by_label('Your name', exact=True).fill('Ada')
            page.get_by_label('Players expected').select_option(str(players))
            page.get_by_label('Pre-game hand setup').select_option('random')
            for label in SWITCHES:
                expect(page.get_by_role('checkbox', name=label, exact=True)).not_to_be_checked()
                if enabled:
                    page.get_by_role('checkbox', name=label, exact=True).click()
                    expect(page.get_by_role('checkbox', name=label, exact=True)).to_be_checked()
            if not enabled:
                expect(page.locator('.starting-food')).to_have_text('Starting food: every player gets 3 food.')
            page.get_by_role('button', name='Create table →').click()
            for label in SWITCHES:
                switch = page.get_by_role('checkbox', name=label, exact=True)
                expect(switch).to_be_checked(checked=enabled)
                expect(switch).to_be_disabled()
            page.reload()
            expect(page.get_by_role('checkbox', name=SWITCHES[0], exact=True)).to_be_checked(checked=enabled)
            page.get_by_role('button', name='Start the game →').click()
            expect(page.locator('.tt-game')).to_be_visible()
            food = page.locator('.tt-inventory [data-goods="food"]').evaluate_all(
                'els => els.map(e => Number(e.dataset.pieceCount)).sort()')
            expected = [3] * players if not enabled else sorted([2] + [3] * (players-1-(players in [3, 4])) + ([4] if players in [3, 4] else []))
            assert food == expected, (players, enabled, food)
            page.get_by_role('button', name='Table menu', exact=True).click()
            page.get_by_role('button', name='Rules', exact=True).click()
            for label in SWITCHES:
                expect(page.get_by_role('checkbox', name=label, exact=True)).to_be_checked(checked=enabled)
            page.set_viewport_size({'width': 390, 'height': 844})
            assert page.evaluate('document.documentElement.scrollWidth <= innerWidth')
            page.screenshot(path=f'/tmp/eurozone-house-rules-{players}-{enabled}.png', full_page=True)
            context.close()
        assert not errors, errors
        browser.close()
        print('House rules: off defaults, 3/4/5-player food, saved setup, in-game rules, and mobile passed')


if __name__ == '__main__':
    main(check)
