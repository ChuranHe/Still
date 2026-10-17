(function (root, factory) {
  'use strict';
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  root.StillSettings = api;
})(typeof globalThis === 'object' ? globalThis : this, function () {
  'use strict';
  const defaults = {
    seconds: 30,
    transition: 'fade',
    transitionMs: 1200,
    background: 'paper',
    orientationMode: 'auto',
    photoDirections: 'matching',
    sleepEnabled: true,
    sleepStart: '00:00',
    sleepEnd: '07:00',
    sleepLux: 10,
    clock: true,
    date: true,
    weather: false,
    weatherLocation: 'ip',
    hour24: false,
    position: 'bottom',
    widgetSize: 100,
    shuffle: false,
    city: '',
    latitude: '',
    longitude: '',
    units: 'celsius',
  };
  function validate(draft, previous) {
    const next = Object.assign({}, previous, draft),
      errors = [];
    const time = /^(?:[01][0-9]|2[0-3]):[0-5][0-9]$/;
    if (
      !time.test(next.sleepStart) ||
      !time.test(next.sleepEnd) ||
      next.sleepStart === next.sleepEnd
    ) {
      next.sleepStart = previous.sleepStart;
      next.sleepEnd = previous.sleepEnd;
      errors.push('Choose different, valid start and end times for sleep mode.');
    }
    if (
      String(draft.sleepLux).trim() === '' ||
      !Number.isFinite(Number(next.sleepLux)) ||
      Number(next.sleepLux) < 0 ||
      Number(next.sleepLux) > 1000
    ) {
      next.sleepLux = previous.sleepLux;
      errors.push('Enter a dim-room threshold from 0 to 1000 lux.');
    } else next.sleepLux = Number(next.sleepLux);
    const validCoordinates = (value) => {
      const latitude = String(value.latitude).trim(),
        longitude = String(value.longitude).trim();
      return (
        latitude !== '' &&
        longitude !== '' &&
        Number.isFinite(Number(latitude)) &&
        Number.isFinite(Number(longitude)) &&
        Math.abs(Number(latitude)) <= 90 &&
        Math.abs(Number(longitude)) <= 180
      );
    };
    if (next.weather && next.weatherLocation === 'manual' && !validCoordinates(next)) {
      next.weatherLocation = previous.weatherLocation;
      next.latitude = previous.latitude;
      next.longitude = previous.longitude;
      if (next.weatherLocation === 'manual' && !validCoordinates(next))
        next.weather = previous.weather;
      errors.push(
        'Enter a valid latitude (−90 to 90) and longitude (−180 to 180) to apply manual weather.',
      );
    }
    return { settings: next, errors };
  }
  return { defaults, validate };
});
