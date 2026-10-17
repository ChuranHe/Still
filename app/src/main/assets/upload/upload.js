(function () {
  'use strict';
  const $ = (id) => document.getElementById(id);
  let files = [],
    busy = false,
    urls = [],
    albumLoad = 0,
    currentBatch = '';
  let requestedAlbum = '';
  function readLink() {
    const fragment = new URLSearchParams(location.hash.substring(1)),
      scanned = fragment.get('code');
    requestedAlbum = fragment.get('album') || requestedAlbum;
    if (scanned && /^\d{8}$/.test(scanned)) {
      sessionStorage.setItem('still-upload-code', scanned);
      history.replaceState(null, '', location.pathname);
    }
    $('code').value = scanned || sessionStorage.getItem('still-upload-code') || '';
  }
  readLink();
  window.addEventListener('hashchange', () => {
    readLink();
    loadAlbums(requestedAlbum);
  });
  const code = () => $('code').value.replace(/\D/g, '');
  async function request(path, method) {
    const response = await fetch(path, {
      method: method || 'GET',
      headers: { 'X-Still-Code': code() },
    });
    if (!response.ok) {
      const error = new Error(await response.text());
      error.status = response.status;
      throw error;
    }
    return response.json();
  }
  async function loadAlbums(select) {
    const token = ++albumLoad;
    if (!/^\d{8}$/.test(code())) return;
    try {
      const data = await request('/albums');
      if (token !== albumLoad) return;
      const previous = select || $('album').value;
      $('album').textContent = '';
      const empty = document.createElement('option');
      empty.value = '';
      empty.textContent = 'Choose an album';
      $('album').appendChild(empty);
      data.albums.forEach((a) => {
        const o = document.createElement('option');
        o.value = a.id;
        o.textContent = a.name;
        $('album').appendChild(o);
      });
      if (previous && data.albums.some((a) => a.id === previous)) $('album').value = previous;
      $('album-status').textContent = $('album').value
        ? 'Ready to add to this album.'
        : 'Choose an album or create a new one.';
    } catch (e) {
      if (token === albumLoad) $('album-status').textContent = e.message;
    }
  }
  $('code').oninput = () => loadAlbums(requestedAlbum);
  $('album').onchange = () => {
    $('album-status').textContent = $('album').value
      ? 'Ready to add to this album.'
      : 'Choose an album first.';
  };
  function closeAlbum() {
    if ($('save-album').disabled) return;
    $('album-modal').hidden = true;
    $('new-album-name').blur();
    $('create-album').focus();
  }
  $('create-album').onclick = () => {
    if (!/^\d{8}$/.test(code())) {
      $('album-status').textContent = 'Enter your upload code first.';
      return;
    }
    $('new-album-name').value = '';
    $('album-name-error').textContent = '';
    $('album-modal').hidden = false;
    $('new-album-name').focus();
  };
  $('cancel-album').onclick = closeAlbum;
  $('album-modal').onclick = (e) => {
    if (e.target === $('album-modal')) closeAlbum();
  };
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !$('album-modal').hidden) closeAlbum();
  });
  $('album-name-form').onsubmit = async (e) => {
    e.preventDefault();
    if ($('save-album').disabled) return;
    const name = $('new-album-name').value.trim();
    if (!name) {
      $('album-name-error').textContent = 'Enter an album name.';
      return;
    }
    $('save-album').disabled = true;
    try {
      const a = await request('/albums?name=' + encodeURIComponent(name), 'POST');
      await loadAlbums(a.id);
      $('save-album').disabled = false;
      closeAlbum();
    } catch (error) {
      $('album-name-error').textContent = error.message;
    } finally {
      $('save-album').disabled = false;
    }
  };
  function preview(file) {
    const img = document.createElement('img');
    img.alt = 'Photo preview';
    img.src = URL.createObjectURL(file);
    urls.push(img.src);
    img.onerror = () => {
      img.classList.add('unavailable');
      img.alt = 'Photo';
    };
    return img;
  }
  function choose(value) {
    if (busy) return;
    files = Array.from(value);
    $('progress-box').hidden = true;
    $('current').textContent = '';
    $('summary').textContent = '';
    $('overall-progress').value = 0;
    $('results').textContent = '';
    $('previews').textContent = '';
    urls.forEach((url) => URL.revokeObjectURL(url));
    urls = [];
    files.forEach((file) => $('previews').appendChild(preview(file)));
    $('selection').textContent =
      files.length + ' photo' + (files.length === 1 ? '' : 's') + ' selected';
  }
  $('files').onchange = () => choose($('files').files);
  for (const event of ['dragenter', 'dragover'])
    $('drop').addEventListener(event, (e) => {
      e.preventDefault();
      $('drop').classList.add('drag');
    });
  for (const event of ['dragleave', 'drop'])
    $('drop').addEventListener(event, (e) => {
      e.preventDefault();
      $('drop').classList.remove('drag');
    });
  $('drop').addEventListener('drop', (e) => choose(e.dataTransfer.files));
  function upload(file, pin, album, batch, index, total) {
    return new Promise((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      xhr.open(
        'POST',
        '/upload?name=' +
          encodeURIComponent(file.name) +
          '&album=' +
          encodeURIComponent(album) +
          '&batch=' +
          batch +
          '&index=' +
          index +
          '&count=' +
          total,
      );
      xhr.setRequestHeader('Content-Type', 'application/octet-stream');
      xhr.setRequestHeader('X-Still-Code', pin);
      xhr.timeout = 300000;
      xhr.upload.onprogress = (e) => {
        if (e.lengthComputable) {
          const percent = Math.min(100, Math.floor((100 * e.loaded) / e.total));
          $('progress').value = percent;
          $('current').textContent =
            'Uploading photo ' + index + ' of ' + total + ' · ' + percent + '%';
        }
      };
      xhr.upload.onload = () => {
        $('progress').removeAttribute('value');
        $('current').textContent = 'Saving photo ' + index + ' of ' + total + ' on Portal…';
      };
      xhr.onload = () => {
        if (xhr.status === 200) {
          try {
            resolve(JSON.parse(xhr.responseText));
          } catch (e) {
            reject({ message: 'Unreadable upload result' });
          }
        } else reject({ status: xhr.status, message: xhr.responseText || 'Upload failed' });
      };
      xhr.onerror = () =>
        reject({ message: 'Connection lost. Check Wi-Fi and the upload session.' });
      xhr.ontimeout = () => reject({ message: 'Upload timed out. Retry this photo.' });
      xhr.send(file);
    });
  }
  function overall(done, total) {
    const percent = Math.floor((100 * done) / total);
    $('overall-progress').value = percent;
    $('overall-label').textContent = percent + '%';
    $('batch-count').textContent = done + ' of ' + total + ' photos processed';
  }
  $('upload-form').onsubmit = async (event) => {
    event.preventDefault();
    if (busy) return;
    const pin = code(),
      album = $('album').value;
    if (!/^\d{8}$/.test(pin) || !album) {
      $('selection').textContent = 'Enter the upload code and choose or create an album first.';
      return;
    }
    if (!files.length || files.length > 5000) {
      $('selection').textContent = 'Choose between 1 and 5000 photos.';
      return;
    }
    sessionStorage.setItem('still-upload-code', pin);
    busy = true;
    for (const id of ['send', 'files', 'code', 'album', 'create-album']) $(id).disabled = true;
    currentBatch = Array.from(crypto.getRandomValues(new Uint8Array(16)))
      .map((b) => b.toString(16).padStart(2, '0'))
      .join('');
    $('progress-box').hidden = false;
    $('results').textContent = '';
    $('current').textContent = 'Starting photo import…';
    $('progress').removeAttribute('value');
    $('summary').textContent = '';
    overall(0, files.length);
    let added = 0,
      duplicates = 0,
      skipped = 0,
      done = 0,
      stopped = false,
      started = false;
    try {
      await request(
        '/batch?action=start&album=' + album + '&batch=' + currentBatch + '&count=' + files.length,
        'POST',
      );
      started = true;
      for (let i = 0; i < files.length; i++) {
        const file = files[i],
          row = document.createElement('li'),
          status = document.createElement('span');
        row.appendChild(preview(file));
        row.appendChild(status);
        $('results').appendChild(row);
        $('current').textContent = 'Uploading photo ' + (i + 1) + ' of ' + files.length;
        $('progress').value = 0;
        try {
          if (file.size > 100 * 1048576) throw { message: 'Larger than 100 MB' };
          const r = await upload(file, pin, album, currentBatch, i + 1, files.length);
          added += r.added || 0;
          duplicates += r.duplicate || 0;
          skipped += (r.invalid || 0) + (r.failed || 0) + (r.mismatch || 0);
          status.textContent = r.message;
        } catch (error) {
          skipped++;
          status.textContent = error.message;
          if (!error.status || [403, 429, 409].includes(error.status)) stopped = true;
        }
        done++;
        overall(done, files.length);
        $('summary').textContent =
          added + ' added · ' + duplicates + ' already in album · ' + skipped + ' skipped/failed';
        if (stopped) break;
      }
      $('current').textContent = stopped
        ? 'Upload stopped. Review the result and check the Portal connection or code.'
        : 'Finished. Review each photo’s result below.';
    } catch (error) {
      stopped = true;
      $('current').textContent = 'Upload could not start: ' + error.message;
    } finally {
      if (started) {
        try {
          await request(
            '/batch?action=finish&batch=' +
              currentBatch +
              '&count=' +
              files.length +
              '&done=' +
              done +
              '&added=' +
              added +
              '&duplicate=' +
              duplicates +
              '&skipped=' +
              skipped +
              '&stopped=' +
              (stopped ? '1' : '0'),
            'POST',
          );
        } catch (error) {
          $('summary').textContent += ' · Portal progress update unavailable';
        }
      }
      if (!stopped) $('progress').value = 100;
      else $('progress').value = 0;
      busy = false;
      for (const id of ['send', 'files', 'code', 'album', 'create-album']) $(id).disabled = false;
    }
  };
  async function pollProgress() {
    try {
      if (/^\d{8}$/.test(code())) {
        const value = await request('/progress');
        StillImportUI.render($('device-import-progress'), value.id === currentBatch ? null : value);
      }
    } catch (error) {
      const card = $('device-import-progress');
      if (!card.classList.contains('hidden') && card.firstChild) {
        card.querySelector('.import-description').textContent =
          'Progress update unavailable. Check the Portal connection and upload session.';
        card.setAttribute('aria-busy', 'false');
      }
    } finally {
      setTimeout(pollProgress, 1000);
    }
  }
  pollProgress();
  loadAlbums(requestedAlbum);
})();
