"""Real game/relay/local companion with a simulated model. No real plan usage."""
import json
import os
import sys
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import quote

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scripts/chatgpt'))
from connect import Connections, handler_for
from test_connect import Provider
from playwright.sync_api import sync_playwright, expect
from browser_smoke import main


class GameProvider(Provider):
    def __init__(self):
        super().__init__()
        self.requests = []
        self.waiting = threading.Event()
        self.release = threading.Event()

    def interpret(self, token, model, packet):
        self.requests.append((token, model, packet))
        text = packet['text']; context = packet['context']; actions = context['actions']
        scope = context.get('view', {}).get('scope', '')
        def choose(test, more=False, low=False, value=None):
            found = next((a for a in actions if test(a)), None)
            if found is None:
                return {'kind':'answer', 'message':'TEST missing control: ' + text + ' in ' + scope}
            return {'kind':'action', 'space':found['space'], 'message':'Using ' + found['name'],
                    'value':value, 'confidence':'low' if low else 'high', 'continue':more}
        def named(part, more=False, low=False):
            return choose(lambda a: part.lower() in a['name'].lower(), more, low)
        if text == 'wait for my choice':
            self.waiting.set(); self.release.wait(15)
            return named('Second player player view')
        if text == 'can I afford another room?':
            return {'kind':'answer', 'message':'You need more building materials for another room.'}
        if text == 'invent a move':
            return {'kind':'action', 'space':'not-a-real-space', 'message':'Bad', 'confidence':'high', 'continue':False}
        if text == 'get building materials' or text == 'uncertain materials':
            if scope != 'Game board': return named('Place family member')
            return choose(lambda a: a.get('board_space') == 'wood', True, text == 'uncertain materials')
        if text == 'prepare a field in the top right':
            if scope == 'Game board': return choose(lambda a: a.get('board_space') == 'plow', True)
            cell = next((a for a in actions if 'row 1, column 5' in a['name']), None)
            if cell and cell.get('selected') != 'true': return named('row 1, column 5', True)
            return named('Place family member')
        if text == 'show Second player farm':
            if scope != 'Game board': return named('Close dialog', True)
            return named('Second player player view')
        if text == 'pull up Second player played improvements':
            return choose(lambda a: 'Open Second player' in a['name'] and 'improvements' in a['name'])
        if text == 'close menu': return named('Close dialog')
        if text == 'use Benefactor with a sheep for the room above my house':
            if scope == 'Game board' or scope.startswith('Played'): return named('Benefactor', True)
            sheep = next((a for a in actions if 'Sheep' in a['name'] and 'Wooden room' in a['name']), None)
            if sheep and sheep.get('selected') != 'true': return named('Sheep', True)
            cell = next((a for a in actions if 'row 1, column 1' in a['name']), None)
            if cell and cell.get('selected') != 'true': return named('row 1, column 1', True)
            return named('Use card ability')
        if text == 'enclose the upper left farm cell':
            if scope == 'Game board': return choose(lambda a: a.get('board_space') == 'fences', True)
            for edge in ['h:0:0', 'h:1:0', 'v:0:0', 'v:0:1']:
                if any(a['name'].startswith('Fence ' + edge) and a['selected'] != 'true' for a in actions):
                    return named('Fence ' + edge, True)
            return named('Place family member')
        if text.startswith('set Lemon Trader exchanges to '):
            if scope == 'Game board' or scope.startswith('Played'): return named('Lemon Trader', True)
            return choose(lambda a: a['mode'] == 'input' and 'Times' in a['name'],
                          value='3' if text.endswith('three') else '2')
        if text == 'convert one grain to food':
            if scope == 'Game board':
                return named('Farm actions', True) if any(a['name'] == 'Farm actions' for a in actions) else named('Voice tester player view', True)
            summary = next((a for a in actions if a['name'].startswith('Convert goods to food')), None)
            if summary is None: return named('Close dialog', True)
            if summary['selected'] != 'true': return named('Convert goods to food', True)
            if not any('Add Grain conversion' in a['name'] for a in context.get('progress', [])):
                return named('Add Grain conversion', True)
            return named('Convert to food')
        if text == 'skip this effect': return named('Skip this effect')
        return {'kind':'answer','message':'Please describe the requested control.'}


