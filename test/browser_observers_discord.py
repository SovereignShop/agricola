"""Observer entry in Discord, including a full table, private draw and reconnect."""
import json
import os
from playwright.sync_api import expect, sync_playwright
import browser_discord


def check(base):
    with sync_playwright() as pw:
        browser=pw.chromium.launch(headless=True,executable_path=os.environ['EUROZONE_BROWSER'])
        errors=[]
        for mode in ['random','draw']:
            contexts=[]; pages=[]; frames=[]
            for uid,name in [('111','Ada'),('222','Ben'),('444','Watcher')]:
                ctx=browser.new_context(viewport={'width':1440,'height':1000})
                contexts.append(ctx)
                parent=browser_discord.PARENT.replace('USER',json.dumps({'id':uid,'username':name})).replace('instance-test','observer-'+mode)
                ctx.route(base+'/discord-test-parent',lambda route,request,parent=parent:route.fulfill(content_type='text/html',body=parent))
                page=ctx.new_page();page.on('pageerror',lambda e:errors.append(str(e)))
                page.goto(base+'/discord-test-parent');pages.append(page);frames.append(page.frame_locator('iframe'))
                expect(frames[-1].get_by_role('heading',name='Activity tables',exact=True)).to_be_visible()
            host,guest,observer=frames
            host.get_by_role('button',name='Create a new table',exact=True).click()
            expect(host.get_by_label('Players expected')).to_be_visible()
            host.get_by_label('Pre-game hand setup').select_option(mode)
            observer.get_by_role('button',name='Join as observer',exact=True).click(timeout=15000)
            expect(observer.locator('.observer-status')).to_have_text('Observing')
            expect(observer.get_by_role('button',name='Take a seat',exact=True)).to_have_count(0)
            expect(observer.get_by_role('button',name='Join as a player',exact=True)).to_be_visible()
            expect(host.locator('.seats .seat')).to_have_count(1)
            pages[2].frames[1].evaluate('location.reload()')
            expect(observer.get_by_role('button',name='Join as a player',exact=True)).to_be_visible()
            expect(observer.get_by_role('button',name='Take a seat',exact=True)).to_have_count(0)
            guest.get_by_role('button',name='Join table',exact=True).click(timeout=15000)
            guest.get_by_placeholder('Your name').fill('Ben')
            guest.get_by_role('button',name='Take a seat',exact=True).click()
            expect(host.locator('.seats .seat')).to_have_count(2)
            expect(observer.get_by_role('button',name='Join as a player',exact=True)).to_have_count(0)
            host.get_by_role('button',name='Start draw →' if mode=='draw' else 'Start the game →',exact=True).click()
            if mode=='draw':
                expect(observer.locator('.draw-view')).to_be_visible()
                expect(observer.locator('.draw-seat')).to_have_count(2)
                expect(observer.locator('.draw-packet')).to_have_count(0)
                expect(observer.locator('.hand-card')).to_have_count(0)
                host.locator('.draw-packet .hand-card:not([disabled])').first.click()
                host.get_by_role('dialog').get_by_role('button',name='Keep card & pass left →',exact=True).click()
                expect(observer.get_by_text('Picked · waiting to pass',exact=True)).to_have_count(1)
                expect(observer.locator('.hand-card')).to_have_count(0)
            else:
                for frame in frames: expect(frame.locator('.tt-game')).to_be_visible()
                expect(observer.locator('.tt-player')).to_have_count(2)
                expect(observer.locator('[data-deck^="hand-"]')).to_have_count(0)
                expect(observer.locator('.action-card:not([disabled])')).to_have_count(0)
                actor=next(f for f in [host,guest] if f.locator('.action-card[data-action="grain"]').is_enabled())
                actor.locator('.action-card[data-action="grain"]').click()
                actor.get_by_role('button',name='Place family member →',exact=True).click()
                expect(observer.locator('[data-piece-key^="worker::grain:"]')).to_have_count(1)
                revision=observer.locator('.game').get_attribute('data-revision')
                pages[2].frames[1].evaluate('location.reload()')
                expect(observer.locator('.game')).to_have_attribute('data-revision',revision)
                expect(observer.locator('[data-deck^="hand-"]')).to_have_count(0)
                observer.get_by_role('button',name='← Tables',exact=True).click()
                observer.get_by_role('button',name='Join as observer',exact=True).click()
                expect(observer.locator('.game')).to_have_attribute('data-revision',revision)
                pages[2].set_viewport_size({'width':390,'height':844})
                expect(observer.locator('.observer-status')).to_be_in_viewport()
                pages[2].screenshot(path='/tmp/eurozone-observer-mobile.png')
            expect(observer.locator('.observer-status')).to_have_text('Observing')
            expect(observer.locator('.toast')).to_have_count(0)
            for ctx in contexts:ctx.close()
            print('PASS: Discord observer '+mode+'; no seat/readiness cost, hidden hands, live updates and reconnect')
        assert not errors,errors
        browser.close()


if __name__=='__main__':
    browser_discord.check=check
    browser_discord.main()
