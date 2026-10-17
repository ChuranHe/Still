(function () {
  'use strict';
  const $ = (id) => document.getElementById(id);
  const defaults = StillSettings.defaults;
  let config = Object.assign({}, defaults),
    state = { width: 1280, height: 800, photos: [], weather: {}, report: {} };
  let eligible = [],
    index = 0,
    currentId = null,
    activeLayer = 0,
    demo = false,
    paused = false,
    panel = null;
  let viewingAlbum = null,
    roundSeen = new Set(),
    albumEditorMode = 'create',
    coverSelection = [];
  let slideTimer,
    hideTimer,
    toastTimer,
    transitionTimer,
    settingsTimer,
    lastSettingsRequest = '',
    generation = 0,
    sleeping = false,
    qrKey = '';
  const native = (method, ...args) => {
    if (window.Still && typeof window.Still[method] === 'function') window.Still[method](...args);
  };
  const layers = [$('photo-a'), $('photo-b')];
  function gcd(a, b) {
    while (b) {
      const t = b;
      b = a % b;
      a = t;
    }
    return a;
  }
  function screenRatio() {
    const d = gcd(state.width, state.height);
    return state.width / d + ':' + state.height / d;
  }
  function hasFrame() {
    return demo || eligible.length > 0;
  }
  function setWelcome() {
    const empty = !hasFrame();
    $('welcome').classList.toggle('hidden', !empty);
    document.body.classList.toggle('welcoming', empty);
    $('demo-label').classList.toggle('hidden', !demo);
    if (empty) {
      // Detach the last photo's load handler before replacing it with the empty-state illustration.
      generation++;
      clearTimeout(transitionTimer);
      layers.forEach((img, i) => {
        img.onload = null;
        img.onerror = null;
        img.style.transition = 'none';
        img.style.transform = 'translateX(0)';
        img.style.opacity = i === activeLayer ? '1' : '0';
      });
      layers[activeLayer].src = 'landscape.svg';
      layers[activeLayer].className = 'photo demo active';
      const waiting = state.photos.filter((p) => p.supported && !p.eligible);
      $('welcome-title').textContent = waiting.length
        ? 'Turn your frame.\nAnother view awaits.'
        : state.photos.length
          ? 'A different fit.\nYour photos are here.'
          : 'Good moments.\nGive them a little space.';
      $('welcome-copy').textContent = waiting.length
        ? waiting.length +
          ' ' +
          waiting[0].orientation +
          ' photo' +
          (waiting.length === 1 ? ' is' : 's are') +
          ' ready.\nTurn the Portal, or change Frame direction in Settings.'
        : state.photos.length
          ? 'Add photos to this album to begin.'
          : 'Your favorite photos, beautifully at home.\nAdd photos from USB or your computer.';
    }
    $('display-label').textContent =
      (state.width > state.height ? 'LANDSCAPE' : 'PORTRAIT') +
      '  /  ' +
      state.width +
      ' × ' +
      state.height +
      '  /  ' +
      screenRatio();
    $('widgets').classList.toggle('hidden', empty);
  }
  function schedule() {
    clearTimeout(slideTimer);
    $('progress').style.transition = 'none';
    $('progress').style.width = '0';
    if (
      sleeping ||
      !hasFrame() ||
      paused ||
      panel ||
      Number(config.seconds) === 0 ||
      (!demo && eligible.length < 2)
    )
      return;
    const ms = Math.max(1, Number(config.seconds)) * 1000;
    requestAnimationFrame(() =>
      requestAnimationFrame(() => {
        $('progress').style.transition = 'width ' + ms + 'ms linear';
        $('progress').style.width = '100%';
      }),
    );
    slideTimer = setTimeout(() => advance(1), ms);
  }
  function present() {
    generation++;
    const token = generation;
    clearTimeout(transitionTimer);
    if (!hasFrame()) {
      setWelcome();
      schedule();
      return;
    }
    const item = demo ? { id: 'demo', name: 'Illustrated landscape' } : eligible[index];
    if (!item) return;
    currentId = item.id;
    const next = 1 - activeLayer,
      img = layers[next];
    img.onload = function () {
      if (token !== generation) return;
      const old = layers[activeLayer],
        duration = config.transition === 'none' ? 0 : Number(config.transitionMs);
      old.style.transition = 'none';
      img.style.transition = 'none';
      old.style.transform = 'translateX(0)';
      img.style.transform = config.transition === 'slide' ? 'translateX(100%)' : 'translateX(0)';
      img.style.opacity = '0';
      img.className = 'photo' + (demo ? ' demo' : '') + (item.crop ? ' crop' : '');
      void img.offsetWidth;
      old.style.transition = 'opacity ' + duration + 'ms, transform ' + duration + 'ms';
      img.style.transition = old.style.transition;
      img.style.opacity = '1';
      img.style.transform = 'translateX(0)';
      old.style.opacity = '0';
      if (config.transition === 'slide') old.style.transform = 'translateX(-100%)';
      activeLayer = next;
      applyMat(item);
      if (!demo) roundSeen.add(item.id);
      $('photo-count').textContent = demo
        ? 'Frame preview'
        : index +
          1 +
          ' / ' +
          eligible.length +
          (paused || Number(config.seconds) === 0 ? ' · Holding' : ' · Slideshow');
      setWelcome();
      schedule();
      transitionTimer = setTimeout(() => {
        if (token === generation) {
          old.removeAttribute('src');
          old.className = 'photo';
        }
      }, duration + 50);
    };
    img.onerror = function () {
      if (token === generation) {
        notice('This photo could not be displayed. Open Photos to remove or re-import it.');
        paused = true;
        updatePlayback();
      }
    };
    img.src = demo ? 'landscape.svg' : item.url;
    img.alt = 'Photo';
  }
  function advance(direction) {
    if (sleeping) return;
    if (!eligible.length || demo) {
      schedule();
      return;
    }
    if (config.shuffle && direction > 0 && index === eligible.length - 1) {
      const unseen = eligible.findIndex((p) => !roundSeen.has(p.id));
      if (unseen >= 0) index = unseen;
      else {
        eligible = StillOrder.round(eligible, currentId);
        roundSeen.clear();
        index = 0;
      }
    } else index = (index + direction + eligible.length) % eligible.length;
    present();
  }
  function updatePlayback() {
    $('pause-icon').setAttribute('href', paused ? '#i-play' : '#i-pause');
    $('pause').setAttribute('aria-label', paused ? 'Resume slideshow' : 'Pause slideshow');
    $('photo-count').textContent = demo
      ? 'Frame preview'
      : eligible.length
        ? index +
          1 +
          ' / ' +
          eligible.length +
          (paused || Number(config.seconds) === 0 ? ' · Holding' : ' · Slideshow')
        : 'Your photo frame';
    schedule();
  }
  function reveal() {
    $('controls').classList.remove('asleep');
    document.body.classList.add('controls-visible');
    clearTimeout(hideTimer);
    if (hasFrame() && !panel)
      hideTimer = setTimeout(() => {
        $('controls').classList.add('asleep');
        document.body.classList.remove('controls-visible');
      }, 6500);
  }
  function openPanel(name) {
    panel = name;
    clearTimeout(slideTimer);
    clearTimeout(hideTimer);
    ['library', 'settings'].forEach((id) => $(id).classList.toggle('hidden', id !== name));
    $('scrim').classList.remove('hidden');
    document.body.classList.add('panel-open');
    $('widgets').classList.add('menu-visible');
    if (name === 'settings') fillSettings();
    if (name === 'library') {
      viewingAlbum = null;
      renderLibrary();
    }
  }
  function closePanel() {
    if (!$('album-modal-scrim').classList.contains('hidden')) {
      closeAlbumModal();
      return;
    }
    if (!$('upload-dialog').classList.contains('hidden')) {
      $('upload-dialog').classList.add('hidden');
      return;
    }
    if (panel === 'settings') saveSettings();
    panel = null;
    ['library', 'settings', 'scrim'].forEach((id) => $(id).classList.add('hidden'));
    document.body.classList.remove('panel-open');
    $('widgets').classList.remove('menu-visible');
    schedule();
    reveal();
  }
  function notice(text) {
    $('toast').textContent = text;
    $('toast').classList.remove('hidden');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => $('toast').classList.add('hidden'), 6500);
  }
  function fillSettings() {
    settingsError('');
    const form = $('settings-form');
    Object.keys(defaults).forEach((key) => {
      const input = form.elements.namedItem(key);
      if (!input) return;
      if (input.type === 'checkbox') input.checked = !!config[key];
      else input.value = String(config[key]);
    });
    showWeatherLocationFields();
  }
  function showWeatherLocationFields() {
    $('manual-weather').classList.toggle(
      'hidden',
      $('settings-form').elements.namedItem('weatherLocation').value !== 'manual',
    );
  }
  function settingsError(message) {
    $('settings-error').textContent = message || '';
    $('settings-error').classList.toggle('hidden', !message);
  }
  function saveSettings() {
    clearTimeout(settingsTimer);
    const draft = {},
      form = $('settings-form');
    Object.keys(defaults).forEach((key) => {
      const input = form.elements.namedItem(key);
      draft[key] =
        input.type === 'checkbox'
          ? input.checked
          : typeof defaults[key] === 'number' && key !== 'sleepLux'
            ? Number(input.value)
            : input.value.trim();
    });
    const result = StillSettings.validate(draft, config),
      raw = JSON.stringify(result.settings);
    settingsError(result.errors.join(' '));
    if (raw === lastSettingsRequest || (!lastSettingsRequest && raw === JSON.stringify(config)))
      return;
    lastSettingsRequest = raw;
    if (window.Still) native('save', raw);
    else {
      localStorage.setItem('still-settings', raw);
      receive(Object.assign({}, state, { settings: result.settings }));
    }
  }
  function queueSettingsSave(event) {
    clearTimeout(settingsTimer);
    if (event.type === 'change') saveSettings();
    else settingsTimer = setTimeout(saveSettings, 300);
  }
  function tickClock() {
    const now = new Date(),
      hour = now.getHours();
    const hours = config.hour24 ? String(hour).padStart(2, '0') : String(hour % 12 || 12),
      minutes = String(now.getMinutes()).padStart(2, '0');
    $('clock').textContent = hours + ':' + minutes;
    if (!config.hour24) {
      const span = document.createElement('span');
      span.className = 'period';
      span.textContent = hour < 12 ? 'AM' : 'PM';
      $('clock').appendChild(span);
    }
    $('date').textContent = now.toLocaleDateString('en-US', {
      weekday: 'long',
      month: 'long',
      day: 'numeric',
    });
    $('clock').classList.toggle('hidden', !config.clock);
    $('date').classList.toggle('hidden', !config.date);
    $('widgets').classList.toggle('top', config.position === 'top');
    $('widgets').classList.toggle('bottom', config.position !== 'top');
    $('clock').style.fontSize = (76 * config.widgetSize) / 100 + 'px';
    $('date').style.fontSize = (16 * config.widgetSize) / 100 + 'px';
    $('weather-block').classList.toggle('hidden', !config.weather);
    const weather = state.weather || {},
      automatic = config.weatherLocation !== 'manual';
    const key = [automatic ? 'ip' : 'manual', config.units];
    if (!automatic) key.push(config.latitude, config.longitude, config.city);
    const status =
      state.weatherStatus && state.weatherStatus.requestKey === JSON.stringify(key)
        ? state.weatherStatus
        : {};
    const valid =
      weather.updated > 0 &&
      Number.isFinite(weather.temperature) &&
      weather.requestKey === JSON.stringify(key);
    const place = valid
      ? weather.place
      : automatic
        ? (state.weatherLocation || {}).place
        : config.city || 'Manual location';
    const detail = weatherDetails(weather.code, weather.isDay);
    $('weather-place').textContent = place
      ? 'Location: ' + place
      : automatic
        ? 'Detecting location…'
        : 'Manual location';
    $('weather-value').textContent = valid
      ? detail[0] +
        '  ' +
        Math.round(weather.temperature) +
        '°' +
        (config.units === 'fahrenheit' ? 'F' : 'C')
      : '—';
    $('weather-description').textContent = valid ? detail[1] : '';
    $('weather-value').style.fontSize = (31 * config.widgetSize) / 100 + 'px';
    const age = valid ? Math.max(0, Date.now() - weather.updated) : 0;
    $('weather-status').textContent = valid
      ? status.error
        ? 'Update unavailable'
        : age > 30 * 60000
          ? Math.floor(age / 60000) + ' min old'
          : ''
      : status.loading
        ? 'Loading weather…'
        : status.error
          ? 'Weather unavailable'
          : 'Waiting for weather';
    $('weather-status').classList.toggle('hidden', !$('weather-status').textContent);
    $('weather-location-status').textContent = !config.weather
      ? 'Enable weather to detect your location.'
      : (place
          ? 'Location: ' +
            place +
            (automatic
              ? weather.locationCached
                ? ' · saved IP location'
                : ' · approximate IP location'
              : '') +
            '. '
          : '') +
        (status.loading
          ? 'Updating location and weather…'
          : status.error ||
            (!valid
              ? 'Waiting for weather.'
              : 'Weather updated ' + Math.floor(age / 60000) + ' min ago.'));
    $('refresh-weather').disabled = !config.weather || !!status.loading;
  }
  function weatherDetails(code, isDay) {
    if (code === 0) return [isDay === false ? '☾' : '☀', 'Clear sky'];
    if (code === 1) return [isDay === false ? '☾' : '☀', 'Mostly clear'];
    if (code === 2) return ['☁', 'Partly cloudy'];
    if (code === 3) return ['☁', 'Overcast'];
    if ([45, 48].includes(code)) return ['≋', 'Fog'];
    if ([51, 53, 55].includes(code)) return ['☂', 'Drizzle'];
    if ([56, 57].includes(code)) return ['☂', 'Freezing drizzle'];
    if ([61, 63, 65].includes(code)) return ['☂', 'Rain'];
    if ([66, 67].includes(code)) return ['☂', 'Freezing rain'];
    if ([71, 73, 75, 77].includes(code)) return ['❄', 'Snow'];
    if ([80, 81, 82].includes(code)) return ['☂', 'Rain showers'];
    if ([85, 86].includes(code)) return ['❄', 'Snow showers'];
    if ([95, 96, 99].includes(code)) return ['ϟ', 'Thunderstorm'];
    return ['◌', 'Weather'];
  }
  function sleepChanged(value) {
    const status = value || {},
      changed = sleeping !== !!status.active;
    sleeping = !!status.active;
    state.sleep = status;
    const light =
      typeof status.lux === 'number'
        ? 'Ambient light: ' + status.lux + ' lux'
        : status.available
          ? 'Waiting for a light reading'
          : 'Ambient light sensor unavailable';
    $('sleep-status').textContent = light + (status.reason ? ' · ' + status.reason : '');
    if (changed) schedule();
  }
  const albumColors = ['#b8c898', '#d4b087', '#8daec5', '#be9cbb', '#b8b47e', '#7eb8a4'];
  function bytes(value) {
    if (value < 1000) return value + ' B';
    if (value < 1000000) return (value / 1000).toFixed(0) + ' KB';
    if (value < 1000000000) return (value / 1000000).toFixed(1) + ' MB';
    return (value / 1000000000).toFixed(1) + ' GB';
  }
  function renderCapacity() {
    const storage = state.storage || {},
      total = storage.total || 1,
      bar = $('capacity-bar'),
      legend = $('capacity-legend');
    bar.textContent = '';
    legend.textContent = '';
    $('capacity-total').textContent = storage.total
      ? bytes(storage.total) + ' total · ' + bytes(storage.free) + ' free'
      : 'Reading storage…';
    const segments = [
      { name: 'System & other data', bytes: storage.others || 0, color: '#607568' },
      ...(state.albums || []).map((a, i) => ({
        name: a.name,
        bytes: a.bytes,
        color: albumColors[i % albumColors.length],
      })),
      { name: 'Free', bytes: storage.free || 0, color: '#edf0df' },
    ];
    segments.forEach((item) => {
      const segment = document.createElement('span');
      segment.style.width = (100 * item.bytes) / total + '%';
      segment.style.backgroundColor = item.color;
      segment.title = item.name + ' · ' + bytes(item.bytes);
      bar.appendChild(segment);
      const entry = document.createElement('span'),
        dot = document.createElement('i');
      dot.style.backgroundColor = item.color;
      entry.appendChild(dot);
      entry.appendChild(document.createTextNode(item.name + ' · ' + bytes(item.bytes)));
      legend.appendChild(entry);
    });
    bar.setAttribute(
      'aria-label',
      segments.map((item) => item.name + ' ' + bytes(item.bytes)).join(', '),
    );
  }
  function applyMat(item) {
    document.documentElement.style.setProperty(
      '--mat-color',
      config.background === 'black' ? '#000000' : '#f2ecdc',
    );
    document.body.classList.toggle(
      'paper-mat',
      !demo && !!item && !item.crop && config.background !== 'black',
    );
  }
  function albumCover(urls) {
    const cover = document.createElement('div');
    cover.className = 'album-cover cover-' + urls.length;
    if (!urls.length) {
      cover.classList.add('album-placeholder');
      cover.textContent = '＋';
    }
    urls.forEach((url) => {
      const img = document.createElement('img');
      img.src = url;
      img.alt = 'Album preview';
      cover.appendChild(img);
    });
    return cover;
  }
  function closeAlbumModal() {
    ['album-name-dialog', 'album-cover-dialog', 'album-modal-scrim'].forEach((id) =>
      $(id).classList.add('hidden'),
    );
    $('album-name-input').blur();
  }
  function openAlbumName(mode) {
    if (mode === 'rename' && !state.selectedAlbum) return;
    albumEditorMode = mode;
    $('album-name-title').textContent = mode === 'create' ? 'Create an album' : 'Rename album';
    $('album-name-save').textContent = mode === 'create' ? 'Create album' : 'Save name';
    $('album-name-input').value = mode === 'create' ? '' : state.selectedAlbum.name;
    $('album-modal-scrim').classList.remove('hidden');
    $('album-name-dialog').classList.remove('hidden');
    $('album-name-input').focus();
  }
  function renderCoverPicker() {
    const photos = state.photos;
    coverSelection = coverSelection.filter((id) => photos.some((p) => p.id === id));
    const preview = $('album-cover-preview');
    preview.textContent = '';
    preview.appendChild(
      albumCover(coverSelection.map((id) => photos.find((p) => p.id === id).thumbnail)),
    );
    $('album-cover-count').textContent = coverSelection.length + ' of 4 selected';
    $('album-cover-save').disabled = coverSelection.length === 0;
    const grid = $('album-cover-grid');
    grid.textContent = '';
    photos.forEach((photo, i) => {
      const button = document.createElement('button'),
        img = document.createElement('img');
      const selected = coverSelection.includes(photo.id);
      button.className = 'cover-choice' + (selected ? ' selected' : '');
      button.dataset.photoId = photo.id;
      button.setAttribute('aria-label', 'Cover photo ' + (i + 1));
      button.setAttribute('aria-pressed', String(selected));
      img.src = photo.thumbnail;
      img.alt = 'Photo preview';
      button.appendChild(img);
      button.onclick = () => {
        if (selected) coverSelection = coverSelection.filter((id) => id !== photo.id);
        else if (coverSelection.length < 4) coverSelection.push(photo.id);
        else {
          notice('Choose up to four photos. Tap a selected photo to remove it first.');
          return;
        }
        renderCoverPicker();
      };
      grid.appendChild(button);
    });
  }
  function renderLibrary() {
    renderCapacity();
    const albums = state.albums || [],
      active = state.selectedAlbum;
    if (viewingAlbum && !albums.some((a) => a.id === viewingAlbum)) viewingAlbum = null;
    const inside = !!viewingAlbum && active && active.id === viewingAlbum;
    $('album-overview').classList.toggle('hidden', inside);
    $('album-content').classList.toggle('hidden', !inside);
    $('library-title').textContent = inside ? active.name : 'Your albums';
    $('no-albums').classList.toggle('hidden', albums.length > 0);
    const list = $('album-list');
    list.textContent = '';
    albums.forEach((a) => {
      const button = document.createElement('button');
      button.className = 'album-card' + (active && active.id === a.id ? ' selected' : '');
      button.dataset.albumId = a.id;
      const cover = albumCover(a.covers || (a.cover ? [a.cover] : [])),
        name = document.createElement('strong'),
        count = document.createElement('small');
      name.textContent = a.name;
      count.textContent = a.count + ' photos';
      button.appendChild(cover);
      button.appendChild(name);
      button.appendChild(count);
      button.onclick = () => native('selectAlbum', a.id);
      list.appendChild(button);
    });
    const direction = state.width > state.height ? 'Landscape' : 'Portrait',
      detected =
        state.deviceDirection && state.deviceDirection !== 'unknown'
          ? state.deviceDirection
          : 'Detecting…';
    $('direction-status').textContent =
      (state.canDetectDirection ? 'Device detected: ' + detected : 'Choose a frame direction') +
      ' · Screen: ' +
      direction;
    $('usb-status').textContent = state.usbStatus || '';
    $('library-count').textContent = state.photos.length;
    $('library-empty').classList.toggle('hidden', state.photos.length > 0);
    const grid = $('photo-grid');
    grid.textContent = '';
    if (inside)
      state.photos.forEach((item) => {
        const card = document.createElement('div');
        card.className = 'photo-card' + (item.eligible ? '' : ' ineligible');
        card.dataset.photoId = item.id;
        const choose = document.createElement('button'),
          img = document.createElement('img'),
          remove = document.createElement('button');
        img.src = item.thumbnail;
        img.alt = 'Photo preview';
        choose.setAttribute('aria-label', 'Show photo');
        choose.appendChild(img);
        choose.onclick = () => {
          if (!item.eligible) {
            notice('Turn the Portal to show this photo, or choose All photos in Settings.');
            return;
          }
          demo = false;
          roundSeen.clear();
          eligible = config.shuffle ? StillOrder.round(eligible, null, item.id) : eligible;
          index = eligible.findIndex((p) => p.id === item.id);
          paused = true;
          present();
          updatePlayback();
          closePanel();
        };
        remove.textContent = '×';
        remove.className = 'delete-photo';
        remove.setAttribute('aria-label', 'Remove photo from album');
        remove.onclick = () => {
          if (window.confirm('Remove this photo from this album?'))
            native('remove', active.id, item.id);
        };
        card.appendChild(choose);
        card.appendChild(remove);
        grid.appendChild(card);
      });
    const r = state.report || {},
      has = inside && Object.prototype.hasOwnProperty.call(r, 'added');
    $('import-report').classList.toggle('hidden', !has);
    if (has)
      $('import-report').textContent =
        'Last import · ' +
        r.added +
        ' added · ' +
        r.duplicate +
        ' already in album · ' +
        (r.invalid + r.failed) +
        ' unreadable';
    $('library-footer').textContent = state.busy
      ? 'Importing photos…'
      : 'Only display-size copies are stored';
    ['usb-import', 'local-import'].forEach((id) => ($(id).disabled = !!state.busy || !inside));
    $('edit-album-cover').disabled = !inside || state.photos.length === 0;
    if (!$('album-cover-dialog').classList.contains('hidden')) renderCoverPicker();
  }
  function renderImportProgress(value) {
    state.importProgress = value || {};
    ['library-import-progress', 'upload-import-progress', 'settings-import-progress'].forEach(
      (id) => StillImportUI.render($(id), state.importProgress),
    );
    StillImportUI.render(
      $('frame-import-progress'),
      state.importProgress.active ? state.importProgress : null,
    );
  }
  function receive(value) {
    const previousAlbum = state.selectedAlbum && state.selectedAlbum.id,
      previousSettings = JSON.stringify(config),
      previousWidth = state.width,
      previousHeight = state.height;
    const previousPool = eligible
        .map((p) => p.id + ':' + p.crop)
        .sort()
        .join(','),
      previousShuffle = !!config.shuffle;
    state = value;
    config = Object.assign({}, defaults, state.settings || {});
    if (JSON.stringify(config) === lastSettingsRequest) lastSettingsRequest = '';
    const pool = state.photos.filter((p) => p.eligible),
      albumId = state.selectedAlbum && state.selectedAlbum.id;
    const changed =
      previousPool !==
        pool
          .map((p) => p.id + ':' + p.crop)
          .sort()
          .join(',') ||
      previousAlbum !== albumId ||
      previousShuffle !== !!config.shuffle;
    if (changed) {
      roundSeen.clear();
      const anchor =
        previousAlbum === albumId && pool.some((p) => p.id === currentId) ? currentId : null;
      eligible = config.shuffle ? StillOrder.round(pool, null, anchor) : pool;
      index = Math.max(
        0,
        eligible.findIndex((p) => p.id === anchor),
      );
    } else {
      const byId = new Map(pool.map((p) => [p.id, p]));
      eligible = eligible.map((p) => byId.get(p.id));
    }
    if (eligible.length) demo = false;
    renderLibrary();
    renderImportProgress(state.importProgress);
    tickClock();
    setWelcome();
    sleepChanged(state.sleep);
    renderUploads();
    applyMat(eligible[index]);
    if (changed || previousWidth !== state.width || previousHeight !== state.height) present();
    else if (previousSettings !== JSON.stringify(config)) {
      schedule();
      updatePlayback();
    }
  }
  function renderUploads() {
    const value = state.uploads || {},
      running = !!value.running;
    $('upload-live').classList.toggle('hidden', !running);
    $('upload-stop').classList.toggle('hidden', !running);
    $('upload-start').classList.toggle('hidden', running);
    $('upload-start').disabled = !!value.starting;
    $('upload-message').textContent =
      value.message || 'Uploads are off. Start a session when you’re ready.';
    if (running) {
      $('upload-address').textContent = value.url;
      $('upload-code').textContent = value.code.slice(0, 4) + ' ' + value.code.slice(4);
      const link =
        value.url +
        '#code=' +
        value.code +
        '&album=' +
        (state.selectedAlbum ? state.selectedAlbum.id : '');
      if (qrKey !== link) {
        const qr = qrcode(0, 'M');
        qr.addData(link);
        qr.make();
        $('upload-qr').src = qr.createDataURL(6, 24);
        qrKey = link;
      }
    }
  }
  $('create-album').onclick = () => openAlbumName('create');
  $('all-albums').onclick = () => {
    viewingAlbum = null;
    renderLibrary();
  };
  $('rename-album').onclick = () => openAlbumName('rename');
  $('album-name-cancel').onclick = closeAlbumModal;
  $('album-modal-scrim').onclick = closeAlbumModal;
  $('album-name-form').onsubmit = (e) => {
    e.preventDefault();
    const name = $('album-name-input').value.trim();
    if (!name) {
      notice('Enter an album name.');
      return;
    }
    if (albumEditorMode === 'create') native('createAlbum', name);
    else {
      native('renameAlbum', state.selectedAlbum.id, name);
      closeAlbumModal();
    }
  };
  $('edit-album-cover').onclick = () => {
    const album = (state.albums || []).find(
      (a) => state.selectedAlbum && a.id === state.selectedAlbum.id,
    );
    if (!album || !state.photos.length) return;
    coverSelection = (album.coverIds || []).slice();
    renderCoverPicker();
    $('album-modal-scrim').classList.remove('hidden');
    $('album-cover-dialog').classList.remove('hidden');
  };
  $('album-cover-cancel').onclick = closeAlbumModal;
  $('album-cover-save').onclick = () => {
    if (coverSelection.length && state.selectedAlbum) {
      native('setAlbumCover', state.selectedAlbum.id, JSON.stringify(coverSelection));
      closeAlbumModal();
    }
  };
  $('delete-album').onclick = () => {
    if (
      state.selectedAlbum &&
      window.confirm('Delete this album and its photos? Other albums stay untouched.')
    ) {
      native('deleteAlbum', state.selectedAlbum.id);
      viewingAlbum = null;
    }
  };
  $('add-first').onclick = () => openPanel('library');
  $('library-button').onclick = () => openPanel('library');
  $('settings-button').onclick = () => openPanel('settings');
  $('welcome-settings').onclick = () => openPanel('settings');
  $('preview-sleep').onclick = () => native('previewSleep');
  $('refresh-weather').onclick = () => native('refreshWeather');
  $('settings-form').elements.namedItem('weatherLocation').onchange = showWeatherLocationFields;
  $('try-demo').onclick = () => {
    demo = true;
    present();
    reveal();
  };
  $('previous').onclick = () => {
    advance(-1);
    reveal();
  };
  $('next').onclick = () => {
    advance(1);
    reveal();
  };
  $('pause').onclick = () => {
    paused = !paused;
    updatePlayback();
    reveal();
  };
  $('usb-import').onclick = () => native('browse', 'usb');
  $('local-import').onclick = () => native('browse', 'local');
  $('computer-upload').onclick = () => {
    $('upload-dialog').classList.remove('hidden');
    renderUploads();
    native('startUploads');
  };
  $('upload-start').onclick = () => native('startUploads');
  $('upload-stop').onclick = () => native('stopUploads');
  $('upload-close').onclick = () => $('upload-dialog').classList.add('hidden');
  $('settings-form').addEventListener('input', queueSettingsSave);
  $('settings-form').addEventListener('change', queueSettingsSave);
  $('settings-form').onsubmit = (e) => {
    e.preventDefault();
    saveSettings();
  };
  $('exit-app').onclick = () => native('exit');
  $('start-show').onclick = () => {
    closePanel();
    if (eligible.length) {
      paused = false;
      updatePlayback();
    }
  };

  document.querySelectorAll('.close-panel').forEach((button) => (button.onclick = closePanel));
  $('scrim').onclick = closePanel;
  $('frame').addEventListener('pointerdown', reveal);
  let touchX = null;
  $('scene').addEventListener(
    'touchstart',
    (e) => {
      touchX = e.touches[0].clientX;
    },
    { passive: true },
  );
  $('scene').addEventListener(
    'touchend',
    (e) => {
      if (touchX !== null && Math.abs(e.changedTouches[0].clientX - touchX) > 70)
        advance(e.changedTouches[0].clientX < touchX ? 1 : -1);
      touchX = null;
    },
    { passive: true },
  );
  document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && !$('album-modal-scrim').classList.contains('hidden')) {
      closeAlbumModal();
      return;
    }
    if (panel) return;
    if (e.key === 'ArrowRight') advance(1);
    if (e.key === 'ArrowLeft') advance(-1);
    if (e.key === ' ') {
      paused = !paused;
      updatePlayback();
    }
  });
  window.StillApp = {
    receive,
    settingsError: (message) => {
      lastSettingsRequest = '';
      settingsError(message);
    },
    importProgress: renderImportProgress,
    sleepChanged,
    notice,
    openAlbum: (id) => {
      viewingAlbum = id;
      closeAlbumModal();
      renderLibrary();
    },
    back: () => {
      if (panel) closePanel();
      else {
        openPanel('settings');
      }
    },
  };
  if (!window.Still) {
    try {
      config = Object.assign(
        {},
        defaults,
        JSON.parse(localStorage.getItem('still-settings') || '{}'),
      );
    } catch (e) {}
  }
  setWelcome();
  tickClock();
  setInterval(tickClock, 1000);
  native('refresh');
  reveal();
})();
