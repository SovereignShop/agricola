"""Camera regression: cached layout, interrupted fits, and manual resize."""
import os
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main


def check(base, fixtures):
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ['EUROZONE_BROWSER'])
        page = browser.new_page(viewport={'width': 1680, 'height': 1050})
        page.add_init_script('sessionStorage.setItem("eurozone-session", "' + 'a'*64 + '")')
        errors = []
        page.on('pageerror', lambda e: errors.append(str(e)))
        page.goto(base + '/#' + fixtures.split(',')[0])
        viewport = page.locator('.tt-viewport')
        expect(page.locator('.tt-player')).to_have_count(5)
        page.wait_for_timeout(600)
        # Instrument actual viewport dimension reads. Camera input must use the
        # resize cache even across separate frames, not just within one event.
        viewport.evaluate('''e => {
            window.cameraReads = 0;
            window.tableRenders = 0;
            const table = document.querySelector('.tt-game');
            const key = Object.keys(table).find(k => k.startsWith('__reactInternalInstance$') || k.startsWith('__reactFiber$'));
            let fiber = table[key];
            while (fiber && !(fiber.stateNode && typeof fiber.stateNode.render === 'function')) fiber = fiber.return;
            if (!fiber) throw new Error('Tabletop component not found');
            const component = fiber.stateNode, render = component.render;
            component.render = function(...args) { window.tableRenders++; return render.apply(this, args); };
            for (const name of ['clientWidth', 'clientHeight']) {
                const get = Object.getOwnPropertyDescriptor(Element.prototype, name).get;
                Object.defineProperty(e, name, {configurable:true, get() {
                    window.cameraReads++; return get.call(this);
                }});
            }
        }''')
        initial = float(viewport.get_attribute('data-zoom'))
        for _ in range(12):
            viewport.evaluate('e => e.dispatchEvent(new WheelEvent("wheel", {deltaY:-10, ctrlKey:true, bubbles:true, cancelable:true}))')
            page.wait_for_timeout(20)
        assert float(viewport.get_attribute('data-zoom')) > initial * 1.4
        assert page.evaluate('cameraReads') == 0
        assert page.evaluate('tableRenders') == 0
        # A fit can be interrupted at its visible scale, without jumping to
        # the scale at which the animation began.
        page.get_by_role('button', name='Board view', exact=True).click()
        page.wait_for_timeout(100)
        result = viewport.evaluate('''e => {
            const before = Number(e.dataset.zoom);
            e.dispatchEvent(new WheelEvent('wheel', {deltaY:-10, ctrlKey:true, bubbles:true, cancelable:true}));
            return [before, Number(e.dataset.zoom), new DOMMatrix(getComputedStyle(document.querySelector('.tt-world')).transform).a];
        }''')
        assert abs(result[1] / result[0] - 1.0304545) < .001, result
        assert abs(result[1] - result[2]) < .00001, result
        page.wait_for_timeout(400)
        assert abs(float(viewport.get_attribute('data-zoom')) - result[1]) < .00001
        # Manual camera stays manual on resize, but its scroll extents and
        # centered origin must still reflect the new viewport dimensions.
        page.set_viewport_size({'width': 1100, 'height': 800})
        page.wait_for_timeout(200)
        dimensions = viewport.evaluate('''e => {
            const world = document.querySelector('.tt-world'), canvas = document.querySelector('.tt-canvas');
            const z = Number(e.dataset.zoom);
            return [parseFloat(world.style.left)-e.clientWidth/2,
                    parseFloat(world.style.top)-e.clientHeight/2,
                    parseFloat(canvas.style.width)-(e.clientWidth+Number(world.dataset.width)*z),
                    parseFloat(canvas.style.height)-(e.clientHeight+Number(world.dataset.height)*z)];
        }''')
        assert all(abs(n) < .02 for n in dimensions), dimensions
        assert abs(float(viewport.get_attribute('data-zoom')) - result[1]) < .00001
        assert not errors, errors
        print('PASS: zero tabletop renders and viewport dimension reads during wheel zoom, interrupted fit continuity, manual resize')
        browser.close()


if __name__ == '__main__':
    main(check, 'test/eurozone/tabletop_browser_fixture.clj')
