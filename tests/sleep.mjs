// Native sensor/brightness/night-clock integration test. Restores all saved settings.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import crypto from 'node:crypto';
import { execFileSync } from 'node:child_process';

const adb = '.tools/platform-tools/adb';
const pages = await (await fetch('http://127.0.0.1:9222/json')).json();
const page =
  pages.find(
    (p) => p.url === 'https://frame.local/index.html' && JSON.parse(p.description || '{}').visible,
  ) || pages.find((p) => p.url === 'https://frame.local/index.html');
assert.ok(page, 'Still must be running as a debug build');
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((resolve, reject) => {
  ws.onopen = resolve;
  ws.onerror = reject;
});
let seq = 0;
const pending = new Map(),
  errors = [];
ws.onmessage = (event) => {
  const data = JSON.parse(event.data);
  if (data.method === 'Runtime.exceptionThrown') errors.push(data.params.exceptionDetails.text);
  if (pending.has(data.id)) {
    const p = pending.get(data.id);
    pending.delete(data.id);
    clearTimeout(p.timer);
    data.error ? p.reject(new Error(JSON.stringify(data.error))) : p.resolve(data.result);
  }
};
function send(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = ++seq,
      timer = setTimeout(() => reject(new Error('CDP timeout: ' + method)), 20000);
    pending.set(id, { resolve, reject, timer });
    ws.send(JSON.stringify({ id, method, params }));
  });
}
async function evaluate(expression) {
  const r = await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) throw new Error(JSON.stringify(r.exceptionDetails));
  return r.result.value;
}
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
async function until(expression, timeout = 20000) {
  const end = Date.now() + timeout;
  do {
    try {
      const value = await evaluate(expression);
      if (value) return value;
    } catch (e) {
      if (!/context|Context/.test(e.message)) throw e;
    }
    await sleep(150);
  } while (Date.now() < end);
  throw new Error('Timed out: ' + expression);
}
async function click(id) {
  await evaluate(`document.getElementById(${JSON.stringify(id)}).click()`);
}
function shot(name) {
  fs.writeFileSync('build/' + name, execFileSync(adb, ['exec-out', 'screencap', '-p']));
}
function clock() {
  return JSON.parse(
    execFileSync('python3', ['tests/read_night_clock.py'], { encoding: 'utf8', timeout: 25000 }),
  );
}
function assertCenter(value, width, height) {
  assert.ok(Math.abs(value.center[0] - width / 2) <= 2);
  assert.ok(Math.abs(value.center[1] - height / 2) <= 2);
}
const photoSource =
  "Array.from(document.querySelectorAll('#scene img')).find(img=>img.style.opacity==='1')?.src";
const time = (minute) =>
  String(Math.floor(((minute + 1440) % 1440) / 60)).padStart(2, '0') +
  ':' +
  String((minute + 1440) % 60).padStart(2, '0');
