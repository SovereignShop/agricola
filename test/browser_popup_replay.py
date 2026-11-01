"""Dismissal cancels unsubmitted placement; replay picks expose their original packets."""
import json,os,re
from playwright.sync_api import sync_playwright,expect
from browser_smoke import main

def check(base,fixture):
    f=json.loads(fixture)
    with sync_playwright() as pw:
        browser=pw.chromium.launch(headless=True,executable_path=os.environ['EUROZONE_BROWSER'])
        context=browser.new_context(viewport={'width':1680,'height':1050})
        context.add_init_script('sessionStorage.setItem("eurozone-session", "'+'a'*64+'")')
        page=context.new_page();errors=[]
        page.on('pageerror',lambda e:errors.append(str(e)))
        page.goto(base+'/#'+f['table'])
        def action(name):
            page.locator('.tt-shared .action-card').filter(has=page.locator('strong',has_text=re.compile('^'+name+'$'))).click()
            expect(page.get_by_role('dialog')).to_have_count(1)
        def dismiss(how):
            if how=='backdrop':page.locator('.dialog-backdrop').last.click(position={'x':3,'y':3})
            elif how=='Escape':page.keyboard.press('Escape')
            else:page.get_by_role('button',name=how,exact=True).click()
        expect(page.locator('.tt-game')).to_be_visible()
        revision=page.locator('.game').get_attribute('data-revision')
        for how in ['Cancel','Close dialog','backdrop','Escape','Choose another space']:
            action('Farmland')
            page.get_by_role('dialog').get_by_role('button',name='Farm space 5: empty',exact=True).click()
            expect(page.get_by_role('dialog').locator('.selected-mark')).to_have_count(1)
            dismiss(how)
            expect(page.get_by_role('dialog')).to_have_count(0)
            expect(page.locator('.game')).to_have_attribute('data-revision',revision)
            action('Farmland')
            expect(page.get_by_role('dialog').locator('.selected-mark')).to_have_count(0)
            dismiss('Cancel')
        # Nested card inspection dismisses only the preview, not its parent action.
        action('Lessons')
        group=page.get_by_role('group',name='Playable occupations',exact=True)
        group.get_by_role('button',name='Wood Cutter',exact=True).click()
        expect(page.get_by_role('dialog')).to_have_count(2)
        dismiss('Escape')
        expect(page.get_by_role('dialog')).to_have_count(1)
        group.get_by_role('button',name='Wood Cutter',exact=True).click()
        page.get_by_role('button',name='Select card',exact=True).click()
        expect(group.locator('[aria-pressed=true]')).to_have_count(1)
        dismiss('Close dialog')
        expect(page.get_by_role('dialog')).to_have_count(0)
        action('Lessons')
        expect(group.locator('[aria-pressed=true]')).to_have_count(0)
        dismiss('Cancel')
        # A committed effect must not silently resolve or decline on dismissal.
        page.goto(base+'/#'+f['optional'])
        expect(page.get_by_role('dialog',name='Resolve Shaving Horse',exact=True)).to_be_visible()
        revision=page.locator('.game').get_attribute('data-revision')
        for how in ['Close dialog','backdrop','Escape']:
            dismiss(how)
            expect(page.get_by_role('dialog')).to_have_count(0)
            expect(page.locator('.game')).to_have_attribute('data-revision',revision)
            page.get_by_role('button',name='Go to my decision',exact=True).click()
            expect(page.get_by_role('dialog',name='Resolve Shaving Horse',exact=True)).to_be_visible()
        dismiss('Close dialog')
        page.goto(base)
        page.get_by_label('Open game recording').set_input_files(f['recording'])
        expect(page.locator('.replay-view')).to_be_visible(timeout=30000)
        def pool_at(i):
            expect(page.locator('.replay-controls')).to_contain_text(f'Move {i} /')
            pool=page.get_by_role('region',name='Draft pick pool',exact=True)
            expected=f['picks'][i-1]
            expect(pool.locator('.replay-pool-card')).to_have_count(len(expected['pool']))
            actual=set(pool.locator('.replay-pool-card').evaluate_all('els=>els.map(e=>e.dataset.card)'))
            assert actual==set(expected['pool']),(i,actual,set(expected['pool']))
            expect(pool.locator('[data-picked=true]')).to_have_attribute('data-card',expected['picked'])
            assert pool.get_by_text('Quota already filled',exact=True).count()==len(set(expected['pool'])-set(expected['legal']))
            return pool
        for i in range(1,len(f['picks'])+1):
            page.get_by_role('button',name='Next',exact=True).click()
            pool_at(i)
        # Final pick remains visible even though the resulting frame is round 1.
        pool=pool_at(len(f['picks']))
        expect(page.locator('.replay-controls')).to_contain_text('Round 1')
        expect(page.locator('.replay-grid .action-board')).to_be_visible()
        pool.locator('.hand-card').first.click()
        expect(page.get_by_role('dialog')).to_have_count(1)
        expect(page.get_by_role('button',name='Select card',exact=True)).to_have_count(0)
        dismiss('backdrop')
        page.get_by_role('button',name='Previous',exact=True).click();pool_at(len(f['picks'])-1)
        page.get_by_role('button',name='First',exact=True).click()
        expect(page.locator('.replay-draft-pool')).to_have_count(0)
        page.get_by_role('button',name='Next',exact=True).click();pool_at(1)
        page.screenshot(path='/tmp/eurozone-replay-draft-pool.png',full_page=True)
        page.set_viewport_size({'width':390,'height':844})
        assert page.locator('.replay-draft-pool').evaluate('e=>e.scrollWidth<=e.clientWidth')
        pool_at(1)
        assert not errors,errors
        browser.close()
        print('Cancel/X/backdrop/Escape reopen, cleared choices, nested and committed decisions; all 28 replay packets, final pick and mobile passed')

if __name__=='__main__':main(check,'test/eurozone/popup_replay_fixture.clj')
