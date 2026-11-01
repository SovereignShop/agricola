"""The bundled Discord Activity opens hosted recordings without leaving its iframe."""
import json
import os
import subprocess
from pathlib import Path
from playwright.sync_api import expect, sync_playwright
import browser_discord as discord
from browser_smoke import ROOT


def check(base):
    fixture = json.loads(subprocess.check_output(['clojure','-M','test/eurozone/end_game_fixture.clj'],cwd=ROOT,text=True).strip().splitlines()[-1])
    game = {'seed':1,'players':2,'mode':'random','extra':0,'policies':['deployed','deployed'],
            'file':'game-1.edn','verified':True,'status':'complete','moves':1,'scores':[-20,-20],
            'begging':[0,0],'played':[],'dealt':[],'effects':[]}
    manifest = {'games':[game],'completed':1,'target':1,'finished':True,'worker_failures':[]}
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ['EUROZONE_BROWSER'])
        context = browser.new_context(viewport={'width':1440,'height':1000})
        parent = discord.PARENT.replace('USER',json.dumps({'id':'111','username':'Ada'}))
        context.route(base+'/discord-test-parent',lambda r:r.fulfill(content_type='text/html',body=parent))
        context.route('**/recordings/review-*/index.html*',lambda r:r.fulfill(content_type='text/html',body=(ROOT/'scripts/review_index.html').read_text()))
        context.route('**/recordings/review-*/manifest.json',lambda r:r.fulfill(json=manifest))
        context.route('**/recordings/review-*/game-1.edn',lambda r:r.fulfill(content_type='application/edn',body=fixture['recording']))
        context.add_init_script('window.testDocumentIdentity=crypto.randomUUID()')
        page=context.new_page(); errors=[]; recording_requests=[]
        page.on('pageerror',lambda e:errors.append(str(e)))
        context.on('request',lambda r:recording_requests.append(r.url) if '/recordings/' in r.url else None)
        page.goto(base+'/discord-test-parent')
        app=page.frame_locator('iframe').first
        expect(app.get_by_role('heading',name='Activity tables',exact=True)).to_be_visible()
        identity=page.frames[1].evaluate('testDocumentIdentity')
        assert not recording_requests, 'Recordings must not load during normal Activity startup'
        app.get_by_role('button',name='Recorded games',exact=True).click()
        library=app.frame_locator('iframe[title="Recorded game collections"]')
        library.get_by_role('link',name='Browse 1,000 games →',exact=True).click()
        expect(library.locator('#games tr')).to_have_count(1)
        # A different frame cannot make the Activity open an arbitrary recording.
        page.evaluate("document.querySelector('iframe').contentWindow.postMessage({type:'eurozone:replay',path:'/recordings/review-20261031-1000/game-1.edn'},location.origin)")
        expect(app.locator('.replay-view')).to_have_count(0)
        library.get_by_role('link',name='Watch',exact=True).click()
        expect(app.locator('.replay-controls')).to_contain_text('Move 0 / 1')
        app.get_by_role('button',name='Last',exact=True).click()
        expect(app.locator('.score-table')).to_be_visible()
        assert len(context.pages)==1, 'Playback must not open an external tab'
        assert page.frames[1].evaluate('testDocumentIdentity')==identity
        page.screenshot(path='/tmp/eurozone-activity-replay.png',full_page=True)
        app.get_by_role('button',name='Close playback',exact=True).click()
        expect(library.locator('#games tr')).to_have_count(1)
        page.set_viewport_size({'width':390,'height':844})
        assert page.frames[1].evaluate('document.documentElement.scrollWidth<=innerWidth')
        app.get_by_role('button',name='Back to Eurozone',exact=True).click()
        expect(app.get_by_role('heading',name='Activity tables',exact=True)).to_be_visible()
        assert not errors, errors
        browser.close()
        print('Discord Activity: lazy replay library, collection navigation, same-frame playback, return, mobile and message validation passed')


if __name__=='__main__':
    discord.check=check
    discord.main()