def check(base, table):
    tables = json.loads(table)
    table = tables['normal']
    with tempfile.TemporaryDirectory(prefix='eurozone-assistant-test-') as directory, sync_playwright() as pw:
        provider = GameProvider()
        connections = Connections(directory, provider)
        for identity in ('alice', 'bob'):
            connections.data['profiles'][identity] = {'client_id': 'oaiapp_' + identity, 'subject': identity,
                'email': identity + '@example.invalid', 'id_token': 'fake-identity',
                'access_token': identity + '-secret', 'expires_at': time.time() + 3600,
                'scopes': ['chatgpt.tokens.use.direct']}
        server = ThreadingHTTPServer(('127.0.0.1', 0), BaseHTTPRequestHandler)
        origin = f'http://127.0.0.1:{server.server_port}'
        server.RequestHandlerClass = handler_for(connections, origin)
        threading.Thread(target=server.serve_forever, daemon=True).start()
        options = {'headless': True}
        if os.environ.get('EUROZONE_BROWSER'):
            options['executable_path'] = os.environ['EUROZONE_BROWSER']
        browser = pw.chromium.launch(**options)
        try:
            context = browser.new_context(viewport={'width': 1200, 'height': 900})
            context.add_init_script("sessionStorage.setItem('eurozone-session', '" + 'a' * 64 + "')")
            page = context.new_page()
            local = context.new_page()
            errors = []
            for tab in (page, local):
                tab.on('pageerror', lambda error: errors.append(str(error)))
            page.goto(base + '/#' + table)
            page.get_by_role('button', name='Voice commands', exact=True).click()
            panel = page.get_by_role('region', name='Voice commands')

            def pair(identity):
                panel.get_by_role('button', name='Connect my ChatGPT', exact=True).click()
                field = panel.get_by_label('ChatGPT pairing link', exact=True)
                expect(field).to_be_visible()
                link = field.input_value()
                local.goto(origin + '/#' + quote(link, safe=''))
                expect(local.get_by_label('Game pairing link')).to_have_value(link)
                expect(local).to_have_url(origin + '/')
                local.locator('article').filter(has_text=identity + '@example.invalid').get_by_role('button', name='Check available models').click()
                local.get_by_role('button', name='Use Test model with my game').click()
                expect(panel.get_by_text('ChatGPT connected · Test model', exact=True)).to_be_visible()
                page.bring_to_front()

            def say(text):
                panel.get_by_label('Or type a command', exact=True).fill(text)
                panel.get_by_role('button', name='Use command', exact=True).click()

            pair('alice')
            assert not provider.requests, 'Pairing must not consume inference'
            revision = page.locator('.game').get_attribute('data-revision')
            say('uncertain materials')
            expect(panel.get_by_role('button', name='Confirm Forest:', exact=False)).to_be_visible()
            expect(page.locator('.game')).to_have_attribute('data-revision', revision)
            say('cancel')
            expect(panel.get_by_role('status')).to_contain_text('Cancelled')
            say('can I afford another room?')
            expect(panel.get_by_role('status')).to_contain_text('need more building materials')
            say('invent a move')
            expect(panel.get_by_role('status')).to_contain_text('unavailable action')
            say('get building materials')
            expect(page.locator('.game')).not_to_have_attribute('data-revision', revision, timeout=15000)
            revision = page.locator('.game').get_attribute('data-revision')
            assert all(token == 'alice-secret' for token, _, _ in provider.requests)
            assert any(a['space'] == 'wood' and a.get('gain') for a in provider.requests[0][2]['context']['board'])
            say('prepare a field in the top right')
            expect(page.locator('.game')).not_to_have_attribute('data-revision', revision, timeout=15000)
            revision = page.locator('.game').get_attribute('data-revision')
            say('show Second player farm')
            expect(page.get_by_role('button', name='Second player player view', exact=True)).to_have_attribute('aria-pressed', 'true', timeout=15000)
            expect(page.locator('.game')).to_have_attribute('data-revision', revision)
            say('pull up Second player played improvements')
            expect(page.get_by_role('dialog')).to_contain_text('Played · improvements')
            # Voice is both clickable and focusable above the actual dialog backdrop.
            field = panel.get_by_label('Or type a command')
            field.fill('close menu'); expect(field).to_be_focused()
            expect(panel.get_by_role('button', name='Use command')).to_be_enabled()
            page.keyboard.press('Tab')
            expect(panel.get_by_role('button', name='Use command')).to_be_focused()
            page.keyboard.press('Tab')
            expect(page.get_by_role('dialog').get_by_role('button', name='Close dialog')).to_be_focused()
            field.click()
            box = field.bounding_box()
            assert page.evaluate('p => !!document.elementFromPoint(p.x,p.y).closest(".voice-controls")',
                                 {'x':box['x']+10, 'y':box['y']+10})
            assert all('Second player' not in a['name'] or 'improvements' in a['name'].lower()
                       for a in page.evaluate('EurozoneActions.snapshot().actions'))
            say('close menu')
            expect(page.get_by_role('dialog')).to_have_count(0)
            say('wait for my choice')
            assert provider.waiting.wait(5)
            panel.get_by_role('button', name='Cancel ChatGPT request').click()
            provider.release.set()
            expect(panel.get_by_role('status')).to_contain_text('Cancelled')
            page.wait_for_timeout(400)
            # A changed popup invalidates an outstanding request even without a new revision.
            provider.waiting.clear(); provider.release.clear()
            say('wait for my choice')
            assert provider.waiting.wait(5)
            page.get_by_role('button', name='Table menu', exact=True).click()
            provider.release.set()
            expect(panel.get_by_role('status')).to_contain_text('changed')
            expect(page.locator('.game')).to_have_attribute('data-revision', revision)
            say('close menu')
            expect(page.get_by_role('dialog')).to_have_count(0)
            panel.get_by_role('button', name='Disconnect ChatGPT').click()
            pair('bob')
            say('show Second player farm')
            expect(page.get_by_role('button', name='Second player player view', exact=True)).to_have_attribute('aria-pressed', 'true', timeout=15000)
            expect(panel.get_by_role('status')).to_contain_text('Using Second player player view', timeout=15000)
            assert provider.requests[-1][0] == 'bob-secret'
            assert not provider.requests[-1][2]['context']['history']

            # The prepared card goes through its real animal and spatial controls.
            page.goto(base + '/#' + tables['card'])
            page.get_by_role('button', name='Voice commands', exact=True).click()
            pair('alice')
            revision = page.locator('.game').get_attribute('data-revision')
            say('use Benefactor with a sheep for the room above my house')
            expect(page.locator('.game')).not_to_have_attribute('data-revision', revision, timeout=20000)
            revision = page.locator('.game').get_attribute('data-revision')
            page.set_viewport_size({'width':390,'height':844})
            assert page.evaluate('document.documentElement.scrollWidth <= innerWidth')
            panel.get_by_role('button', name='Minimize voice commands').click()
            expect(panel).to_have_count(0)
            page.get_by_role('button', name='Voice commands', exact=True).click()
            expect(panel.get_by_role('button', name='Disconnect ChatGPT')).to_be_visible()
            page.screenshot(path='/tmp/eurozone-assistant-mobile.png', full_page=True)
            page.reload()
            expect(page.locator('.game')).to_have_attribute('data-revision', revision)
            page.set_viewport_size({'width':1200,'height':900})
            page.get_by_role('button', name='Table menu', exact=True).click()
            dialog = page.get_by_role('dialog', name='Table menu', exact=True)
            with page.expect_download() as download:
                dialog.get_by_role('button', name='Save recording', exact=True).click()
            recording = Path(download.value.path()).read_text()
            assert ':room-used? true' in recording and ':saved-state-hash' in recording
            dialog.get_by_label('Open game recording').set_input_files(
                {'name':'voice-benefactor.edn','mimeType':'application/edn','buffer':recording.encode()})
            expect(page.locator('.replay-controls')).to_be_visible()
            page.get_by_role('button', name='Last', exact=True).click()
            expect(page.get_by_role('alert')).to_have_count(0)

            page.get_by_role('button', name='Close playback', exact=True).click()
            page.goto(base + '/#' + tables['optional'])
            page.get_by_role('button', name='Voice commands', exact=True).click()
            pair('alice')
            revision = page.locator('.game').get_attribute('data-revision')
            say('skip this effect')
            expect(page.locator('.game')).not_to_have_attribute('data-revision', revision)
            page.goto(base + '/#' + tables['fences'])
            page.get_by_role('button', name='Voice commands', exact=True).click()
            pair('alice')
            revision = page.locator('.game').get_attribute('data-revision')
            say('enclose the upper left farm cell')
            expect(page.locator('.game')).not_to_have_attribute('data-revision', revision, timeout=20000)
            assert sum('Fence ' in step['name'] for _, _, packet in provider.requests
                       if packet['text'] == 'enclose the upper left farm cell'
                       for step in packet['context'].get('progress', [])[-1:]) == 4
            page.goto(base + '/#' + tables['quantity'])
            page.get_by_role('button', name='Voice commands', exact=True).click()
            pair('alice')
            revision = page.locator('.game').get_attribute('data-revision')
            say('set Lemon Trader exchanges to two')
            expect(page.get_by_label('Times', exact=True)).to_have_value('2', timeout=20000)
            say('set Lemon Trader exchanges to three')
            expect(panel.get_by_role('status')).to_contain_text('outside the available range')
            expect(page.get_by_label('Times', exact=True)).to_have_value('2')
            expect(page.locator('.game')).to_have_attribute('data-revision', revision)
            say('convert one grain to food')
            expect(page.locator('.game')).not_to_have_attribute('data-revision', revision, timeout=20000)
            assert not errors, errors
            local.locator('article').filter(has_text='alice@example.invalid').get_by_role('button', name='Sign out', exact=True).click()
            expect(panel.get_by_role('button', name='Connect my ChatGPT')).to_be_visible()
            print('Assistant v2: automatic moves, low-confidence clarification, full native controls, farm/view navigation, modal overlay, card spatial ability, optional decline, persistence/playback, isolation and stale/cancel rejection passed.')
        except Exception:
            print('Voice status:', panel.inner_text(), flush=True)
            print('Controls:', page.evaluate('EurozoneActions.snapshot()'), flush=True)
            print('Recent steps:', [(p['text'], p['context'].get('progress', [])[-1:]) for _, _, p in provider.requests[-8:]], flush=True)
            raise
        finally:
            provider.release.set()
            browser.close()
            server.shutdown(); server.server_close(); connections.close()


if __name__ == '__main__':
    main(check, 'test/eurozone/voice_controls_fixture.clj')
