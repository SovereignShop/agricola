"""Graphic card decisions in Discord, including mobile, saves and Baguette's recipe."""
import json
import os
from playwright.sync_api import expect, sync_playwright
import browser_discord

CASES=[('baguette','Baguette'),('foreman','Foreman'),('fence-builder','Fence Builder'),
       ('fence-overseer','Fence Overseer'),('animal-breeder','Animal Breeder'),
       ('resource-seller','Resource Seller'),('silage','Silage'),('stable-planner','Stable Planner'),
       ('harvest-helper','Harvest Helper'),('hide-farmer','Hide Farmer'),
       ('clearing-spade','Clearing Spade'),('asparagus-knife','Asparagus Knife'),
       ('work-certificate','Work Certificate'),('stagehand','Stagehand'),('cottager','Cottager'),('copse','Copse')]


def check(base):
    with sync_playwright() as pw:
        browser=pw.chromium.launch(headless=True,executable_path=os.environ['EUROZONE_BROWSER'])
        for mobile in [False,True]:
            ctx=browser.new_context(viewport={'width':390 if mobile else 1440,'height':844 if mobile else 1000},has_touch=mobile,is_mobile=mobile)
            parent=browser_discord.PARENT.replace('<style>', '<meta name="viewport" content="width=device-width, initial-scale=1"><style>').replace('e.source.postMessage','e.source?.postMessage').replace('USER',json.dumps({'id':'111','username':'Ada'}))
            ctx.route(base+'/discord-test-parent',lambda route:route.fulfill(content_type='text/html',body=parent))
            page=ctx.new_page();errors=[];page.on('pageerror',lambda e:errors.append(str(e)))
            page.goto(base+'/discord-test-parent');frame=page.frame_locator('iframe')
            def amount(resource):
                marker=frame.locator(f'.tt-player[data-player="0"] .tt-inventory [data-goods="{resource}"] .pile-count')
                return int(marker.inner_text()) if marker.count() else 0
            def cell(dialog,n,kind='empty'):
                return dialog.get_by_role('button',name=f'Farm space {n}: {kind}',exact=True)
            def resolve(dialog):
                expect(dialog.get_by_role('combobox')).to_have_count(0)
                button=dialog.get_by_role('button',name='Resolve effect →',exact=True)
                expect(button).to_be_enabled();button.click()
            try:
                for id,name in CASES:
                    expect(frame.get_by_role('heading',name='Activity tables',exact=True)).to_be_visible(timeout=20000)
                    details=frame.locator('details').filter(has=frame.get_by_text('Card test tables',exact=True))
                    if details.get_attribute('open') is None: details.get_by_text('Card test tables',exact=True).click()
                    frame.get_by_label('Card to test').select_option(id)
                    frame.get_by_role('button',name='Open test table',exact=True).click()
                    expect(frame.locator('.tt-player[data-player="0"]')).to_be_visible(timeout=20000)
                    dialog=frame.get_by_role('dialog',name='Resolve '+name,exact=True)
                    if id not in ('clearing-spade','copse'):expect(dialog).to_be_visible(timeout=20000)
                    if id=='baguette':
                        expect(dialog.get_by_role('combobox')).to_have_count(0)
                        expect(dialog.get_by_role('spinbutton')).to_have_count(0)
                        expect(dialog.locator('svg.farm')).to_have_count(0)
                        expect(dialog.get_by_role('group',name='Baking exchange')).to_be_visible()
                        expect(dialog.locator('.bake-entry-cost [data-piece-type=wood]')).to_have_count(1)
                        expect(dialog.get_by_role('status',name='Receive 5 food',exact=True)).to_be_visible()
                        expect(dialog.get_by_role('button',name='Add grain to bake',exact=True)).to_be_disabled()
                        dialog.get_by_role('button',name='Remove grain to bake',exact=True).click()
                        expect(dialog.get_by_role('status',name='Receive 0 food',exact=True)).to_be_visible()
                        expect(dialog.get_by_role('button',name='Bake bread →',exact=True)).to_be_disabled()
                        dialog.get_by_role('button',name='Add grain to bake',exact=True).click()
                        dialog.get_by_role('button',name='Close dialog',exact=True).click()
                        frame.get_by_role('button',name='Go to my decision',exact=True).click()
                        expect(dialog.get_by_role('status',name='Receive 5 food',exact=True)).to_be_visible()
                        box=dialog.bounding_box();width=390 if mobile else 1440
                        assert box['x']>=0 and box['x']+box['width']<=width+1,box
                        assert dialog.evaluate('(el)=>el.scrollWidth<=el.clientWidth+1')
                        page.frames[1].wait_for_function('Array.from(document.querySelectorAll(".bake-workflow img")).every(i=>i.complete && i.naturalWidth>0)')
                        page.screenshot(path='/tmp/eurozone-baguette-'+('mobile' if mobile else 'desktop')+'.png')
                        before={r:amount(r) for r in ['food','wood','grain']}
                        dialog.get_by_role('button',name='Bake bread →',exact=True).click();expect(dialog).to_have_count(0)
                        for r,delta in [('food',5),('wood',-1),('grain',-1)]:
                            expect(frame.locator(f'.tt-player[data-player="0"] .tt-inventory [data-goods="{r}"] .pile-count')).to_have_text(str(before[r]+delta))
                        page.frames[1].evaluate('location.reload()')
                        expect(frame.locator('.tt-player[data-player="0"]')).to_be_visible(timeout=20000)
                        expect(frame.locator('.tt-player[data-player="0"] .tt-inventory [data-goods="wood"] .pile-count')).to_have_text(str(before['wood']-1))
                    elif id in ('foreman','fence-builder','work-certificate'):
                        space='grain' if id=='foreman' else 'wood'
                        dialog.locator(f'.board-picker [data-action={space}]').click()
                        page.screenshot(path=f'/tmp/eurozone-graphical-{id}-'+('mobile' if mobile else 'desktop')+'.png')
                        resolve(dialog)
                    elif id=='fence-overseer':cell(dialog,1).click();resolve(dialog)
                    elif id=='animal-breeder':dialog.get_by_role('button',name='2 Wild boar for 2 food',exact=True).click();resolve(dialog)
                    elif id=='resource-seller':dialog.get_by_role('group',name='Resource receipt order',exact=True).get_by_role('button').first.click();resolve(dialog)
                    elif id=='silage':
                        cell(dialog,3,'field').click()
                        dialog.get_by_role('group',name='Species to breed',exact=True).get_by_role('button',name='Sheep',exact=True).click();resolve(dialog)
                    elif id=='stable-planner':
                        for n in [4,7]:dialog.get_by_role('button',name=f'Reserve stable for round {n}',exact=True).click()
                        expect(dialog.get_by_role('button',name='Reserve stable for round 4',exact=True)).to_have_attribute('aria-pressed','true')
                        resolve(dialog)
                    elif id=='harvest-helper':
                        dialog.get_by_role('group',name="Partner's farm",exact=True).get_by_role('button',name='Farm space 1: field',exact=True).click();resolve(dialog)
                    elif id=='hide-farmer':
                        for n in [1,2]:cell(dialog,n).click()
                        resolve(dialog)
                    elif id=='clearing-spade':
                        frame.locator('[data-owner="0"][data-deck="improvements"]').get_by_role('button',name='Open Played · improvements spread',exact=True).click()
                        frame.get_by_role('dialog').get_by_role('button',name='Clearing Spade',exact=True).click()
                        inspection=frame.get_by_role('dialog',name='Clearing Spade',exact=True)
                        expect(inspection.get_by_role('combobox')).to_have_count(0)
                        cell(inspection,1,'field').click()
                        inspection.get_by_role('button',name='Field on Bean Field',exact=True).click()
                        expect(inspection.get_by_role('button',name='Use card ability',exact=True)).to_be_enabled()
                        inspection.get_by_role('button',name='Use card ability',exact=True).click()
                        expect(inspection.get_by_role('button',name='Use card ability',exact=True)).to_be_disabled()
                    elif id=='asparagus-knife':cell(dialog,1,'field').click();resolve(dialog)
                    elif id=='stagehand':
                        dialog.get_by_role('button',name='Build stables',exact=True).click();resolve(dialog)
                        for n in [1,2]:cell(dialog,n).click()
                        resolve(dialog)
                    elif id=='cottager':
                        dialog.get_by_role('button',name='Build one room',exact=True).click();cell(dialog,1).click();resolve(dialog)
                    elif id=='copse':
                        frame.locator('.action-card[data-action=sow-bake]').click()
                        action=frame.get_by_role('dialog')
                        for n in [1,2]:action.get_by_role('button',name=f'Field on Copse · stack {n}',exact=True).click()
                        expect(action.get_by_role('combobox')).to_have_count(0)
                        action.get_by_role('button',name='Place family member →',exact=True).click()
                        expect(action).to_have_count(0)
                    expect(frame.locator('[role=alert]')).to_have_count(0)
                    if id!='clearing-spade':expect(dialog).to_have_count(0)
                    # Inspections can remain open after an anytime ability; close before returning to lobby.
                    for _ in range(4):
                        close=frame.get_by_role('button',name='Close dialog',exact=True)
                        if not close.count():break
                        close.last.click()
                        expect(close).to_have_count(0)
                    frame.get_by_role('button',name='← Tables',exact=True).click()
                    assert not errors,errors
                    print('PASS:', 'mobile' if mobile else 'desktop',name,'graphical decision',flush=True)
            except Exception:
                print('STATE',page.frames[1].locator('body').inner_text()[:18000],'ERRORS',errors,flush=True)
                page.screenshot(path='/tmp/eurozone-graphical-failure.png');raise
            ctx.close()
        browser.close()

if __name__=='__main__':
    browser_discord.check=check
    browser_discord.main()
