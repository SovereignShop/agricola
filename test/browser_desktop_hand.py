"""Desktop left-hand placement, mobile defaults, saved coordinates and five-player packing."""
import os
from playwright.sync_api import expect,sync_playwright
from browser_smoke import main


def boxes(page):
    return page.locator('.tt-player, .tt-shared').evaluate_all('''els=>els.map(e=>({
        id:e.dataset.player??'board', x:parseFloat(e.style.left), y:parseFloat(e.style.top),
        w:e.offsetWidth,h:e.offsetHeight}))''')


def no_overlap(page):
    areas=boxes(page)
    assert len(areas)==6
    for i,a in enumerate(areas):
        assert a['x']>=0 and a['y']>=0,a
        for b in areas[i+1:]:
            assert a['x']+a['w']<=b['x'] or b['x']+b['w']<=a['x'] or a['y']+a['h']<=b['y'] or b['y']+b['h']<=a['y'],(a,b)
    return areas


def wait_fit(page,selector):
    page.wait_for_function('''selector=>{
      const v=document.querySelector('.tt-viewport'),t=document.querySelector(selector);
      const z=Math.max(.02,Math.min(1.5,(v.clientWidth-56)/t.offsetWidth,(v.clientHeight-48)/t.offsetHeight));
      return Math.abs(Number(v.dataset.zoom)-z)<.0001;
    }''',arg=selector)


