"""Always-on microphone: real AudioWorklet/VAD/WAV/Whisper and game/companion relay."""
import os
import subprocess
import tempfile
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
from browser_smoke import main

AUDIO = """
window.SpeechRecognition = class { constructor() { throw new Error('Expected local transcription'); } };
navigator.mediaDevices.getUserMedia = async () => {
  window.microphoneRequests = (window.microphoneRequests || 0) + 1;
  const ctx = new AudioContext(); await ctx.resume();
  const destination = ctx.createMediaStreamDestination();
  window.microphoneContext = ctx; window.microphoneStream = destination.stream;
  window.sayTone = seconds => {
    const oscillator = ctx.createOscillator(), gain = ctx.createGain();
    oscillator.frequency.value = 220; gain.gain.value = .15;
    oscillator.connect(gain); gain.connect(destination); oscillator.start(); oscillator.stop(ctx.currentTime + seconds);
    return new Promise(resolve => oscillator.onended = resolve);
  };
  window.sayAudio = async name => {
    const data = await (await fetch('/__speech/' + name)).arrayBuffer();
    const buffer = await ctx.decodeAudioData(data);
    const source = ctx.createBufferSource(); source.buffer = buffer; source.connect(destination);
    source.start();
    return new Promise(resolve => source.onended = resolve);
  };
  return destination.stream;
};
"""


