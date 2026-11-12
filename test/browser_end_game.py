"""Finished local, server and peer games can be saved and replayed by participants."""
import json
import os
from urllib.parse import urlparse, parse_qs
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main
from browser_bridge import TRANSPORT_PROBE


def check(base, fixture):
    fixture = json.loads(fixture)
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ['EUROZONE_BROWSER'])
        errors = []

        def page(token):
            context = browser.new_context(viewport={'width':1440,'height':1000})
            context.add_init_script('sessionStorage.setItem("eurozone-session", "' + token*64 + '")')
            context.add_init_script(TRANSPORT_PROBE)
            p = context.new_page()
            p.on('pageerror', lambda e: errors.append(str(e)))
            return p

        def save_unfinished(p):
            revision = p.locator('.game').get_attribute('data-revision')
            p.get_by_role('button', name='Table menu', exact=True).click()
            dialog = p.get_by_role('dialog', name='Table menu', exact=True)
            with p.expect_download() as download:
                dialog.get_by_role('button', name='Save recording', exact=True).click()
            text = open(download.value.path()).read()
            assert ':saved-state ' in text and ':saved-state-hash ' in text
            assert ':saved-state-hash "' + fixture['unfinished-hash'] + '"' in text
            assert ':record :result' not in text
            expect(p.locator('.game')).to_have_attribute('data-revision', revision)
            expect(p.get_by_role('alert')).to_have_count(0)
            dialog.get_by_role('button', name='Close dialog', exact=True).click()

        def review(p):
            dialog = p.get_by_role('dialog', name='Game complete', exact=True)
            expect(dialog).to_be_visible()
            revision = p.locator('.game').get_attribute('data-revision')
            with p.expect_download() as download:
                dialog.get_by_role('button', name='Save recording', exact=True).click()
            assert ':record :result' in open(download.value.path()).read()
            dialog.get_by_role('button', name='Replay game', exact=True).click()
            expect(p.locator('.replay-controls')).to_contain_text('Move 0 / 1')
            p.get_by_role('button', name='Last', exact=True).click()
            expect(p.locator('.score-table')).to_be_visible()
            expect(p.locator('.action-card:not([disabled])')).to_have_count(0)
            p.get_by_role('button', name='Close playback', exact=True).click()
            expect(dialog).to_be_visible()
            expect(p.locator('.game')).to_have_attribute('data-revision', revision)
            p.reload()
            expect(dialog.get_by_role('button', name='Replay game', exact=True)).to_be_visible()

        local = page('a'); local.goto(base+'/#'+fixture['local'])
        local.get_by_role('button', name='Complete breeding →', exact=True).click()
        review(local)
        local.set_viewport_size({'width':390,'height':844})
        expect(local.get_by_role('button',name='Save recording',exact=True)).to_be_in_viewport()
        expect(local.get_by_role('button',name='Replay game',exact=True)).to_be_in_viewport()
        local.set_viewport_size({'width':1440,'height':1000})
        local.screenshot(path='/tmp/eurozone-end-game.png', full_page=True)
        local.context.close()

        host, guest, observer = page('a'), page('b'), page('c')
        for p in [host, guest, observer]: p.goto(base+'/#'+fixture['online'])
        expect(host.get_by_role('button', name='Replay game', exact=True)).to_have_count(0)
        save_unfinished(host)
        guest.get_by_role('dialog').get_by_role('button', name='Close dialog', exact=True).click()
        guest.get_by_role('button', name='Table menu', exact=True).click()
        expect(guest.get_by_role('button', name='Save recording', exact=True)).to_have_count(0)
        guest.get_by_role('dialog', name='Table menu', exact=True).get_by_role('button', name='Close dialog', exact=True).click()
        guest.get_by_role('button', name='Go to my decision', exact=True).click()
        guest.get_by_role('button', name='Complete breeding →', exact=True).click()
        review(guest); review(host)
        expect(observer.get_by_role('dialog', name='Game complete')).to_be_visible()
        expect(observer.get_by_role('button', name='Save recording', exact=True)).to_have_count(0)
        expect(observer.get_by_role('button', name='Replay game', exact=True)).to_have_count(0)
        for p in [host, guest, observer]: p.context.close()

        # Exercise the same export request through the Discord-style peer relay.
        host, guest = page('a'), page('b')
        host.goto(base+'/?transport=relay')
        host.get_by_text('Connected', exact=True).wait_for()
        host.get_by_label('Your name', exact=True).fill('Ada')
        host.get_by_label('Table name').fill('Peer final harvest')
        host.get_by_role('button', name='Peer-hosted table', exact=True).click()
        host.get_by_label('Players expected').select_option('2')
        host.get_by_role('button', name='Create table →').click()
        link = host.get_by_label('Table invitation link').input_value()
        guest.goto(link)
        guest.get_by_placeholder('Your name').fill('Ben')
        guest.get_by_role('button', name='Take a seat', exact=True).click()
        guest.get_by_text('The host will start once everyone is ready.').wait_for()
        room = parse_qs(urlparse(link).query)['peer'][0]
        peer_save = fixture['peer'].replace('__ROOM__',room).replace('a'*64,host.evaluate('testPeerIdentity.self')).replace('b'*64,guest.evaluate('testPeerIdentity.self'))
        host.evaluate('([id,text])=>EurozonePeer.save(id,text)', [room, peer_save])
        host.reload()
        expect(host.locator('.game')).to_be_visible()
        host.set_viewport_size({'width':390,'height':844})
        save_unfinished(host)
        host.set_viewport_size({'width':1440,'height':1000})
        guest.get_by_role('dialog').get_by_role('button', name='Close dialog', exact=True).click()
        guest.get_by_role('button', name='Table menu', exact=True).click()
        expect(guest.get_by_role('button', name='Save recording', exact=True)).to_have_count(0)
        guest.get_by_role('dialog', name='Table menu', exact=True).get_by_role('button', name='Close dialog', exact=True).click()
        guest.get_by_role('button', name='Go to my decision', exact=True).click()
        guest.get_by_role('button', name='Complete breeding →', exact=True).click()
        review(guest); review(host)
        assert not errors, errors
        browser.close()
        print('End-game save/replay: local, online host/guest, peer relay, spectators, reload and read-only playback passed')


if __name__ == '__main__':
    main(check, 'test/eurozone/end_game_fixture.clj')
