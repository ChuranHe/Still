import assert from 'node:assert/strict';
import fs from 'node:fs';
import { execFileSync } from 'node:child_process';
import { connectFrame } from './cdp.mjs';

const adb = process.env.STILL_ADB || '.tools/platform-tools/adb';
let session = await connectFrame(),
  baseline;
const result = { passed: false };
const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));
async function capture() {
  await session.until('window.StillApp');
  await session.evaluate(
    '(()=>{const previous=StillApp.receive;StillApp.receive=s=>{window.__state=s;previous(s)};Still.refresh()})()',
  );
  return session.until('window.__state&&!__state.busy&&__state');
}
async function restart() {
  session.close();
  execFileSync(adb, ['shell', 'am', 'force-stop', 'com.portalframe']);
  execFileSync(adb, ['shell', 'am', 'start', '-n', 'com.portalframe/.MainActivity']);
  await sleep(1000);
  const pid = execFileSync(adb, ['shell', 'pidof', 'com.portalframe'], { encoding: 'utf8' }).trim();
  assert.match(pid, /^\d+$/);
  execFileSync(adb, ['forward', 'tcp:9222', 'localabstract:webview_devtools_remote_' + pid]);
  session = await connectFrame();
  return capture();
}
try {
  baseline = await capture();
  const original = baseline.settings;
  await session.evaluate('document.getElementById("settings-button").click()');
  assert.equal(await session.evaluate('document.querySelectorAll("#save-settings").length'), 0);
  const clock = !original.clock;
  await session.evaluate(
    `(()=>{const input=document.querySelector('[name="clock"]');input.checked=${clock};input.dispatchEvent(new Event('change',{bubbles:true}))})()`,
  );
  await session.until(`__state.settings.clock===${clock}`);
  assert.equal(
    await session.evaluate('document.getElementById("settings").classList.contains("hidden")'),
    false,
  );
  assert.equal(
    await session.evaluate('document.getElementById("toast").classList.contains("hidden")'),
    true,
  );
  await session.evaluate(
    `(()=>{const input=document.querySelector('[name="clock"]');input.checked=${original.clock};input.dispatchEvent(new Event('change',{bubbles:true}));input.checked=${clock};input.dispatchEvent(new Event('change',{bubbles:true}))})()`,
  );
  await sleep(600);
  assert.equal(await session.evaluate('__state.settings.clock'), clock);
  result.rapidChanges = true;
  await session.evaluate(
    `(()=>{const input=document.querySelector('[name="city"]');input.value='Automatic settings test';input.dispatchEvent(new Event('input',{bubbles:true}))})()`,
  );
  await session.until('__state.settings.city==="Automatic settings test"');
  result.textDebounce = true;
  await session.evaluate(
    `(()=>{const form=document.getElementById('settings-form');form.elements.sleepLux.value='';form.elements.sleepLux.dispatchEvent(new Event('input',{bubbles:true}));form.elements.date.checked=${!original.date};form.elements.date.dispatchEvent(new Event('change',{bubbles:true}))})()`,
  );
  await session.until(`__state.settings.date===${!original.date}`);
  assert.equal(await session.evaluate('__state.settings.sleepLux'), original.sleepLux);
  assert.match(
    await session.evaluate('document.getElementById("settings-error").textContent'),
    /lux/,
  );
  result.invalidFieldIsolation = true;
  await session.evaluate(
    `(()=>{const input=document.querySelector('[name="sleepLux"]');input.value='0';input.dispatchEvent(new Event('input',{bubbles:true}));document.querySelector('#settings .close-panel').click()})()`,
  );
  await session.until('__state.settings.sleepLux===0');
  result.closeFlush = true;
  await session.evaluate(
    `(()=>{document.getElementById('settings-button').click();const form=document.getElementById('settings-form');form.elements.weather.checked=false;form.elements.weatherLocation.value='manual';form.elements.latitude.value='';form.elements.longitude.value='';form.elements.weatherLocation.dispatchEvent(new Event('change',{bubbles:true}))})()`,
  );
  await session.until(
    '__state.settings.weather===false&&__state.settings.weatherLocation==="manual"',
  );
  await session.evaluate(
    `(()=>{const input=document.querySelector('[name="weather"]');input.checked=true;input.dispatchEvent(new Event('change',{bubbles:true}))})()`,
  );
  await sleep(400);
  assert.equal(await session.evaluate('__state.settings.weather'), false);
  assert.match(
    await session.evaluate('document.getElementById("settings-error").textContent'),
    /latitude/,
  );
  await session.evaluate(
    `(()=>{const form=document.getElementById('settings-form');form.elements.weather.checked=false;form.elements.latitude.value='0';form.elements.longitude.value='0';form.dispatchEvent(new Event('change',{bubbles:true}))})()`,
  );
  await session.until('__state.settings.latitude==="0"&&__state.settings.longitude==="0"');
  assert.equal(
    await session.evaluate(
      'document.getElementById("settings-error").classList.contains("hidden")',
    ),
    true,
  );
  result.manualCoordinates = true;
  const expected = await session.evaluate('__state.settings');
  await session.screenshot('build/autosave-settings.png');
  const state = await restart();
  assert.deepEqual(state.settings, expected);
  assert.deepEqual(state.photos.map((p) => p.id).sort(), baseline.photos.map((p) => p.id).sort());
  assert.deepEqual(
    state.albums.map((a) => a.coverIds),
    baseline.albums.map((a) => a.coverIds),
  );
  result.restartPersistence = true;
  assert.deepEqual(session.errors, []);
  result.passed = true;
  console.log(
    'PASS: silent automatic settings, rapid changes, debounced text, independent validation, close flush and restart persistence',
  );
} finally {
  if (baseline) {
    await session
      .evaluate(`Still.save(${JSON.stringify(JSON.stringify(baseline.settings))})`)
      .catch(() => {});
    await session
      .until(
        `JSON.stringify(__state.settings)===${JSON.stringify(JSON.stringify(baseline.settings))}`,
      )
      .catch(() => {});
    const state = await restart();
    assert.deepEqual(state.settings, baseline.settings);
    assert.deepEqual(state.photos.map((p) => p.id).sort(), baseline.photos.map((p) => p.id).sort());
    result.ownerDataPreserved = true;
  }
  session.close();
  fs.writeFileSync('build/autosave-test-result.json', JSON.stringify(result, null, 2) + '\n');
}
