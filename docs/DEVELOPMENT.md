# Development

## Toolchains

The application is native Java with an offline WebView UI, built without Gradle. Python 3.12+ drives JDK 17, Android platform 32, build-tools 32.0.0, D8, AAPT, zipalign, and apksigner. The minimum device API is 28.

`python3 scripts/bootstrap.py` installs a checksummed macOS toolchain under `.tools/`. Alternatively, set `JAVA_HOME`/`STILL_JAVA_HOME` and `ANDROID_HOME`/`ANDROID_SDK_ROOT` to an existing JDK and SDK containing those Android packages. `STILL_ANDROID_TOOLS` selects a combined directory containing `android.jar`, `core-lambda-stubs.jar`, `d8`, `aapt`, `zipalign`, and `apksigner`, matching the bootstrap layout.

Use `python3 scripts/build.py` for a normal APK, or add `--debug` for WebView inspection. Output is `build/still.apk`. Normal builds disable WebView debugging. Install with `adb install --no-incremental -r build/still.apk` and launch `com.portalframe/.MainActivity`.

### Signing and updates

The build script generates `.tools/still-debug.keystore` for local development. Its standard development password is public configuration, not a distribution secret. Keep your local key: an installed Android app can be updated in place only with a compatible signer. Do not commit a key or delete app data to solve a signing mismatch. Distribution signing and APK release automation are not configured; establish your own signing process before distributing binaries.

## Architecture

- `MainActivity.java`: lifecycle, Android decoding, imports, JavaScript bridge, sensors, app brightness, weather scheduling, and state publication.
- `AlbumStore.java`: independent private album folders, validated names/IDs, cover metadata, and retryable legacy migration.
- `PhotoRules.java` / `PhotoSizing.java`: direction eligibility, crop policy, bounded display-copy sizes, and no upscaling.
- `ImportScanner.java` / `ImportProgress.java`: bounded USB/local discovery and synchronized progress snapshots. Progress reaches the main thread independently of the import worker.
- `UsbMassStorage.java` / `UsbPhotoDisk.java`: USB Host bulk-only SCSI transport and read-only FAT32/exFAT parsing.
- `UploadServer.java`: temporary HTTP server, authenticated album/batch/progress operations, body limits, origin/host checks, staging cleanup, and single-upload admission.
- `WeatherClient.java` / `SleepRules.java`: weather/location requests and nightstand time/light logic.
- `assets/app.js`, `settings.js`, `order.js`: frame UI, validated automatic settings, and complete shuffle rounds.
- `assets/import-progress.js` / `.css`: shared Portal/browser progress cards.
- `assets/upload/`: browser photo selection and upload workflow.

Photos are converted to bounded JPEG display copies with separate thumbnails and original-dimension metadata. Source hashes deduplicate within an album; copies across albums are independent. No originals, cloud account sessions, or music integrations are retained.

## Hardware-independent tests

Requirements: JDK 17, Python 3.12+, and Node.js 22+. `scripts/test.py` finds Node on PATH or uses `STILL_NODE`. No Android device or npm packages are needed for the core suite.

```sh
python3 scripts/test.py
```

This generates sparse USB disk fixtures under `build/`, compiles/runs the Java tests, and runs shuffle/settings JavaScript tests. `WeatherClientTest.java` uses Android's JSON implementation and is a device-side test rather than part of this JVM suite.

## UI dependencies and formatting

```sh
npm ci
npm run format:check
npm run format
```

Playwright and Prettier are pinned development dependencies. `STILL_BROWSER` can select a browser executable. On macOS, browser tests use an installed Google Chrome when available; otherwise install Playwright Chromium:

```sh
npx playwright install chromium
```

Java sources use Google Java Format 1.22.0. Download the `all-deps.jar` from its official release and run it with JDK 17. Vendored QR source is excluded from formatting.

## On-device browser tests

The maintained suites require a debug APK, ADB, an existing album, and a running Still WebView. Each suite creates synthetic test albums, removes its own albums, and restores the original album/settings. Close other interactive sessions during tests so their edits are not confused with test restoration.

On macOS, generate JPEG/HEIF fixtures with Pillow and the system `sips` encoder:

```sh
python3 -m pip install -r requirements-dev.txt
python3 tests/make_orientation_fixtures.py
python3 tests/make_album_fixtures.py
python3 tests/make_progress_fixtures.py
clang tests/decode_qr.m -framework Foundation -framework CoreImage -framework ImageIO -o build/decode_qr
python3 scripts/build.py --debug
adb install --no-incremental -r build/still.apk
adb shell am start -n com.portalframe/.MainActivity
adb shell pidof com.portalframe
```

Use the PID printed above in the WebView forward, then forward the local upload port:

```sh
adb forward tcp:9222 localabstract:webview_devtools_remote_PID
adb forward tcp:8080 tcp:8080
node tests/autosave.mjs
node tests/albums.mjs
node tests/progress.mjs
```

`tests/albums.mjs` reconnects its WebView forward after its restart test. The suites use raw CDP for the older Android WebView and Playwright for the desktop browser. Progress tests throttle transfers, import a temporary local folder, and interrupt a body. USB discovery is also tested against FAT32/exFAT disk images; a physical drive/adapter check remains separate.

After testing, install a normal build and remove only the forwards you created:

```sh
python3 scripts/build.py
adb install --no-incremental -r build/still.apk
adb shell am start -n com.portalframe/.MainActivity
adb forward --remove tcp:9222
adb forward --remove tcp:8080
```

The `sleep.mjs` and `weather.mjs` suites exercise sensor/clock and live weather behavior. They depend on the device environment; weather also requires internet access. `scripts/frame_debug.mjs` evaluates a CDP expression and can save a screenshot for diagnosis. Captured states, images, and local evidence belong in ignored `build/` directories.

## Privacy and networking

The browser server listens only during a temporary session, uses an eight-digit code for album listings/mutations, uploads, and progress, and enforces Host/Origin checks and attempt limits. Upload transport is local HTTP. No image-download endpoint is exposed. WebView assets use the intercepted `https://frame.local/` origin with restricted content security policy and no filesystem access.

IP weather sends requests to IPWHOIS.io and Open-Meteo; photos are not included. Settings and album metadata stay on the device. Personal photos, signed APKs, tool downloads, and account secrets are not repository contents.
