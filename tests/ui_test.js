// Renders assets/index.html with a stand-in bridge in three states and reports script errors.
// Usage: node ui_test.js <path to index.html> <folder for screenshots>. Needs Playwright with Chromium.
const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch();
  const errors = [];
  for (const [name, w, h, stub] of [['idle', 412, 500, null], ['rec', 412, 500, 'rec'], ['land', 480, 380, 'list']]) {
    const p = await b.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: 1 });
    p.on('pageerror', e => errors.push(name + ': ' + e.message));
    p.on('console', m => { if (m.type() === 'error') errors.push(name + ': ' + m.text()); });
    await p.addInitScript((stub) => {
      if (!stub) return;
      const files = [{name:'2026-11-11_01-40-00.frv',size:18200,time:2,ms:-0,codec:''},{name:'2026-11-10_18-02-11.frv',size:25600,time:1,ms:31000,codec:'AV1'}];
      window.Android = { calls: [],
        status: () => JSON.stringify(stub === 'rec' ? {rec:true,camera:true,avail:'0,2',w:240,h:320,ms:83000,bytes:18200,frames:166,vbps:4870,abps:1296,level:0.9,overload:false,encoder:'AV1 240x320, asked 5 kbps (c2.android.av1.encoder)'} : {camera:true,avail:'0,2',w:320,h:240,play:'2026-11-10_18-02-11.frv',pos:12000,dur:31000,notice:'Saved to Movies/FlatRaster/x.mp4'}),
        list: () => JSON.stringify(stub === 'rec' ? files : files.slice(1)),
        voiceModes: () => '["3200","2400","1600","1400","1300","1200","700C","450"]',
        config: (j) => window.Android.calls.push(j), newPalette(){}, startRec(){}, stopRec(){}, play(){}, stopPlay(){}, mp4(){}, remove(){}, rename(){return true}, share(){} };
    }, stub);
    await p.goto('file://' + process.argv[2]);
    await p.waitForTimeout(700);
    const info = await p.evaluate(() => ({
      overflowX: document.documentElement.scrollWidth > innerWidth,
      rec: document.getElementById('recb').textContent, rate: document.getElementById('rate').textContent,
      hevcDisabled: document.querySelector('#codec option[value="1"]').disabled, sizeDisabled: document.getElementById('size').disabled,
      voice: document.getElementById('voice').value, items: document.querySelectorAll('.item').length,
      buttons: [...document.querySelectorAll('.item button')].map(b => b.textContent + (b.disabled ? '(off)' : '')).join(','),
      cfg: window.Android && window.Android.calls ? window.Android.calls[0] : null, msg: document.getElementById('msg').textContent }));
    console.log(name, JSON.stringify(info));
    await p.screenshot({ path: process.argv[3] + '/ui_' + name + '.png' });
    await p.close();
  }
  // Rename and Delete must work without the browser's prompt/confirm boxes (a WebView does not show them).
  {
    const p = await b.newPage({ viewport: { width: 412, height: 700 } });
    p.on('pageerror', e => errors.push('edit: ' + e.message));
    p.on('dialog', d => { errors.push('edit: a browser dialog was opened: ' + d.type()); d.dismiss(); });
    await p.addInitScript(() => {
      window.calls = [];
      window.Android = { status: () => '{"camera":true,"avail":"0"}', list: () => '[{"name":"a.frv","size":100,"time":1,"ms":1000,"codec":"AV1"}]',
        voiceModes: () => '["1200"]', config(){}, newPalette(){}, startRec(){}, stopRec(){}, play(){}, stopPlay(){}, mp4(){}, share(){},
        remove: (n) => { window.calls.push('remove:' + n) }, rename: (n, to) => { window.calls.push('rename:' + n + '>' + to); return to !== 'bad' } };
    });
    await p.goto('file://' + process.argv[2]);
    await p.waitForTimeout(500);
    await p.click('text=Rename'); await p.fill('.item input', 'bad'); await p.click('text=Save name'); await p.waitForTimeout(400);
    const refused = await p.evaluate(() => document.getElementById('msg').textContent);
    await p.fill('.item input', 'holiday'); await p.press('.item input', 'Enter'); await p.waitForTimeout(400);
    await p.click('text=Delete'); await p.click('text=Cancel'); await p.waitForTimeout(400);
    await p.click('text=Delete'); await p.click('text=Yes, delete forever'); await p.waitForTimeout(400);
    const calls = await p.evaluate(() => window.calls.join(' '));
    const want = 'rename:a.frv>bad rename:a.frv>holiday remove:a.frv';
    console.log('edit', JSON.stringify({ calls, refused }));
    if (calls !== want) errors.push('edit: expected "' + want + '", got "' + calls + '"');
    if (!refused) errors.push('edit: a refused name showed no message');
    await p.screenshot({ path: process.argv[3] + '/ui_edit.png' });
    await p.close();
  }
  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
