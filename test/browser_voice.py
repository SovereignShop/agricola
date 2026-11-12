"""Speech API is simulated; game commands, revisions, persistence and relay are real."""
import os
from playwright.sync_api import sync_playwright, expect
from browser_smoke import main

SPEECH = """
window.SpeechRecognition = class {
  start() { window.voiceRecognizer = this; }
  abort() { if (this.onend) this.onend(); }
  emit(text) {
    const result = [{transcript:text}]; result.isFinal = true;
    this.onresult({resultIndex:0, results:[result]});
    this.onend();
  }
};
"""


def check(base, table):
    with sync_playwright() as pw:
        options = {"headless": True}
        if os.environ.get("EUROZONE_BROWSER"):
            options["executable_path"] = os.environ["EUROZONE_BROWSER"]
        browser = pw.chromium.launch(**options)
        context = browser.new_context(viewport={"width": 1440, "height": 1000})
        context.add_init_script("sessionStorage.setItem('eurozone-session', '" + "a" * 64 + "');")
        context.add_init_script(SPEECH)
        page = context.new_page()
        errors = []
        page.on("pageerror", lambda error: errors.append(str(error)))
        page.goto(base + "/#" + table)
        page.get_by_role("button", name="Voice commands", exact=True).click()
        panel = page.get_by_role("region", name="Voice commands")
        revision = page.locator(".game").get_attribute("data-revision")

        def say(text):
            page.evaluate("window.voiceRecognizer = null")
            panel.get_by_role("button", name="Speak", exact=True).click()
            page.wait_for_function("window.voiceRecognizer !== null")
            page.evaluate("text => window.voiceRecognizer.emit(text)", text)

        say("take wood")
        expect(page.locator(".game")).not_to_have_attribute("data-revision", revision)
        revision = page.locator(".game").get_attribute("data-revision")
        say("take wood")
        expect(panel.get_by_role("status")).to_contain_text("No matching available space")
        say("cancel")
        expect(panel.get_by_role("status")).to_contain_text("Cancelled")
        expect(page.locator(".game")).to_have_attribute("data-revision", revision)

        # Start listening, then move through another authenticated browser connection.
        panel.get_by_role("button", name="Speak", exact=True).click()
        other = context.new_page()
        other.goto(base + "/#" + table)
        other.get_by_role("button", name="Voice commands", exact=True).click()
        other.get_by_label("Or type a command", exact=True).fill("take grain")
        other.get_by_role("button", name="Use command", exact=True).click()
        expect(page.locator(".game")).not_to_have_attribute("data-revision", revision)
        revision = page.locator(".game").get_attribute("data-revision")
        page.evaluate("window.voiceRecognizer.emit('fishing')")
        expect(panel.get_by_role("status")).to_contain_text("controls changed")
        expect(page.locator(".game")).to_have_attribute("data-revision", revision)
        other.close()

        panel.get_by_role("button", name="Speak", exact=True).click()
        page.evaluate("window.voiceRecognizer.onerror({error:'not-allowed'}); window.voiceRecognizer.onend();")
        expect(panel.get_by_role("status")).to_contain_text("Microphone access was denied")

        # Browser speech fallback restarts between phrases without another gesture.
        panel.get_by_role("button", name="Start continuous listening").click()
        page.wait_for_function("window.voiceRecognizer.continuous === true")
        page.evaluate("window.previousRecognizer = window.voiceRecognizer; window.voiceRecognizer.onspeechstart(); window.voiceRecognizer.emit('Table menu')")
        expect(page.get_by_role("dialog", name="Table menu", exact=True)).to_be_visible()
        page.wait_for_function("window.voiceRecognizer !== window.previousRecognizer")
        page.evaluate("window.previousRecognizer = window.voiceRecognizer; window.voiceRecognizer.onspeechstart(); window.voiceRecognizer.emit('Close dialog')")
        expect(page.get_by_role("dialog")).to_have_count(0)
        page.wait_for_function("window.voiceRecognizer !== window.previousRecognizer")
        page.evaluate("window.voiceRecognizer.onspeechstart(); window.voiceRecognizer.emit('stop listening')")
        expect(page.get_by_role("button", name="Stop listening", exact=True)).to_have_count(0)
        expect(panel.locator('[data-microphone]')).to_have_attribute('data-microphone', 'off')
        panel.get_by_role("button", name="Start continuous listening").click()
        page.wait_for_function("window.voiceRecognizer.continuous === true")
        page.evaluate("window.voiceRecognizer.onerror({error:'network'})")
        expect(panel.get_by_role("status")).to_contain_text("speech service stopped")
        expect(page.get_by_role("button", name="Stop listening", exact=True)).to_have_count(0)

        # Exercise the actual pairing relay with an Activity-style external-link opener.
        page.evaluate("""window.EUROZONE_ACTIVITY = true;
          window.EUROZONE_OPEN_VOICE = url => { window.voiceLink = url; return Promise.resolve({opened:true}); };""")
        panel.get_by_role("button", name="Close voice commands").click()
        page.get_by_role("button", name="Voice commands", exact=True).click()
        panel.get_by_role("button", name="Open voice companion").click()
        page.wait_for_function("typeof window.voiceLink === 'string'")
        companion = context.new_page()
        companion.goto(page.evaluate("window.voiceLink"))
        expect(companion.get_by_role("button", name="Send command")).to_be_enabled()
        assert "#" not in companion.url, "Pairing secret must be removed from the displayed URL"
        companion.get_by_label("Or type a command").fill("take reed")
        companion.get_by_role("button", name="Send command").click()
        expect(page.locator(".game")).not_to_have_attribute("data-revision", revision)
        revision = page.locator(".game").get_attribute("data-revision")
        page.set_viewport_size({"width": 390, "height": 844})
        assert page.evaluate("document.documentElement.scrollWidth <= innerWidth")
        page.screenshot(path="/tmp/eurozone-voice-mobile.png", full_page=True)
        panel.get_by_role("button", name="Close voice commands").click()
        expect(companion.get_by_role("status")).to_contain_text("disconnected")
        companion.close()
        page.reload()
        expect(page.locator(".game")).to_have_attribute("data-revision", revision)
        observer = browser.new_page()
        observer.goto(base + "/#" + table)
        expect(observer.locator(".game")).to_be_visible()
        expect(observer.get_by_role("button", name="Voice commands", exact=True)).to_have_count(0)
        assert not errors, errors
        browser.close()
        print("Voice: real moves, cancel, occupied/stale rejection, denied mic, companion relay, persistence, observer isolation and mobile passed.")


if __name__ == "__main__":
    main(check, "test/eurozone/voice_browser_fixture.clj")
