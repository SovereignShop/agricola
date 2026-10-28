"""Real peer-hosted games: direct channels, relay, privacy, reconnect and host saves."""
import os
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main, choose_action, confirm_action

PROBE = '''() => {
 const RTC = window.RTCPeerConnection;
 window.peerConnections = [];
 window.RTCPeerConnection = class extends RTC { constructor(...args) { super(...args); window.peerConnections.push(this); } };
 window.coordinatorMessages = [];
 const WS = window.WebSocket;
 window.WebSocket = class extends WS { constructor(...args) { super(...args); const send = this.send.bind(this); this.send = data => { if (String(args[0]).includes('/peer')) window.coordinatorMessages.push(JSON.parse(data)); return send(data); }; } };
}'''

def check(base, _):
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ['EUROZONE_BROWSER'])
        errors = []
        for relay in [False, True]:
            contexts = [browser.new_context(viewport={'width': 1440, 'height': 1100}) for _ in range(3 if not relay else 2)]
            for c in contexts: c.add_init_script('(' + PROBE + ')()')
            pages = [c.new_page() for c in contexts]
            for p in pages: p.on('pageerror', lambda e: errors.append(str(e)))
            host = pages[0]
            host.goto(base + ('/?transport=relay' if relay else '/'))
            host.get_by_text('Connected', exact=True).wait_for()
            host.get_by_label('Your name', exact=True).fill('Ada')
            host.get_by_label('Table name').fill('Peer farm ' + str(relay))
            host.get_by_role('button', name='Peer-hosted table', exact=True).click()
            host.get_by_label('Players expected').select_option(str(len(pages)))
            host.get_by_label('Pre-game hand setup').select_option('random')
            host.get_by_role('button', name='Create table →').click()
            try:
                host.get_by_role('heading', name='Peer farm ' + str(relay)).wait_for(timeout=10000)
            except Exception:
                print('HOST BODY:', host.locator('body').inner_text()); print('JS ERRORS:',errors); raise
            link = host.get_by_label('Table invitation link').input_value()
            assert 'peer=' in link and 'key=' in link
            for i,p in enumerate(pages[1:],1):
                p.goto(link)
                p.get_by_placeholder('Your name').fill(['Ada','Ben','Cam'][i])
                p.get_by_role('button', name='Take a seat', exact=True).click()
                p.get_by_text('The host will start once everyone is ready.').wait_for()
            expect(host.get_by_role('button', name='Start the game →')).to_be_enabled()
            if not relay:
                for p in pages:
                    p.wait_for_function('peerConnections.some(p => p.connectionState === "connected")')
                host.wait_for_timeout(300)
            host.get_by_role('button', name='Start the game →').click()
            for p in pages:
                expect(p.get_by_role('heading', name='Agricola', exact=True)).to_be_visible()
                expect(p.locator('.hand-panel .hand-card')).to_have_count(7)
            # Each client sees only their own hand.
            hands = [set(p.locator('.hand-panel .hand-card > strong').all_text_contents()) for p in pages]
            assert all(not hands[i] & hands[j] for i in range(len(hands)) for j in range(i))
            for p in pages: p.evaluate('coordinatorMessages = []')
            actor = next(p for p in pages if p.get_by_role('button', name='Forest', exact=False).is_enabled())
            choose_action(actor,'Forest'); confirm_action(actor)
            for p in pages: expect(p.locator('[data-piece-key^="worker::wood:"]')).to_have_count(1)
            if not relay:
                assert all(not any(m['op']=='relay' for m in p.evaluate('coordinatorMessages')) for p in pages)
            else:
                assert any(any(m['op']=='relay' for m in p.evaluate('coordinatorMessages')) for p in pages)
                assert all(p.evaluate('peerConnections.length') == 0 for p in pages)
            # Reload the authoritative host: the durable browser save restores the same move.
            revision = host.locator('.game').get_attribute('data-revision')
            host.reload()
            expect(host.locator('.game')).to_have_attribute('data-revision',revision)
            expect(host.locator('[data-piece-key^="worker::wood:"]')).to_have_count(1)
            guest = pages[1]
            guest.reload()
            expect(guest.locator('.hand-panel .hand-card')).to_have_count(7)
            # Chat goes through authenticated host links, including Activity-compatible relay.
            host.get_by_placeholder('Say something…').fill('The host is back')
            host.get_by_role('button',name='Send',exact=True).click()
            expect(guest.get_by_text('The host is back',exact=True)).to_be_visible()
            host.close()
            expect(guest.locator('.connection')).to_contain_text('Waiting for the host')
            replacement = contexts[0].new_page(); replacement.goto(link)
            expect(replacement.locator('.game')).to_have_attribute('data-revision',revision)
            expect(guest.locator('.game')).to_have_attribute('data-revision',revision)
            observer = browser.new_context().new_page(); observer.goto(base)
            expect(observer.get_by_text('Connected',exact=True)).to_be_visible()
            expect(observer.get_by_role('button',name='Peer farm '+str(relay),exact=False)).to_have_count(0)
            observer.context.close()
            replacement.screenshot(path=f'/tmp/eurozone-peer-{relay}.png',full_page=True)
            for c in contexts: c.close()
            print(('Relay' if relay else 'Direct P2P') + ': private hands, moves, chat, host reload/return, and server-state isolation passed')
        # Five players on one machine, each with an independent browser-tab seat.
        context=browser.new_context(viewport={'width':1440,'height':1100})
        host=context.new_page();host.goto(base+'/?transport=relay')
        host.get_by_text('Connected',exact=True).wait_for()
        host.get_by_label('Your name',exact=True).fill('Draw host')
        host.get_by_label('Table name').fill('Five-player peer draw')
        host.get_by_role('button',name='Peer-hosted table',exact=True).click()
        host.get_by_label('Players expected').select_option('5')
        host.get_by_label('Pre-game hand setup').select_option('draw')
        host.get_by_role('button',name='Create table →').click()
        pages=[host]
        for i in range(4):
            with host.expect_popup() as popup:
                host.get_by_role('link',name='Open another player in a new tab ↗').click()
            guest=popup.value;pages.append(guest)
            guest.get_by_placeholder('Your name').fill('Player '+str(i+2))
            guest.get_by_role('button',name='Take a seat',exact=True).click()
            guest.get_by_text('The host will start once everyone is ready.').wait_for()
        for p in pages:p.on('pageerror',lambda e:errors.append(str(e)))
        assert len({p.evaluate('sessionStorage.getItem("eurozone-session")') for p in pages})==5
        host.get_by_role('button',name='Start draw →').click()
        for p in pages:expect(p.locator('.draw-packet .hand-card')).to_have_count(16)
        picks=0
        for _ in range(60):
            if host.get_by_role('heading',name='Agricola',exact=True).count():break
            for p in pages:
                expect(p.locator('.app')).to_have_attribute('aria-busy','false')
                cards=p.locator('.draw-packet > div .hand-grid').first.locator('.hand-card:not([disabled])')
                if cards.count():
                    cards.first.click()
                    revision=p.locator('.game').get_attribute('data-revision')
                    p.get_by_role('button',name='Keep card & pass left →').click()
                    expect(p.locator('.game')).not_to_have_attribute('data-revision',revision)
                    expect(p.locator('.toast')).to_have_count(0)
                    picks+=1
                    if picks==1:
                        expect(p.locator('.draw-kept .hand-card')).to_have_count(1)
                        expect(p.locator('.draw-waiting')).to_be_visible()
                        expect(pages[1].locator('.draw-kept .hand-card')).to_have_count(0)
                        p.screenshot(path='/tmp/eurozone-draw-kept.png',full_page=True)
                    if picks==5:
                        host.reload();expect(host.get_by_role('heading',name='The draw',exact=True)).to_be_visible()
            host.wait_for_timeout(50)
        assert picks==70,picks
        for p in pages:
            expect(p.get_by_role('heading',name='Make your move.')).to_be_visible()
            expect(p.locator('.hand-panel .hand-card')).to_have_count(7)
        host.get_by_role('button',name='Show all farms',exact=True).click()
        for i in range(5):expect(host.locator(f'[data-tile="farm-{i}"]')).to_be_visible()
        context.close()
        print('Five peer seats in one browser, 70 private draw picks, host recovery during draw, and all five farm panels: passed')
        assert not errors, errors
        browser.close()

if __name__ == '__main__': main(check)
