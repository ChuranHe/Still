(function () {
  'use strict';
  function render(container, p) {
    const visible = !!p && !!p.id;
    container.classList.toggle('hidden', !visible);
    if (!visible) return;
    if (!container.firstChild)
      container.innerHTML =
        '<div class="import-heading"><strong class="import-source"></strong><span class="import-percent"></span></div><p class="import-album"></p><p class="import-description"></p><progress class="import-overall" max="100" aria-label="Photos processed"></progress><div class="import-file"><p class="import-file-label"></p><progress class="import-bytes" max="100" aria-label="Current photo transfer"></progress></div><p class="import-summary"></p>';
    const get = (s) => container.querySelector(s),
      scanning = p.stage === 'scanning',
      active = !!p.active;
    get('.import-source').textContent = p.source;
    get('.import-album').textContent = p.albumName ? 'Album: ' + p.albumName : '';
    get('.import-percent').textContent = scanning ? '' : Math.floor(p.percent || 0) + '%';
    get('.import-description').textContent = scanning
      ? 'Finding photos… ' + (p.discovered || 0) + ' found'
      : !active
        ? p.message || (p.stage === 'complete' ? 'Import finished' : 'Import stopped')
        : (p.completed || 0) + ' of ' + p.total + ' photos processed';
    const overall = get('.import-overall');
    if (scanning) overall.removeAttribute('value');
    else overall.value = p.percent || 0;
    const transferring = ['receiving', 'copying'].includes(p.stage),
      processing = ['processing', 'saving'].includes(p.stage);
    get('.import-file').classList.toggle('hidden', !active || (!transferring && !processing));
    const label = transferring
      ? p.stage === 'receiving'
        ? 'Receiving'
        : 'Copying'
      : p.stage === 'saving'
        ? 'Saving'
        : 'Preparing';
    get('.import-file-label').textContent =
      label +
      ' photo ' +
      p.current +
      ' of ' +
      p.total +
      (transferring && p.totalBytes
        ? ' · ' + Math.min(100, Math.floor((100 * p.bytes) / p.totalBytes)) + '%'
        : '…');
    const file = get('.import-bytes');
    if (transferring && p.totalBytes) file.value = Math.min(100, (100 * p.bytes) / p.totalBytes);
    else file.removeAttribute('value');
    get('.import-summary').textContent =
      (p.added || 0) +
      ' added · ' +
      (p.duplicate || 0) +
      ' already in album · ' +
      (p.skipped || 0) +
      ' skipped';
    container.classList.toggle('import-stopped', p.stage === 'stopped');
    container.setAttribute('aria-busy', String(active));
  }
  window.StillImportUI = { render };
})();
