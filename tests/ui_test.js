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
        voiceModes: () => '["best","balanced","small","smallest"]',
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
        voiceModes: () => '["best","balanced","small","smallest"]', config(){}, newPalette(){}, startRec(){}, stopRec(){}, play(){}, stopPlay(){}, mp4(){}, share(){},
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
  // Self-test panel: the button opens it, its buttons call the bridge, the report shows, SHARE waits for a finished report.
  {
    const p = await b.newPage({ viewport: { width: 412, height: 700 } });
    p.on('pageerror', e => errors.push('selftest: ' + e.message));
    await p.addInitScript(() => {
      window.calls = []; window.T = { on: false, done: false, step: '', text: '' };
      window.Android = { status: () => '{"camera":true,"avail":"0"}', list: () => '[]', voiceModes: () => '["best","balanced","small","smallest"]', config(){}, newPalette(){},
        startRec(){}, stopRec(){}, play(){}, stopPlay(){}, mp4(){}, share(){}, remove(){}, rename(){ return true },
        testState: () => JSON.stringify(window.T), testRun(){ window.calls.push('run') }, testClip(){ window.calls.push('clip') }, testStop(){ window.calls.push('stop') }, testShare(){ window.calls.push('share') }, testCopy(){ window.calls.push('copy') } };
    });
    await p.goto('file://' + process.argv[2]);
    await p.waitForTimeout(500);
    const hidden0 = await p.evaluate(() => document.getElementById('tp').hidden);
    await p.click('#tst'); await p.click('#trun'); await p.click('#tclip');
    await p.evaluate(() => { window.T = { on: true, done: false, step: 'Video encoders', text: 'Self-test\\nPhone: X' } }); await p.waitForTimeout(1300);
    const running = await p.evaluate(() => [document.getElementById('trun').hidden, document.getElementById('tstop').hidden, document.getElementById('trep').textContent.includes('Video encoders')].join());
    await p.evaluate(() => { window.T = { on: false, done: true, step: '', text: 'Self-test\\nEnd of report.' } }); await p.waitForTimeout(1300);
    const shareOn = await p.evaluate(() => !document.getElementById('tshare').disabled);
    await p.click('#tcopy'); await p.click('#tshare');
    const calls = await p.evaluate(() => window.calls.join(' '));
    console.log('selftest', JSON.stringify({ hidden0, running, shareOn, calls }));
    if (!hidden0 || running !== 'true,false,true' || !shareOn || calls !== 'run clip copy share') errors.push('selftest panel: ' + JSON.stringify({ hidden0, running, shareOn, calls }));
    if (await p.evaluate(() => document.documentElement.scrollWidth > innerWidth)) errors.push('selftest: sticks out sideways');
    await p.screenshot({ path: process.argv[3] + '/ui_selftest.png' });
    await p.close();
  }
  // Voice: DRED levels, default small, and a Codec 2 mode stored by 0.12 falls back to small.
  for (const stored of [null, '1200', 'smallest']) {
    const p = await b.newPage({ viewport: { width: 412, height: 700 } });
    p.on('pageerror', e => errors.push('voice: ' + e.message));
    await p.addInitScript((st) => {
      window.calls = [];
      if (st) localStorage.setItem('set', JSON.stringify({ voice: st }));
      window.Android = { status: () => '{"camera":true,"avail":"0"}', list: () => '[]', voiceModes: () => '["best","balanced","small","smallest"]',
        config: (c) => window.calls.push(JSON.parse(c).voice), newPalette(){}, startRec(){}, stopRec(){}, play(){}, stopPlay(){}, mp4(){}, share(){}, remove(){}, rename(){ return true } };
    }, stored);
    await p.goto('file://' + process.argv[2]); await p.waitForTimeout(300);
    const v = await p.evaluate(() => ({ value: document.getElementById('voice').value, text: document.getElementById('voice').selectedOptions[0].textContent, n: document.getElementById('voice').options.length }));
    const want = stored === 'smallest' ? 'smallest' : 'small';
    console.log('voice', stored, JSON.stringify(v));
    if (v.value !== want || v.n !== 4 || !v.text.startsWith('DRED ' + want)) errors.push('voice: stored ' + stored + ' gave ' + JSON.stringify(v));
    await p.close();
  }
  // Sound settings: Codec2 Recorder's defaults go to the app, fixed ones lock while recording, live ones don't.
  {
    const p = await b.newPage({ viewport: { width: 393, height: 1400 } });
    p.on('pageerror', e => errors.push('sound: ' + e.message));
    await p.addInitScript(() => {
      window.cfg = []; window.calls = []; window.recNow = false;
      window.Android = { status: () => JSON.stringify({camera:true,avail:'0',rec:window.recNow,ms:5000,bytes:900,vbps:3000,abps:600,level:.5,gain:4,vad:.4,routed:'Phone microphone #7',sampling:false,hasNoise:true,encoder:'AV1'}),
        list: () => '[]', voiceModes: () => '["best","balanced","small","smallest"]', microphones: () => '[{"id":7,"name":"Phone microphone #7"},{"id":9,"name":"USB #9"}]',
        config: (c) => window.cfg.push(JSON.parse(c)), sampleNoise: () => window.calls.push('sample'), clearNoise: () => window.calls.push('clear'),
        newPalette(){}, startRec(){}, stopRec(){}, play(){}, stopPlay(){}, mp4(){}, share(){}, remove(){}, rename(){ return true } };
    });
    await p.goto('file://' + process.argv[2]); await p.waitForTimeout(400);
    const c = await p.evaluate(() => window.cfg[window.cfg.length - 1]);
    const want = {mic:0,src:6,auto:true,rl:12,dn:true,st:0.18,rm:180,cp:3,ng:12,cl:6,tb:1,sens:5,hold:1};
    const bad = Object.keys(want).filter(k => c[k] !== want[k]);
    console.log('sound defaults', JSON.stringify(c));
    if (bad.length) errors.push('sound: defaults differ from Codec2 Recorder: ' + bad.join(','));
    const mics = await p.$$eval('#mic option', o => o.map(x => x.textContent).join('|'));
    if (mics !== 'Automatic|Phone microphone #7|USB #9') errors.push('sound: microphone list ' + mics);
    await p.selectOption('#rl', '18'); await p.selectOption('#mic', '9'); await p.selectOption('#skip', '1'); await p.selectOption('#sens', '8');
    const c2 = await p.evaluate(() => window.cfg[window.cfg.length - 1]);
    if (c2.auto !== false || c2.rl !== 18 || c2.mic !== 9 || c2.sens !== 8) errors.push('sound: changes not sent ' + JSON.stringify(c2));
    await p.click('#samp'); await p.click('#nclr');
    if ((await p.evaluate(() => window.calls.join(','))) !== 'sample,clear') errors.push('sound: noise buttons');
    await p.evaluate(() => { window.recNow = true }); await p.waitForTimeout(500);
    const lock = await p.evaluate(() => ['mic','src','dn','st','rm','cp','ng','cl','tb'].filter(id => !document.getElementById(id).disabled).concat(['rl','sens','hold'].filter(id => document.getElementById(id).disabled).map(x => 'live:' + x)));
    if (lock.length) errors.push('sound: wrong lock while recording: ' + lock.join(','));
    const enc = await p.evaluate(() => document.getElementById('enc').textContent);
    if (!enc.includes('gain 12 dB') || !enc.includes('Phone microphone')) errors.push('sound: status line ' + enc);
    await p.screenshot({ path: process.argv[3] + '/ui_sound.png', fullPage: true });
    await p.close();
  }
  console.log('errors:', JSON.stringify(errors));
  await b.close();
})();
