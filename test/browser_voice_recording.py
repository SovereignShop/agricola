"""Real MediaRecorder → local Whisper → game move, with synthesized test audio."""
import os
from pathlib import Path
import subprocess
import tempfile
from playwright.sync_api import sync_playwright, expect
from browser_smoke import main


def check(base, table):
    with tempfile.TemporaryDirectory(prefix='eurozone-voice-audio-') as directory, sync_playwright() as pw:
        wav = Path(directory) / 'command.wav'
        subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', "flite=text='take wood':voice=slt",
                        '-ar', '16000', '-ac', '1', str(wav)], check=True)
        options = {'headless': True}
        if os.environ.get('EUROZONE_BROWSER'):
            options['executable_path'] = os.environ['EUROZONE_BROWSER']
        browser = pw.chromium.launch(**options)
        context = browser.new_context(viewport={'width': 1200, 'height': 900})
        context.add_init_script("sessionStorage.setItem('eurozone-session','" + 'a' * 64 + "')")
        context.add_init_script("""
          window.SpeechRecognition = class { constructor() { throw new Error('Browser speech must not be used'); } };
          navigator.mediaDevices.getUserMedia = async () => {
            const ctx = new AudioContext(); await ctx.resume();
            const buffer = await ctx.decodeAudioData(await (await fetch('/__voice_audio')).arrayBuffer());
            const source = ctx.createBufferSource(); source.buffer = buffer;
            const destination = ctx.createMediaStreamDestination(); source.connect(destination);
            window.voiceSampleDone = false;
            source.onended = () => { window.voiceSampleDone = true; };
            source.start(ctx.currentTime + 0.1);
            window.voiceAudioContext = ctx;
            window.voiceAudioStream = destination.stream;
            return destination.stream;
          };
        """)
        page = context.new_page()
        errors = []
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.on('response', lambda response: print('Transcription:', response.status, response.text(), flush=True)
                if response.url.endswith('/voice/transcribe') else None)
        page.route('**/__voice_audio', lambda route: route.fulfill(path=str(wav), content_type='audio/wav'))
        page.goto(base + '/#' + table)
        page.get_by_role('button', name='Voice commands', exact=True).click()
        panel = page.get_by_role('region', name='Voice commands')
        revision = page.locator('.game').get_attribute('data-revision')
        page.get_by_role('button', name='Speak', exact=True).click()
        page.wait_for_function('window.voiceSampleDone === true')
        page.get_by_role('button', name='Finish recording', exact=True).click()
        expect(page.locator('.game')).not_to_have_attribute('data-revision', revision, timeout=60000)
        assert page.evaluate("window.voiceAudioStream.getTracks().every(t => t.readyState === 'ended')")
        await_revision = page.locator('.game').get_attribute('data-revision')
        # Cancelling an active recording must not upload or emit a late command.
        page.get_by_role('button', name='Speak', exact=True).click()
        expect(panel.get_by_role('status')).to_contain_text('Recording')
        page.get_by_role('button', name='Cancel recording', exact=True).click()
        expect(page.get_by_role('button', name='Speak', exact=True)).to_be_enabled()
        expect(page.locator('.game')).to_have_attribute('data-revision', await_revision)
        # The external microphone page uploads using only its live pairing capability.
        subprocess.run(['ffmpeg', '-v', 'error', '-f', 'lavfi', '-i', "flite=text='take reed':voice=slt",
                        '-ar', '16000', '-ac', '1', '-y', str(wav)], check=True)
        page.evaluate("""window.EUROZONE_ACTIVITY = true;
          window.EUROZONE_OPEN_VOICE = url => { window.voiceLink = url; return Promise.resolve({opened:true}); };""")
        panel.get_by_role('button', name='Close voice commands').click()
        page.get_by_role('button', name='Voice commands', exact=True).click()
        panel.get_by_role('button', name='Open voice companion').click()
        page.wait_for_function("typeof window.voiceLink === 'string'")
        companion = context.new_page()
        companion.on('pageerror', lambda error: errors.append(str(error)))
        companion.on('response', lambda response: print('Companion transcription:', response.status, response.text(), flush=True)
                     if response.url.endswith('/voice/transcribe') else None)
        companion.route('**/__voice_audio', lambda route: route.fulfill(path=str(wav), content_type='audio/wav'))
        companion.goto(page.evaluate('window.voiceLink'))
        expect(companion.get_by_role('button', name='Speak', exact=True)).to_be_enabled()
        companion.get_by_role('button', name='Speak', exact=True).click()
        companion.wait_for_function('window.voiceSampleDone === true')
        companion.get_by_role('button', name='Finish recording', exact=True).click()
        expect(page.locator('.game')).not_to_have_attribute('data-revision', await_revision, timeout=60000)
        companion.close()
        assert not errors, errors
        page.screenshot(path='/tmp/eurozone-local-voice.png', full_page=True)
        browser.close()
        print('Real audio encoding, local transcription, automatic moves, microphone cleanup, cancellation and paired audio upload passed.')


if __name__ == '__main__':
    if not os.environ.get('EUROZONE_TRANSCRIBE_URL'):
        raise SystemExit('Start the local worker and set EUROZONE_TRANSCRIBE_URL first.')
    main(check, 'test/eurozone/voice_browser_fixture.clj')
