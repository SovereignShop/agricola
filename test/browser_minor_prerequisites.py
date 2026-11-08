"""English Brushwood Roof prerequisites on desktop and mobile, using native play."""
import os,re
from playwright.sync_api import sync_playwright,expect
from browser_smoke import main

def check(base, ids):
 with sync_playwright() as pw:
  browser=pw.chromium.launch(headless=True,executable_path=os.environ['EUROZONE_BROWSER'])
  for width,room in zip([1440,390],ids.split(',')):
   context=browser.new_context(viewport={'width':width,'height':950},is_mobile=width<500,has_touch=width<500)
   context.add_init_script('sessionStorage.setItem("eurozone-session", "'+'a'*64+'")')
   page=context.new_page()
   def action(name):
    page.locator('.tt-shared .action-card').filter(has=page.locator('strong',has_text=re.compile('^'+name+'$'))).click()
   try:
    page.goto(base+'/#'+room)
    expect(page.locator('.tt-game')).to_be_visible()
    action('Meeting Place')
    roof=page.get_by_role('group',name='Playable minor improvements',exact=True).get_by_role('button',name='Brushwood Roof',exact=True)
    expect(roof).to_have_count(0)
    page.get_by_text("Unavailable cards",exact=True).click()
    expect(page.get_by_text("Brushwood Roof: Requires: 3 Occupations.",exact=True)).to_be_visible()
    page.get_by_role('button',name='Cancel',exact=True).click()
    action('Lessons')
    page.get_by_role('group',name='Playable occupations',exact=True).get_by_role('button',name='Clay Deliveryman',exact=True).click()
    page.get_by_role('button',name='Place family member →',exact=True).last.click()
    expect(page.get_by_role('dialog')).to_have_count(0)
    action('Grain Seeds')
    page.get_by_role('button',name='Place family member →',exact=True).click()
    expect(page.get_by_role('dialog')).to_have_count(0)
    action('Meeting Place')
    expect(roof).to_be_enabled()
    roof.click()
    page.get_by_role('button',name='Place family member →',exact=True).last.click()
    expect(page.get_by_role('dialog')).to_have_count(0)
    page.reload();expect(page.locator('.tt-game')).to_be_visible()
    print(f'Brushwood Roof blocks two occupations, allows three, reload: {width}px PASS',flush=True)
   except Exception:
    page.screenshot(path='/tmp/brushwood-browser-failure.png');open('/tmp/brushwood-browser-failure.html','w').write(page.content());raise
   context.close()
  browser.close()
if __name__=='__main__':main(check,'test/eurozone/minor_prerequisite_browser_fixture.clj')
