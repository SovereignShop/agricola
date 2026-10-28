"""Private OAuth and real bundled SDK in an iframe; only Discord's endpoints/RPC are simulated."""
import json
import os
import socket
import subprocess
import tempfile
import time
import urllib.parse
import urllib.request
from pathlib import Path
from playwright.sync_api import expect, sync_playwright
from browser_smoke import ROOT, choose_action, confirm_action
from browser_peer import PROBE

PARENT = '''<!doctype html><style>body{margin:0}iframe{width:100vw;height:100vh;border:0}</style>
<script>
const user = USER;
window.rpc = [];
addEventListener('message', e => {
 const [op,p] = e.data || [];
 const reply = data => e.source.postMessage([1,data],e.origin);
 if (op === 0) reply({cmd:'DISPATCH',evt:'READY',nonce:null,data:{v:1,config:{api_endpoint:'test',environment:'test'}}});
 if (op === 1) {
   rpc.push(p.cmd);
   let data = null;
   if (p.cmd === 'AUTHORIZE') data = {code:user.id};
   if (p.cmd === 'AUTHENTICATE') data = {access_token:'access-'+user.id, user:{...user,discriminator:'0',avatar:null,public_flags:0},scopes:['identify'],expires:'2121-01-01T00:00:00Z',application:{id:'123',name:'Eurozone',description:'test',icon:null}};
   reply({cmd:p.cmd,nonce:p.nonce,evt:null,data});
 }
});
</script><iframe src="/?frame_id=frame-test&instance_id=instance-test&platform=desktop"></iframe>'''

