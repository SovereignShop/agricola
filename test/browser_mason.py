"""Existing save: use Mason / Master Builder through the printed card and farm controls."""
import os
from playwright.sync_api import sync_playwright, expect
from browser_smoke import main


def check(base, ids):
    with sync_playwright() as pw:
        browser=pw.chromium.launch(headless=True,executable_path=os.environ['EUROZONE_BROWSER'])
        context=browser.new_context(viewport={'width':1440,'height':1000})
        context.add_init_script('sessionStorage.setItem("eurozone-session", "'+'a'*64+'")')
        page=context.new_page();errors=[]
        page.on('pageerror',lambda e:errors.append(str(e)))
        for game,name,rooms in zip(ids.split(','),['Mason','Master Builder'],[5,6]):
            page.goto(base+'/#'+game)
            page.get_by_role('button',name='Ada player view',exact=True).click()
            page.wait_for_timeout(450)
            deck=page.locator('[data-owner="0"][data-deck="occupations"]')
            deck.get_by_role('button',name=name,exact=True).click()
            page.get_by_role('dialog').get_by_role('button',name=name,exact=True).click()
            dialog=page.get_by_role('dialog',name=name,exact=True)
            ability=dialog.locator('.card-ability')
            expect(ability).to_be_visible()
            use=ability.get_by_role('button',name='Use card ability',exact=True)
            expect(use).to_be_disabled()
            revision=page.locator('.game').get_attribute('data-revision')
            # Invalid location does not enable the command or change the game.
            invalid=ability.get_by_role('button',name='Farm space 15: empty',exact=True)
            invalid.click()
            expect(invalid.locator('rect').first).to_have_attribute('fill','#f9dc7170')
            expect(use).to_be_disabled()
            invalid.click()
            expect(invalid.locator('rect').first).to_have_attribute('fill','transparent')
            cell=ability.get_by_role('button',name='Farm space 2: empty',exact=True)
            if name=='Mason': cell.click()
            else: cell.focus();page.keyboard.press('Enter')
            try: expect(use).to_be_enabled()
            except Exception:
                print(name,ability.inner_text(),flush=True)
                print('focused',page.evaluate('document.activeElement.outerHTML'),flush=True)
                raise
            expect(page.locator('.game')).to_have_attribute('data-revision',revision)
            page.screenshot(path='/tmp/eurozone-'+name.lower().replace(' ','-')+'-room.png')
            use.click()
            expect(page.locator('.game')).not_to_have_attribute('data-revision',revision)
            expect(ability.get_by_role('button',name='Farm space 2: room',exact=True)).to_be_visible()
            expect(use).to_be_disabled()
            page.get_by_role('button',name='Close dialog',exact=True).click()
            farm=page.locator('.tt-player[data-player="0"]')
            expect(farm.locator('[aria-label^="Farm space"][aria-label$=": room"]')).to_have_count(rooms)
            expect(farm.locator('.tt-player-title')).to_contain_text('2 / 2 workers ready')
            expect(farm.locator('[data-goods="food"] .pile-count')).to_have_text('3')
            expect(farm.locator('[data-goods="stone"],[data-goods="reed"],[data-goods="wood"]')).to_have_count(0)
            page.reload()
            expect(farm.locator('[aria-label^="Farm space"][aria-label$=": room"]')).to_have_count(rooms)
            page.get_by_role('button',name='Ada player view',exact=True).click()
            page.get_by_role('button',name='Farm actions',exact=True).click()
            ability=page.get_by_role('dialog').locator('.card-ability').filter(has=page.locator('summary',has_text=name))
            ability.locator('summary').click()
            expect(ability.get_by_role('button',name='Use card ability',exact=True)).to_be_disabled()
        assert not errors,errors
        print('Mason and Master Builder: saved game, mouse/keyboard room selection, free construction, unchanged workers, once-only use, reload passed')
        browser.close()

if __name__=='__main__': main(check,'test/eurozone/mason_browser_fixture.clj')