let baseline, working;
const result = { passed: false };
async function save(patch) {
  Object.assign(working, patch);
  await evaluate(`Still.save(${JSON.stringify(JSON.stringify(working))})`);
  for (const [key, value] of Object.entries(patch))
    await until(`window.__state.settings[${JSON.stringify(key)}]===${JSON.stringify(value)}`);
  if (patch.orientationMode && patch.orientationMode !== 'auto')
    await until(
      `window.__state.direction===${JSON.stringify(patch.orientationMode)} && innerWidth===window.__state.width && innerHeight===window.__state.height`,
    );
}
try {
  await send('Runtime.enable');
  await evaluate('window.__sleepReloadMarker=true');
  await send('Page.reload');
  await until('!window.__sleepReloadMarker && window.StillApp && window.Still');
  await evaluate(
    '(()=>{const original=window.StillApp.receive;window.StillApp.receive=s=>{window.__state=s;original(s)};Still.refresh();})()',
  );
  baseline = await until(
    'window.__state && window.__state.sleep.available && typeof window.__state.sleep.lux==="number" && window.__state',
  );
  working = { ...baseline.settings };
  fs.writeFileSync('build/sleep-baseline.json', JSON.stringify(baseline, null, 2));
  result.sensor = baseline.sleep;
  assert.match(baseline.sleep.sensor, /tcs34x0/);
  assert.ok(
    baseline.sleep.lux > 0 && baseline.sleep.lux < 1000,
    'Room light must be above zero and below test threshold',
  );
  const [h, m] = baseline.sleep.localTime.split(':').map(Number),
    minute = h * 60 + m;
  const during = { sleepStart: time(minute - 2), sleepEnd: time(minute + 10) };
  const outside = { sleepStart: time(minute + 30), sleepEnd: time(minute + 60) };
  await save({
    sleepEnabled: false,
    ...during,
    sleepLux: 1000,
    orientationMode: 'landscape',
    photoDirections: 'all',
    seconds: 5,
    transition: 'none',
    clock: true,
    hour24: false,
    weather: false,
    cropFourThree: true,
  });

  // Use only our own inbox folder; leave any existing user photos untouched.
  await evaluate("Still.browse('local')");
  for (const label of [
    'Computer import inbox',
    'still-sleep-tests',
    'Import this folder and subfolders',
  ])
    execFileSync('python3', ['tests/tap_text.py', label], { stdio: 'pipe', timeout: 25000 });
  const imported = await until(
    'window.__state.report.seen===2 && !window.__state.busy && window.__state',
  );
  const originalIds = new Set(baseline.photos.map((p) => p.id));
  const known = new Set(
    fs.readdirSync('build/sleep-fixtures').map(
      (name) =>
        crypto
          .createHash('sha256')
          .update(fs.readFileSync('build/sleep-fixtures/' + name))
          .digest('hex') + '.jpg',
    ),
  );
  const newIds = imported.photos
    .filter((p) => known.has(p.id) && !originalIds.has(p.id))
    .map((p) => p.id);
  fs.writeFileSync('build/sleep-new-fixture-ids.json', JSON.stringify(newIds));
  assert.ok(imported.photos.filter((p) => known.has(p.id)).length === 2);
  await sleep(6200);
  assert.equal(await evaluate('window.__state.sleep.active'), false);
  console.log(
    'PASS: live light sensor, settings validation, folder import, and disabled sleep gate',
  );

  await click('settings-button');
  await click('preview-sleep');
  await until('window.__state.sleep.active && window.__state.sleep.preview');
  const preview = clock();
  assertCenter(preview, 1280, 800);
  assert.match(preview.text, / [AP]M$/);
  shot('sleep-preview-landscape.png');
  await until('!window.__state.sleep.active && !window.__state.sleep.preview', 20000);
  assert.equal(await evaluate('window.__state.sleep.brightness'), -1);
  await evaluate("document.querySelector('#settings .close-panel').click()");
  console.log(
    'PASS: centered native clock preview, 12-hour format, 15-second expiry and brightness restoration',
  );

  await save({ sleepEnabled: true, ...outside, sleepLux: 1000 });
  await sleep(6200);
  assert.equal(await evaluate('window.__state.sleep.active'), false);
  assert.equal(await evaluate('window.__state.sleep.scheduled'), false);
  await save({ ...during, sleepLux: 0 });
  await sleep(6200);
  assert.equal(await evaluate('window.__state.sleep.active'), false);
  assert.equal(await evaluate('window.__state.sleep.scheduled'), true);
  console.log('PASS: both the time window and low light are required');

  await save({ sleepLux: 1000, hour24: true });
  await evaluate("Still.browse('usb')"); // Night entry must also dismiss any app-owned folder dialog.
  const night = await until(
    'window.__state.sleep.active && !window.__state.sleep.preview && window.__state.sleep',
    12000,
  );
  assert.ok(Math.abs(night.brightness - 1 / 255) < 1e-7);
  result.automaticNight = night;
  const landscape = clock();
  assertCenter(landscape, 1280, 800);
  assert.match(landscape.text, /^\d{2}:\d{2}$/);
  const power = execFileSync(adb, ['shell', 'dumpsys', 'power'], { encoding: 'utf8' });
  assert.match(power, /mScreenBrightnessOverrideFromWindowManager=1\b/);
  fs.writeFileSync(
    'build/sleep-power-evidence.txt',
    power
      .split('\n')
      .filter((line) => /BrightnessOverride|mWakefulness=/.test(line))
      .join('\n') + '\n',
  );
  const display = execFileSync(adb, ['shell', 'dumpsys', 'display'], { encoding: 'utf8' });
  assert.match(display, /mScreenBrightnessRangeMinimum=1\b/);
  assert.match(display, /mScreenBrightness=1\s/);
  assert.match(display, /mBrightnessReason=override/);
  fs.writeFileSync(
    'build/sleep-display-evidence.txt',
    display
      .split('\n')
      .filter((line) =>
        /mScreenBrightnessRangeMinimum|mScreenBrightness=|mBrightnessReason=|mScreenBrightnessRampAnimator/.test(
          line,
        ),
      )
      .join('\n') + '\n',
  );
  result.panelBrightness = { minimum: 1, actual: 1, reason: 'override' };
  shot('sleep-automatic-landscape.png');
  const held = await evaluate(photoSource);
  assert.ok(held);
  await sleep(6200);
  assert.equal(await evaluate(photoSource), held, 'Slideshow must pause while sleeping');
  console.log(
    'PASS: actual light-triggered night mode, OS minimum brightness, black clock screen and slideshow pause',
  );

  await save({ orientationMode: 'portrait' });
  const portrait = clock();
  assertCenter(portrait, 800, 1280);
  shot('sleep-automatic-portrait.png');
  result.landscapeClock = landscape;
  result.portraitClock = portrait;
  execFileSync(adb, ['shell', 'input', 'tap', '400', '640']);
  await until('!window.__state.sleep.active && window.__state.sleep.brightness===-1');
  const awakePhoto = await evaluate(photoSource);
  await until('(' + photoSource + ') !== ' + JSON.stringify(awakePhoto), 9000);
  await sleep(1200);
  assert.equal(await evaluate('window.__state.sleep.active'), false);
  assert.match(await evaluate('window.__state.sleep.reason'), /Awake temporarily/);
  console.log(
    'PASS: portrait clock stays centered, tap wakes for five minutes and slideshow resumes',
  );

  await save({ ...outside, sleepLux: 999 });
  await sleep(6200);
  assert.equal(await evaluate('window.__state.sleep.active'), false);
  // Native validation must reject equal schedule times without overwriting preferences.
  const valid = await evaluate('window.__state.settings');
  await evaluate(
    `Still.save(${JSON.stringify(JSON.stringify({ ...valid, sleepStart: '00:00', sleepEnd: '00:00' }))})`,
  );
  await sleep(500);
  await evaluate('Still.refresh()');
  await sleep(300);
  assert.equal(await evaluate('window.__state.settings.sleepEnd'), valid.sleepEnd);
  assert.deepEqual(errors, []);
  result.passed = true;
  result.errors = errors;
  result.checks = [
    'disabled gate',
    'time and darkness gates',
    'preview expiry',
    'minimum app brightness',
    '12/24-hour native clock',
    'centered in both directions',
    'slideshow pause/resume',
    'five-minute touch override',
    'invalid schedule rejected',
  ];
} finally {
  if (baseline) {
    await evaluate(`Still.save(${JSON.stringify(JSON.stringify(baseline.settings))})`).catch(
      () => {},
    );
    await until('!window.__state.sleep.active && window.__state.sleep.brightness===-1').catch(
      () => {},
    );
    result.settingsRestored = true;
  }
  fs.writeFileSync('build/sleep-test-result.json', JSON.stringify(result, null, 2));
  ws.close();
}
