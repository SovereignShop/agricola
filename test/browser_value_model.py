"""Compare browser/main-worker features and predictions with native JVM samples."""
import json
import os
from pathlib import Path
from playwright.sync_api import sync_playwright, expect
import browser_discord as discord
import browser_activity_bots as bots

PREDICT = '''samples => samples.map(sample => {
 const state=cljs.reader.read_string(sample.state);
 const features=agricola.afterstate_value.features(state,sample.player);
 return {features:cljs.core.clj__GT_js(features),
   prediction:agricola.afterstate_value.predict(eurozone.play_values.model,features)};
})'''


def check(base):
    samples = json.loads(Path(os.environ['EUROZONE_VALUE_SAMPLES']).read_text())
    with sync_playwright() as pw:
        browser = pw.chromium.launch(headless=True, executable_path=os.environ['EUROZONE_BROWSER'])
        ctx = browser.new_context(viewport={'width': 1440, 'height': 1200})
        parent = discord.PARENT.replace('USER', json.dumps({'id': '111', 'username': 'Parity host'})).replace('instance-test', 'value-parity')
        ctx.route(base + '/discord-test-parent', lambda route: route.fulfill(content_type='text/html', body=parent))
        page = ctx.new_page(); page.goto(base + '/discord-test-parent')
        discord.enter_activity(page.frame_locator('iframe'),True)
        expect(page.frame_locator('iframe').get_by_label('Players expected')).to_be_visible()
        child = page.frames[1]
        with page.expect_worker() as created:
            child.evaluate("window.valueParityWorker = new Worker('/js/bot-worker.js')")
        worker = created.value
        for runtime in [child, worker]:
            actual = runtime.evaluate(PREDICT, samples)
            for expected, value in zip(samples, actual):
                assert value['features'].keys() == expected['features'].keys()
                for key, number in expected['features'].items():
                    assert abs(number - value['features'][key]) < 1e-9, (key, number, value['features'][key])
                assert abs(expected['prediction'] - value['prediction']) < 1e-8
        browser.close()
    print('Main browser and real worker: features and value predictions match JVM at five game stages', flush=True)
    bots.check(base, cases=[(2, 'draw')], mixed=False)


if __name__ == '__main__':
    discord.check = check
    discord.main()
