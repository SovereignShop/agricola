"""White English prints, a prerequisite-checked minor play and saved bake/feeding choices."""
import os
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main


def check(base, fixture_ids):
    art, steam, distill = fixture_ids.split(',')
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ['EUROZONE_BROWSER'])
        context = browser.new_context(viewport={'width':1680, 'height':1050})
        context.add_init_script('sessionStorage.setItem("eurozone-session", "'+'a'*64+'")')
        page = context.new_page(); errors = []
        page.on('pageerror', lambda e: errors.append(str(e)))
        def goods(resource, n):
            loc=page.locator('.tt-player[data-player="0"] .tt-inventory [data-goods="'+resource+'"] .pile-count')
            expect(loc).to_have_text(str(n)) if n else expect(loc).to_have_count(0)
        def resolve(): page.get_by_role('button', name='Resolve effect →', exact=True).click()
        page.goto(base+'/#'+art)
        prints=set()
        for deck, label, count in [('hand-occupation','Hand · occupations',11),('hand-minor','Hand · minors',9)]:
            page.locator('[data-owner="0"][data-deck="'+deck+'"]').get_by_role('button', name='Open '+label+' spread', exact=True).click()
            images=page.get_by_role('dialog').locator('img[src*="wizkids-white-"]')
            expect(images).to_have_count(count)
            for src in images.evaluate_all('els=>els.map(e=>e.getAttribute("src"))'):
                assert page.request.get(base+src).ok,src
                prints.add(src)
            page.get_by_role('button', name='Close dialog', exact=True).click()
        assert len(prints)==20
        page.locator('.action-card[data-action="meeting"]').click()
        page.get_by_role('group', name='Playable minor improvements', exact=True).get_by_role('button', name='Horse-Drawn Boat', exact=True).click()
        page.get_by_role('button', name='Place family member →', exact=True).click()
        expect(page.locator('[data-owner="0"][data-deck="improvements"] img[src*="wizkids-white-d041"]')).to_have_count(1)
        goods('wood',18)
        page.goto(base+'/#'+steam)
        expect(page.get_by_role('dialog', name='Resolve Steam Machine', exact=True)).to_be_visible()
        page.reload();resolve()
        expect(page.get_by_role('dialog')).to_have_count(0)
        goods('grain',0);goods('food',5)
        page.set_viewport_size({'width':390,'height':844})
        page.goto(base+'/#'+distill)
        expect(page.get_by_role('dialog', name='Resolve Schnapps Distillery', exact=True)).to_be_visible()
        page.reload();resolve()
        expect(page.get_by_role('dialog', name='Resolve Schnapps Distillery', exact=True)).to_have_count(0)
        goods('vegetable',0);goods('food',7)
        assert page.locator('body').evaluate('e=>e.scrollWidth<=innerWidth')
        assert not errors,errors
        print('PASS: twenty English prints, paid Horse-Drawn Boat, saved Steam Machine baking and mobile Schnapps Distillery', flush=True)
        browser.close()


if __name__ == '__main__': main(check, 'test/eurozone/wizkids_white_minors_browser_fixture.clj')
