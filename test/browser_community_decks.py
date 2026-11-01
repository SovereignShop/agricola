"""English prints, new-deck selection, future pieces, and persisted optional decisions."""
import os
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main


def check(base, fixture_ids):
    gallery, pond, gate = fixture_ids.split(',')
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ['EUROZONE_BROWSER'])
        context = browser.new_context(viewport={'width':1680, 'height':1050}, has_touch=True)
        context.add_init_script('sessionStorage.setItem("eurozone-session", "'+'a'*64+'")')
        page = context.new_page(); errors=[]
        page.on('pageerror', lambda e: errors.append(str(e)))
        def goods(resource, n):
            loc=page.locator('.tt-player[data-player="0"] .tt-inventory [data-goods="'+resource+'"] .pile-count')
            expect(loc).to_have_text(str(n)) if n else expect(loc).to_have_count(0)
        page.goto(base)
        page.get_by_label('Pre-game hand setup').select_option('random')
        for label in ['Gamers’ deck · G (Original)', 'World Championship · WM/G2 (Original)', 'France · FR/G3 (Original)']:
            expect(page.get_by_role('checkbox', name='Include '+label, exact=True)).to_be_checked()
        page.goto(base+'/#'+gallery)
        prints=set()
        for deck, label, count in [('hand-occupation','Hand · occupations',7),('hand-minor','Hand · minors',25)]:
            page.locator('[data-owner="0"][data-deck="'+deck+'"]').get_by_role('button',name='Open '+label+' spread',exact=True).click()
            images=page.get_by_role('dialog').locator('img[src*="/cards/original-"]')
            expect(images).to_have_count(count)
            for src in images.evaluate_all('els=>els.map(e=>e.getAttribute("src"))'):
                assert page.request.get(base+src).ok, src
                prints.add(src)
            page.get_by_role('button',name='Close dialog',exact=True).click()
        assert len(prints)==32
        page.set_viewport_size({'width':390,'height':844})
        page.locator('.action-card[data-action="meeting"]').click()
        page.get_by_role('group',name='Playable minor improvements',exact=True).get_by_role('button',name='Drained Pond',exact=True).click()
        preview=page.get_by_role('dialog',name='Drained Pond',exact=True)
        expect(preview.locator('img[src*="original-wm076"]')).to_be_visible()
        page.screenshot(path='/tmp/eurozone-community-card-preview.png')
        preview.get_by_role('button',name='Select card',exact=True).click()
        page.get_by_role('button',name='Place family member →',exact=True).click()
        goods('reed',19)
        marker=page.locator('.future-round-resources[data-round="5"] [data-resource="field"]')
        expect(marker).to_have_count(1)
        expect(marker).to_have_attribute('title', 'Ada · 1 field · Drained Pond · optional · pay 1 food')
        page.goto(base+'/#'+pond)
        expect(page.get_by_role('dialog',name='Resolve Drained Pond',exact=True)).to_be_visible()
        page.reload()
        pond_dialog=page.get_by_role('dialog',name='Resolve Drained Pond',exact=True)
        dropdown=pond_dialog.get_by_role('combobox',name='Farm space',exact=True)
        confirm=pond_dialog.get_by_role('button',name='Resolve effect →',exact=True)
        revision=page.locator('.game').get_attribute('data-revision')
        expect(confirm).to_be_disabled()
        # At round start the farm must be interactive even outside the work phase.
        pond_dialog.get_by_role('button',name='Farm space 6: room',exact=True).tap()
        expect(confirm).to_be_disabled()
        first=pond_dialog.get_by_role('button',name='Farm space 1: empty',exact=True)
        first.tap()
        expect(dropdown).to_have_value('0')
        expect(first.locator('rect').first).to_have_attribute('fill','#f9dc7170')
        expect(confirm).to_be_enabled()
        expect(page.locator('.game')).to_have_attribute('data-revision',revision)
        goods('food',23)
        # Dropdown and spatial picker share the same choice; mouse and touch both work.
        dropdown.select_option('1')
        second=pond_dialog.get_by_role('button',name='Farm space 2: empty',exact=True)
        expect(second.locator('rect').first).to_have_attribute('fill','#f9dc7170')
        first.click()
        expect(dropdown).to_have_value('0')
        page.screenshot(path='/tmp/eurozone-drained-pond-field.png')
        confirm.click()
        expect(page.locator('.tt-player[data-player="0"] [aria-label="Farm space 1: field"]')).to_have_count(1)
        goods('food',22)
        page.goto(base+'/#'+gate)
        expect(page.get_by_role('dialog',name='Resolve Open Gate',exact=True)).to_be_visible()
        page.reload()
        page.get_by_role('button',name='Resolve effect →',exact=True).click()
        goods('sheep',0);goods('food',23)
        expect(page.get_by_role('dialog',name='Resolve Open Gate',exact=True)).to_have_count(0)
        assert page.evaluate('document.documentElement.scrollWidth<=innerWidth')
        assert not errors, errors
        browser.close()
        print('G/WM/FR: 32 English images, deck setup, mobile selection, field markers, saved field/point decisions passed')


if __name__ == '__main__':
    main(check, 'test/eurozone/community_decks_browser_fixture.clj')
