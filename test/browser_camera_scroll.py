"""Wheel easing in the advanced build: timing, distances, cancellation, accessibility."""
import os
from playwright.sync_api import expect, sync_playwright
from browser_smoke import main


def check(base, fixtures):
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ['EUROZONE_BROWSER'])
        context = browser.new_context(viewport={'width': 1440, 'height': 1000})
        context.add_init_script('sessionStorage.setItem("eurozone-session", "'+'a'*64+'")')
        page = context.new_page()
        errors = []
        page.on('pageerror', lambda error: errors.append(str(error)))
        page.goto(base+'/#'+fixtures.split(',')[0])
        camera = page.locator('.tt-viewport')
        expect(page.locator('.tt-player')).to_have_count(5)
        page.wait_for_timeout(500)
        # Sample actual animation frames: intermediate positions, then exact
        # endpoints, without touching reactive game state or overshooting.
        result = camera.evaluate('''async el => {
          el.scrollLeft=200; el.scrollTop=200;
          const x=el.scrollLeft, y=el.scrollTop, began=performance.now(), frames=[];
          const event=new WheelEvent('wheel',{deltaY:120,bubbles:true,cancelable:true});
          el.dispatchEvent(event);
          const immediate=el.scrollTop;
          await new Promise(resolve=>requestAnimationFrame(function sample(time){
            frames.push([time-began,el.scrollTop-y]);
            if(time-began<180) requestAnimationFrame(sample); else resolve();
          }));
          return {x,y,immediate,frames,prevented:event.defaultPrevented,final:el.scrollTop};
        }''')
        assert result['prevented'] and result['immediate'] == result['y'], result
        intermediate = [v for _, v in result['frames'] if 0 < v < 120]
        assert len(intermediate) >= 2, result
        assert result['final']-result['y'] == 120, result
        assert all(0 <= v <= 120 for _, v in result['frames']), result
        assert [v for _, v in result['frames']] == sorted(v for _, v in result['frames']), result
        # A burst of fine diagonal deltas accumulates rather than dropping all
        # but the most recent event, and uses the same camera on both axes.
        start = camera.evaluate('e=>[e.scrollLeft,e.scrollTop]')
        camera.evaluate('''el=>{for(let i=0;i<10;i++) el.dispatchEvent(new WheelEvent('wheel',
          {deltaX:3,deltaY:5,bubbles:true,cancelable:true}));}''')
        page.wait_for_function('([x,y])=>{const e=document.querySelector(".tt-viewport");return e.scrollLeft===x && e.scrollTop===y}',arg=[start[0]+30,start[1]+50])
        # Starting a real drag interrupts the wheel coast immediately.
        camera.evaluate('el=>el.dispatchEvent(new WheelEvent("wheel",{deltaY:200,bubbles:true,cancelable:true}))')
        bounds=camera.bounding_box()
        x,y=bounds['x']+6,bounds['y']+12
        page.mouse.move(x,y);page.mouse.down()
        origin=camera.evaluate('e=>[e.scrollLeft,e.scrollTop]')
        page.mouse.move(x+30,y+30,steps=4);page.mouse.up()
        end=camera.evaluate('e=>[e.scrollLeft,e.scrollTop]')
        assert end == [origin[0]-30,origin[1]-30], (origin,end)
        page.wait_for_timeout(160)
        assert camera.evaluate('e=>[e.scrollLeft,e.scrollTop]') == end
        # Shift-wheel remains horizontal; line units are converted to pixels.
        camera.evaluate('el=>el.dispatchEvent(new WheelEvent("wheel",{deltaY:3,deltaMode:1,shiftKey:true,bubbles:true,cancelable:true}))')
        page.wait_for_function('(x)=>document.querySelector(".tt-viewport").scrollLeft===x',arg=end[0]+48)
        assert camera.evaluate('e=>e.scrollTop') == end[1]
        page.wait_for_timeout(100)  # Finish the preceding gesture before repositioning the fixture.
        # At an edge, further scrolling cannot build up a hidden target that
        # makes reversing direction feel stuck.
        camera.evaluate('''el=>{el.scrollTop=el.scrollHeight;el.dispatchEvent(new WheelEvent('wheel',
          {deltaY:1000,bubbles:true,cancelable:true}));el.dispatchEvent(new WheelEvent('wheel',
          {deltaY:-120,bubbles:true,cancelable:true}));}''')
        page.wait_for_function('()=>{const e=document.querySelector(".tt-viewport");return e.scrollTop===e.scrollHeight-e.clientHeight-120}')
        # Reduced-motion preference bypasses easing, including page-sized units.
        page.wait_for_timeout(100)
        page.emulate_media(reduced_motion='reduce')
        result=camera.evaluate('''el=>{el.scrollTop=0;el.dispatchEvent(new WheelEvent('wheel',
          {deltaY:1,deltaMode:2,bubbles:true,cancelable:true}));return [el.scrollTop,Math.min(el.clientHeight,el.scrollHeight-el.clientHeight)];}''')
        assert result[0] == result[1], result
        expect(page.get_by_role('dialog')).to_have_count(0)
        assert not errors, errors
        print('Wheel easing: multiple frames, exact distance, diagonal bursts, immediate drag cancellation, Shift/line/page units, edge reversal, reduced motion: passed')
        browser.close()


if __name__ == '__main__':
    main(check, 'test/eurozone/tabletop_browser_fixture.clj')
