"""Community card test tables in the real Discord Activity/browser runtime."""
import json
import os
import re
from playwright.sync_api import expect, sync_playwright
import browser_discord

CARDS = [('trailblazer','Trailblazer'),('gardening-tools','Gardening Tools'),
         ('shovel','Shovel'),('singer','Singer'),('herdsman','Herdsman'),
         ('ruins','Ruins'),('baguette','Baguette'),('homework','Homework'),
         ('businesswoman','Businesswoman'),('debris-dealer','Debris Dealer'),
         ('steamer','Steamer'),('barber-shop','Barber Shop')]


def check(base):
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ['EUROZONE_BROWSER'])
        context = browser.new_context(viewport={'width':1440,'height':1000})
        parent = browser_discord.PARENT.replace('e.source.postMessage', 'e.source?.postMessage').replace('USER', json.dumps({'id':'111','username':'Ada'}))
        context.route(base+'/discord-test-parent', lambda route: route.fulfill(content_type='text/html', body=parent))
        page = context.new_page()
        errors = []
        page.on('pageerror', lambda e: errors.append(str(e)))
        page.goto(base+'/discord-test-parent')
        frame = page.frame_locator('iframe')
        try:
            for id, name in CARDS:
                expect(frame.get_by_role('heading',name='Activity tables',exact=True)).to_be_visible(timeout=20000)
                details=frame.locator('details').filter(has=frame.get_by_text('Card test tables',exact=True))
                if details.get_attribute('open') is None:
                    details.get_by_text('Card test tables',exact=True).click()
                frame.get_by_label('Card to test').select_option(id)
                frame.get_by_role('button',name='Open test table',exact=True).click()
                if id in ('debris-dealer','steamer','barber-shop'):
                    expect(frame.locator('.tt-player[data-player="0"]')).to_be_visible(timeout=20000)
                    assert frame.locator('.tt-deck img[src*="original-"]').count()>0
                else:
                    dialog=frame.get_by_role('dialog',name='Resolve '+name,exact=True)
                    expect(dialog).to_be_visible(timeout=20000)
                    if id=='trailblazer':
                        page.frames[1].evaluate('location.reload()')
                        expect(dialog).to_be_visible(timeout=20000)
                        farm=dialog.get_by_role('group',name="Ada's farm",exact=True)
                        farm.get_by_role('button',name='Farm space 2: empty',exact=True).click()
                        dialog.get_by_role('button',name='Resolve effect →',exact=True).click()
                        expect(dialog).to_have_count(0)
                        expect(frame.locator('.tt-player[data-player="0"] [aria-label$=": field"]')).to_have_count(2)
                        page.frames[1].evaluate('location.reload()')
                        expect(frame.locator('.tt-player[data-player="0"] [aria-label$=": field"]')).to_have_count(2)
                    elif id in ('gardening-tools','shovel'):
                        dialog.get_by_role('button',name='Take Sow action',exact=True).click()
                        dialog.get_by_label('Sow field 1',exact=True).select_option('grain')
                        dialog.get_by_role('button',name='Resolve effect →',exact=True).click()
                        expect(dialog).to_have_count(0)
                    elif id in ('singer','herdsman'):
                        for resource in (['stone','stone'] if id=='singer' else ['boar']):
                            dialog.get_by_label('Resource',exact=True).select_option(resource)
                            revision=frame.locator('.game').get_attribute('data-revision')
                            dialog.get_by_role('button',name='Resolve effect →',exact=True).click()
                            expect(frame.locator('.game')).not_to_have_attribute('data-revision',revision)
                        expect(dialog).to_have_count(0)
                    elif id=='ruins':
                        expect(dialog.get_by_text('Take the next Stone',exact=False)).to_be_visible()
                        dialog.get_by_role('button',name='Take resource →',exact=True).click()
                        expect(dialog).to_have_count(0)
                        marker=frame.locator('.tt-player[data-player="0"] .tt-card-markers').filter(has=frame.locator('[aria-label="Next resource on Ruins"]'))
                        expect(marker).to_contain_text('3')
                    elif id=='baguette':
                        dialog.get_by_role('button',name='Skip this effect',exact=True).click()
                        expect(dialog).to_have_count(0)
                    elif id in ('homework','businesswoman'):
                        target='Singer' if id=='homework' else 'Shovel'
                        dialog.get_by_role('button',name=target,exact=True).click()
                        preview=frame.get_by_role('dialog',name=target,exact=True)
                        expect(preview.locator('img[src*="original-"]')).to_have_count(1)
                        preview.get_by_role('button',name=('Play occupation →' if id=='homework' else 'Play improvement →'),exact=True).click()
                        expect(preview).to_have_count(0)
                        if id=='homework':
                            nested=frame.get_by_role('dialog',name='Resolve Singer',exact=True)
                            for resource in ['wood','reed']:
                                expect(nested).to_be_visible()
                                nested.get_by_label('Resource',exact=True).select_option(resource)
                                revision=frame.locator('.game').get_attribute('data-revision')
                                nested.get_by_role('button',name='Resolve effect →',exact=True).click()
                                expect(frame.locator('.game')).not_to_have_attribute('data-revision',revision)
                            expect(nested).to_have_count(0)
                        expect(dialog).to_be_visible()
                        dialog.get_by_role('button',name='Skip this effect',exact=True).click()
                        expect(dialog).to_have_count(0)
                if id=='steamer':
                    frame.locator('[data-owner="0"][data-deck="improvements"]').get_by_role('button',name='Open Played · improvements spread',exact=True).click()
                    frame.get_by_role('dialog').get_by_role('button',name='Steamer',exact=True).click()
                    inspection=frame.get_by_role('dialog',name='Steamer',exact=True)
                    inspection.get_by_label(re.compile('^Exchange')).select_option('1')
                    inspection.get_by_role('button',name='Use card ability',exact=True).click()
                    # Close inspection to expose the triggered Pelts decision.
                    inspection.get_by_role('button',name='Close dialog',exact=True).click()
                    frame.get_by_role('button',name='Go to my decision',exact=True).click()
                    pelts=frame.get_by_role('dialog',name='Resolve Pelts',exact=True)
                    expect(pelts).to_be_visible()
                    pelts.get_by_role('button',name='Resolve effect →',exact=True).click()
                    expect(pelts).to_have_count(0)
                if id=='debris-dealer':
                    frame.locator('.action-card[data-action="renovate"]').click()
                    frame.get_by_role('button',name='Place family member →',exact=True).click()
                    expect(frame.locator('.action-editor')).to_have_count(0)
                if id=='barber-shop':
                    frame.locator('.action-card[data-action="meeting"]').click()
                    frame.get_by_role('group',name='Playable minor improvements',exact=True).get_by_role('button',name='Barber Shop',exact=True).click()
                    frame.get_by_role('dialog',name='Barber Shop',exact=True).get_by_role('button',name='Place family member & play card →',exact=True).click()
                    expect(frame.locator('.action-editor')).to_have_count(0)
                page.screenshot(path='/tmp/eurozone-community-'+id+'.png')
                expect(frame.locator('[role=alert]')).to_have_count(0)
                close=frame.get_by_role('button',name='Close dialog',exact=True)
                if close.count():
                    close.last.click()
                    expect(close).to_have_count(0)
                frame.get_by_role('button',name='← Tables',exact=True).click()
                print('PASS:',name,'prepared table and workflow',flush=True)
            assert not errors, errors
        except Exception:
            print('BROWSER STATE:',page.frames[1].locator('body').inner_text()[:18000], 'ERRORS:',errors,flush=True)
            page.screenshot(path='/tmp/eurozone-community-failure.png')
            raise
        context.close()
        browser.close()


if __name__=='__main__':
    browser_discord.check=check
    browser_discord.main()
