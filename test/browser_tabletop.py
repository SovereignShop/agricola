"""Experimental tabletop: real server, camera, arrangement, privacy, and commands.
Run after scripts/build_browser.py, with the same browser environment as smoke.
"""
import os
import re
from pathlib import Path
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main


def check(base, fixtures):
    five, online, optional, major, upgrade = fixtures.split(',')
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ['EUROZONE_BROWSER'])
        context = browser.new_context(viewport={'width':1680,'height':1050})
        context.add_init_script('sessionStorage.setItem("eurozone-session", "'+'a'*64+'")')
        page = context.new_page()
        errors=[]
        page.on('pageerror', lambda e: errors.append(str(e)))

        def open_game(id):
            page.goto(base+'/#'+id)
            expect(page.locator('.tt-player')).not_to_have_count(0)
            page.wait_for_timeout(500)

        def focus(name):
            page.get_by_role('button',name=name+' player view',exact=True).click()
            page.wait_for_timeout(450)

        def action(name):
            page.locator('.tt-shared .action-card').filter(has=page.locator('strong',has_text=re.compile('^'+name+'$'))).click()

        def position(locator):
            return [int(locator.get_attribute('data-x')),int(locator.get_attribute('data-y'))]

        def confirm():
            revision=page.locator('.game').get_attribute('data-revision')
            page.get_by_role('button',name='Place family member →',exact=True).click()
            expect(page.locator('.game')).not_to_have_attribute('data-revision',revision)
            expect(page.locator('[role=alert]')).to_have_count(0)

        def close():
            page.get_by_role('button',name='Close dialog',exact=True).click()

        open_game(five)
        expect(page.locator('.tt-player')).to_have_count(5)
        inventory=page.locator('.tt-inventory[data-inventory="0"]')
        expect(inventory.locator('[data-goods]')).to_have_count(5)
        expect(inventory.locator('[data-goods="grain"],[data-goods="sheep"]')).to_have_count(0)
        farm=page.locator('.tt-player[data-player="0"] .tt-farm')
        assert abs(inventory.bounding_box()['width']-farm.bounding_box()['width'])<1
        assert inventory.bounding_box()['y']+inventory.bounding_box()['height']<farm.bounding_box()['y']
        expect(page.locator('.five-extension')).to_have_count(0)
        expect(page.locator('.five-printed-board .action-card')).to_have_count(11)
        assert page.locator('.five-printed-board').bounding_box()['x']<page.locator('.physical-board').bounding_box()['x']
        expect(page.locator('.tile-grid,.table-tile,.tile-content')).to_have_count(0)
        expect(page.locator('.round-card-slot[data-revealed=false]')).to_have_count(13)
        assert page.locator('.round-card-slot[data-revealed=false] .round-card-front').evaluate_all('(els)=>els.every(e=>!e.textContent.trim())')
        # Every area fits; the shared board and fifth farm do not overlap.
        viewport=page.locator('.tt-viewport').bounding_box()
        world=page.locator('.tt-world').bounding_box()
        assert abs((world['x']+world['width']/2)-(viewport['x']+viewport['width']/2))<3
        shared=page.locator('.tt-shared').bounding_box()
        fifth=page.locator('.tt-player[data-player="4"]').bounding_box()
        assert shared['y']+shared['height'] < fifth['y']
        scrollables=page.locator('.tt-world *').evaluate_all('els=>els.filter(e=>{const s=getComputedStyle(e);return [s.overflowX,s.overflowY].some(v=>v==="auto"||v==="scroll")}).map(e=>e.className)')
        assert not scrollables,scrollables
        page.screenshot(path='/tmp/eurozone-tabletop-board.png')
        focus('Ada')
        deck=page.locator('[data-owner="0"][data-deck="hand-occupation"]')
        expect(deck).to_have_attribute('data-mode','spread')
        expect(deck.locator('.tt-card')).to_have_count(7)
        area=page.locator('.tt-player[data-player="0"]').bounding_box()
        assert abs((area['x']+area['width']/2)-(viewport['x']+viewport['width']/2))<4
        deck.get_by_role('button',name='Wood Cutter',exact=True).click()
        expect(page.get_by_role('dialog',name='Wood Cutter',exact=True)).to_be_visible()
        close()
        deck.get_by_role('button',name='Stack Hand · occupations',exact=True).click()
        expect(deck).to_have_attribute('data-mode','stack')
        expect(deck.locator('.tt-card-caption,.tt-deck-browse')).to_have_count(0)
        deck.get_by_role('button',name='Wood Cutter',exact=True).click(position={'x':50,'y':10})
        expect(page.get_by_role('dialog',name='Wood Cutter',exact=True)).to_be_visible()
        close()
        expect(deck).to_have_attribute('data-mode','stack')
        expect(deck.locator('.exposed')).to_have_count(0)
        deck.get_by_role('button',name='Spread Hand · occupations',exact=True).click()
        # Drag at a non-unit zoom: camera scale must not affect saved coordinates.
        revision=page.locator('.game').get_attribute('data-revision')
        handle=deck.get_by_role('button',name='Move Hand · occupations',exact=True)
        start=position(deck); z=float(page.locator('.tt-viewport').get_attribute('data-zoom'))
        box=handle.bounding_box(); x=box['x']+box['width']/2; y=box['y']+box['height']/2
        page.mouse.move(x,y);page.mouse.down();page.mouse.move(x+80*z,y+48*z,steps=12);page.mouse.up()
        expect(deck).not_to_have_attribute('data-x',str(start[0]))
        moved=position(deck)
        assert abs(moved[0]-(start[0]+80))<=8 and abs(moved[1]-(start[1]+48))<=8,moved
        assert all(v%16==0 for v in moved),moved
        handle.focus();page.keyboard.press('ArrowRight')
        expect(deck).to_have_attribute('data-x',str(moved[0]+16))
        moved=position(deck)
        goods=page.locator('.tt-inventory[data-inventory="0"]')
        gh=goods.get_by_role('button',name='Move Ada resources',exact=True)
        before_goods=position(goods)
        gh.focus();page.keyboard.press('ArrowDown')
        expect(goods).to_have_attribute('data-y',str(before_goods[1]+16))
        expected_goods=position(goods)
        expect(page.locator('.game')).to_have_attribute('data-revision',revision)
        page.reload();expect(deck).to_have_attribute('data-x',str(moved[0]))
        expect(deck).to_have_attribute('data-y',str(moved[1]))
        assert position(goods)==expected_goods
        focus('Ada')
        page.screenshot(path='/tmp/eurozone-tabletop-player.png')
        # Surface selection is also local and persists through reload.
        page.get_by_role('button',name='Tablecloth',exact=True).click()
        for label,id in [('White linen','white'),('Natural linen','tan'),('Sage check','checkered'),('Cognac leather','leather')]:
            page.get_by_role('button',name=label,exact=True).click()
            expect(page.locator('.tt-game')).to_have_attribute('data-material',id)
            assert page.request.get(base+'/art/tablecloths/'+id+'.webp').ok
        close();page.screenshot(path='/tmp/eurozone-tabletop-leather.png')
        page.reload();expect(page.locator('.tt-game')).to_have_attribute('data-material','leather')
        expect(page.locator('.game')).to_have_attribute('data-revision',revision)
        page.wait_for_timeout(500)
        before=page.locator('.tt-viewport').get_attribute('data-zoom')
        page.get_by_role('button',name='Zoom in',exact=True).click()
        expect(page.locator('.tt-viewport')).not_to_have_attribute('data-zoom',before)
        assert float(page.locator('.tt-viewport').get_attribute('data-zoom'))>float(before)
        # Spatial command keeps the normal engine validation and asks before placing.
        action('Farmland')
        expect(page.get_by_role('heading',name='Place a family member here?',exact=True)).to_be_visible()
        expect(page.locator('.game')).to_have_attribute('data-revision',revision)
        page.get_by_role('button',name='Farm space 5: empty',exact=True).click()
        page.screenshot(path='/tmp/eurozone-tabletop-action.png')
        confirm()
        expect(page.locator('.tt-player[data-player="0"] [aria-label="Farm space 5: field"]')).to_have_count(1)
        expect(page.get_by_role('dialog')).to_have_count(0)
        # Cancellation does not place a worker.
        revision=page.locator('.game').get_attribute('data-revision')
        action('Forest')
        page.get_by_role('button',name='Choose another space',exact=True).click()
        expect(page.get_by_role('dialog')).to_have_count(0)
        expect(page.locator('.game')).to_have_attribute('data-revision',revision)
        # A takeback vote is the sole popup and restores the requesting player's turn.
        page.get_by_role('button',name='Request takeback',exact=True).click()
        expect(page.get_by_role('dialog')).to_have_count(1)
        page.get_by_role('button',name='Ask for takeback',exact=True).click()
        page.wait_for_function("document.querySelector('[role=dialog]')?.textContent.includes('Takeback vote') || document.querySelector('[role=dialog]')?.textContent.includes('Takeback approved')")
        expect(page.locator('.app')).to_have_attribute('aria-busy','false')
        for _ in range(5):
            if page.get_by_role('button',name='Approve takeback',exact=True).count():
                vote_revision=page.locator('.game').get_attribute('data-revision')
                page.get_by_role('button',name='Approve takeback',exact=True).first.click()
                expect(page.locator('.game')).not_to_have_attribute('data-revision',vote_revision)
                expect(page.locator('.app')).to_have_attribute('aria-busy','false')
        expect(page.get_by_role('dialog',name='Takeback approved',exact=True)).to_be_visible()
        expect(page.get_by_role('dialog')).to_have_count(1)
        page.get_by_role('button',name='Continue',exact=True).click()
        expect(page.get_by_role('dialog')).to_have_count(0)
        expect(page.locator('.tt-player[data-player="0"] [aria-label="Farm space 5: field"]')).to_have_count(0)
        # Card choices are playable image spreads; unmet prerequisites are excluded.
        action('Meeting Place')
        minors=page.get_by_role('group',name='Playable minor improvements',exact=True)
        expect(minors.get_by_role('button',name='Beanfield',exact=True)).to_have_count(0)
        expect(page.get_by_role('combobox',name='Minor improvement',exact=True)).to_have_count(0)
        page.get_by_role('button',name='Choose another space',exact=True).click()
        action('Lessons')
        occupations=page.get_by_role('group',name='Playable occupations',exact=True)
        expect(occupations.locator('.card-choice')).to_have_count(7)
        occupations.get_by_role('button',name='Wood Cutter',exact=True).click()
        expect(occupations.get_by_role('button',name='Wood Cutter',exact=True)).to_have_attribute('aria-pressed','true')
        confirm()
        expect(page.locator('[data-owner="0"][data-deck="occupations"] .tt-card')).to_have_count(1)
        action('Copse')
        confirm()
        expect(page.locator('.five-printed-space[data-action="lessons-copse"] .action-card')).to_be_disabled()
        # Small viewports still have one camera and a fitting modal; Ctrl+ is text only.
        page.set_viewport_size({'width':390,'height':844})
        focus('Ben')
        assert page.locator('body').evaluate('(e)=>e.scrollWidth<=innerWidth')
        z=page.locator('.tt-viewport').get_attribute('data-zoom')
        page.locator('.tt-viewport').focus();page.keyboard.press('Control+=')
        expect(page.get_by_role('button',name='Reset text size',exact=True)).to_have_text('110%')
        page.screenshot(path='/tmp/eurozone-tabletop-mobile.png')
        page.set_viewport_size({'width':1680,'height':1050})
        open_game(optional)
        expect(page.get_by_role('dialog',name='Resolve Shaving Horse',exact=True)).to_be_visible()
        revision=page.locator('.game').get_attribute('data-revision')
        close();expect(page.get_by_role('dialog')).to_have_count(0)
        expect(page.locator('.game')).to_have_attribute('data-revision',revision)
        page.get_by_role('button',name='Go to my decision',exact=True).click()
        page.get_by_role('button',name='Resolve effect →',exact=True).click()
        expect(page.locator('.game')).not_to_have_attribute('data-revision',revision)
        expect(page.get_by_role('dialog')).to_have_count(0)
        expect(page.locator('.tt-player[data-player="0"] [data-goods="wood"] .pile-count')).to_have_text('4')
        expect(page.locator('.tt-player[data-player="0"] [data-goods="food"] .pile-count')).to_have_text('5')
        # Only affordable English major cards are offered, including returning a fireplace.
        open_game(major)
        action('Major or Minor Improvement')
        major_cards=page.get_by_role('group',name='Playable major improvements',exact=True)
        expect(major_cards.locator('.card-choice')).to_have_count(1)
        expect(major_cards.locator('.major-card-art')).to_have_count(1)
        assert 'majors-en.jpg' in major_cards.locator('.major-card-art').get_attribute('style')
        major_cards.get_by_role('button',name='Fireplace',exact=True).click()
        confirm()
        expect(page.locator('[data-owner="0"][data-deck="improvements"] .major-card-art')).to_have_count(1)
        open_game(upgrade)
        action('Major or Minor Improvement')
        expect(major_cards.locator('.card-choice')).to_have_count(2)
        major_cards.get_by_role('button',name='Cooking Hearth',exact=True).first.click()
        expect(page.get_by_role('combobox',name='Payment',exact=True)).to_have_value('fireplace-2')
        confirm()
        expect(page.locator('[data-owner="0"][data-deck="improvements"] .major-card-art')).to_have_count(1)
        # Real online permissions, including unauthorized actions and hidden cards.
        open_game(online)
        focus('Ben')
        hidden=page.locator('[data-owner="1"][data-deck="hand-occupation"]')
        expect(hidden.locator('.tt-card')).to_have_count(2)
        expect(hidden.locator('.tt-card-back')).to_have_count(2)
        expect(hidden).not_to_contain_text('Wood Cutter')
        expect(hidden.locator('img')).to_have_count(0)
        other=browser.new_context(viewport={'width':1200,'height':900},storage_state=context.storage_state())
        other.add_init_script('sessionStorage.setItem("eurozone-session", "'+'b'*64+'")')
        guest=other.new_page();guest.on('pageerror',lambda e:errors.append(str(e)))
        guest.goto(base+'/#'+online)
        expect(guest.locator('.tt-player')).to_have_count(2)
        expect(guest.locator('.tt-shared .action-card:not(:disabled)')).to_have_count(0)
        expect(guest.locator('.tt-game')).to_have_attribute('data-material','tan')
        assert guest.evaluate('Object.keys(localStorage).some(k=>k.startsWith("eurozone-tabletop-v1:"))')  # Host prefs exist, but are not used for this user.
        assert not errors,errors
        print('PASS: five-player camera, no subpanel scroll, deck stack/spread popups, drag/snap/save, surfaces, placement confirmation, spatial action, mobile, optional effect, takeback, online privacy and per-user isolation',flush=True)
        browser.close()


if __name__=='__main__':
    main(check,'test/eurozone/tabletop_browser_fixture.clj')
