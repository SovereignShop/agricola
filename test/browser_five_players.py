"""Five real browser seats, private drafting, extension actions and piece motion."""
import os
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main, choose_action, confirm_action


def check(base, fixture_id, network=True):
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, **({'executable_path': os.environ['EUROZONE_BROWSER']} if os.environ.get('EUROZONE_BROWSER') else {}))
        errors = []
        if network:
            contexts = [browser.new_context(viewport={'width': 1600, 'height': 1100}) for _ in range(5)]
            pages = [c.new_page() for c in contexts]
            for p in pages:
                p.on('pageerror', lambda e: errors.append(str(e)))
            host = pages[0]
            host.goto(base)
            host.get_by_text('Connected', exact=True).wait_for()
            host.get_by_label('Your name', exact=True).fill('Player 1')
            host.get_by_label('Table name').fill('Five browser seats')
            host.get_by_role('button', name='Online table', exact=True).click()
            host.get_by_label('Players expected').select_option('5')
            host.get_by_label('Pre-game hand setup').select_option('draw')
            host.get_by_role('button', name='Create table →').click()
            start = host.get_by_role('button', name='Start draw →')
            expect(start).to_be_disabled()
            for i, p in enumerate(pages[1:], 2):
                p.goto(host.url)
                p.get_by_placeholder('Your name').fill(f'Player {i}')
                p.get_by_role('button', name='Take a seat', exact=True).click()
                p.get_by_text('The host will start once everyone is ready.').wait_for()
                if i < 5:
                    expect(start).to_be_disabled()
            expect(start).to_be_enabled()
            start.click()
            hands = []
            for p in pages:
                expect(p.locator('.draw-packet .hand-card')).to_have_count(16)
                hands.extend(p.locator('.draw-packet .hand-card > strong').all_text_contents())
            assert len(set(hands)) == 80
            picks = 0
            for _ in range(80):
                if host.get_by_role('heading', name='Agricola', exact=True).count():
                    break
                for p in pages:
                    expect(p.locator('.app')).to_have_attribute('aria-busy', 'false')
                    cards = p.locator('.draw-packet > div .hand-grid').first.locator('.hand-card:not([disabled])')
                    if cards.count():
                        cards.first.click()
                        revision = p.locator('.game').get_attribute('data-revision')
                        p.get_by_role('button', name='Keep card & pass left →').click()
                        expect(p.locator('.game')).not_to_have_attribute('data-revision', revision)
                        picks += 1
            assert picks == 70, picks
            for p in pages:
                expect(p.get_by_role('heading', name='Five-player village')).to_be_visible()
                expect(p.locator('.player-tab')).to_have_count(5)
                expect(p.locator('[role=alert]')).to_have_count(0)
            host.get_by_role('button', name='Show all farms', exact=True).click()
            expect(host.locator('[data-tile^="farm-"]')).to_have_count(5)
            expect(host.locator('[data-piece-type="worker:4"]')).not_to_have_count(0)
            pages[4].reload()
            expect(pages[4].locator('.player-tab')).to_have_count(5)
            print('Five remote seats, readiness, private 14+2 draft, all farms and reconnect: passed')

        c = browser.new_context(viewport={'width': 1800, 'height': 1400})
        c.add_init_script('if (location.origin === ' + repr(base) + ') sessionStorage.setItem("eurozone-session", "' + 'a'*64 + '")')
        p = c.new_page(); p.on('pageerror', lambda e: errors.append(str(e)))
        p.goto(base + '/#' + fixture_id)
        p.get_by_role('heading', name='Five-player village').wait_for()
        p.evaluate('''() => {
          window.flights = [];
          new MutationObserver(records => records.forEach(r => r.addedNodes.forEach(n => {
            if (n.classList?.contains('piece-flight')) window.flights.push({src:n.src, source:n.dataset.motionSource});
          }))).observe(document.body, {childList:true});
        }''')
        choose_action(p, 'Copse')
        hora = p.get_by_label('Master Hora: pay 1 food for a vegetable before placing')
        hora.click()
        expect(hora).to_be_checked()
        confirm_action(p)
        expect(p.locator('.action-card').filter(has=p.locator('strong', has_text='Copse Lessons'))).to_be_disabled()
        p.wait_for_function('window.flights.some(f => f.src.includes("farmer_") && f.source === "visible")')
        p.wait_for_function('document.querySelectorAll(".piece-flight").length === 0')
        assert p.locator('[data-piece-key="worker::copse:0"]').count() == 1
        # Hide farms and use a printed board space: the entering worker uses the local fallback.
        p.get_by_role('button', name="Hide Selected player's farm", exact=True).click()
        p.evaluate('window.flights = []')
        remote_context = browser.new_context(viewport={'width': 1800, 'height': 1400})
        remote_context.add_init_script('if (location.origin === ' + repr(base) + ') sessionStorage.setItem("eurozone-session", "' + 'a'*64 + '")')
        remote = remote_context.new_page(); remote.goto(p.url)
        choose_action(remote, 'Forest'); confirm_action(remote)
        p.wait_for_function('window.flights.some(f => f.src.includes("farmer_") && f.source === "offscreen")')
        p.wait_for_function('document.querySelectorAll(".piece-flight").length === 0')
        remote_context.close()
        p.get_by_role('button', name='Panels', exact=True).click()
        p.get_by_label("Selected player's farm", exact=True).click()
        p.emulate_media(reduced_motion='reduce')
        p.evaluate('window.flights = []')
        choose_action(p, 'Grain Seeds'); confirm_action(p)
        p.wait_for_timeout(100)
        assert p.evaluate('window.flights.length') == 0
        for action in ['Clay Pit', 'Reed Bank']:
            choose_action(p, action); confirm_action(p)
        # Back to Ada: a native five-player-only card can be played through the actual UI.
        choose_action(p, 'Lessons')
        p.get_by_label('Occupation', exact=True).select_option(':revised-five/a173')
        confirm_action(p)
        p.get_by_role('button', name='Panels', exact=True).click()
        p.get_by_role('button', name='Table view', exact=True).click()
        p.get_by_role('heading', name='Five-player village').scroll_into_view_if_needed()
        p.screenshot(path='/tmp/eurozone-five-players.png', full_page=True)
        p.set_viewport_size({'width': 390, 'height': 844})
        assert p.evaluate('document.documentElement.scrollWidth <= innerWidth')
        expect(p.locator('[role=alert]')).to_have_count(0)
        assert not errors, errors
        browser.close()
        print('Expansion card play, optional before-action payment, linked disabling, piece flights, hidden-panel fallback, reduced motion and mobile: passed')

if __name__ == '__main__':
    main(check, 'test/eurozone/five_player_fixture.clj')
