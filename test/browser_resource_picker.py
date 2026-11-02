"""Editable resource choices, contextual dialogs, mobile layout and persistence."""
import json
import os
from playwright.sync_api import expect, sync_playwright
import browser_discord


def check(base):
    with sync_playwright() as pw:
        browser=pw.chromium.launch(headless=True,executable_path=os.environ['EUROZONE_BROWSER'])
        for mobile in [False, True]:
            ctx=browser.new_context(viewport={'width':390 if mobile else 1440,'height':844 if mobile else 1000},has_touch=mobile,is_mobile=mobile)
            parent=browser_discord.PARENT.replace('<style>', '<meta name="viewport" content="width=device-width, initial-scale=1"><style>').replace('e.source.postMessage','e.source?.postMessage').replace('USER',json.dumps({'id':'111','username':'Ada'}))
            ctx.route(base+'/discord-test-parent',lambda route:route.fulfill(content_type='text/html',body=parent))
            page=ctx.new_page();errors=[];page.on('pageerror',lambda error:errors.append(str(error)))
            try:
                page.goto(base+'/discord-test-parent');frame=page.frame_locator('iframe')
                expect(frame.get_by_role('heading',name='Activity tables',exact=True)).to_be_visible()
                frame.get_by_text('Card test tables',exact=True).click()
                frame.get_by_label('Card to test').select_option('singer')
                frame.get_by_role('button',name='Open test table',exact=True).click()
                dialog=frame.get_by_role('dialog',name='Resolve Singer',exact=True)
                expect(dialog).to_be_visible(timeout=20000)
                expect(dialog.get_by_role('group',name="Ada's farm",exact=True)).to_have_count(0)
                expect(dialog.locator('svg.farm')).to_have_count(0)
                expect(dialog.get_by_role('combobox')).to_have_count(0)
                take=dialog.get_by_role('button',name='Take 2 resources →',exact=True)
                expect(take).to_be_disabled()
                revision=frame.locator('.game').get_attribute('data-revision')
                dialog.get_by_role('button',name='Add Wood',exact=True).click()
                dialog.get_by_role('button',name='Add Stone',exact=True).click()
                expect(take).to_be_enabled()
                expect(dialog.get_by_role('button',name='Add Reed',exact=True)).to_be_disabled()
                dialog.get_by_role('button',name='Remove Wood choice 1',exact=True).click()
                expect(dialog.get_by_role('button',name='Remove Stone choice 1',exact=True)).to_be_visible()
                expect(take).to_be_disabled()
                dialog.get_by_role('button',name='Add Stone',exact=True).click()
                expect(dialog.get_by_role('button',name='Remove Stone choice 2',exact=True)).to_be_visible()
                dialog.get_by_role('button',name='Clear choices',exact=True).click()
                expect(dialog.get_by_role('group',name='Selected resources',exact=True).get_by_role('button')).to_have_count(0)
                expect(frame.locator('.game')).to_have_attribute('data-revision',revision)
                # Closing and reopening edits the same uncommitted decision.
                dialog.get_by_role('button',name='Add Clay',exact=True).click()
                dialog.get_by_role('button',name='Close dialog',exact=True).click()
                frame.get_by_role('button',name='Go to my decision',exact=True).click()
                expect(dialog.get_by_role('button',name='Remove Clay choice 1',exact=True)).to_be_visible()
                dialog.get_by_role('button',name='Clear choices',exact=True).click()
                dialog.get_by_role('button',name='Add Stone',exact=True).dblclick()
                expect(dialog.get_by_role('button',name='Remove Stone choice 2',exact=True)).to_be_visible()
                box=dialog.bounding_box();width=390 if mobile else 1440
                assert box['x']>=0 and box['x']+box['width']<=width+1,box
                page.frames[1].wait_for_function('Array.from(document.querySelectorAll(".resource-choice-queue img")).every(img=>img.complete && img.naturalWidth>0)')
                page.screenshot(path='/tmp/eurozone-resource-picker-'+('mobile' if mobile else 'desktop')+'.png')
                take.click()
                expect(dialog).to_have_count(0)
                expect(frame.locator('.game')).not_to_have_attribute('data-revision',revision)
                stone=frame.locator('.tt-player[data-player="0"] .tt-inventory [data-goods="stone"] .pile-count')
                expect(stone).to_have_text('10')
                page.frames[1].evaluate('location.reload()')
                expect(stone).to_have_text('10',timeout=20000)
                expect(dialog).to_have_count(0)
                assert not errors,errors
                print('PASS:', 'mobile' if mobile else 'desktop','resource queue, independent removal, duplicates, clear, reopen, one confirmation and saved result',flush=True)
            except Exception:
                print(page.frames[1].locator('body').inner_text()[:12000],errors,flush=True)
                page.screenshot(path='/tmp/eurozone-resource-picker-failure.png')
                raise
            finally:
                ctx.close()
        browser.close()


if __name__=='__main__':
    browser_discord.check=check
    browser_discord.main()
