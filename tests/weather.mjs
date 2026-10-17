// Real Portal IP/forecast requests plus source, cache, settings and layout checks.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { execFileSync } from 'node:child_process';
const adb = '.tools/platform-tools/adb';
const pages = await (await fetch('http://127.0.0.1:9222/json')).json();
const page =
  pages.find(
    (p) => p.url === 'https://frame.local/index.html' && JSON.parse(p.description || '{}').visible,
  ) || pages.find((p) => p.url === 'https://frame.local/index.html');
assert.ok(page, 'Launch the debug build and forward its WebView port');
const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((resolve, reject) => {
  ws.onopen = resolve;
  ws.onerror = reject;
});
let seq = 0;
const pending = new Map(),
  errors = [];
ws.onmessage = (event) => {
  const d = JSON.parse(event.data);
  if (d.method === 'Runtime.exceptionThrown') errors.push(d.params.exceptionDetails.text);
  if (pending.has(d.id)) {
    const p = pending.get(d.id);
    pending.delete(d.id);
    clearTimeout(p.timer);
    d.error ? p.reject(new Error(JSON.stringify(d.error))) : p.resolve(d.result);
  }
};
function send(method, params = {}) {
  return new Promise((resolve, reject) => {
    const id = ++seq,
      timer = setTimeout(() => reject(new Error('CDP timeout ' + method)), 20000);
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
async function until(expression, timeout = 35000) {
  const end = Date.now() + timeout;
  do {
    const value = await evaluate(expression);
    if (value) return value;
    await sleep(200);
  } while (Date.now() < end);
  throw new Error('Timed out: ' + expression);
}
const key = (s) =>
  JSON.stringify([
    s.weatherLocation,
    s.units,
    ...(s.weatherLocation === 'manual' ? [s.latitude, s.longitude, s.city] : []),
  ]);
let baseline, working;
async function save(patch) {
  Object.assign(working, patch);
  await evaluate(`Still.save(${JSON.stringify(JSON.stringify(working))})`);
  for (const [k, v] of Object.entries(patch))
    await until(`__state.settings[${JSON.stringify(k)}]===${JSON.stringify(v)}`);
}
async function forecast() {
  return until(
    `__state.weather.requestKey===${JSON.stringify(key(working))} && !__state.weatherStatus.loading && !__state.weatherStatus.error && __state.weather`,
  );
}
function shot(name) {
  fs.writeFileSync('build/' + name, execFileSync(adb, ['exec-out', 'screencap', '-p']));
}
const result = { passed: false };
try {
  await send('Runtime.enable');
  await evaluate('window.__weatherReloadMarker=true');
  await send('Page.reload');
  await until('!window.__weatherReloadMarker && window.StillApp && window.Still');
  await evaluate(
    '(()=>{const original=StillApp.receive;StillApp.receive=s=>{window.__state=s;original(s)};Still.refresh()})()',
  );
  const state = await until('window.__state');
  baseline = state.settings;
  working = { ...baseline };
  fs.writeFileSync('build/weather-baseline.json', JSON.stringify(baseline, null, 2));
  await save({
    weather: true,
    weatherLocation: 'ip',
    units: 'celsius',
    orientationMode: 'landscape',
  });
  const c = await forecast();
  assert.equal(c.locationMode, 'ip');
  assert.ok(c.place.length > 0);
  assert.ok(Number.isFinite(c.temperature));
  assert.equal(
    await evaluate('document.getElementById("weather-place").textContent'),
    'Location: ' + c.place,
  );
  assert.doesNotMatch(
    await evaluate('document.getElementById("weather-status").textContent'),
    /Open-Meteo/,
  );
  assert.ok(await evaluate('document.getElementById("weather-description").textContent.length>0'));
  await evaluate('document.getElementById("try-demo").click()');
  await sleep(700);
  shot('weather-ip-landscape.png');
  result.ipWeather = c;
  console.log('PASS: Portal IP location, live forecast, city/state label and conditions');

  await evaluate('document.getElementById("settings-button").click()');
  assert.equal(
    await evaluate('document.getElementById("manual-weather").classList.contains("hidden")'),
    true,
  );
  // Automatic mode must save with blank manual coordinates.
  await evaluate(
    '(()=>{const f=document.getElementById("settings-form");f.elements.latitude.value="";f.elements.longitude.value="";f.dispatchEvent(new Event("change",{bubbles:true}))})()',
  );
  await until('__state.settings.latitude==="" && __state.settings.longitude===""');
  working.latitude = '';
  working.longitude = '';
  await evaluate('document.getElementById("settings-button").click()');
  await evaluate(
    '(()=>{const f=document.getElementById("settings-form");f.elements.weatherLocation.value="manual";f.elements.weatherLocation.dispatchEvent(new Event("change"));f.dispatchEvent(new Event("change",{bubbles:true}))})()',
  );
  assert.equal(
    await evaluate('document.getElementById("manual-weather").classList.contains("hidden")'),
    false,
  );
  assert.match(
    await evaluate('document.getElementById("settings-error").textContent'),
    /valid latitude/,
  );
  assert.equal(await evaluate('__state.settings.weatherLocation'), 'ip');
  await evaluate('document.querySelector("#settings .close-panel").click()');
  console.log('PASS: automatic location needs no coordinates; invalid manual location rejected');

  await save({ units: 'fahrenheit' });
  const f = await forecast();
  assert.equal(f.place, c.place);
  assert.ok(Math.abs(f.temperature - ((c.temperature * 9) / 5 + 32)) < 2);
  assert.match(await evaluate('document.getElementById("weather-value").textContent'), /°F$/);
  await save({
    weatherLocation: 'manual',
    city: 'Santa Clara, CA',
    latitude: '37.35',
    longitude: '-121.96',
  });
  const manual = await forecast();
  assert.equal(manual.place, 'Santa Clara, CA');
  assert.equal(manual.locationMode, 'manual');
  assert.equal(
    await evaluate('document.getElementById("weather-place").textContent'),
    'Location: Santa Clara, CA',
  );
  console.log('PASS: Fahrenheit and explicit manual override');

  await save({ weather: false });
  assert.ok(
    await evaluate('document.getElementById("weather-block").classList.contains("hidden")'),
  );
  await save({ weather: true, weatherLocation: 'ip', units: 'celsius' });
  await forecast();
  // Model a cached/offline UI update without changing the native cache or networking.
  const actual = await evaluate('__state');
  const stale = {
    ...actual,
    weather: { ...actual.weather, updated: Date.now() - 45 * 60000 },
    weatherStatus: {
      loading: false,
      requestKey: key(working),
      error: 'Weather unavailable. Check your connection and refresh.',
    },
  };
  await evaluate(`StillApp.receive(${JSON.stringify(stale)})`);
  assert.match(
    await evaluate('document.getElementById("weather-status").textContent'),
    /Update unavailable/,
  );
  assert.match(await evaluate('document.getElementById("weather-value").textContent'), /°C$/);
  await evaluate(
    `StillApp.receive(${JSON.stringify({ ...actual, weatherStatus: { loading: false, requestKey: 'different source', error: 'Old failure' } })})`,
  );
  assert.doesNotMatch(
    await evaluate('document.getElementById("weather-status").textContent'),
    /Update unavailable/,
    'Ignore errors belonging to earlier location settings',
  );
  await evaluate(
    `StillApp.receive(${JSON.stringify({ ...actual, weather: { ...actual.weather, locationCached: true } })})`,
  );
  assert.match(
    await evaluate('document.getElementById("weather-status").textContent'),
    /Saved IP location/,
  );
  await evaluate(
    `StillApp.receive(${JSON.stringify({ ...actual, settings: { ...actual.settings, weatherLocation: 'manual' } })})`,
  );
  assert.equal(
    await evaluate('document.getElementById("weather-value").textContent'),
    '—',
    'Never show a forecast for a different location source',
  );
  await evaluate(`StillApp.receive(${JSON.stringify(actual)})`);

  await save({ orientationMode: 'portrait' });
  await until('__state.width===800 && innerWidth===800');
  await sleep(500);
  const bounds = await evaluate(
    '(()=>{const a=document.getElementById("time-block").getBoundingClientRect(),b=document.getElementById("weather-block").getBoundingClientRect();return {timeRight:a.right,weatherLeft:b.left,weatherRight:b.right,weatherBottom:b.bottom,width:innerWidth,height:innerHeight}})()',
  );
  assert.ok(
    bounds.timeRight <= bounds.weatherLeft &&
      bounds.weatherRight <= bounds.width &&
      bounds.weatherBottom <= bounds.height,
  );
  result.portraitBounds = bounds;
  shot('weather-ip-portrait.png');
  await save({ orientationMode: 'landscape' });
  await until('__state.width===1280 && innerWidth===1280');
  await evaluate(
    'document.getElementById("settings-button").click();document.getElementById("refresh-weather").scrollIntoView({block:"center"});document.getElementById("refresh-weather").click()',
  );
  await until('__state.weather.updated>' + actual.weather.updated);
  await forecast();
  shot('weather-ip-settings.png');
  const cached = await evaluate('__state.weather');
  await send('Page.reload');
  await until('window.StillApp');
  await evaluate(
    '(()=>{const original=StillApp.receive;StillApp.receive=s=>{window.__state=s;original(s)};Still.refresh()})()',
  );
  const after = await until('window.__state && __state.weather.updated && __state');
  assert.equal(after.weather.updated, cached.updated);
  assert.deepEqual(errors, []);
  result.errors = errors;
  result.passed = true;
  result.checks = [
    'Portal IP lookup and actual forecast',
    'automatic save without coordinates',
    'manual override and invalid coordinates',
    'Celsius/Fahrenheit',
    'weather toggle',
    'cached offline display',
    'source mismatch prevents stale forecast',
    'portrait layout',
    'refresh button',
    'cache across WebView reload',
  ];
  console.log(
    'PASS: weather toggle, cache/error display, both directions, refresh and cached reload',
  );
} finally {
  if (baseline) {
    await evaluate(`Still.save(${JSON.stringify(JSON.stringify(baseline))})`).catch(() => {});
    await until(
      `__state.settings.weatherLocation===${JSON.stringify(baseline.weatherLocation)} && __state.settings.units===${JSON.stringify(baseline.units)}`,
    ).catch(() => {});
    result.settingsRestored = true;
  }
  fs.writeFileSync('build/weather-test-result.json', JSON.stringify(result, null, 2));
  ws.close();
}
