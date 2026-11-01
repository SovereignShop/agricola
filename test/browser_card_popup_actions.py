"""Commit a card directly from its inspection, using the production server rules."""
import json,os,re
from playwright.sync_api import sync_playwright,expect
from browser_smoke import main

def check(base,fixture):
    ids=json.loads(fixture)
    with sync_playwright() as pw:
        browser=pw.chromium.launch(headless=True,executable_path=os.environ['EUROZONE_BROWSER'])
        context=browser.new_context(viewport={'width':1680,'height':1050})
        context.add_init_script('sessionStorage.setItem("eurozone-session", "'+'a'*64+'")')
        page=context.new_page();errors=[]
        page.on('pageerror',lambda e:errors.append(str(e)))
        def open_game(kind):
            page.goto(base+'/#'+ids[kind]);expect(page.locator('.game')).to_be_visible()
        def action(name):
            page.locator('.tt-shared .action-card').filter(has=page.locator('strong',has_text=re.compile('^'+name+'$'))).click()
        def commit(dialog,label):
            revision=page.locator('.game').get_attribute('data-revision')
            expect(dialog.get_by_role('button',name=label,exact=True)).to_be_enabled()
            dialog.get_by_role('button',name=label,exact=True).click()
            expect(page.locator('.game')).not_to_have_attribute('data-revision',revision)
            expect(page.locator('.app')).to_have_attribute('aria-busy','false')
            expect(page.get_by_role('alert')).to_have_count(0)
        direct='Place family member & play card →'
        open_game('occupation');action('Lessons')
        page.get_by_role('group',name='Playable occupations',exact=True).get_by_role('button',name='Wood Cutter',exact=True).click()
        dialog=page.get_by_role('dialog',name='Wood Cutter',exact=True)
        commit(dialog,direct)
        expect(page.locator('[data-owner="0"][data-deck="occupations"] .tt-card')).to_have_count(1)
        expect(page.get_by_role('dialog')).to_have_count(0)
        open_game('minor');action('Meeting Place')
        page.get_by_role('group',name='Playable minor improvements',exact=True).get_by_role('button',name='Fish Trap',exact=True).click()
        commit(page.get_by_role('dialog',name='Fish Trap',exact=True),direct)
        expect(page.locator('[data-owner="0"][data-deck="improvements"] .tt-card')).to_have_count(1)
        expect(page.get_by_role('dialog')).to_have_count(0)
        open_game('major');action('Major or Minor Improvement')
        page.get_by_role('group',name='Playable major improvements',exact=True).get_by_role('button',name='Fireplace',exact=True).click()
        commit(page.get_by_role('dialog',name='Fireplace',exact=True),direct)
        expect(page.locator('[data-major-slot="fireplace-2"]')).to_have_attribute('data-available','false')
        open_game('upgrade');action('Major or Minor Improvement')
        page.get_by_role('group',name='Playable major improvements',exact=True).get_by_role('button',name='Cooking Hearth',exact=True).first.click()
        dialog=page.get_by_role('dialog',name='Cooking Hearth',exact=True)
        expect(dialog).to_contain_text('Return Fireplace')
        commit(dialog,direct)
        expect(page.locator('[data-major-slot="fireplace-2"]')).to_have_attribute('data-available','true')
        expect(page.locator('[data-major-slot="hearth-4"]')).to_have_attribute('data-available','false')
        # Reopening a selected card must preserve an explicitly chosen payment.
        open_game('payment');action('Meeting Place')
        group=page.get_by_role('group',name='Playable minor improvements',exact=True)
        group.get_by_role('button',name='Baseboards',exact=True).click()
        page.get_by_role('dialog',name='Baseboards',exact=True).get_by_role('button',name='Select card',exact=True).click()
        page.get_by_label('Card payment',exact=True).select_option('1')
        group.get_by_role('button',name='Baseboards',exact=True).click()
        commit(page.get_by_role('dialog',name='Baseboards',exact=True),direct)
        expect(page.locator('.tt-player[data-player="0"] [data-goods="grain"]')).to_have_count(0)
        expect(page.locator('.tt-player[data-player="0"] [data-goods="food"] .pile-count')).to_have_text('13')
        open_game('scholar')
        expect(page.get_by_role('dialog',name='Resolve Scholar',exact=True)).to_be_visible()
        page.get_by_role('group',name='Playable occupations',exact=True).get_by_role('button',name='Wood Cutter',exact=True).click()
        dialog=page.get_by_role('dialog',name='Wood Cutter',exact=True)
        expect(dialog.get_by_role('button',name=direct,exact=True)).to_have_count(0)
        commit(dialog,'Play occupation →')
        expect(page.locator('[data-owner="0"][data-deck="occupations"] .tt-card')).to_have_count(2)
        open_game('draft')
        expect(page.locator('.draw-choices .hand-card').first).to_be_visible()
        page.locator('.draw-choices .hand-card').first.click()
        dialog=page.get_by_role('dialog')
        commit(dialog,'Keep card & pass left →')
        expect(page.locator('.draw-kept .hand-card')).to_have_count(1)
        expect(page.get_by_role('dialog')).to_have_count(0)
        expect(page.locator('.draw-waiting')).to_be_visible()
        assert not errors,errors
        browser.close()
        print('Direct popup: occupation, minor, major, upgrade payment, Scholar effect and draft pick passed')

if __name__=='__main__':main(check,'test/eurozone/card_popup_actions_fixture.clj')
