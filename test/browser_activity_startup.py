"""Missing Discord handshake/auth replies produce a bounded, readable failure."""
import json
import os
from playwright.sync_api import expect, sync_playwright
import browser_discord as discord


def check(base):
    with sync_playwright() as pw:
        browser=pw.chromium.launch(headless=True,executable_path=os.environ['EUROZONE_BROWSER'])
        for command,label,milliseconds in [(None,'Connecting to Discord',31000),('AUTHORIZE','Discord sign-in',91000),('AUTHENTICATE','Discord authentication',31000)]:
            ctx=browser.new_context()
            parent=discord.PARENT.replace('USER',json.dumps({'id':'111','username':'Startup host'}))
            if command:
                parent=parent.replace('rpc.push(p.cmd);',f'rpc.push(p.cmd); if (p.cmd === {json.dumps(command)}) return;')
            else:
                parent=parent.replace("if (op === 0) reply", "if (op === 0) return; if (op === 0) reply")
            ctx.route(base+'/discord-test-parent',lambda route,request,parent=parent:route.fulfill(content_type='text/html',body=parent))
            page=ctx.new_page();page.clock.install();page.goto(base+'/discord-test-parent')
            if command:page.wait_for_function('command => rpc.includes(command)',arg=command)
            else:page.frames[1].wait_for_function("EUROZONE_BOOT_STAGE === 'Connecting to Discord…'")
            page.clock.fast_forward(milliseconds)
            expect(page.frame_locator('iframe').locator('body')).to_contain_text(label+' timed out.')
            expect(page.frame_locator('iframe').locator('body')).to_contain_text('Close and reopen the Activity to retry.')
            ctx.close()
        browser.close()
    print('Discord ready, authorize and authenticate timeouts show readable errors instead of endless loading',flush=True)


if __name__=='__main__':
    discord.check=check
    discord.main()
