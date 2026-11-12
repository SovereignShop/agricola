"""On-play baking prompts: native construction, graphic choices, mobile and reload."""
import json
import os
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main
import browser_discord

CASES = ['clay-oven', 'stone-oven', 'bakers-oven', 'wood-fired-oven', 'bakers-kitchen',
         'bakehouse', 'grain-mill-oven', 'beehive-oven', 'errant-oven', 'meat-pie', 'pumpkin-pie']


def check(base, fixtures, modes=(False, True)):
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ['EUROZONE_BROWSER'])
        for mobile in modes:
            context = browser.new_context(viewport={'width': 390 if mobile else 1440, 'height': 844 if mobile else 1000},
                                          is_mobile=mobile, has_touch=mobile)
            if not mobile:
                context.add_init_script('sessionStorage.setItem("eurozone-session", "' + 'a' * 64 + '")')
            page = context.new_page()
            errors = []
            page.on('pageerror', lambda e: errors.append(str(e)))
            if mobile:
                parent = browser_discord.PARENT.replace('<style>', '<meta name="viewport" content="width=device-width, initial-scale=1"><style>').replace('e.source.postMessage', 'e.source?.postMessage').replace('USER', json.dumps({'id': '111', 'username': 'Ada'}))
                context.route(base + '/discord-test-parent', lambda route: route.fulfill(content_type='text/html', body=parent))
                page.goto(base + '/discord-test-parent')
                surface = page.frame_locator('iframe')
            else:
                page.goto(base)
                surface = page

            def open_table(id):
                details = surface.locator('details').filter(has=surface.get_by_text('Card test tables', exact=True))
                expect(details).to_be_visible(timeout=20000)
                if details.get_attribute('open') is None:
                    details.get_by_text('Card test tables', exact=True).click()
                surface.get_by_label('Card to test').select_option(id)
                surface.get_by_role('button', name='Open test table', exact=True).click()
                expect(surface.locator('.tt-player[data-player="0"]')).to_be_visible(timeout=20000)

            def reload_table():
                if mobile:
                    child = page.frames[1]
                    child.goto(child.url)
                else:
                    page.reload()

            def amount(resource):
                token = surface.locator(f'.tt-player[data-player="0"] .tt-inventory [data-goods="{resource}"] .pile-count')
                return int(token.inner_text()) if token.count() else 0

            try:
                # Reproduce the reported path, including the card preview's direct build button.
                open_table('bargaining-baker')
                surface.locator('.tt-shared .action-card').filter(has=surface.locator('strong', has_text='Major or Minor Improvement')).click()
                surface.get_by_role('group', name='Playable major improvements', exact=True).get_by_role('button', name='Clay Oven', exact=True).click()
                surface.get_by_role('dialog', name='Clay Oven', exact=True).get_by_role('button', name='Place family member & play card →', exact=True).click()
                dialog = surface.get_by_role('dialog').filter(has=surface.get_by_role('group', name='Baking exchange', exact=True))
                expect(dialog).to_be_visible()
                expect(dialog.get_by_role('status', name='Receive 5 food', exact=True)).to_be_visible()
                dialog.get_by_role('button', name='Bake bread →', exact=True).click()
                expect(surface.get_by_role('group', name='Baking exchange', exact=True)).to_have_count(0)
                surface.get_by_role('button', name='← Tables', exact=True).click()

                for id in CASES:
                    print(('mobile Discord' if mobile else 'desktop web') + ': ' + id, flush=True)
                    for skip in [False, True]:
                        open_table('baking-' + id)
                        dialog = surface.get_by_role('dialog').filter(has=surface.get_by_role('group', name='Baking exchange', exact=True))
                        expect(dialog).to_be_visible()
                        expect(dialog.get_by_role('combobox')).to_have_count(0)
                        expect(dialog.locator('svg.farm')).to_have_count(0)
                        expect(dialog.get_by_role('group', name='Your baking improvements', exact=True)).to_be_visible()
                        grain, food = amount('grain'), amount('food')
                        expected_food = int(dialog.locator('.bake-output strong').inner_text())
                        assert expected_food > 0
                        dialog.get_by_role('button', name='Remove grain to bake', exact=True).click()
                        expect(dialog.get_by_role('button', name='Bake bread →', exact=True)).to_be_disabled()
                        dialog.get_by_role('button', name='Add grain to bake', exact=True).click()
                        if id == 'clay-oven':
                            dialog.get_by_role('button', name='Close dialog', exact=True).click()
                            surface.get_by_role('button', name='Go to my decision', exact=True).click()
                            expect(dialog).to_be_visible()
                            reload_table()
                            expect(dialog).to_be_visible(timeout=20000)
                        assert dialog.evaluate('(el) => el.scrollWidth <= el.clientWidth + 1')
                        if id == 'clay-oven' and not skip:
                            page.screenshot(path=f'/tmp/baking-review-{"mobile" if mobile else "desktop"}.png')
                        dialog.get_by_role('button', name='Skip this effect' if skip else 'Bake bread →', exact=True).click()
                        expect(surface.get_by_role('group', name='Baking exchange', exact=True)).to_have_count(0)
                        assert amount('grain') == grain - (0 if skip else 1), id
                        assert amount('food') == food + (0 if skip else expected_food), id
                        reload_table()
                        expect(surface.locator('.tt-player[data-player="0"]')).to_be_visible(timeout=20000)
                        assert amount('grain') == grain - (0 if skip else 1), id
                        # Pie cards may offer their separate bonus recipe after baking.
                        followup = surface.get_by_role('dialog')
                        if followup.count():
                            followup.get_by_role('button', name='Skip this effect', exact=True).click()
                        surface.get_by_role('button', name='← Tables', exact=True).click()
                if not mobile:
                    for skip, room in zip([False, True], fixtures.split(',')):
                        page.goto(base + '/#' + room)
                        expect(page.locator('.tt-player[data-player="0"]')).to_be_visible()
                        page.locator('.tt-shared .action-card').filter(has=page.locator('strong', has_text='Major Improvement')).click()
                        page.get_by_role('group', name='Playable major improvements', exact=True).get_by_role('button', name='Clay Oven', exact=True).click()
                        preview = page.get_by_role('dialog', name='Clay Oven', exact=True)
                        expect(preview.get_by_role('button', name='Place family member & play card →', exact=True)).to_have_count(0)
                        preview.get_by_role('button', name='Select card', exact=True).click()
                        expect(page.get_by_role('group', name='Baking exchange', exact=True)).to_be_visible()
                        if not skip:
                            page.get_by_role('button', name='Add grain to bake', exact=True).click()
                            expect(page.get_by_role('status', name='Receive 5 food', exact=True)).to_be_visible()
                        page.get_by_role('button', name='Place family member →', exact=True).click()
                        expect(page.get_by_role('dialog')).to_have_count(0)
                        assert amount('grain') == (1 if skip else 0)
                        assert amount('food') == (3 if skip else 8)
                assert not errors, errors
            except Exception:
                page.screenshot(path='/tmp/baking-review-failure.png')
                print(surface.locator('body').inner_text()[:10000])
                raise
            finally:
                context.close()
        browser.close()
        print('PASS: construction prompt and all 11 on-play baking improvements; bake/decline and persistence; modes=' + str(modes))


if __name__ == '__main__':
    main(lambda base, fixtures: check(base, fixtures, (False,)), 'test/eurozone/improvement_baking_browser_fixture.clj')
    browser_discord.check = lambda base: check(base, None, (True,))
    browser_discord.main()