def check(base,id):
    with sync_playwright() as pw:
        browser=pw.chromium.launch(headless=True,executable_path=os.environ['EUROZONE_BROWSER'])
        errors=[]
        ctx=browser.new_context(viewport={'width':1600,'height':1000})
        ctx.add_init_script('sessionStorage.setItem("eurozone-session","'+'a'*64+'")')
        page=ctx.new_page();page.on('pageerror',lambda e:errors.append(str(e)))
        page.goto(base+'/#'+id)
        own=page.locator('.tt-player[data-player="0"]')
        farm=own.locator('.tt-farm')
        occ=own.locator('[data-deck="hand-occupation"]')
        minor=own.locator('[data-deck="hand-minor"]')
        expect(occ).to_have_attribute('data-y','160')
        expect(page.locator('[data-deck^="hand-"]')).to_have_count(2)
        def left_of_farm():
            f=farm.bounding_box();o=occ.bounding_box();m=minor.bounding_box()
            assert o['x']+o['width']<m['x'] and m['x']+m['width']<f['x'],(f,o,m)
            assert abs(f['y']-o['y'])<2 and abs(f['y']-m['y'])<2
            inv=own.locator('.tt-inventory').bounding_box()
            assert abs(inv['x']-f['x'])<2
        left_of_farm();desktop=no_overlap(page)
        expect(page.locator('.tt-game')).to_have_attribute('data-view','table')
        table_zoom=float(page.locator('.tt-viewport').get_attribute('data-zoom'))
        page.get_by_role('button',name='Board view',exact=True).click()
        page.wait_for_function('(z)=>Number(document.querySelector(".tt-viewport").dataset.zoom)>z+.01',arg=table_zoom)
        expect(page.locator('.tt-game')).to_have_attribute('data-view','board')
        page.wait_for_function("Math.abs(document.querySelector('.tt-shared').getBoundingClientRect().x+document.querySelector('.tt-shared').getBoundingClientRect().width/2-(document.querySelector('.tt-viewport').getBoundingClientRect().x+document.querySelector('.tt-viewport').clientWidth/2))<3")
        expect(page.locator('.tt-shared')).to_be_in_viewport(ratio=.95)
        board_zoom=float(page.locator('.tt-viewport').get_attribute('data-zoom'))
        page.get_by_role('button',name='Zoom in',exact=True).click()
        page.get_by_role('button',name='Fit view',exact=True).click()
        expect(page.locator('.tt-shared')).to_be_in_viewport(ratio=.95)
        page.get_by_role('button',name='Table view',exact=True).click()
        expect(page.locator('.tt-game')).to_have_attribute('data-view','table')
        for area in page.locator('.tt-player, .tt-shared').all():
            expect(area).to_be_in_viewport(ratio=.95)
        page.wait_for_function('(z)=>Number(document.querySelector(".tt-viewport").dataset.zoom)<z',arg=board_zoom)
        wait_fit(page,'.tt-world')
        page.screenshot(path='/tmp/eurozone-hand-left-table.png')
        desktop_second_left=next(a['y'] for a in desktop if a['id']=='2')
        page.get_by_role('button',name='Ada player view',exact=True).click()
        expect(occ).to_have_attribute('data-mode','spread')
        expect(minor).to_have_attribute('data-mode','spread')
        wait_fit(page,'.tt-player[data-player="0"]')
        left_of_farm();no_overlap(page)
        expect(farm).to_be_in_viewport(ratio=.9)
        page.screenshot(path='/tmp/eurozone-hand-left-desktop.png')
        # Resizing changes defaults, without writing them as personal positions.
        page.get_by_role('button',name='Table view',exact=True).click()
        page.set_viewport_size({'width':390,'height':844})
        expect(occ).to_have_attribute('data-y','704')
        expect(occ).to_have_attribute('data-x','24')
        mobile=no_overlap(page)
        for area in page.locator('.tt-player, .tt-shared').all():
            expect(area).to_be_in_viewport(ratio=.95)
        page.get_by_role('button',name='Board view',exact=True).click()
        expect(page.locator('.tt-shared')).to_be_in_viewport(ratio=.95)
        page.get_by_role('button',name='Table view',exact=True).click()
        assert next(a['y'] for a in mobile if a['id']=='2')>desktop_second_left
        page.set_viewport_size({'width':1600,'height':1000})
        expect(occ).to_have_attribute('data-y','160')
        no_overlap(page)
        page.get_by_role('button',name='Ada player view',exact=True).click()
        expect(occ).to_have_attribute('data-mode','spread')
        # Left-side decks still nudge, save and reload in farm-relative coordinates.
        old_x=int(occ.get_attribute('data-x'))
        handle=occ.get_by_role('button',name='Move Hand · occupations',exact=True)
        handle.focus();handle.press('ArrowLeft')
        expect(occ).to_have_attribute('data-x',str(old_x-16))
        page.reload()
        expect(occ).to_have_attribute('data-x',str(old_x-16))
        # Existing positive coordinates keep their position relative to the farm.
        page.evaluate('''() => { const k='eurozone-tabletop-v1:' + sessionStorage.getItem('eurozone-session');
          localStorage.setItem(k,'{:arrangement-version 2 0 {:hand-occupation {:position [24 704]} :hand-minor {:position [176 704]}}}'); }''')
        page.reload()
        expect(occ).to_have_attribute('data-x','24')
        expect(occ).to_have_attribute('data-y','704')
        f=farm.bounding_box();o=occ.bounding_box()
        assert abs(f['x']-o['x'])<2 and o['y']>f['y']+f['height']
        # A wide phone remains on the mobile defaults because its input is touch.
        phone=browser.new_context(viewport={'width':960,'height':600},is_mobile=True,has_touch=True)
        phone.add_init_script('sessionStorage.setItem("eurozone-session","'+'b'*64+'")')
        pp=phone.new_page();pp.on('pageerror',lambda e:errors.append(str(e)))
        pp.goto(base+'/#'+id)
        expect(pp.locator('[data-deck="hand-occupation"]')).to_have_attribute('data-owner','1')
        expect(pp.locator('[data-deck="hand-occupation"]')).to_have_attribute('data-y','704')
        expect(pp.locator('.tt-player').first).to_have_attribute('data-player','1')
        no_overlap(pp)
        assert not errors,errors
        browser.close()
        print('PASS: desktop hand stacks/spreads left of farm, responsive five-player reflow, mobile/landscape unchanged, saved layouts and negative-coordinate movement')


if __name__=='__main__':main(check,'test/eurozone/desktop_hand_browser_fixture.clj')