def check(base, table):
    with tempfile.TemporaryDirectory(prefix='eurozone-continuous-') as temp, sync_playwright() as pw:
        for name, phrase in [('wood','take wood'), ('grain','take grain'), ('reed','take reed'), ('stop','stop listening')]:
            subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i',f"flite=text='{phrase}':voice=slt",
                            '-ar','16000','-ac','1',str(Path(temp)/(name+'.wav'))], check=True)
        opts = {'headless':True}
        if os.getenv('EUROZONE_BROWSER'): opts['executable_path'] = os.environ['EUROZONE_BROWSER']
        browser = pw.chromium.launch(**opts)
        context = browser.new_context(viewport={'width':1200,'height':900})
        context.add_init_script("sessionStorage.setItem('eurozone-session','" + 'a'*64 + "')")
        context.add_init_script(AUDIO)
        context.route('**/__speech/*', lambda route: route.fulfill(path=str(Path(temp)/(route.request.url.rsplit('/',1)[1]+'.wav')), content_type='audio/wav'))
        page = context.new_page(); errors = []; uploads = []
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.on('request', lambda req: uploads.append(req) if req.url.endswith('/voice/transcribe') else None)
        page.goto(base + '/#' + table)
        page.get_by_role('button', name='Voice commands', exact=True).click()
        panel = page.get_by_role('region', name='Voice commands')
        panel.get_by_role('button', name='Start continuous listening').click()
        expect(panel.locator('[data-microphone]')).to_contain_text('Listening —', timeout=15000)
        page.wait_for_timeout(1800)
        assert not uploads, 'Idle silence must not be uploaded'
        page.evaluate('void window.sayTone(9.5)')
        expect(panel.locator('[data-microphone]')).to_contain_text('too long', timeout=12000)
        page.wait_for_timeout(1000)
        assert not uploads, 'Overlong audio must be discarded, not transcribed as a partial command'
        for phrase in ['wood', 'grain']:
            revision = page.locator('.game').get_attribute('data-revision')
            page.evaluate('name => window.sayAudio(name)', phrase)
            expect(page.locator('.game')).not_to_have_attribute('data-revision', revision, timeout=60000)
            expect(panel.locator('[data-microphone]')).to_contain_text('Listening —')
            page.wait_for_timeout(500)  # End-of-utterance guard; no re-opening of the microphone.
        assert len(uploads) == 2
        assert page.evaluate('window.microphoneRequests') == 1
        panel.get_by_role('button', name='Minimize voice commands').click()
        expect(page.get_by_role('button', name='Stop listening', exact=True)).to_be_visible()
        page.set_viewport_size({'width':390, 'height':844})
        assert page.evaluate('document.documentElement.scrollWidth <= innerWidth')
        page.screenshot(path='/tmp/eurozone-continuous-mobile.png')
        page.set_viewport_size({'width':1200, 'height':900})
        page.get_by_role('button', name='Voice commands', exact=True).click()
        # A manual menu change while an utterance is in flight must reject the old context.
        revision = page.locator('.game').get_attribute('data-revision')
        page.evaluate("void window.sayAudio('reed')")
        expect(panel.locator('[data-microphone]')).to_contain_text('Hearing you')
        page.get_by_role('button', name='Table menu', exact=True).click()
        expect(panel.get_by_role('status')).to_contain_text('changed', timeout=60000)
        expect(page.locator('.game')).to_have_attribute('data-revision', revision)
        page.get_by_role('button', name='Close dialog', exact=True).click()
        page.wait_for_timeout(500)
        page.evaluate("window.sayAudio('stop')")
        expect(page.get_by_role('button', name='Stop listening', exact=True)).to_have_count(0, timeout=60000)
        assert page.evaluate("window.microphoneStream.getTracks().every(t => t.readyState === 'ended')")
        assert len(uploads) == 4
        # Stop cancels an in-flight transcription, including a late action response.
        def delayed(route):
            page.get_by_role('button', name='Stop listening', exact=True).click()
            route.fulfill(json={'text':'take reed'})
        page.route('**/voice/transcribe', delayed)
        panel.get_by_role('button', name='Start continuous listening').click()
        expect(panel.locator('[data-microphone]')).to_contain_text('Listening —')
        page.evaluate("window.sayAudio('reed')")
        expect(page.get_by_role('button', name='Stop listening', exact=True)).to_have_count(0, timeout=10000)
        expect(page.locator('.game')).to_have_attribute('data-revision', revision)
        assert page.evaluate("window.microphoneStream.getTracks().every(t => t.readyState === 'ended')")
        page.unroute('**/voice/transcribe', delayed)
        # External microphone mode keeps listening across game revision changes.
        page.evaluate("""window.EUROZONE_ACTIVITY = true;
          window.EUROZONE_OPEN_VOICE = url => { window.voiceLink = url; return Promise.resolve({opened:true}); };""")
        panel.get_by_role('button', name='Close voice commands').click()
        page.get_by_role('button', name='Voice commands', exact=True).click()
        panel.get_by_role('button', name='Open voice companion').click()
        page.wait_for_function("typeof window.voiceLink === 'string'")
        companion = context.new_page()
        companion.on('pageerror', lambda error: errors.append(str(error)))
        companion.goto(page.evaluate('window.voiceLink'))
        companion.get_by_role('button', name='Start continuous listening').click()
        expect(companion.locator('#microphone')).to_contain_text('Listening —')
        companion.evaluate("window.sayAudio('reed')")
        expect(page.locator('.game')).not_to_have_attribute('data-revision', revision, timeout=60000)
        expect(companion.get_by_role('button', name='Stop listening', exact=True)).to_be_visible()
        companion.wait_for_timeout(500)
        companion.evaluate("window.sayAudio('stop')")
        expect(companion.get_by_role('button', name='Start continuous listening')).to_be_visible(timeout=60000)
        assert companion.evaluate("window.microphoneStream.getTracks().every(t => t.readyState === 'ended')")
        companion.get_by_role('button', name='Start continuous listening').click()
        expect(companion.locator('#microphone')).to_contain_text('Listening —')
        panel.get_by_role('button', name='Close voice commands').click()
        expect(companion.get_by_role('status')).to_contain_text('disconnected')
        assert companion.evaluate("window.microphoneStream.getTracks().every(t => t.readyState === 'ended')")
        # Permission failure and permission resolving after Stop both leave the mode off.
        page.reload()
        page.get_by_role('button', name='Voice commands', exact=True).click()
        page.evaluate("() => { window.originalMicrophone = navigator.mediaDevices.getUserMedia; navigator.mediaDevices.getUserMedia = async () => { throw new DOMException('denied', 'NotAllowedError'); }; }")
        panel.get_by_role('button', name='Start continuous listening').click()
        expect(panel.get_by_role('status')).to_contain_text('access was denied')
        expect(page.get_by_role('button', name='Stop listening', exact=True)).to_have_count(0)
        page.evaluate("() => { navigator.mediaDevices.getUserMedia = () => new Promise(resolve => window.releaseMicrophone = resolve); }")
        panel.get_by_role('button', name='Start continuous listening').click()
        page.wait_for_function("typeof window.releaseMicrophone === 'function'")
        page.get_by_role('button', name='Stop listening', exact=True).click()
        page.evaluate("window.originalMicrophone().then(stream => window.releaseMicrophone(stream))")
        page.wait_for_function("window.microphoneStream.getTracks().every(t => t.readyState === 'ended')")
        assert not errors, errors
        page.screenshot(path='/tmp/eurozone-continuous-voice.png')
        browser.close()
        print('Continuous listening: silent idle, repeated utterances on one mic, real VAD/WAV/Whisper, fresh/stale context, minimize, spoken stop, late cancellation and companion revision/disconnect passed.')


if __name__ == '__main__':
    if not os.getenv('EUROZONE_TRANSCRIBE_URL'): raise SystemExit('Set EUROZONE_TRANSCRIBE_URL to the local worker.')
    main(check, 'test/eurozone/voice_browser_fixture.clj')
