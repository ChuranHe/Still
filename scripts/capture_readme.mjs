// Render the production WebView UI with generated photos and a fixture Android bridge.
// This never connects to a Portal or reads a personal photo library.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import { launchBrowser } from '../tests/browser.mjs';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const media = path.join(root, 'docs/media');
const frames = path.join(root, 'build/readme-frames');
const require = createRequire(import.meta.url);
const { defaults } = require('../app/src/main/assets/settings.js');
fs.mkdirSync(frames, { recursive: true });
for (const name of fs.readdirSync(frames)) {
  if (/^frame-\d+\.png$/.test(name)) fs.unlinkSync(path.join(frames, name));
}
const samples = [
  { id: 'coast', width: 1536, height: 1024 },
  { id: 'mountains', width: 1536, height: 1024 },
  { id: 'people', width: 1024, height: 1536 },
];
for (const photo of samples)
  assert.ok(fs.existsSync(path.join(media, 'samples', photo.id + '.jpg')));
const albums = [
  { id: 'weekends', name: 'Weekends away', ids: ['coast', 'mountains', 'people'] },
  { id: 'coast', name: 'Coast & quiet', ids: ['coast'] },
  { id: 'friends', name: 'Good company', ids: ['people', 'mountains'] },
].map((album) => ({
  ...album,
  count: album.ids.length,
  bytes: album.ids.length * 240000,
  coverIds: album.ids,
  covers: album.ids.map((id) => 'samples/' + id + '.jpg'),
}));
const initial = {
  width: 1280,
  height: 800,
  direction: 'landscape',
  deviceDirection: 'landscape',
  canDetectDirection: true,
  settings: { ...defaults, seconds: 0, weather: true, transitionMs: 1200 },
  albums,
  selectedAlbum: { id: albums[0].id, name: albums[0].name },
  storage: { total: 16000000000, free: 5100000000, others: 10898560000 },
  sleep: { available: true, lux: 85, active: false, reason: 'Outside sleep hours' },
  weather: {
    temperature: 22,
    code: 0,
    isDay: true,
    place: 'Santa Clara, CA',
    updated: Date.parse('2026-10-17T16:41:00-07:00'),
    requestKey: '["ip","celsius"]',
  },
  weatherLocation: { place: 'Santa Clara, CA' },
  uploads: { running: false },
  importProgress: {},
  report: {},
  usbStatus: 'Connect a USB drive to add photos',
  busy: false,
};
const browser = await launchBrowser();
const context = await browser.newContext({
  viewport: { width: 1280, height: 800 },
  timezoneId: 'America/Los_Angeles',
  deviceScaleFactor: 1,
});
const page = await context.newPage();
const errors = [];
page.on('pageerror', (error) => errors.push(error.message));
await page.clock.setFixedTime(new Date('2026-10-17T16:41:00-07:00'));
await page.route('**/*', async (route) => {
  const url = new URL(route.request().url());
  assert.equal(url.origin, 'https://frame.local', 'Demo must stay offline');
  const name = decodeURIComponent(url.pathname).slice(1) || 'index.html';
  const base = name.startsWith('samples/') ? media : path.join(root, 'app/src/main/assets');
  const filename = path.resolve(base, name);
  assert.ok(filename.startsWith(base + path.sep));
  await route.fulfill({ path: filename });
});
await page.addInitScript(
  ({ initial, samples }) => {
    const state = structuredClone(initial);
    function emit() {
      const album = state.albums.find((a) => a.id === state.selectedAlbum.id);
      state.photos = samples
        .filter((p) => album.ids.includes(p.id))
        .map((photo) => ({
          ...photo,
          url: 'samples/' + photo.id + '.jpg',
          thumbnail: 'samples/' + photo.id + '.jpg',
          supported: true,
          eligible:
            state.settings.photoDirections === 'all' ||
            (photo.width > photo.height ? 'landscape' : 'portrait') === state.direction,
          crop: true,
        }));
      window.StillApp.receive(structuredClone(state));
    }
    window.Still = {
      refresh: emit,
      selectAlbum(id) {
        const album = state.albums.find((a) => a.id === id);
        state.selectedAlbum = { id, name: album.name };
        emit();
        window.StillApp.openAlbum(id);
      },
      save(raw) {
        state.settings = JSON.parse(raw);
        emit();
      },
    };
    window.readmeDemo = {
      rotate(direction) {
        state.direction = state.deviceDirection = direction;
        state.width = direction === 'portrait' ? 800 : 1280;
        state.height = direction === 'portrait' ? 1280 : 800;
        emit();
      },
    };
  },
  { initial, samples },
);

const timeline = [];
async function loaded() {
  await page.waitForFunction(() =>
    [...document.querySelectorAll('#scene img')].some(
      (img) => img.style.opacity === '1' && img.complete && img.naturalWidth,
    ),
  );
  await page.evaluate(async () => {
    await Promise.all([...document.images].map((img) => img.decode().catch(() => {})));
    await document.fonts.ready;
  });
}
async function shot(name) {
  await loaded();
  await page.screenshot({ path: path.join(media, name) });
}
async function frame(duration) {
  const name = 'frame-' + String(timeline.length).padStart(3, '0') + '.png';
  await page.screenshot({ path: path.join(frames, name) });
  timeline.push({ name, duration });
}
try {
  await page.goto('https://frame.local/index.html');
  await loaded();
  await page.waitForTimeout(7000); // The real toolbar hides after 6.5 seconds.
  await shot('slideshow-landscape.png');
  await frame(1800);
  await page.locator('#scene').click();
  await page.waitForTimeout(400);
  await frame(1000);
  await page.locator('#next').click();
  for (let i = 0; i < 10; i++) {
    await page.waitForTimeout(100);
    await frame(100);
  }
  await page.waitForTimeout(350);
  await frame(1500);
  await page.locator('#library-button').click();
  await loaded();
  await page.waitForTimeout(350);
  await shot('albums.png');
  await frame(2200);
  await page.locator('[data-album-id="weekends"]').click();
  await loaded();
  await page.waitForTimeout(300);
  await frame(1800);
  await page.locator('#edit-album-cover').click();
  await loaded();
  await page.waitForTimeout(300);
  await frame(1800);
  await page.locator('#album-cover-cancel').click();
  await page.locator('#start-show').click();
  await page.locator('#settings-button').click();
  await page.waitForTimeout(350);
  await frame(1800);
  await page.locator('#settings .close-panel').click();
  await page.locator('#previous').click();
  await page.waitForTimeout(7000);
  await frame(1800);

  await page.setViewportSize({ width: 800, height: 1280 });
  await page.evaluate(() => window.readmeDemo.rotate('portrait'));
  await loaded();
  await page.waitForTimeout(1500);
  await shot('slideshow-portrait.png');
  assert.equal(await page.locator('#photo-count').textContent(), '1 / 1 · Holding');
  assert.deepEqual(errors, []);
  fs.writeFileSync(path.join(frames, 'timeline.json'), JSON.stringify(timeline, null, 2) + '\n');
  console.log(
    'Captured three screenshots and ' + timeline.length + ' GIF frames using the production UI.',
  );
} finally {
  await browser.close();
}
