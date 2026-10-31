"""Real browser choices for E15/E33, including saved granted-action workflows."""
import os
import re
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main


def check(base, fixture_ids):
    meeting, scholar, artist, minor, merchant, madonna, resources = fixture_ids.split(',')
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ['EUROZONE_BROWSER'])
        context = browser.new_context(viewport={'width':1680, 'height':1050})
        context.add_init_script('sessionStorage.setItem("eurozone-session", "'+'a'*64+'")')
        page = context.new_page()
        errors = []
        page.on('pageerror', lambda error: errors.append(str(error)))

        def supply_oven():
            page.get_by_role('group', name='Playable major improvements', exact=True).get_by_role('button', name='Clay Oven', exact=True).click()
            page.get_by_role('combobox', name='Baking Tray discount', exact=True).select_option('stone')
            page.get_by_role('spinbutton', name='Grain to bake', exact=True).fill('1')

        def meeting_space():
            page.locator('.tt-shared .action-card').filter(has=page.locator('strong', has_text=re.compile('^Meeting Place$'))).click()

        def food(amount):
            expect(page.locator('.tt-player[data-player="0"] [data-goods="food"] .pile-count')).to_have_text(str(amount))

        page.goto(base+'/#'+meeting)
        expect(page.locator('.tt-player')).to_have_count(2)
        meeting_space()
        supply_oven()
        page.get_by_role('button', name='Place family member →', exact=True).click()
        expect(page.get_by_role('dialog')).to_have_count(0)
        food(7)
        expect(page.locator('.tt-player[data-player="0"] [data-goods="clay"]')).to_have_count(0)

        page.goto(base+'/#'+scholar)
        expect(page.get_by_role('dialog', name='Resolve Scholar', exact=True)).to_be_visible()
        page.reload()
        expect(page.get_by_role('dialog', name='Resolve Scholar', exact=True)).to_be_visible()
        supply_oven()
        page.get_by_role('button', name='Resolve effect →', exact=True).click()
        expect(page.get_by_role('dialog')).to_have_count(0)
        food(7)

        page.goto(base+'/#'+artist)
        expect(page.get_by_role('dialog', name='Resolve Young Artist', exact=True)).to_be_visible()
        page.reload()
        page.get_by_role('combobox', name='Young Artist action', exact=True).select_option('play')
        page.get_by_role('group', name='Playable major improvements', exact=True).get_by_role('button', name='Pottery', exact=True).click()
        page.get_by_role('button', name='Resolve effect →', exact=True).click()
        expect(page.get_by_role('dialog')).to_have_count(0)
        food(1)

        page.goto(base+'/#'+minor)
        expect(page.locator('.tt-player')).to_have_count(2)
        meeting_space()
        page.get_by_role('group', name='Playable minor improvements', exact=True).get_by_role('button', name='Wood-Fired Oven', exact=True).click()
        page.get_by_role('combobox', name='Baking Tray discount', exact=True).select_option('stone')
        page.get_by_role('button', name='Place family member →', exact=True).click()
        expect(page.get_by_role('dialog', name='Resolve Wood-Fired Oven', exact=True)).to_be_visible()
        page.reload()
        page.get_by_role('spinbutton', name='Grain to bake', exact=True).fill('1')
        page.get_by_role('button', name='Resolve effect →', exact=True).click()
        expect(page.get_by_role('dialog')).to_have_count(0)
        food(5)
        page.goto(base+'/#'+merchant)
        expect(page.locator('.tt-player')).to_have_count(2)
        meeting_space()
        page.get_by_role('group', name='Playable minor improvements', exact=True).get_by_role('button', name='Clogs', exact=True).click()
        page.get_by_role('button', name='Place family member →', exact=True).click()
        expect(page.get_by_role('dialog', name='Resolve Merchant', exact=True)).to_be_visible()
        page.reload()
        page.get_by_role('group', name='Playable minor improvements', exact=True).get_by_role('button', name='Baking Tray', exact=True).click()
        page.get_by_role('button', name='Resolve effect →', exact=True).click()
        expect(page.get_by_role('dialog')).to_have_count(0)
        food(1)

        page.goto(base+'/#'+madonna)
        expect(page.locator('.tt-player')).to_have_count(2)
        meeting_space()
        page.get_by_role('group', name='Playable minor improvements', exact=True).get_by_role('button', name='Madonna Statue', exact=True).click()
        expect(page.get_by_role('group', name='Return two improvements', exact=True).locator('[aria-pressed="true"]')).to_have_count(2)
        page.get_by_role('button', name='Place family member →', exact=True).click()
        expect(page.get_by_role('dialog')).to_have_count(0)

        page.goto(base+'/#'+resources)
        expect(page.locator('.tt-player')).to_have_count(4)
        page.locator('.tt-shared .action-card').filter(has=page.locator('strong', has_text=re.compile('^Fishing$'))).click()
        page.get_by_role('button', name='Place family member →', exact=True).click()
        expect(page.get_by_role('dialog', name='Resolve Fisherman', exact=True)).to_be_visible()
        page.reload()
        page.get_by_role('button', name='Resolve effect →', exact=True).click()
        expect(page.get_by_role('dialog')).to_have_count(0)
        food(6)
        # Local hotseat moves to Ben, who owns the extra forest and owes no fee.
        page.get_by_role('button', name='Take wood', exact=True).click()
        page.get_by_role('button', name='Place family member →', exact=True).click()
        expect(page.get_by_role('dialog')).to_have_count(0)
        expect(page.locator('.tt-player[data-player="1"] [data-goods="wood"] .pile-count')).to_have_text('6')
        assert not errors, errors
        print('PASS: E15 selected discounts for major/minor ovens, Meeting Place and saved Scholar baking, E33 free Pottery through saved Young Artist, Merchant, Madonna, Fisherman, Master Forester', flush=True)
        browser.close()


if __name__ == '__main__':
    main(check, 'test/eurozone/original_permissions_browser_fixture.clj')
