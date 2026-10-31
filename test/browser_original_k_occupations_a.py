"""Original K first-half occupation workflows in the compiled browser."""
import os, re
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main


def check(base, fixture_ids):
    serf, nurse, piece, scholar, student, salesman, relocation = fixture_ids.split(',')
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ['EUROZONE_BROWSER'])
        context = browser.new_context(viewport={'width':1680, 'height':1050})
        context.add_init_script('sessionStorage.setItem("eurozone-session", "'+'a'*64+'")')
        page = context.new_page(); errors = []
        page.on('pageerror', lambda e: errors.append(str(e)))
        def table(room, label):
            print('Checking '+label, flush=True)
            page.goto(base+'/#'+room)
            expect(page.get_by_role('dialog')).to_be_visible()
            page.reload()
        def resolve(): page.get_by_role('button', name='Resolve effect →', exact=True).click()
        def goods(resource, n):
            loc = page.locator('.tt-player[data-player="0"] .tt-inventory [data-goods="'+resource+'"] .pile-count')
            expect(loc).to_have_text(str(n)) if n else expect(loc).to_have_count(0)
        table(serf, 'Serf')
        page.get_by_role('combobox', name='Serf benefit', exact=True).select_option('vegetable')
        resolve()
        page.get_by_role('button', name='Take Sow action', exact=True).click()
        page.get_by_role('combobox', name='Sow field 1', exact=True).select_option('vegetable')
        resolve(); expect(page.get_by_role('dialog')).to_have_count(0); goods('grain', 0)
        table(nurse, 'Wet Nurse and Adoptive Parents')
        page.get_by_role('spinbutton', name='New family members', exact=True).fill('2'); resolve()
        expect(page.get_by_role('spinbutton', name='Newborns to adopt', exact=True)).to_be_visible()
        page.reload()
        page.get_by_role('spinbutton', name='Newborns to adopt', exact=True).fill('2'); resolve()
        expect(page.get_by_role('dialog')).to_have_count(0)
        expect(page.locator('.tt-player[data-player="0"]')).to_contain_text('3 / 4 workers ready'); goods('food', 18)
        table(piece, 'Pieceworker')
        for resource in ['Reed', 'Stone']:
            box=page.get_by_role('checkbox', name=resource+' · 1 food', exact=True);box.click();expect(box).to_be_checked()
        resolve(); expect(page.get_by_role('dialog')).to_have_count(0)
        goods('reed', 2);goods('stone', 2);goods('food', 1)
        table(scholar, 'Scholar')
        page.get_by_role('group', name='Playable occupations', exact=True).get_by_role('button', name='Storehouse Clerk', exact=True).click()
        resolve();expect(page.get_by_role('dialog')).to_have_count(0);goods('wood', 21);goods('stone', 21)
        page.set_viewport_size({'width':390, 'height':844})
        table(student, 'Perpetual Student')
        expect(page.get_by_role('dialog').locator('.card-sheet-art')).to_have_count(1)
        resolve();expect(page.get_by_role('dialog')).to_have_count(0)
        expect(page.locator('.tt-player[data-player="0"]')).to_contain_text('2 / 3 workers ready')
        page.set_viewport_size({'width':1680, 'height':1050})
        table(salesman, 'Traveling Salesman and Merchant')
        for card in ['House Goat', 'Wooden Strongbox', 'Spit Roast']:
            page.get_by_role('group', name='Playable minor improvements', exact=True).get_by_role('button', name=card, exact=True).click()
            resolve()
        expect(page.get_by_role('dialog')).to_have_count(0);goods('food', 21)
        expect(page.locator('[data-owner="0"][data-deck="improvements"] .tt-card')).to_have_count(4)
        table(relocation, 'Countryman')
        page.get_by_role('button', name=re.compile('^Countryman ·')).click();resolve()
        dialog=page.get_by_role('dialog')
        dialog.get_by_label('Farm space 1: field', exact=True).click()
        page.get_by_role('button', name='Place family member →', exact=True).click()
        expect(page.get_by_role('dialog')).to_have_count(0)
        expect(page.locator('.tt-player[data-player="0"] [aria-label="Farm space 1: field"]')).to_have_count(1)
        assert not errors, errors
        print('PASS: saved Serf, multi-growth/adoption, atomic Pieceworker, Scholar, phone random occupation, Salesman/Merchant, and end-work relocation', flush=True)
        browser.close()


if __name__ == '__main__': main(check, 'test/eurozone/original_k_occupations_a_browser_fixture.clj')
