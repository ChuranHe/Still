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
let baseline, browser;
const created = [],
  result = { passed: false };
const adb = '.tools/platform-tools/adb';
let remote;
async function enter(id) {
  await evaluate(
    `document.getElementById('library-button').click();Still.selectAlbum(${JSON.stringify(id)})`,
  );
  await until(
    `__state.selectedAlbum&&__state.selectedAlbum.id===${JSON.stringify(id)}&&!document.getElementById('album-content').classList.contains('hidden')`,
  );
}
try {
  await send('Runtime.enable');
  await until('window.StillApp');
  await evaluate(
    `(()=>{window.__events=[];const receive=StillApp.receive,progress=StillApp.importProgress;StillApp.receive=s=>{window.__state=s;receive(s)};StillApp.importProgress=p=>{window.__progress=p;__events.push(p);progress(p)};Still.refresh()})()`,
  );
  baseline = await until('window.__state&&!__state.busy&&__state');
  assert.ok(baseline.selectedAlbum, 'Create an album before running the device suite');
  const suffix = Date.now();
  for (const letter of ['A', 'B']) {
    const name = 'Progress test ' + suffix + ' ' + letter;
    await evaluate(`Still.createAlbum(${JSON.stringify(name)})`);
    const state = await until(
      `__state.selectedAlbum&&__state.selectedAlbum.name===${JSON.stringify(name)}&&__state`,
    );
    created.push(state.selectedAlbum.id);
  }
  const [a, b] = created;
  await enter(a);
  await evaluate("document.getElementById('computer-upload').click()");
  const upload = await until('__state.uploads.running&&__state.uploads');
  await evaluate("document.getElementById('upload-close').click()");
  browser = await launchBrowser();
  const context = await browser.newContext(),
    tab = await context.newPage();
  tab.on('pageerror', (e) => errors.push(e.message));
  await tab.goto('http://127.0.0.1:8080/#code=' + upload.code + '&album=' + b);
  await tab.waitForFunction((id) => document.getElementById('album').value === id, b);
  await tab.evaluate(() => {
    window.progressEvents = [];
    new MutationObserver(() =>
      progressEvents.push({
        text: document.getElementById('current').textContent,
        overall: document.getElementById('overall-progress').value,
        current: document.getElementById('progress').getAttribute('value'),
      }),
    ).observe(document.getElementById('progress-box'), {
      subtree: true,
      childList: true,
      attributes: true,
      characterData: true,
    });
  });
  const network = await context.newCDPSession(tab);
  await network.send('Network.enable');
  await network.send('Network.emulateNetworkConditions', {
    offline: false,
    latency: 100,
    downloadThroughput: -1,
    uploadThroughput: 800000,
  });
  await tab
    .locator('#files')
    .setInputFiles([
      'build/progress-fixtures/large.jpg',
      'build/orientation-fixtures/still-v020-h-portrait.heif',
      'build/progress-fixtures/corrupt.jpg',
      'build/progress-fixtures/large.jpg',
    ]);
  await tab.locator('#send').click();
  const receiving = await until(
    'window.__progress&&__progress.stage==="receiving"&&__progress.bytes>0&&__progress.bytes<__progress.totalBytes&&__progress',
  );
  assert.equal(receiving.total, 4);
  assert.equal(receiving.percent, 0);
  assert.equal(receiving.albumId, b);
  assert.equal((await evaluate('__state.selectedAlbum')).id, a);
  assert.ok(
    !(await evaluate(
      'document.getElementById("library-import-progress").textContent.includes(".jpg")',
    )),
  );
  await shot('build/v100-portal-upload-progress.png');
  await tab.screenshot({ path: 'build/v100-browser-upload-progress.png', fullPage: true });
  await evaluate('document.getElementById("settings-button").click()');
  assert.equal(
    await evaluate(
      'document.getElementById("settings-import-progress").classList.contains("hidden")',
    ),
    false,
  );
  await evaluate('document.querySelector("#settings .close-panel").click()');
  assert.equal(
    await evaluate('document.getElementById("frame-import-progress").classList.contains("hidden")'),
    false,
  );
  await shot('build/v100-frame-upload-progress.png');
  await enter(a);
  result.progressAcrossPortalViews = true;
  const blocked = await tab.evaluate(
    async ({ code, album }) =>
      (
        await fetch(
          '/batch?action=start&album=' + album + '&batch=aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa&count=1',
          { method: 'POST', headers: { 'X-Still-Code': code } },
        )
      ).status,
    { code: upload.code, album: a },
  );
  assert.equal(blocked, 409);
  result.concurrentImportGuard = true;
  await tab.waitForFunction(
    () =>
      document.getElementById('overall-progress').value > 0 &&
      document.getElementById('overall-progress').value < 100,
  );
  await tab.waitForFunction(
    () => document.getElementById('current').textContent.startsWith('Finished.'),
    null,
    { timeout: 120000 },
  );
  assert.equal(await tab.locator('#overall-progress').evaluate((el) => el.value), 100);
  assert.equal(await tab.locator('#batch-count').textContent(), '4 of 4 photos processed');
  assert.match(
    await tab.locator('#summary').textContent(),
    /2 added · 1 already in album · 1 skipped/,
  );
  const events = await tab.evaluate(() => progressEvents);
  assert.ok(
    events.some((e) => e.text.startsWith('Saving photo') && e.current === null && e.overall < 100),
  );
  assert.ok(
    events.filter((e) => e.text.startsWith('Uploading photo')).every((e) => e.overall < 100),
  );
  const finished = await until(
    'window.__progress&&!__progress.active&&__progress.completed===4&&__progress',
  );
  assert.equal(finished.percent, 100);
  assert.equal(finished.added, 2);
  assert.equal(finished.duplicate, 1);
  assert.equal(finished.skipped, 1);
  assert.equal((await evaluate('__state.selectedAlbum')).id, a);
  result.batchUpload = true;
  result.savingStage = true;
  result.nativeReceiptProgress = true;
  console.log(
    'PASS: throttled batch transfer, native progress, saving stage, album isolation and concurrent-import rejection',
  );
  await network.send('Network.emulateNetworkConditions', {
    offline: false,
    latency: 0,
    downloadThroughput: -1,
    uploadThroughput: -1,
  });
  remote = '/sdcard/Android/data/com.portalframe/files/inbox/progress-' + suffix;
  execFileSync(adb, ['shell', 'mkdir', '-p', '/sdcard/Android/data/com.portalframe/files/inbox']);
  execFileSync(adb, ['push', 'build/progress-fixtures/local', remote], { stdio: 'pipe' });
  await evaluate('window.__events=[];Still.browse("local")');
  for (const label of [
    'Computer import inbox',
    'progress-' + suffix,
    'Import this folder and subfolders',
  ])
    execFileSync('python3', ['tests/tap_text.py', label], { timeout: 30000 });
  await until('__events.some(p=>p.source==="Local folder"&&p.active&&p.total===24)');
  await tab.waitForFunction(
    () =>
      document.getElementById('device-import-progress').textContent.includes('Local folder') &&
      !document.getElementById('device-import-progress').classList.contains('hidden'),
    null,
    { timeout: 30000 },
  );
  await shot('build/v100-portal-folder-progress.png');
  await tab.screenshot({ path: 'build/v100-browser-folder-progress.png', fullPage: true });
  const local = await until(
    'window.__progress&&__progress.source==="Local folder"&&!__progress.active&&__progress',
  );
  assert.equal(local.total, 24);
  assert.equal(local.completed, 24);
  assert.equal(local.added, 24);
  assert.equal(local.percent, 100);
  const localEvents = await evaluate('__events.filter(p=>p.source==="Local folder")');
  assert.ok(localEvents.some((p) => p.active && p.percent > 0 && p.percent < 100));
  await until('__state.photos.length===24&&!__state.busy');
  assert.equal(
    execFileSync(adb, ['shell', 'find', remote, '-type', 'f'], { encoding: 'utf8' }).trim(),
    '',
  );
  execFileSync(adb, ['shell', 'rmdir', remote]);
  remote = null;
  result.localFolderProgress = true;
  result.browserMonitorsFolder = true;
  result.nativeUpdatesDuringWorkerImport = true;
  console.log(
    'PASS: 24-photo folder import, live Portal progress while worker is busy, browser monitoring and original-copy cleanup',
  );
  // Rejected input before the file body still completes the batch instead of leaving a stuck bar.
  await tab.locator('#files').setInputFiles({
    name: 'unsupported.bmp',
    mimeType: 'image/bmp',
    buffer: Buffer.from('invalid'),
  });
  await tab.locator('#send').click();
  await tab.waitForFunction(() =>
    document.getElementById('current').textContent.startsWith('Finished.'),
  );
  await until(
    'window.__progress&&!__progress.active&&__progress.total===1&&__progress.completed===1',
  );
  assert.equal(await evaluate('__progress.skipped'), 1);
  assert.equal(await evaluate('__progress.percent'), 100);
  result.rejectedFileCompletesBatch = true;
  // Interrupt a real HTTP body and verify it never appears as a completed photo.
  const { createConnection } = await import('node:net');
  const socket = createConnection({ host: '127.0.0.1', port: 8080 });
  await new Promise((r, j) => {
    socket.once('connect', r);
    socket.once('error', j);
  });
  let response = '';
  socket.on('data', (b) => (response += b));
  const closed = new Promise((r) => socket.once('close', r));
  socket.write(
    'POST /upload?name=interrupted.jpg&album=' +
      b +
      ' HTTP/1.1\r\nHost: 127.0.0.1:8080\r\nX-Still-Code: ' +
      upload.code +
      '\r\nContent-Type: application/octet-stream\r\nContent-Length: 1048576\r\n\r\n',
  );
  socket.write(Buffer.alloc(2048));
  await until('window.__progress&&__progress.stage==="receiving"&&__progress.bytes===2048');
  socket.end();
  await closed;
  assert.ok(response === '' || response.startsWith('HTTP/1.1 400 '));
  const stopped = await until('window.__progress&&__progress.stage==="stopped"&&__progress');
  assert.equal(stopped.completed, 0);
  assert.equal(stopped.percent, 0);
  result.interruptedBody = true;
  await tab.setViewportSize({ width: 390, height: 844 });
  assert.ok(await tab.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
  await tab.screenshot({ path: 'build/v100-browser-mobile.png', fullPage: true });
  assert.deepEqual(errors, []);
  result.errors = errors;
  result.passed = true;
  console.log('PASS: rejected-file completion, interrupted-body recovery and mobile layout');
} finally {
  if (baseline) {
    await evaluate('Still.stopUploads()').catch(() => {});
    await until('!__state.busy&&(!window.__progress||!__progress.active)', 120000).catch(() => {});
    for (const id of created)
      if (!baseline.albums.some((a) => a.id === id))
        await evaluate(`Still.deleteAlbum(${JSON.stringify(id)})`).catch(() => {});
    await evaluate(`Still.selectAlbum(${JSON.stringify(baseline.selectedAlbum.id)})`).catch(
      () => {},
    );
    await until(
      `__state.albums.length===${baseline.albums.length}&&__state.selectedAlbum&&__state.selectedAlbum.id===${JSON.stringify(baseline.selectedAlbum.id)}`,
    ).catch(() => {});
    const restored = await evaluate('__state').catch(() => null);
    if (restored) {
      assert.deepEqual(restored.settings, baseline.settings);
      assert.deepEqual(
        restored.photos.map((p) => p.id).sort(),
        baseline.photos.map((p) => p.id).sort(),
      );
    }
    result.ownerDataPreserved = true;
    await evaluate('document.getElementById("start-show").click()').catch(() => {});
  }
  if (browser) {
    if (!result.passed) {
      const tabs = browser.contexts().flatMap((c) => c.pages());
      for (let i = 0; i < tabs.length; i++)
        await tabs[i]
          .screenshot({ path: 'build/v100-progress-failure-' + i + '.png', fullPage: true })
          .catch(() => {});
    }
    await browser.close();
  }
  ws.close();
  fs.writeFileSync('build/v100-progress-test-result.json', JSON.stringify(result, null, 2) + '\n');
}