def check(base):
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ['EUROZONE_BROWSER'])
        errors = []
        def context(user):
            ctx = browser.new_context(viewport={'width':1440,'height':1100})
            ctx.add_init_script('(' + PROBE + ')()')
            def authorize(route):
                login=route.fetch(max_redirects=0)
                params = urllib.parse.parse_qs(urllib.parse.urlsplit(login.headers['location']).query)
                callback = params['redirect_uri'][0] + '?' + urllib.parse.urlencode({'code':user,'state':params['state'][0]})
                route.fulfill(response=login,headers={**login.headers,'location':callback})
            ctx.route(base+'/discord/login?*',authorize)
            ctx.route('https://discord.com/**',lambda route:route.abort())
            ctx.on('page',lambda p: p.on('pageerror',lambda e:errors.append(str(e))))
            return ctx
        # A copied invitation or forged random session cannot bypass private mode.
        anon = browser.new_context().new_page(); anon.goto(base+'/health')
        for endpoint, packet in [('/chsk','{:event :hello :token "'+'a'*64+'"}'),
                                 ('/peer',json.dumps({'op':'create','room':'a'*64,'token':'b'*64,'invite':'c'*64,'options':{}}))]:
            reply = anon.evaluate('''([endpoint,packet]) => new Promise(resolve => {
              const ws=new WebSocket(location.origin.replace('http','ws')+endpoint);
              ws.onopen=()=>ws.send(packet); ws.onmessage=e=>{resolve(e.data);ws.close()};
            })''',[endpoint,packet])
            assert 'Sign in' in reply,reply
        anon.context.close()
        host_ctx, guest_ctx = context('111'), context('222')
        host, guest = host_ctx.new_page(), guest_ctx.new_page()
        host.goto(base+'/?seat=new')
        expect(host.get_by_text('Connected',exact=True)).to_be_visible()
        assert host.evaluate('EUROZONE_DISCORD_USER') == '111'
        host.get_by_label('Table name').fill('Private browser game')
        host.get_by_role('button',name='Peer-hosted table',exact=True).click()
        host.get_by_role('button',name='Create table →').click()
        link = host.get_by_label('Table invitation link').input_value()
        expect(host.get_by_role('link',name='Open another player in a new tab ↗')).to_have_count(0)
        guest.goto(link)
        guest.get_by_placeholder('Your name').fill('Ben')
        guest.get_by_role('button',name='Take a seat',exact=True).click()
        host.get_by_role('button',name='Start the game →').click()
        expect(guest.get_by_role('heading',name='Make your move.')).to_be_visible()
        outsider=context('333'); page=outsider.new_page();page.goto(link)
        expect(page.locator('body')).to_contain_text('limited to invited Discord users')
        outsider.close();host_ctx.close();guest_ctx.close()
        print('Private browser OAuth, independent Discord seats, invitation denial, and both WebSocket gates: passed')
        # Real SDK bundle and production CLJS inside a simulated Discord RPC parent.
        contexts=[context('111'),context('222')]; pages=[]; frames=[]
        for ctx, uid, name in zip(contexts,['111','222'],['Ada','Ben']):
            ctx.route(base+'/discord-test-parent',lambda route,request,uid=uid,name=name: route.fulfill(content_type='text/html',body=PARENT.replace('USER',json.dumps({'id':uid,'username':name}))))
            page=ctx.new_page();page.goto(base+'/discord-test-parent');pages.append(page)
            frame=page.frame_locator('iframe');frames.append(frame)
            if uid=='111':
                expect(frame.get_by_role('heading',name='Agricola with friends')).to_be_visible()
            else:
                frame.get_by_placeholder('Your name').fill(name)
                frame.get_by_role('button',name='Take a seat',exact=True).click()
        frames[0].get_by_role('button',name='Start the game →').click()
        for frame in frames:
            expect(frame.get_by_role('heading',name='Make your move.')).to_be_visible()
            expect(frame.locator('.hand-panel .hand-card')).to_have_count(7)
            expect(frame.get_by_text('Fullscreen: use Discord’s controls')).to_be_visible()
        actor=next(f for f in frames if f.get_by_role('button',name='Forest',exact=False).is_enabled())
        choose_action(actor,'Forest');confirm_action(actor)
        for frame in frames:
            expect(frame.locator('[data-piece-key^="worker::wood:"]')).to_have_count(1)
        for page in pages:
            child=page.frames[1]
            assert child.evaluate('peerConnections.length')==0
            assert child.evaluate('EUROZONE_ACTIVITY')
            assert {'AUTHORIZE','AUTHENTICATE'} <= set(page.evaluate('rpc'))
        pages[0].screenshot(path='/tmp/eurozone-discord-activity.png',full_page=True)
        for ctx in contexts: ctx.close()
        # An existing default two-seat Activity can be enlarged without another
        # slash command. Settings survive host reload and reach all participants.
        for count in [3,4,5]:
            contexts=[]; pages=[]; frames=[]
            for i,uid in enumerate(['111','222','444','555','666'][:count]):
                ctx=context(uid); contexts.append(ctx)
                parent=PARENT.replace('USER',json.dumps({'id':uid,'username':f'Farmer {i+1}'})).replace('instance-test',f'instance-{count}')
                ctx.route(base+'/discord-test-parent',lambda route,request,parent=parent: route.fulfill(content_type='text/html',body=parent))
                page=ctx.new_page();pages.append(page);page.goto(base+'/discord-test-parent')
                frame=page.frame_locator('iframe');frames.append(frame)
                if i==0:
                    expect(frame.get_by_label('Players expected')).to_have_value('2')
                else:
                    expect(frame.get_by_label('Players expected')).to_have_count(0)
                    frame.get_by_placeholder('Your name').fill(f'Farmer {i+1}')
                    frame.get_by_role('button',name='Take a seat',exact=True).click()
                    expect(frame.get_by_role('button',name='Mark not ready',exact=True)).to_be_visible()
                if i==1:
                    host=frames[0]
                    host.get_by_label('Players expected').select_option(str(count))
                    expect(frame.get_by_text(f'2 / {count} players',exact=False)).to_be_visible()
                    expect(host.get_by_role('button',name='Start the game →')).to_be_disabled()
                    if count==5:
                        host.get_by_label('Pre-game hand setup').select_option('draw')
                        host.get_by_label('Extra cards per packet (N)').select_option('3')
                        expect(frame.get_by_text('Draw: 14 + 3 cards',exact=False)).to_be_visible()
                    frame.get_by_role('button',name="I'm ready",exact=True).click()
                    expect(frame.get_by_role('button',name='Mark not ready',exact=True)).to_be_visible()
                    pages[0].reload()
                    expect(host.get_by_label('Players expected')).to_have_value(str(count))
                    expect(frame.get_by_role('button',name='Mark not ready',exact=True)).to_be_visible()
            start=frames[0].get_by_role('button',name='Start draw →' if count==5 else 'Start the game →')
            expect(start).to_be_enabled();start.click()
            for frame in frames:
                expect(frame.get_by_label('Players expected')).to_have_count(0)
                if count==5: expect(frame.locator('.draw-packet .hand-card')).to_have_count(17)
                else:
                    expect(frame.get_by_role('heading',name='Make your move.')).to_be_visible()
                    expect(frame.locator('.hand-panel .hand-card')).to_have_count(7)
            for ctx in contexts: ctx.close()
            print(f'{count}-player Activity: host settings, guest synchronization, readiness, reload and start passed')
        assert not errors,errors
        browser.close()
        print('Bundled Discord SDK handshake, OAuth, verified instance, two-player relay gameplay, and full-frame layout: passed')

def main():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1',0));port=sock.getsockname()[1]
    with tempfile.TemporaryDirectory(prefix='eurozone-discord-') as tmp:
        env=dict(os.environ,EUROZONE_DATA=str(Path(tmp)/'games.edn'))
        with open(Path(tmp)/'server.log','w+') as log:
            server=subprocess.Popen(['clojure','-M','test/eurozone/discord_browser_server.clj',str(port)],cwd=ROOT,env=env,stdout=log,stderr=log)
            try:
                base=f'http://127.0.0.1:{port}'
                for _ in range(100):
                    try: urllib.request.urlopen(base+'/health',timeout=1).close();break
                    except OSError:
                        if server.poll() is not None: log.seek(0);raise RuntimeError(log.read())
                        time.sleep(.2)
                else: raise RuntimeError('Test server did not start')
                check(base)
            finally:
                server.terminate();server.wait(timeout=10)

if __name__=='__main__': main()
