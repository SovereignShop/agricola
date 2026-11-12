"""Wheel bursts, reversals, horizontal modifier, and fast player focus."""
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
        # Deliberately burst several events into one frame: all distance must
        # survive, and each subsequent frame must advance without a restart.
        samples = viewport.evaluate('''async e => {
            e.scrollTop = 200;
            for(let i=0;i<10;i++) e.dispatchEvent(new WheelEvent('wheel', {deltaY:12, bubbles:true, cancelable:true}));
            const samples=[]; const start=performance.now();
            while(performance.now()-start<180) {
                await new Promise(requestAnimationFrame); samples.push(e.scrollTop);
            }
            return samples;
        }''')
        assert all(b >= a for a,b in zip(samples, samples[1:])), samples
        assert abs(samples[-1] - 320) < 1, samples
        # Reverse before the forward travel finishes. No lingering forward tail.
        samples = viewport.evaluate('''async e => {
            e.dispatchEvent(new WheelEvent('wheel', {deltaY:240, bubbles:true, cancelable:true}));
            await new Promise(requestAnimationFrame);
            const samples=[e.scrollTop];
            e.dispatchEvent(new WheelEvent('wheel', {deltaY:-120, bubbles:true, cancelable:true}));
            for(let i=0;i<12;i++) {await new Promise(requestAnimationFrame); samples.push(e.scrollTop);}
            return samples;
        }''')
        assert all(b <= a for a,b in zip(samples, samples[1:])), samples
        assert abs(samples[-1] - (samples[0]-120)) < 2, samples
        box=viewport.bounding_box()
        page.mouse.move(box['x']+15,box['y']+60)
        viewport.evaluate('e=>e.scrollLeft=300')
        page.mouse.down(button='right')
        start=viewport.evaluate('e=>[e.scrollLeft,e.scrollTop]')
        page.mouse.wheel(0,120)
        page.wait_for_timeout(180)
        end=viewport.evaluate('e=>[e.scrollLeft,e.scrollTop]')
        assert abs(end[0]-start[0]-120)<2 and end[1]==start[1], (start,end)
        z=viewport.get_attribute('data-zoom')
        page.keyboard.down('Control');page.mouse.wheel(0,-30);page.keyboard.up('Control')
        expect(viewport).not_to_have_attribute('data-zoom',z)
        # Release outside the viewport must restore vertical wheel input.
        page.mouse.move(5,5);page.mouse.up(button='right')
        page.mouse.move(box['x']+15,box['y']+60)
        start=viewport.evaluate('e=>[e.scrollLeft,e.scrollTop]')
        page.mouse.wheel(0,60);page.wait_for_timeout(180)
        end=viewport.evaluate('e=>[e.scrollLeft,e.scrollTop]')
        assert end[0]==start[0] and abs(end[1]-start[1]-60)<2, (start,end)
        # Player focus reaches its final scale promptly, monotonically, and
        # doesn't keep coasting after arrival.
        samples = page.evaluate('''async () => {
            document.querySelector('[aria-label="Ada player view"]').click();
            const e=document.querySelector('.tt-viewport'), samples=[], start=performance.now();
            while(performance.now()-start<500) {
                await new Promise(requestAnimationFrame);
                samples.push([performance.now()-start, Number(e.dataset.zoom)]);
            }
            return samples;
        }''')
        values=[s[1] for s in samples]
        direction=1 if values[-1]>values[0] else -1
        assert all((b-a)*direction>=-1e-8 for a,b in zip(values,values[1:])),samples
        first_motion=next(t for t,z in samples if abs(z-values[0])>1e-8)
        settled=[z for t,z in samples if t>=first_motion+260]
        assert settled and max(settled)-min(settled)<1e-8,samples
        page.emulate_media(reduced_motion='reduce')
        delta=viewport.evaluate('''e=>{e.scrollTop=200; e.dispatchEvent(new WheelEvent('wheel',{deltaY:60,bubbles:true,cancelable:true}));return e.scrollTop-200;}''')
        assert delta==60,delta
        assert not errors,errors
        print('PASS: wheel distance, monotonic frames, immediate reversal, right-wheel horizontal, zoom precedence, outside release, fast player focus, reduced motion')
        browser.close()


if __name__ == '__main__':
    main(check, 'test/eurozone/tabletop_browser_fixture.clj')
