"""Eurozone Ascension: independent clients, private hands, rejoin, bots and Discord."""
import os,json,urllib.parse,re
from pathlib import Path
from playwright.sync_api import sync_playwright,expect
import browser_discord

def check(base):
 with sync_playwright() as pw:
  browser=pw.chromium.launch(headless=True,executable_path=os.environ['EUROZONE_BROWSER'])
  errors=[]
  def web(user,width=1440):
   ctx=browser.new_context(viewport={'width':width,'height':900},is_mobile=width<500,has_touch=width<500)
   page=ctx.new_page();page.on('pageerror',lambda e:errors.append(str(e)))
   def authorize(route):
    login=route.fetch(max_redirects=0);q=urllib.parse.parse_qs(urllib.parse.urlsplit(login.headers['location']).query)
    callback=q['redirect_uri'][0]+'?'+urllib.parse.urlencode({'code':user,'state':q['state'][0]})
    route.fulfill(response=login,headers={**login.headers,'location':callback})
   ctx.route(base+'/discord/login?*',authorize)
   return ctx,page
  alice,a=web('111');bob,b=web('222');observer,o=web('444',390)
  try:
   a.goto(base+'/');a.get_by_role('button',name='Ascension: Claim the Throne · 2–4 players',exact=True).click()
   expect(a.get_by_role('button',name='Create table',exact=True)).to_be_enabled(timeout=30000)
   a.locator('#onlineName').fill('Ada');a.get_by_role('button',name='3 players',exact=True).click();a.get_by_role('button',name='1 bots',exact=True).click();a.get_by_role('button',name='Create table',exact=True).click()
   expect(a.get_by_role('button',name='Start game',exact=True)).to_be_disabled()
   a.wait_for_url(re.compile('#table='));url=a.url
   b.goto(url);expect(b.get_by_role('button',name='Join table',exact=True)).to_be_visible(timeout=30000);b.get_by_role('button',name='Join table',exact=True).click()
   expect(a.get_by_role('button',name='Start game',exact=True)).to_be_enabled(timeout=10000)
   o.goto(url);expect(o.locator('#onlineStatus')).to_contain_text('Observing',timeout=30000)
   a.get_by_role('button',name='Start game',exact=True).click()
   for p in [a,b,o]:expect(p.locator('body')).not_to_have_class(re.compile('online-lobby'),timeout=10000)
   for p,seat in [(a,0),(b,1)]:
    assert p.evaluate('state.cards.filter(c=>c.zone==="hand").every(c=>c.owner===AscensionOnline.seat())')
    assert p.evaluate('state.cards.filter(c=>c.zone==="hand").length')==3
    assert p.evaluate('AscensionOnline.seat()')==seat
   assert o.evaluate('state.cards.filter(c=>c.zone==="hand").length')==0
   assert o.evaluate('state.deck===undefined && state.rng===undefined')
   expect(o.locator('#next')).to_be_disabled()
   # Any seated player can advance common phases, including when creator leaves.
   a.close()
   for phase in [1,2,3]:
    b.locator('#next').click();b.wait_for_function('(phase)=>state.phase===phase',arg=phase)
    expect(b.locator('#onlineStatus')).not_to_contain_text('Saving move')
   # Reconnect creator to the same persisted seat, not a replacement table.
   a=alice.new_page();a.on('pageerror',lambda e:errors.append(str(e)));a.goto(url)
   a.wait_for_function('window.AscensionOnline?.seat()===0 && state.phase===3')
   # Both humans draft a public card. Bots take their own turn on the server.
   for _ in range(2):
    b.wait_for_function('state.phase!==3 || !state.players[state.active].bot')
    active=b.evaluate('state.active');p=a if active==0 else b
    p.locator('#focusHand').click();p.locator('[data-inspect]').first.click()
    p.get_by_role('button',name='Draft card',exact=True).click()
    p.wait_for_function('(old)=>state.active!==old || state.phase!==3',arg=active)
   b.wait_for_function('state.phase===4')
   # Play an estate card using the map, then verify both clients and observer.
   b.wait_for_function('!state.players[state.active].bot')
   active=b.evaluate('state.active');p=a if active==0 else b
   p.locator('#handButton').click();p.locator('#dialog').get_by_role('button',name=re.compile('Expand the Demesne')).click()
   p.get_by_role('button',name='Choose targets',exact=True).click()
   before=p.evaluate('(i)=>Engine.power(state,i)',active)
   for _ in range(2):p.locator('[data-county].legal-target').first.click()
   p.get_by_role('button',name='Confirm targets',exact=True).click()
   for q in [a,b,o]:q.wait_for_function('(v)=>Engine.power(state,v[0])===v[1]',arg=[active,before+2])
   bob.set_offline(True);expect(b.locator('#onlineStatus')).to_contain_text('Reconnecting',timeout=15000);bob.set_offline(False)
   expect(b.locator('#onlineStatus')).to_contain_text('Connected',timeout=15000)
   b.reload();b.wait_for_function('window.AscensionOnline?.seat()===1 && state.mode==="game"')
   assert b.evaluate('(i)=>Engine.power(state,i)',active)==before+2
   o.screenshot(path='/tmp/ascension-observer-mobile.png');b.screenshot(path='/tmp/ascension-online-desktop.png')
   print('Independent web players: create/join/start, bot turns, private hands, observer, creator disconnect, spatial play, reload PASS',flush=True)
   # Four genuine human seats, each in an isolated browser profile.
   dctx,d=web('555');d.goto(base+'/ascension/index.html')
   b.locator('#allTables').click();b.get_by_role('button',name='4 players',exact=True).click();b.get_by_role('button',name='0 bots',exact=True).click();b.get_by_role('button',name='Create table',exact=True).click()
   b.wait_for_url(re.compile('#table='));four=b.url
   for p in [a,o,d]:
    p.goto(four);p.get_by_role('button',name='Join table',exact=True).click()
    expect(p.get_by_role('button',name='Join table',exact=True)).to_have_count(0)
   expect(b.get_by_role('button',name='Start game',exact=True)).to_be_enabled();b.get_by_role('button',name='Start game',exact=True).click()
   for p in [a,b,o,d]:
    p.wait_for_function('state.mode==="game" && state.players.length===4')
    assert p.evaluate('state.cards.filter(c=>c.zone==="hand").length')==3
   b.reload();b.wait_for_function('state.mode==="game" && state.players.length===4');dctx.close()
   print('Four-human table start, private hands and reload PASS',flush=True)
   # Real bundled Discord SDK handshake, then navigate from Eurozone into Ascension.
   ctx=browser.new_context(viewport={'width':390,'height':844},has_touch=True,is_mobile=True);page=ctx.new_page();page.on('pageerror',lambda e:errors.append(str(e)))
   parent=browser_discord.PARENT.replace('<style>','<meta name="viewport" content="width=device-width, initial-scale=1"><style>').replace('e.source.postMessage','e.source?.postMessage').replace('USER',json.dumps({'id':'111','username':'Ada'}))
   ctx.route(base+'/discord-test-parent',lambda route:route.fulfill(content_type='text/html',body=parent));page.goto(base+'/discord-test-parent');frame=page.frame_locator('iframe')
   frame.get_by_role('button',name='Ascension: Claim the Throne · 2–4 players',exact=True).click()
   expect(frame.get_by_role('button',name='Create table',exact=True)).to_be_enabled(timeout=30000)
   frame.get_by_role('button',name='2 players',exact=True).click();frame.get_by_role('button',name='1 bots',exact=True).click();frame.get_by_role('button',name='Create table',exact=True).click()
   frame.get_by_role('button',name='Start game',exact=True).click();expect(frame.locator('#next')).to_be_enabled()
   frame.locator('#next').click();expect(frame.locator('#phaseLabel')).to_contain_text('Income')
   page.screenshot(path='/tmp/ascension-discord-mobile.png')
   # Finish a real hosted game: human passes, server bot plays legal turns.
   gameframe=page.frames[1]
   for _ in range(300):
    gameframe.wait_for_function('state.winner || !document.querySelector("#next").disabled',timeout=15000)
    if gameframe.evaluate('!!state.winner'):break
    frame.locator('#next').click()
   assert gameframe.evaluate('!!state.winner'), 'Hosted game did not finish'
   expect(frame.locator('#next')).to_be_disabled()
   gameframe.evaluate('location.reload()')
   gameframe.wait_for_function('window.AscensionOnline && !!state.winner',timeout=30000)
   page.screenshot(path='/tmp/ascension-online-finished-mobile.png');ctx.close()
   print('Hosted human/bot game completed and terminal result survived reload PASS',flush=True)
   assert not errors,errors
   print('Discord Activity mobile: game selection, OAuth, lobby, bots, native first action PASS',flush=True)
  except Exception:
   print('Browser errors:',errors,flush=True)
   for label,p in [('a',a),('b',b),('o',o)]:
    if not p.is_closed():
     p.screenshot(path='/tmp/ascension-failure-'+label+'.png')
     Path('/tmp/ascension-failure-'+label+'.html').write_text(p.content())
   raise
  finally:
   alice.close();bob.close();observer.close();browser.close()

if __name__=='__main__':
 browser_discord.check=check
 browser_discord.main()
