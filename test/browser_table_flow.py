"""Independent browsers exercise private harvest plans, majority ballots and real clocks."""
import json
import os
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main, choose_action, confirm_action


def check(base, fixture):
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ['EUROZONE_BROWSER'])
        errors = []
        contexts = [browser.new_context(viewport={'width':1440,'height':1000}) for _ in range(3)]
        for c, token in zip(contexts, 'abc'):
            c.add_init_script('localStorage.setItem("eurozone-session", "' + token * 64 + '")')
        pages = [c.new_page() for c in contexts]
        for p in pages:
            p.on('pageerror', lambda e: errors.append(str(e)))
            p.goto(base + '/#' + json.loads(fixture)['harvest'])
            try: expect(p.locator('.harvest-planner')).to_have_count(1)
            except Exception:
                print(p.locator('body').inner_text(),errors,flush=True)
                raise
        h,b,c = pages
        c.get_by_role('button',name='Queue harvest decisions',exact=True).click()
        expect(c.locator('.queued-label')).to_contain_text('feeding and breeding')
        expect(h.locator('.queued-label')).to_have_count(0)
        c.reload()
        expect(c.locator('.queued-label')).to_contain_text('feeding and breeding')
        h.screenshot(path='/tmp/eurozone-harvest-plan.png',full_page=True)
        h.get_by_role('button',name='Queue harvest decisions',exact=True).click()
        b.get_by_role('button',name='Queue harvest decisions',exact=True).click()
        for p in pages:
            expect(p.locator('.harvest-planner')).to_have_count(0)
            expect(p.locator('.round-heading')).to_contain_text('5')
            expect(p.locator('.toast')).to_have_count(0)
        h.get_by_role('button',name='Request takeback',exact=True).click()
        h.get_by_role('button',name='Ask for takeback',exact=True).click()
        for p in pages:
            expect(p.get_by_role('dialog',name='Takeback vote',exact=True)).to_be_visible()
            expect(p.locator('.turn-banner')).to_contain_text('play paused')
        b.reload()
        expect(b.get_by_role('dialog',name='Takeback vote',exact=True)).to_be_visible()
        b.screenshot(path='/tmp/eurozone-takeback-vote.png',full_page=True)
        b.get_by_role('button',name='Approve takeback',exact=True).click()
        for p in pages:
            p.get_by_role('dialog',name='Takeback approved',exact=True).get_by_role('button',name='Continue',exact=True).click()
            expect(p.locator('.round-heading')).to_contain_text('4')
        h.reload()
        expect(h.locator('.harvest-planner')).to_have_count(0)
        expect(h.get_by_role('dialog')).to_have_count(0)
        c.get_by_role('button',name='Complete breeding →').click()
        expect(h.locator('.round-heading')).to_contain_text('5')
        for ctx in contexts:ctx.close()
        print('Three remote seats: private persistent queues, ordered harvest, majority popup vote, restoration and dismissal passed',flush=True)

        # Normal server and peer-hosted clocks run concurrently, with actual elapsed time.
        timed=[]
        for peer in [False,True]:
            ctx=browser.new_context(viewport={'width':1440,'height':1000})
            host,guest=ctx.new_page(),ctx.new_page()
            for p in [host,guest]:p.on('pageerror',lambda e:errors.append(str(e)))
            host.goto(base+'/?transport=relay')
            host.get_by_text('Connected',exact=True).wait_for()
            host.get_by_label('Your name',exact=True).fill('Clock host')
            host.get_by_label('Table name').fill('Clock '+str(peer))
            host.get_by_role('button',name='Peer-hosted table' if peer else 'Online table',exact=True).click()
            host.get_by_label('Turn time limit').select_option('30')
            host.get_by_role('button',name='Create table →').click()
            link=host.get_by_label('Table invitation link').input_value()
            guest.close()
            guest_ctx=browser.new_context(viewport={'width':1440,'height':1000})
            guest=guest_ctx.new_page()
            guest.on('pageerror',lambda e:errors.append(str(e)))
            guest.goto(link)
            expect(guest.locator('.connection.online')).to_be_visible()
            guest.get_by_placeholder('Your name').fill('Clock guest')
            guest.get_by_role('button',name='Take a seat',exact=True).click()
            guest.get_by_text('The host will start once everyone is ready.').wait_for()
            try: host.get_by_role('button',name='Start the game →').click(timeout=10000)
            except Exception:
                print('TIMED HOST',peer,host.locator('body').inner_text(),'GUEST',guest.locator('body').inner_text(),errors,flush=True)
                raise
            expect(host.locator('.turn-countdown')).to_be_visible()
            timed.append((ctx,guest_ctx,host,guest,peer))
        for ctx,guest_ctx,host,guest,peer in timed:
            expect(host.locator('.turn-banner.clock-urgent')).to_be_visible(timeout=35000)
            host.screenshot(path=f'/tmp/eurozone-countdown-{peer}.png',full_page=True)
            expect(host.locator('.action-card.occupied')).to_have_count(1,timeout=20000)
            expect(guest.locator('.action-card.occupied')).to_have_count(1)
            expect(host.locator('.toast')).to_have_count(0)
            # Clock and decision survive host reload, without a second timeout action.
            host.reload()
            expect(host.locator('.action-card.occupied')).to_have_count(1)
            expect(host.locator('.turn-countdown')).to_be_visible()
            # Remote votes over the same authoritative transport.
            host.get_by_role('button',name='Request takeback',exact=True).click()
            host.get_by_role('button',name='Ask for takeback',exact=True).click()
            expect(guest.get_by_role('dialog',name='Takeback vote',exact=True)).to_be_visible()
            guest.get_by_role('button',name='Approve takeback',exact=True).click()
            for p in [host,guest]:
                p.get_by_role('dialog',name='Takeback approved',exact=True).get_by_role('button',name='Continue',exact=True).click()
                expect(p.locator('.action-card.occupied')).to_have_count(0)
            guest_ctx.close()
            ctx.close()
            print(('Peer' if peer else 'Server')+' real countdown, bot timeout, reload, remote takeback passed',flush=True)
        assert not errors,errors
        browser.close()

if __name__=='__main__': main(check,'test/eurozone/table_flow_browser_fixture.clj')
