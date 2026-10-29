"""Real Activity iframe + browser worker: bots draft, play and resume after reload."""
import json
import os
import tempfile
import time
import subprocess
from pathlib import Path
from playwright.sync_api import expect, sync_playwright
import browser_discord as discord
from browser_smoke import ROOT

STATUS = '''() => {
 const c=cljs.core, k=c.keyword, get=c.get;
 const session=c.deref(eurozone.browser.peer.session), room=get(session,k('table'));
 const state=get(room,k('state'));
 return {phase:c.name(get(state,k('phase'))),round:get(state,k('round')),
   revision:get(room,k('revision')),bot:eurozone.bot_player.actor(room),
   worker:!!c.deref(eurozone.browser.bots.worker),
   error:get(c.deref(re_frame.db.app_db),k('error'))};
}'''
HUMAN_MOVE = '''() => {
 const c=cljs.core,k=c.keyword,get=c.get;
 const session=c.deref(eurozone.browser.peer.session),room=get(session,k('table')),state=get(room,k('state'));
 if(eurozone.bot_player.actor(room)!=null || c.name(get(state,k('phase')))==='finished') return false;
 const cmd=eurozone.bot_player.choose(eurozone.bot_player.observation(state,0),0);
 let msg=c.hash_map(k('event'),k('command'),k('command'),cmd,k('id'),get(room,k('id')),
   k('revision'),get(room,k('revision')),k('request-id'),crypto.randomUUID());
 if(c.name(get(state,k('phase')))==='draw') {
   msg=c.assoc(msg,k('event'),k('draw-pick'),k('player'),0,k('wave'),get(cmd,k('wave')),k('card'),get(cmd,k('card')));
 }
 eurozone.browser.peer.send_BANG_(msg); return true;
}'''

def mixed_seats(browser,base,errors):
    contexts=[];pages=[];frames=[]
    for uid,name in [('111','Host'),('222','Friend')]:
        ctx=browser.new_context(viewport={'width':1440,'height':1200});contexts.append(ctx)
        parent=discord.PARENT.replace('USER',json.dumps({'id':uid,'username':name})).replace('instance-test','mixed-bots')
        ctx.route(base+'/discord-test-parent',lambda route,request,parent=parent:route.fulfill(content_type='text/html',body=parent))
        page=ctx.new_page();pages.append(page);page.on('pageerror',lambda e:errors.append(str(e)))
        page.goto(base+'/discord-test-parent');frame=page.frame_locator('iframe');frames.append(frame)
        discord.enter_activity(frame,uid=='111')
        if uid=='111':
            frame.get_by_label('Players expected').select_option('5')
            frame.get_by_label('Bot players').select_option('3')
            expect(frame.get_by_role('button',name='Start the game →')).to_be_disabled()
        else:
            frame.get_by_placeholder('Your name').fill(name)
            frame.get_by_role('button',name='Take a seat',exact=True).click()
            expect(frame.get_by_label('Bot players')).to_have_count(0)
    frames[0].get_by_role('button',name='Start the game →').click()
    for frame in frames:
        expect(frame.locator('.game')).to_be_visible()
        expect(frame.locator('.hand-panel .hand-card')).to_have_count(7)
    children=[p.frames[1] for p in pages]
    # Test agents control the two real, independently authenticated human seats.
    # Bot actions still come exclusively from the production host worker.
    packet='''() => {
      const c=cljs.core,k=c.keyword,get=c.get,room=get(c.deref(eurozone.browser.peer.session),k('table')),state=get(room,k('state'));
      if(eurozone.bot_player.actor(room)!=null) return null;
      const pid=agricola.card_game.acting_player(state),cmd=eurozone.bot_player.choose(eurozone.bot_player.observation(state,pid),pid);
      return {player:pid,text:c.pr_str(c.hash_map(k('event'),k('command'),k('command'),cmd,k('id'),get(room,k('id')),
        k('revision'),get(room,k('revision')),k('request-id'),crypto.randomUUID()))};
    }'''
    deadline=time.monotonic()+90;human_seats=set()
    while time.monotonic()<deadline:
        status=children[0].evaluate(STATUS)
        assert not status['error'],status
        if status['round']>=3:break
        move=children[0].evaluate(packet)
        if move:
            human_seats.add(move['player'])
            children[0 if move['player']==0 else 1].evaluate('text=>eurozone.browser.peer.send_BANG_(cljs.reader.read_string(text))',move['text'])
            children[0].wait_for_function('rev => '+STATUS.replace('() =>','(() =>',1)+')().revision > rev',arg=status['revision'])
        else:pages[0].wait_for_timeout(100)
    else:raise AssertionError('Mixed human/bot table stalled')
    assert human_seats=={0,4},human_seats
    children[1].wait_for_function('rev => Number(document.querySelector(".game").dataset.revision) >= rev',arg=status['revision'])
    for frame in frames:expect(frame.locator('[role=alert]')).to_have_count(0)
    for ctx in contexts:ctx.close()
    print('Two authenticated humans + three bots: readiness, private hands, remote turns and automatic relay updates passed',flush=True)

