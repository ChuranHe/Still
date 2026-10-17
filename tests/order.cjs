const assert = require('node:assert/strict');
const { round } = require('../app/src/main/assets/order.js');
let seed = 12345;
const rng = () => {
  seed = (Math.imul(seed, 1664525) + 1013904223) >>> 0;
  return seed / 4294967296;
};
const photos = Array.from({ length: 57 }, (_, id) => ({ id: String(id) }));
let last = null;
let checks = 0;
for (let i = 0; i < 100; i++) {
  const r = round(photos, last, null, rng);
  assert.equal(new Set(r.map((p) => p.id)).size, 57);
  assert.notEqual(r[0].id, last);
  assert.deepEqual(r.map((p) => p.id).sort(), photos.map((p) => p.id).sort());
  last = r.at(-1).id;
  checks += 3;
}
assert.equal(round(photos, null, '17', rng)[0].id, '17');
assert.deepEqual(round([], null, null, rng), []);
assert.equal(round([{ id: 'one' }], 'one', null, rng)[0].id, 'one');
console.log('PASS: ' + (checks + 3) + ' full-round shuffle assertions');
