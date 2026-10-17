const assert = require('node:assert/strict');
const { defaults, validate } = require('../app/src/main/assets/settings.js');
const previous = { ...defaults };
let result = validate({ ...previous, clock: false }, previous);
assert.equal(result.settings.clock, false);
assert.deepEqual(result.errors, []);
for (const sleepLux of ['', ' ', 'no', -1, 1001, Infinity]) {
  result = validate({ ...previous, sleepLux, clock: false }, previous);
  assert.equal(result.settings.sleepLux, 10);
  assert.equal(result.settings.clock, false);
  assert.equal(result.errors.length, 1);
}
result = validate({ ...previous, sleepStart: '07:00', sleepEnd: '07:00', date: false }, previous);
assert.equal(result.settings.sleepStart, '00:00');
assert.equal(result.settings.date, false);
assert.equal(result.errors.length, 1);
result = validate({ ...previous, sleepStart: '23:30', sleepEnd: '06:30', sleepLux: '0' }, previous);
assert.equal(result.settings.sleepStart, '23:30');
assert.equal(result.settings.sleepLux, 0);
assert.deepEqual(result.errors, []);
for (const [latitude, longitude] of [
  ['', ''],
  [' ', ' '],
  ['91', '0'],
  ['0', '181'],
  ['no', '-122'],
]) {
  result = validate(
    { ...previous, weather: true, weatherLocation: 'manual', latitude, longitude, clock: false },
    previous,
  );
  assert.equal(result.settings.weatherLocation, 'ip');
  assert.equal(result.settings.clock, false);
  assert.equal(result.errors.length, 1);
}
result = validate(
  { ...previous, weather: true, weatherLocation: 'manual', latitude: '0', longitude: '0' },
  previous,
);
assert.equal(result.settings.weatherLocation, 'manual');
assert.equal(result.settings.weather, true);
assert.deepEqual(result.errors, []);
const pending = {
  ...previous,
  weatherLocation: 'manual',
  weather: false,
  latitude: '',
  longitude: '',
};
result = validate({ ...pending, weather: true, date: false }, pending);
assert.equal(result.settings.weather, false);
assert.equal(result.settings.date, false);
assert.equal(result.errors.length, 1);
result = validate({ ...pending, weather: true, latitude: '37.35', longitude: '-121.96' }, pending);
assert.equal(result.settings.weather, true);
assert.deepEqual(result.errors, []);
assert.deepEqual(previous, defaults);
console.log(
  'PASS: settings validation, partial edits, independent changes and manual-location activation',
);