def check(base,cases=None,mixed=True):
    with sync_playwright() as pw:
        browser=pw.chromium.launch(headless=True,executable_path=os.environ['EUROZONE_BROWSER'])
        errors=[]
        for count,mode in (cases if cases is not None else [(2,'beginner'),(2,'draw'),(5,'draw')]):
            ctx=browser.new_context(viewport={'width':1440,'height':1200},accept_downloads=True)
            ctx.add_init_script('''const OriginalWorker=Worker; window.botWorkerErrors=[];
                window.Worker=class extends OriginalWorker {constructor(...args){super(...args);
                    this.addEventListener('error',e=>window.botWorkerErrors.push({message:e.message,file:e.filename,line:e.lineno}));}};''')
            parent=discord.PARENT.replace('USER',json.dumps({'id':'111','username':'Human host'})).replace('instance-test',f'bots-{count}-{mode}')
            ctx.route(base+'/discord-test-parent',lambda route,request,parent=parent:route.fulfill(content_type='text/html',body=parent))
            page=ctx.new_page();page.on('pageerror',lambda e:errors.append(str(e)))
            page.goto(base+'/discord-test-parent');frame=page.frame_locator('iframe')
            discord.enter_activity(frame,True)
            frame.get_by_label('Players expected').select_option(str(count))
            frame.get_by_label('Bot players').select_option(str(count-1))
            frame.get_by_label('Pre-game hand setup').select_option(mode)
            expect(frame.locator('.seat')).to_have_count(count)
            expect(frame.get_by_text('Bot · Ready',exact=True)).to_have_count(count-1)
            page.frames[1].evaluate('location.reload()')
            expect(frame.get_by_label('Bot players')).to_have_value(str(count-1))
            frame.get_by_role('button',name='Start draw →' if mode=='draw' else 'Start the game →').click()
            expect(frame.locator('.game')).to_be_visible()
            child=page.frames[1]
            deadline=time.monotonic()+300;last_round=None;reloaded=False
            while time.monotonic()<deadline:
                status=child.evaluate(STATUS)
                assert not status['error'],status
                if status['round']!=last_round:
                    last_round=status['round'];print(f'{count} players ({count-1} bots): round {last_round}',flush=True)
                if status['phase']=='finished':break
                if not reloaded and status['round']>=3:
                    assert status['worker'],child.evaluate('botWorkerErrors')
                    before=status['revision'];page.frames[1].evaluate('location.reload()');child=page.frames[1]
                    expect(frame.locator('.game')).to_be_visible()
                    assert child.evaluate(STATUS)['revision']>=before
                    reloaded=True
                elif status['bot'] is None and child.evaluate(HUMAN_MOVE):
                    child.wait_for_function('rev => '+STATUS.replace('() =>','(() =>',1)+')().revision > rev',arg=status['revision'])
                else: page.wait_for_timeout(100)
            else: raise AssertionError(f'Bot game stalled: {status}')
            assert reloaded
            expect(frame.locator('.player-tab')).to_have_count(count)
            with page.expect_download() as download:
                frame.get_by_role('button',name='Save recording',exact=True).click()
            with tempfile.TemporaryDirectory(prefix='eurozone-bot-replay-') as temp:
                path=Path(temp)/'game.edn';download.value.save_as(path)
                subprocess.run(['clojure','-M:simulate','--replay',str(path)],cwd=ROOT,check=True,stdout=subprocess.DEVNULL)
            print(f'{count}-player {mode} Activity: full game, real worker, host reload, and exported replay passed',flush=True)
            ctx.close()
        if mixed:mixed_seats(browser,base,errors)
        assert not errors,errors
        browser.close()

if __name__=='__main__':
    discord.check=check
    discord.main()
