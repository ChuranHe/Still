import assert from 'node:assert/strict';
import fs from 'node:fs';
import { execFileSync } from 'node:child_process';
import { launchBrowser } from './browser.mjs';
const page = (await (await fetch('http://127.0.0.1:9222/json')).json()).find(
  (p) => p.url === 'https://frame.local/index.html',
);
assert.ok(page);
let ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((r, j) => {
  ws.onopen = r;
  ws.onerror = j;
});
let sequence = 0;
const pending = new Map(),
  errors = [];
ws.onmessage = (e) => {
  const d = JSON.parse(e.data);
  if (d.method === 'Runtime.exceptionThrown') errors.push(d.params.exceptionDetails.text);
  if (pending.has(d.id)) {
    const p = pending.get(d.id);
    pending.delete(d.id);
    clearTimeout(p.timer);
    d.error ? p.reject(Error(JSON.stringify(d.error))) : p.resolve(d.result);
  }
};
function send(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = ++sequence,
      timer = setTimeout(() => reject(Error('CDP timeout')), 20000);
    pending.set(id, { resolve, reject, timer });
    ws.send(JSON.stringify({ id, method, params }));
  });
}
async function evaluate(expression) {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) throw Error(JSON.stringify(r.exceptionDetails));
  return r.result.value;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function until(expression, timeout = 30000) {
  const end = Date.now() + timeout;
  do {
    const x = await evaluate(expression);
    if (x) return x;
    await sleep(100);
  } while (Date.now() < end);
  throw Error('Timeout: ' + expression);
}
async function shot(path) {
  await sleep(350);
  const r = await send('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(path, Buffer.from(r.data, 'base64'));
}
let baseline, browser, working;
const created = [],
  result = { passed: false };
async function save(patch) {
  Object.assign(working, patch);
  await evaluate(`Still.save(${JSON.stringify(JSON.stringify(working))})`);
  for (const [k, v] of Object.entries(patch))
    await until(`__state.settings[${JSON.stringify(k)}]===${JSON.stringify(v)}`);
  if (patch.orientationMode && patch.orientationMode !== 'auto')
    await until(
      `__state.direction===${JSON.stringify(patch.orientationMode)}&&innerWidth===__state.width`,
    );
}
async function enter(id) {
  await evaluate(
    `document.getElementById('library-button').click();Still.selectAlbum(${JSON.stringify(id)})`,
  );
  await until(
    `__state.selectedAlbum&&__state.selectedAlbum.id===${JSON.stringify(id)}&&!document.getElementById('album-content').classList.contains('hidden')`,
  );
}
const active =
  '(()=>{const i=Array.from(document.querySelectorAll("#scene img")).find(i=>i.style.opacity==="1"&&i.complete&&i.naturalWidth>0);return i&&i.src.split("/").pop()})()';
async function choose(id) {
  await enter(albumA);
  await evaluate(`document.querySelector('[data-photo-id="${id}"]>button').click()`);
  await until(`${active}===${JSON.stringify(id)}`);
}
let albumA, albumB;
try {
  await send('Runtime.enable');
  await until('window.StillApp');
  await evaluate(
    '(()=>{const previous=StillApp.receive;StillApp.receive=s=>{window.__state=s;previous(s)};Still.refresh()})()',
  );
  baseline = await until('window.__state&&!__state.busy&&__state');
  working = { ...baseline.settings };
  assert.ok(baseline.selectedAlbum, 'Create an album before running the device suite');
  const old = process.argv[2] ? JSON.parse(fs.readFileSync(process.argv[2])) : baseline;
  assert.deepEqual(baseline.photos.map((p) => p.id).sort(), old.photos.map((p) => p.id).sort());
  assert.equal(await evaluate('document.querySelectorAll("#music-button,#i-music").length'), 0);
  await evaluate(
    "document.getElementById('library-button').click();document.getElementById('create-album').click()",
  );
  assert.equal(
    await evaluate('document.getElementById("album-name-dialog").classList.contains("hidden")'),
    false,
  );
  await shot('build/v090-create-dialog.png');
  await evaluate(
    "document.getElementById('album-name-input').value='Canceled album';document.getElementById('album-name-cancel').click()",
  );
  assert.equal(await evaluate('__state.albums.length'), baseline.albums.length);
  assert.equal(
    await evaluate('document.getElementById("library").classList.contains("hidden")'),
    false,
  );
  await evaluate(
    "document.getElementById('create-album').click();document.getElementById('album-name-input').value='Outside cancel'",
  );
  await send('Input.dispatchTouchEvent', { type: 'touchStart', touchPoints: [{ x: 30, y: 120 }] });
  await send('Input.dispatchTouchEvent', { type: 'touchEnd', touchPoints: [] });
  await until('document.getElementById("album-name-dialog").classList.contains("hidden")');
  assert.equal(await evaluate('__state.albums.length'), baseline.albums.length);
  assert.equal(await evaluate('document.querySelectorAll("#inbox-import").length'), 0);
  result.nativeCreateCancelOutside = true;
  console.log('PASS: Portal create dialog cancel and outside touch');
  const suffix = Date.now(),
    nameA = 'Still test ' + suffix + ' A';
  await evaluate(
    `document.getElementById('create-album').click();document.getElementById('album-name-input').value=${JSON.stringify(nameA)};document.getElementById('album-name-save').click()`,
  );
  const stateA = await until(
    `__state.selectedAlbum&&__state.selectedAlbum.name===${JSON.stringify(nameA)}&&__state`,
  );
  albumA = stateA.selectedAlbum.id;
  created.push(albumA);
  assert.equal(stateA.photos.length, 0);
  await evaluate("document.getElementById('computer-upload').click()");
  const upload = await until('__state.uploads.running&&__state.uploads');
  await until('document.getElementById("upload-qr").naturalWidth>0');
  const data = await evaluate('document.getElementById("upload-qr").src');
  fs.writeFileSync('build/album-upload-qr.gif', Buffer.from(data.split(',')[1], 'base64'));
  const decoded = execFileSync('build/decode_qr', ['build/album-upload-qr.gif'], {
    encoding: 'utf8',
    timeout: 15000,
  }).trim();
  assert.equal(decoded, upload.url + '#code=' + upload.code + '&album=' + albumA);
  result.qrDecoded = true;
  browser = await launchBrowser();
  const context = await browser.newContext();
  const tab = await context.newPage();
  tab.on('pageerror', (e) => errors.push(e.message));
  await tab.goto('http://127.0.0.1:8080/#code=' + upload.code + '&album=' + albumA);
  await tab.waitForFunction((id) => document.getElementById('album').value === id, albumA);
  const fixtures = fs
    .readdirSync('build/album-fixtures')
    .filter((x) => x.endsWith('.jpg'))
    .map((x) => 'build/album-fixtures/' + x);
  fixtures.push('build/orientation-fixtures/still-v020-h-portrait.heif');
  fixtures.push('build/album-fixtures/square.jpg');
  await tab.locator('#files').setInputFiles(fixtures);
  await tab.locator('#send').click();
  await tab.waitForFunction(
    () => document.getElementById('current').textContent.startsWith('Finished.'),
    null,
    { timeout: 120000 },
  );
  const summary = await tab.locator('#summary').textContent();
  assert.match(summary, /11 added · 1 already in album · 1 skipped/);
  result.uploadSummary = summary;
  await tab.screenshot({ path: 'build/albums-browser.png', fullPage: true });
  const lan = JSON.parse(
    execFileSync(
      '/usr/bin/curl',
      [
        '--fail',
        '--silent',
        '--show-error',
        '--connect-timeout',
        '3',
        '--max-time',
        '15',
        '--header',
        'X-Still-Code: ' + upload.code,
        '--header',
        'Content-Type: application/octet-stream',
        '--data-binary',
        '@build/album-fixtures/square.jpg',
        upload.url + 'upload?name=square.jpg&album=' + albumA,
      ],
      { encoding: 'utf8', timeout: 20000 },
    ),
  );
  assert.equal(lan.duplicate, 1);
  result.directLanUpload = true;
  const imported = await until('__state.photos.length===11&&!__state.busy&&__state');
  const byName = (name) => imported.photos.find((p) => p.name === name);
  assert.equal(imported.albums.find((a) => a.id === albumA).coverIds.length, 1);
  assert.deepEqual(
    imported.albums.find((a) => a.id === baseline.selectedAlbum.id).coverIds,
    baseline.albums.find((a) => a.id === baseline.selectedAlbum.id).coverIds,
  );
  result.defaultRandomSingleCover = true;
  for (const name of ['square.jpg', 'near-landscape.jpg', 'near-portrait.jpg'])
    assert.equal(byName(name).unrestricted, true);
  assert.deepEqual([byName('square.jpg').width, byName('square.jpg').height], [1280, 1280]);
  assert.deepEqual(
    [byName('near-landscape.jpg').width, byName('near-landscape.jpg').height],
    [1280, 1024],
  );
  assert.deepEqual(
    [byName('near-portrait.jpg').width, byName('near-portrait.jpg').height],
    [1024, 1280],
  );
  result.higherResolutionCopies = true;
  console.log('PASS: real JPEG/HEIF imports and larger uncropped dimensions');
  assert.equal(byName('near-landscape.jpg').width / byName('near-landscape.jpg').height, 1.25);
  assert.ok(byName('panorama.jpg').width / byName('panorama.jpg').height > 2.99);
  await evaluate("document.getElementById('upload-close').click()");
  await enter(albumA);
  assert.equal(
    await evaluate('getComputedStyle(document.querySelector("#photo-grid img")).backgroundColor'),
    'rgb(14, 32, 23)',
  );
  // Choose a single cover, then a collage. Canceling leaves the previous cover untouched.
  await evaluate(
    "document.getElementById('edit-album-cover').click();Array.from(document.querySelectorAll('#album-cover-grid .selected')).forEach(b=>b.click())",
  );
  const single = byName('square.jpg').id;
  await evaluate(
    `document.querySelector('#album-cover-grid [data-photo-id="${single}"]').click();document.getElementById('album-cover-save').click()`,
  );
  await until(
    `JSON.stringify(__state.albums.find(a=>a.id===${JSON.stringify(albumA)}).coverIds)===${JSON.stringify(JSON.stringify([single]))}`,
  );
  assert.deepEqual(
    await evaluate(`__state.albums.find(a=>a.id===${JSON.stringify(albumA)}).coverIds`),
    [single],
  );
  await evaluate("document.getElementById('all-albums').click()");
  await shot('build/v090-single-cover.png');
  await enter(albumA);
  await evaluate("document.getElementById('edit-album-cover').click()");
  const collage = [
    single,
    byName('camera-landscape.jpg').id,
    byName('camera-portrait.jpg').id,
    byName('panorama.jpg').id,
  ];
  for (const [i, id] of collage.slice(1).entries()) {
    await evaluate(`document.querySelector('#album-cover-grid [data-photo-id="${id}"]').click()`);
    const layout = await evaluate(
      '(()=>{const c=document.querySelector("#album-cover-preview .album-cover"),s=getComputedStyle(c),r=c.getBoundingClientRect();return {gap:s.gap,area:r.width*r.height,tiles:Array.from(c.children).map(img=>{const b=img.getBoundingClientRect();return {fit:getComputedStyle(img).objectFit,area:b.width*b.height}})}})()',
    );
    assert.equal(layout.tiles.length, i + 2);
    assert.equal(layout.gap, '0px');
    assert.ok(layout.tiles.every((t) => t.fit === 'cover'));
    assert.ok(
      Math.abs(layout.tiles.reduce((n, t) => n + t.area, 0) - layout.area) < layout.area * 0.001,
      JSON.stringify(layout),
    );
    assert.ok(new Set(layout.tiles.map((t) => Math.round(t.area))).size >= 2);
  }
  await evaluate(
    `document.querySelector('#album-cover-grid [data-photo-id="${byName('near-landscape.jpg').id}"]').click()`,
  );
  assert.equal(
    await evaluate('document.querySelectorAll("#album-cover-grid .selected").length'),
    4,
  );
  result.edgeToEdgeVariedCollage = true;
  await shot('build/v090-cover-picker.png');
  await evaluate("document.getElementById('album-cover-save').click()");
  await until(`__state.albums.find(a=>a.id===${JSON.stringify(albumA)}).coverIds.length===4`);
  assert.deepEqual(
    await evaluate(`__state.albums.find(a=>a.id===${JSON.stringify(albumA)}).coverIds`),
    collage,
  );
  await evaluate(
    "document.getElementById('edit-album-cover').click();document.querySelector('#album-cover-grid .selected').click();document.getElementById('album-modal-scrim').click()",
  );
  assert.deepEqual(
    await evaluate(`__state.albums.find(a=>a.id===${JSON.stringify(albumA)}).coverIds`),
    collage,
  );
  await evaluate("document.getElementById('all-albums').click()");
  assert.equal(
    await evaluate(
      `document.querySelectorAll('[data-album-id="${albumA}"] .album-cover img').length`,
    ),
    4,
  );
  await shot('build/v090-album-collage.png');
  // Restart the real app: custom cover survives and 1280-square copies must not be shrunk.
  const messageHandler = ws.onmessage;
  ws.close();
  execFileSync('.tools/platform-tools/adb', ['shell', 'am', 'force-stop', 'com.portalframe']);
  execFileSync('.tools/platform-tools/adb', [
    'shell',
    'am',
    'start',
    '-n',
    'com.portalframe/.MainActivity',
  ]);
  await sleep(1000);
  const pid = execFileSync('.tools/platform-tools/adb', ['shell', 'pidof', 'com.portalframe'], {
    encoding: 'utf8',
  }).trim();
  execFileSync('.tools/platform-tools/adb', [
    'forward',
    'tcp:9222',
    'localabstract:webview_devtools_remote_' + pid,
  ]);
  let next;
  for (let n = 0; n < 40 && !next; n++) {
    try {
      next = (await (await fetch('http://127.0.0.1:9222/json')).json()).find(
        (p) => p.url === 'https://frame.local/index.html',
      );
    } catch {}
    if (!next) await sleep(250);
  }
  assert.ok(next);
  ws = new WebSocket(next.webSocketDebuggerUrl);
  await new Promise((r, j) => {
    ws.onopen = r;
    ws.onerror = j;
  });
  ws.onmessage = messageHandler;
  await send('Runtime.enable');
  await until('window.StillApp');
  await evaluate(
    '(()=>{const previous=StillApp.receive;StillApp.receive=s=>{window.__state=s;previous(s)};Still.refresh()})()',
  );
  await until('window.__state&&!__state.busy');
  assert.deepEqual(
    await evaluate(`__state.albums.find(a=>a.id===${JSON.stringify(albumA)}).coverIds`),
    collage,
  );
  assert.deepEqual(
    await evaluate('__state.photos.find(p=>p.name==="square.jpg")').then((p) => [
      p.width,
      p.height,
    ]),
    [1280, 1280],
  );
  result.coverAndResolutionSurviveRestart = true;
  await enter(albumA);
  await evaluate('Still.startUploads()');
  const restartedUpload = await until('__state.uploads.running&&__state.uploads');
  await tab.goto('http://127.0.0.1:8080/#code=' + restartedUpload.code + '&album=' + albumA);
  await tab.waitForFunction((id) => document.getElementById('album').value === id, albumA);
  await tab.waitForFunction(
    (expected) => document.getElementById('code').value === expected,
    restartedUpload.code,
  );
  assert.equal(await tab.locator('#code').inputValue(), restartedUpload.code);
  result.reusedBrowserUsesNewSession = true;
  result.singleAndCollageCovers = true;
  console.log(
    'PASS: single/collage covers and resolution survive app restart; reused browser refreshes its QR code',
  );
  await save({
    seconds: 0,
    transition: 'none',
    orientationMode: 'portrait',
    photoDirections: 'matching',
    shuffle: true,
    background: 'paper',
  });
  for (const direction of ['portrait', 'landscape']) {
    await save({ orientationMode: direction });
    const state = await evaluate('__state');
    assert.equal(state.photos.filter((p) => p.eligible).length, 7);
    assert.equal(
      state.photos.find((p) => p.name === 'camera-' + direction + '.jpg').eligible,
      true,
    );
    assert.equal(
      state.photos.find(
        (p) =>
          p.name === 'camera-' + (direction === 'portrait' ? 'landscape' : 'portrait') + '.jpg',
      ).eligible,
      false,
    );
    for (const p of state.photos.filter((p) => p.unrestricted)) assert.equal(p.eligible, true);
  }
  await save({ orientationMode: 'portrait', background: 'paper' });
  await choose(byName('near-landscape.jpg').id);
  assert.equal(
    await evaluate('getComputedStyle(document.getElementById("scene")).backgroundColor'),
    'rgb(242, 236, 220)',
  );
  assert.equal(await evaluate('document.body.classList.contains("paper-mat")'), true);
  await shot('build/albums-paper-fit.png');
  await save({ background: 'black' });
  assert.equal(
    await evaluate('getComputedStyle(document.getElementById("scene")).backgroundColor'),
    'rgb(0, 0, 0)',
  );
  await enter(albumA);
  assert.equal(
    await evaluate('getComputedStyle(document.querySelector("#photo-grid img")).backgroundColor'),
    'rgb(14, 32, 23)',
  );
  await evaluate("document.getElementById('start-show').click()");
  await shot('build/albums-black-fit.png');
  result.previewsIgnoreMat = true;
  await save({ orientationMode: 'landscape', photoDirections: 'matching' });
  const eligible = (await evaluate('__state.photos.filter(p=>p.eligible).map(p=>p.id)')).sort();
  await until(active);
  let previous;
  for (let round = 0; round < 3; round++) {
    const ids = [];
    for (let i = 0; i < eligible.length; i++) {
      const id = await evaluate(active);
      ids.push(id);
      if (i === 2) {
        await evaluate('Still.refresh()');
        await sleep(150);
        assert.equal(await evaluate(active), id);
      }
      previous = id;
      await evaluate('document.getElementById("next").click()');
      await until(`${active}!==${JSON.stringify(previous)}`);
    }
    assert.deepEqual(ids.slice().sort(), eligible);
    assert.equal(new Set(ids).size, eligible.length);
  }
  result.fullShuffleRounds = 3;
  await choose(byName('square.jpg').id);
  const navigated = [await evaluate(active)];
  await evaluate('document.getElementById("previous").click()');
  await until(`${active}!==${JSON.stringify(navigated[0])}`);
  navigated.push(await evaluate(active));
  for (let i = 0; i < eligible.length - 2; i++) {
    const before = await evaluate(active);
    await evaluate('document.getElementById("next").click()');
    await until(`${active}!==${JSON.stringify(before)}`);
    navigated.push(await evaluate(active));
  }
  assert.deepEqual(navigated.sort(), eligible);
  result.previousPreservesRound = true;
  await save({ photoDirections: 'all' });
  assert.equal((await evaluate('__state.photos.filter(p=>p.eligible)')).length, 11);
  await save({ photoDirections: 'matching', orientationMode: 'portrait', background: 'paper' });
  await enter(albumA);
  assert.equal(
    await evaluate('document.querySelectorAll("#photo-grid p,#photo-grid small").length'),
    0,
  );
  assert.ok(
    !(await evaluate('document.getElementById("photo-grid").textContent.includes(".jpg")')),
  );
  assert.ok(
    !(await evaluate(
      'document.getElementById("weather-status").textContent.includes("Open-Meteo")',
    )),
  );
  assert.match(
    await evaluate('document.getElementById("capacity-total").textContent'),
    /16\.0 GB total/,
  );
  await shot('build/albums-gallery.png');
  const nameB = 'Still test ' + suffix + ' B';
  await tab.locator('#create-album').click();
  await tab.locator('#new-album-name').fill('Canceled browser album');
  await tab.locator('#cancel-album').click();
  assert.equal(await tab.locator('#album-modal').isVisible(), false);
  await tab.locator('#create-album').click();
  await tab.locator('#album-modal').click({ position: { x: 3, y: 3 } });
  assert.equal(await tab.locator('#album-modal').isVisible(), false);
  await tab.locator('#create-album').click();
  await tab.locator('#new-album-name').fill(nameB);
  await tab.locator('#save-album').click();
  result.browserCreateCancelOutside = true;
  await tab.waitForFunction((name) => {
    const s = document.getElementById('album');
    return s.selectedOptions.length && s.selectedOptions[0].textContent === name;
  }, nameB);
  albumB = await tab.locator('#album').inputValue();
  created.push(albumB);
  assert.notEqual(albumB, albumA);
  for (let i = 0; i < 2; i++) {
    await tab.locator('#files').setInputFiles('build/album-fixtures/square.jpg');
    await tab.locator('#send').click();
    await tab.waitForFunction(
      () => document.getElementById('current').textContent.startsWith('Finished.'),
      null,
      { timeout: 30000 },
    );
    assert.match(
      await tab.locator('#summary').textContent(),
      i === 0 ? /1 added/ : /1 already in album/,
    );
    if (i === 0) {
      const r = await evaluate('__state.report');
      assert.equal(r.duplicate, 1);
      assert.equal(r.added, 0);
    }
  }
  const state = await until(
    `__state.albums.find(a=>a.id===${JSON.stringify(albumB)}).count===1&&__state`,
  );
  assert.equal(state.selectedAlbum.id, albumA);
  assert.equal(state.photos.length, 11);
  assert.equal(
    state.storage.albumBytes,
    state.albums.reduce((n, a) => n + a.bytes, 0),
  );
  assert.equal(
    state.storage.others + state.storage.free + state.storage.albumBytes,
    state.storage.total,
  );
  assert.ok(state.storage.total >= state.storage.free);
  result.storage = state.storage;
  await enter(albumB);
  assert.equal((await evaluate('__state.photos')).length, 1);
  await evaluate(`Still.renameAlbum(${JSON.stringify(albumB)},'Still renamed test')`);
  await until("__state.selectedAlbum.name==='Still renamed test'");
  assert.equal(
    await evaluate(`__state.albums.find(a=>a.id===${JSON.stringify(albumB)}).covers.length`),
    1,
  );
  await evaluate(
    `Still.setAlbumCover(${JSON.stringify(albumB)},${JSON.stringify(JSON.stringify([single]))})`,
  );
  await sleep(250);
  await evaluate(`Still.remove(${JSON.stringify(albumB)},${JSON.stringify(single)})`);
  await until('__state.photos.length===0');
  assert.equal(
    await evaluate(`__state.albums.find(a=>a.id===${JSON.stringify(albumB)}).covers.length`),
    0,
  );
  result.deletedCoverFallsBack = true;
  await tab.setViewportSize({ width: 390, height: 844 });
  assert.ok(await tab.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
  await tab.screenshot({ path: 'build/albums-browser-mobile.png', fullPage: true });
  assert.deepEqual(errors, []);
  result.photosMigrated = baseline.photos.length;
  result.crossAlbumDuplicates = true;
  result.allRatios = true;
  result.noPhotoMetadata = true;
  result.errors = errors;
  result.passed = true;
  console.log(
    'PASS: create dialog cancel/outside on Portal and browser, edge-to-edge varied collages, random single covers and restart, higher-resolution JPEG/HEIF imports, preview backgrounds, deletion fallback, and slideshow/storage regressions',
  );
} finally {
  if (baseline) {
    await evaluate('Still.stopUploads()').catch(() => {});
    for (const id of created)
      if (!(baseline.albums || []).some((a) => a.id === id))
        await evaluate(`Still.deleteAlbum(${JSON.stringify(id)})`).catch(() => {});
    await evaluate(
      `Still.selectAlbum(${JSON.stringify(baseline.selectedAlbum.id)});Still.save(${JSON.stringify(JSON.stringify(baseline.settings))})`,
    ).catch(() => {});
    result.settingsRestored = true;
  }
  if (browser) {
    if (!result.passed) {
      const tabs = browser.contexts().flatMap((c) => c.pages());
      for (let i = 0; i < tabs.length; i++)
        await tabs[i]
          .screenshot({ path: 'build/v090-browser-failure-' + i + '.png', fullPage: true })
          .catch(() => {});
    }
    await browser.close();
  }
  ws.close();
  fs.writeFileSync(
    process.argv[3] || 'build/v090-test-result.json',
    JSON.stringify(result, null, 2) + '\n',
  );
}
