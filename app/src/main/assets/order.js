(function (root) {
  'use strict';
  function round(items, lastId, anchorId, random) {
    const result = items.slice(),
      rng = random || Math.random;
    for (let i = result.length - 1; i > 0; i--) {
      const j = Math.floor(rng() * (i + 1));
      [result[i], result[j]] = [result[j], result[i]];
    }
    if (anchorId) {
      const index = result.findIndex((p) => p.id === anchorId);
      if (index >= 0) [result[0], result[index]] = [result[index], result[0]];
    } else if (result.length > 1 && result[0].id === lastId) {
      const j = 1 + Math.floor(rng() * (result.length - 1));
      [result[0], result[j]] = [result[j], result[0]];
    }
    return result;
  }
  const api = { round };
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.StillOrder = api;
})(typeof window === 'object' ? window : globalThis);
