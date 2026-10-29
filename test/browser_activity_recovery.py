"""Activity lobby: independent games, unavailable saves, pack settings and safe rejoining."""
import json
import os
from browser_bridge import Bridge
from playwright.sync_api import expect, sync_playwright
import browser_discord as discord
from browser_smoke import choose_action, confirm_action


def check(base):
    with Bridge() as bridge, sync_playwright() as pw:
        browser=pw.chromium.launch(headless=True,executable_path=os.environ['EUROZONE_BROWSER'])
        errors=[]
        def participant(uid,name):
            ctx=browser.new_context(viewport={'width':1440,'height':1000})
            parent=discord.PARENT.replace('USER',json.dumps({'id':uid,'username':name})).replace('instance-test','recovery-test')
            ctx.route(base+'/discord-test-parent',lambda route:route.fulfill(content_type='text/html',body=parent))
            page=ctx.new_page();page.on('pageerror',lambda e:(errors.append(str(e)),print('PAGE ERROR:',str(e),flush=True)))
            ctx.add_init_script('window.testDocumentIdentity = crypto.randomUUID()')
            page.goto(base+'/discord-test-parent')
            return ctx,page,page.frame_locator('iframe')
        original_ctx,original,h=participant('111','Ada')
        expect(h.get_by_role('heading',name='Activity tables',exact=True)).to_be_visible()
        original_document=original.frames[1].evaluate('testDocumentIdentity')
        discord.enter_activity(h,True)
        assert original.frames[1].evaluate('testDocumentIdentity')==original_document
        guest_ctx,guest,g=participant('222','Ben')
        expect(g.get_by_role('heading',name='Activity tables',exact=True)).to_be_visible()
        guest_document=guest.frames[1].evaluate('testDocumentIdentity')
        discord.enter_activity(g)
        g.get_by_placeholder('Your name').fill('Ben')
        g.get_by_role('button',name='Take a seat',exact=True).click()
        h.get_by_role('button',name='Start the game →').click()
        expect(g.locator('.game')).to_be_visible()
        actor=next(f for f in [h,g] if f.get_by_role('button',name='Forest',exact=False).is_enabled())
        choose_action(actor,'Forest');confirm_action(actor)
        old_room=original.frames[1].evaluate("new URL(location.href).searchParams.get('peer')")
        old_save=original.frames[1].evaluate('room => EurozonePeer.read(room)',old_room)
        assert old_save
        original.close()
        expect(g.locator('.connection')).to_contain_text('Waiting for the host')
        fresh_ctx,fresh,f=participant('111','Ada')
        expect(f.get_by_role('heading',name='Activity tables',exact=True)).to_be_visible()
        expect(f.locator('.activity-table')).to_contain_text('Host save is in another browser')
        expect(f.get_by_role('button',name='Open table',exact=True)).to_be_disabled()
        f.get_by_role('button',name='Create a new table',exact=True).click()
        expect(f.get_by_label('Players expected')).to_be_visible()
        new_room=fresh.frames[1].evaluate("new URL(location.href).searchParams.get('peer')")
        assert new_room!=old_room
        # Creating a separate game does not pull someone out of the old one.
        assert guest.frames[1].evaluate("new URL(location.href).searchParams.get('peer')")==old_room
        g.get_by_role('button',name='← All tables',exact=True).click()
        expect(g.locator('.activity-table')).to_have_count(2)
        g.locator('.activity-table').filter(has_text='Host connected').get_by_role('button',name='Join table').click()
        g.get_by_placeholder('Your name').fill('Ben')
        g.get_by_role('button',name='Take a seat',exact=True).click()
        expect(g.get_by_role('button',name='Mark not ready',exact=True)).to_be_visible()
        expect(f.locator('.seat')).to_have_count(2)
        # Pack settings are synchronized, host-only, and stop undersupplied draws.
        f.get_by_label('Pre-game hand setup').select_option('draw')
        expect(f.get_by_role('button',name='Start draw →')).to_be_visible()
        expect(f.locator('.app')).to_have_attribute('aria-busy','false')
        f.get_by_label('Include Base game · A cards',exact=True).click()
        expect(f.get_by_label('Include Base game · A cards',exact=True)).not_to_be_checked()
        expect(g.get_by_label('Include Base game · A cards',exact=True)).not_to_be_checked()
        expect(g.get_by_label('Include Base game · A cards',exact=True)).to_be_disabled()
        f.get_by_label('Include Base game · B cards',exact=True).click()
        expect(f.get_by_label('Include Base game · B cards',exact=True)).not_to_be_checked()
        expect(f.get_by_role('button',name='Start draw →')).to_be_disabled()
        expect(f.locator('.pack-picker')).to_contain_text('Include more packs')
        f.get_by_label('Include Base game · B cards',exact=True).click()
        expect(f.get_by_label('Include Base game · B cards',exact=True)).to_be_checked()
        with fresh.frames[1].expect_navigation(wait_until='load'):
            fresh.frames[1].evaluate('location.reload()')
        expect(f.get_by_label('Include Base game · A cards',exact=True)).not_to_be_checked()
        g.get_by_role('button',name="I'm ready",exact=True).click()
        f.get_by_role('button',name='Start draw →').click()
        expect(f.locator('.draw-choices .hand-card')).to_have_count(16)
        # Inspect the authoritative pool, not only the visible labels.
        saved=fresh.frames[1].evaluate("() => EurozonePeer.read(new URL(location.href).searchParams.get('peer'))")
        packets=bridge.request('decode',saved)['table']['state']['draw']['packets']
        ids=[card for packet in packets for card in packet]
        assert len(ids)==32 and all(card.startswith('b') for card in ids),ids
        inspect=original_ctx.new_page();inspect.goto(base+'/health');inspect.add_script_tag(url=base+'/peer-runtime.js')
        assert inspect.evaluate('room => EurozonePeer.read(room)',old_room)==old_save
        # All tables keeps Discord's frame identity; any participant may host another game.
        g.get_by_role('button',name='← All tables',exact=True).click()
        expect(g.get_by_role('heading',name='Activity tables',exact=True)).to_be_visible()
        g.get_by_role('button',name='Create a new table',exact=True).click()
        expect(g.get_by_label('Players expected')).to_be_visible()
        g.get_by_label('Pre-game hand setup').select_option('beginner')
        g.get_by_label('Bot players').select_option('1')
        g.get_by_role('button',name='Start the game →').click()
        expect(g.locator('.turn-banner')).to_have_attribute('data-your-turn','true',timeout=15000)
        def state_hash():
            saved=guest.frames[1].evaluate("() => EurozonePeer.read(new URL(location.href).searchParams.get('peer'))")
            return bridge.request('state-hash',saved)
        before=state_hash()
        action=next(name for name in ['Forest','Clay Pit','Day Laborer'] if g.get_by_role('button',name=name,exact=False).is_enabled())
        choose_action(g,action);confirm_action(g)
        expect(g.locator('.turn-banner')).to_have_attribute('data-your-turn','true',timeout=15000)
        assert state_hash()!=before
        g.get_by_role('button',name='Request takeback',exact=True).click()
        expect(g.get_by_role('dialog')).to_contain_text('Ben’s last turn')
        g.get_by_role('button',name='Ask for takeback',exact=True).click()
        g.get_by_role('dialog',name='Takeback approved',exact=True).get_by_role('button',name='Continue',exact=True).click()
        assert state_hash()==before
        print('Player takeback restores the pre-turn state including the following bot move',flush=True)
        g.get_by_role('button',name='← All tables',exact=True).click()
        expect(g.locator('.activity-table')).to_have_count(3)
        g.get_by_role('button',name='Open table',exact=True).click()
        expect(g.locator('.game')).to_be_visible()
        assert state_hash()==before, 'Returning to a table changed its saved state'
        # Leave while a command is waiting for durable storage. The new lobby
        # must wait for that write, and the command must survive reopening.
        guest.frames[1].evaluate("""() => {
          const save=EurozonePeer.save; window.testSaveStarted=false;
          EurozonePeer.save=(...args)=>{
            EurozonePeer.save=save;window.testSaveStarted=true;
            return new Promise(resolve=>{window.testReleaseSave=()=>resolve(save(...args));});
          };
        }""")
        choose_action(g,action)
        g.get_by_role('button',name='Place family member →').click()
        guest.frames[1].wait_for_function('testSaveStarted')
        g.get_by_role('button',name='← All tables',exact=True).click()
        guest.frames[1].evaluate('testReleaseSave()')
        expect(g.locator('.activity-table')).to_have_count(3)
        g.get_by_role('button',name='Open table',exact=True).click()
        expect(g.locator('.game')).to_be_visible()
        assert state_hash()!=before, 'Leaving discarded an accepted command'
        print('Leaving during a pending save waits for persistence; reopening preserves the move',flush=True)
        g.get_by_role('button',name='← All tables',exact=True).click()
        expect(g.locator('.activity-table')).to_have_count(3)
        guest.screenshot(path='/tmp/eurozone-activity-lobby.png',full_page=True)
        guest.set_viewport_size({'width':390,'height':844})
        assert guest.frames[1].evaluate('document.documentElement.scrollWidth<=innerWidth')
        assert guest.frames[1].evaluate('testDocumentIdentity')==guest_document, 'Table transitions reloaded the iframe'
        assert not errors,errors
        browser.close()
        print('Activity lobby: separate tables, join/create, missing host save, unchanged old save, host-only pack selection, actual filtered draw, reload and mobile layout passed',flush=True)

if __name__=='__main__':
    discord.check=check
    discord.main()
