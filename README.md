# Still

A local photo-frame app for Meta Portal. Import photos from USB or a browser, organize them into albums, and keep a slideshow on screen with optional clock, date, weather, and an automatic nightstand mode.

**Version 0.11.0 · Android 9+ · MIT license**

[Download the latest release](https://github.com/ChuranHe/Still/releases/latest) · [Install on your Portal](#install-a-prebuilt-apk)

## Features

- **Albums:** create, rename, select, and delete albums. Each album has independent photo copies; duplicates are skipped within an album.
- **Album covers:** choose 1–4 photos for an edge-to-edge collage with differently sized tiles. Without a custom cover, one photo is chosen at random.
- **Imports:** USB drives, folders already on the Portal, and a temporary Wi-Fi upload page with an address, QR code, and upload code.
- **Progress:** photo counts, current-file transfer progress, preparing/saving stages, and final added/duplicate/skipped results on the Portal and browser.
- **Photo fitting:** JPEG, HEIF/HEIC, PNG, and WebP; automatic orientation; all aspect ratios; crop-to-fill or preserved proportions as appropriate.
- **Slideshow:** complete shuffle rounds, adjustable timing and transitions, hold, pause, previous/next, and swipe controls.
- **Widgets:** configurable clock/date, IP-based or manual weather location, units, position, and size.
- **Nightstand:** a dim clock during selected hours when ambient light falls below a configurable threshold.
- **Storage:** total/free space, system/other usage, and usage by album. Only optimized display copies and thumbnails are retained.
- **Automatic settings:** valid changes save as you make them, without a save button or confirmation toast.

Still has been tested on an Android 10 Portal with a 1280 × 800 display that rotates to 800 × 1280. It runs in the foreground and keeps the screen awake. Other Android devices and HEIF codec variants may behave differently.

## Install a prebuilt APK

No development toolchain or source build is needed. You need a computer, a USB data cable, and a Portal that allows USB debugging and sideloaded Android apps.

1. Download [**still-0.11.0.apk**](https://github.com/ChuranHe/Still/releases/download/v0.11.0/still-0.11.0.apk) from the [GitHub release](https://github.com/ChuranHe/Still/releases/latest). Download the `.apk` asset, rather than either source-code archive.
2. Download Google's [SDK Platform-Tools for your computer](https://developer.android.com/tools/releases/platform-tools), extract the ZIP, and copy the APK into the extracted `platform-tools` folder. Android Studio is not required.
3. On the Portal, enable **Developer options → USB debugging** in system settings. Android usually reveals Developer options after tapping **Build number** seven times in About; the location and availability depend on Portal firmware. This installation method requires firmware that exposes USB debugging.
4. Keep the Portal powered, connect it to the computer with a USB data cable, and accept **Allow USB debugging** on the Portal. Remove any USB photo drive while the computer uses the port.
5. Open a terminal in `platform-tools` and run the commands below for your computer.

**macOS / Linux:**

```sh
./adb devices
./adb install --no-incremental -r still-0.11.0.apk
./adb shell am start -n com.portalframe/.MainActivity
```

**Windows PowerShell:**

```powershell
.\adb.exe devices
.\adb.exe install --no-incremental -r still-0.11.0.apk
.\adb.exe shell am start -n com.portalframe/.MainActivity
```

After the first command, the Portal must appear with status `device`. If it says `unauthorized`, accept the debugging prompt and run the command again. If nothing appears, check USB debugging and use a data-capable cable/port. Windows may also require an appropriate device USB driver. If several devices appear, add `-s PORTAL_SERIAL` after `adb` in the install and launch commands, replacing `PORTAL_SERIAL` with the Portal's value from the list.

The install command should print **Success**; the final command opens Still even if the Portal launcher hides sideloaded apps. You can disconnect the computer afterward; photos are imported separately inside an album.

For later updates, download the newer release's APK and repeat the install/launch commands with its filename. **`-r` keeps existing albums and settings when the signing certificate matches.** A source build uses its own local key and may not be compatible with the published APK. If Android reports `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, stop and use a build signed with the original key; uninstalling Still deletes its private photo library and settings.

Each release includes **SHA256SUMS** and **SIGNING_CERTIFICATE.txt**. To check a download, compare its SHA-256 with the matching entry in SHA256SUMS: `shasum -a 256 still-0.11.0.apk` on macOS, `sha256sum still-0.11.0.apk` on Linux, or `Get-FileHash .\still-0.11.0.apk -Algorithm SHA256` in PowerShell. The release APK is a normal signed build with app/WebView debugging disabled.

## Build from source

Requirements: Python 3.12+, JDK 17, Android platform 32, Android build-tools 32.0.0, and ADB. Node.js is needed only for JavaScript tests and formatting.

On macOS, the bootstrap script downloads a project-local JDK and Android tools from their official providers and verifies archive checksums:

```sh
python3 scripts/bootstrap.py
python3 scripts/build.py
.tools/platform-tools/adb devices
.tools/platform-tools/adb install --no-incremental -r build/still.apk
.tools/platform-tools/adb shell am start -n com.portalframe/.MainActivity
```

Accept the USB-debugging prompt on the Portal. If ADB reports `unauthorized`, authorize this computer and retry. If the launcher does not list the sideloaded app, use the launch command above.

An existing toolchain can be selected with `JAVA_HOME` (or `STILL_JAVA_HOME`) and `ANDROID_HOME`/`ANDROID_SDK_ROOT`. `STILL_ANDROID_TOOLS` supports the bootstrap's combined tool directory. See [development instructions](docs/DEVELOPMENT.md) for details.

Builds generate a local development signing key in `.tools/`. Keep that key to install compatible updates with `adb install -r`. Signing keys, APKs, device data, and tool downloads are excluded from Git. See [signing and updates](docs/DEVELOPMENT.md#signing-and-updates) before distributing your own builds.

## First use

1. Open **Photos**, or **Add photos** on an empty frame.
2. Tap **＋ Create an album**, enter a name, and tap **Create album**. Tap **Cancel** or outside the dialog to leave without creating it.
3. Add photos using one of the methods below.
4. Tap **Back to frame →** to play the selected album.
5. Open **Settings** to adjust the frame. Valid changes save automatically; text and numeric edits save after a short pause. Closing Settings also applies pending valid edits.

Incomplete or invalid sleep/location fields show an inline message and keep the last valid values. Other valid settings can still change. There is no “settings saved” notification.

## Albums and covers

Tap an album to select it for playback and reveal its import controls. Only that album's eligible photos appear in the slideshow. **← Albums** returns to the list. Album names are 1–80 characters; entering an existing name reopens that album, ignoring case.

Inside an album:

- **Rename** opens a name dialog; **Save name** confirms the album name.
- **Delete album** removes that album and its copies after confirmation.
- The **×** on a photo removes only that photo from this album, after confirmation.
- **Choose cover** opens the cover editor. Tap selected photos to deselect them, select 1–4 photos, review the live preview, then tap **Save cover**. Cancel or tap outside to discard edits. Deselect a photo before adding a replacement when all four slots are occupied.

| Cover choice    | Thumbnail                                                                                 |
| --------------- | ----------------------------------------------------------------------------------------- |
| No custom cover | One random photo, stable across refreshes/restarts while the album contents stay the same |
| One photo       | A single image filling the thumbnail                                                      |
| Two photos      | Two tiles of different widths                                                             |
| Three photos    | A large lead image beside two stacked tiles                                               |
| Four photos     | Four differently sized tiles in a mosaic                                                  |

Cover images fill their tiles without gaps or background mats; partial cropping is allowed. This changes only the thumbnail. Gallery previews preserve each saved photo's proportions over the gallery background. Neither uses the slideshow's warm-white/black spare background.

Custom covers survive renaming and restarts. Deleted cover photos disappear from the cover; if none remain, an available photo becomes the automatic cover. Empty albums show a placeholder. Copies in different albums remain independent when photos or albums are deleted.

## Add photos

Create or enter the destination album before importing.

### USB drive

1. Disconnect the computer's USB data cable and connect a **FAT32 or exFAT** drive to the Portal, directly or through a USB-C OTG adapter.
2. Enter an album and tap **USB drive**.
3. Allow USB access, choose the drive/volume, and navigate to the photo folder.
4. Tap **Import this folder and subfolders** and wait for the result.
5. Remove the drive after copying finishes. Photos play from the Portal's storage.

Some Portal firmware does not mount USB mass-storage drives. Still includes a **read-only USB Host/SCSI reader** for FAT32 and exFAT, so a drive can work in Still even when Android does not show it as a normal volume.

Supported: MBR primary partitions, GPT partitions, unpartitioned FAT32/exFAT, 512–4096-byte sectors, LUN 0, fragmented/contiguous files, FAT32 long names, and exFAT Unicode names. NTFS, APFS, encrypted drives, extended MBR partitions, UAS-only devices, and READ CAPACITY(16) devices are unsupported. Some drives need a powered hub. Disconnecting a drive stops reading while completed copies remain.

### Computer or phone over Wi-Fi

1. Connect both devices to the same Wi-Fi network.
2. On the Portal, enter an album and tap **Computer upload**. A temporary server displays its address, QR code, and eight-digit code.
3. Scan the QR code, or open the displayed address and enter its code. Allow browser local-network access if prompted. The QR also preselects the destination album.
4. Check **Add to album**, or create another album using **＋ Create an album**.
5. Choose/drop photos and click **Add to my Portal →**. Wait for **Finished** and review the results.
6. Tap **Stop uploads** when finished, then **Back to photos → Back to frame →**.

Always use the address shown on the Portal, such as `http://<portal-ip>:8080/`. Guest-network isolation can prevent access. Sessions expire after 30 minutes or when stopped/closed. **Back to photos** leaves the session running. New sessions have new codes; opening a new QR link updates an existing browser tab's code and album.

Browser uploads to another album do not switch the Portal's selected slideshow album. Enter that album on the Portal to play it.

### Files already on the Portal

Enter an album, tap **On this Portal**, choose an available Pictures, DCIM, Download, mounted-volume, or **Computer import inbox** folder, then choose **Import this folder and subfolders**. Grant storage access if requested.

For an ADB folder transfer:

```sh
python3 scripts/import_photos.py /path/to/photos
```

Then choose **On this Portal → Computer import inbox → Import this folder and subfolders** in the destination album. Processed inbox originals are removed after import; source files outside this app-owned inbox stay untouched.

### Import progress

The Portal shows progress in Photos, Settings, the upload dialog, and over the frame while importing. It identifies the destination album even if another album is selected.

| Stage               | Meaning                                                                                   |
| ------------------- | ----------------------------------------------------------------------------------------- |
| Finding photos      | USB/local folders are being scanned; the number found is shown with an animated bar       |
| Copying / Receiving | Current-photo byte progress and the number of photos processed                            |
| Preparing / Saving  | The Portal is decoding, resizing, or saving the display copy                              |
| Finished            | All queued photos were processed; check the separate added, duplicate, and skipped counts |
| Stopped             | The operation was interrupted; completed copies remain                                    |

Overall progress measures **photos processed**, not time or bytes. A photo counts after its import result returns. Duplicates and unreadable files count as processed, so 100% does not mean every photo was added.

The browser has separate overall and current-photo bars. Once bytes are transferred, it shows **Saving photo … on Portal** until processing finishes. To monitor a USB/local import from the browser, open a computer-upload session first, then start the folder import on the Portal. Its progress card updates about once per second while that session remains reachable. USB still uses the physical port; browser monitoring uses Wi-Fi.

A browser batch left idle between files is marked stopped after about a minute. Retry from the original files after an interruption; existing copies are reported as duplicates.

## Photo fitting and storage

JPEG, HEIF/HEIC, PNG, and WebP are decoded by Android, including supported EXIF/container rotation and mirroring. Stored display copies are JPEG at quality 90, with thumbnails up to 360 pixels. Inputs are limited to 100 MB and 150 megapixels; folder imports scan at most 5000 photos and 32 directory levels.

| Photo shape                                      | Frame behavior                               | With “Same direction only”        |
| ------------------------------------------------ | -------------------------------------------- | --------------------------------- |
| Squarer than 4:3, including square               | Preserve proportions and fit the whole image | Appears in either frame direction |
| 4:3 / 3:4 or 3:2 / 2:3                           | Center-crop to fill                          | Matches photo direction           |
| Other ratios losing at most 25% in a center crop | Center-crop to fill                          | Matches photo direction           |
| More extreme panoramas/tall photos               | Fit the whole image                          | Matches photo direction           |

A 0.5% rounding allowance keeps resized 4:3 camera images in the camera group. The crop threshold retains at least 75% of the image area. Choose **Settings → Frame direction → Auto · follow Portal** for sensor-based rotation, or force Landscape/Portrait. **Photos to display → All photos** allows opposite-direction images too.

**Spare background → Warm white photo paper / Black** changes unused slideshow space. It does not alter stored photos or gallery previews.

On a 1280 × 800 Portal, cropped copies are bounded by 1280 × 800 landscape or 800 × 1280 portrait. Uncropped copies preserve their aspect ratio with a maximum long edge of 1280 pixels, retaining detail for either display direction:

| Original    | Stored copy            |
| ----------- | ---------------------- |
| 4000 × 3000 | 1280 × 800, cropped    |
| 3000 × 4000 | 800 × 1280, cropped    |
| 2000 × 2000 | 1280 × 1280, uncropped |
| 1500 × 1200 | 1280 × 1024, uncropped |
| 1200 × 1500 | 1024 × 1280, uncropped |
| 3600 × 1200 | 1280 × 427, uncropped  |

Smaller images are never enlarged. Reopening the app does not recompress bounded copies. Temporary upload/decoder originals are removed after processing, and interrupted staging files are cleared at startup. USB/computer source files remain untouched.

The same original is skipped if already in the destination album. To replace an older, smaller copy, remove it from that album and re-import the original. Discarded detail cannot be recovered from an existing display copy. Legacy flat libraries migrate into an **Imported photos** album; existing album names and contents are preserved.

Photos shows a capacity bar for physical total storage, free space, system/other data, and each album. Album usage includes copies, thumbnails, and metadata; copies in different albums consume space in both. The remainder includes Android, other apps, reserved space, and filesystem overhead. Units are decimal GB/MB.

## Slideshow, widgets, and sleep

Settings save automatically. Under **Slideshow**, choose Hold forever, 5/10/30 seconds, 1/5/15 minutes, or 1 hour; Soft fade, Slide across, or Instant; a 0.4/1.2/2.5-second transition; and optional shuffle.

Shuffle shows every eligible photo once before a new random round. With multiple photos, the next round cannot begin with the last photo of the previous round. Normal weather/library refreshes preserve the round. Changing albums, the eligible set, or manually selecting a preview starts a new round.

Tap the frame for playback controls, or swipe to move between photos. Tapping an eligible gallery preview holds it paused; use play to resume. Hold forever disables timed advancement. Opening a panel suspends automatic advancement until it closes.

### Clock and weather

Under **Little details**, toggle Clock, Date, or 24-hour clock, and choose widget size and top/bottom corners. Date/time follow the Portal's system clock and timezone.

Enable **Show weather** and choose **Automatic · public IP**, or **Manual location** with a place name and valid latitude/longitude. Choose Celsius/Fahrenheit. Manual location applies once its fields are valid; **Refresh location & weather** requests an update using the current settings.

Automatic location uses [IPWHOIS.io](https://ipwhois.io/); conditions use [Open-Meteo](https://open-meteo.com/). The frame shows city/state, temperature, and conditions. Provider attribution is in Settings. IP location is approximate and can identify a nearby ISP city or VPN exit. Updates run about every 15 minutes; no photos are sent to either service.

### Automatic nightstand

Under **Sleep mode**, enable Automatic nightstand, set different From/Until times, and choose the dim-room threshold. Fresh defaults are midnight–7 AM and 10 lux. Both the time window and dim-light condition must hold. Overnight windows are supported.

The tested Portal exposes a `tcs34x0` ambient-light sensor. Nightstand shows a centered clock on black at minimum app brightness and pauses the slideshow. Photos and brightness return when the room brightens or the window ends. Entry waits five seconds; exit has a three-second delay and hysteresis. Tap the clock to wake for five minutes, or use **Preview night clock · 15 seconds** to preview it. Global device brightness is unchanged.

## Troubleshooting

| Symptom                              | Check                                                                                                               |
| ------------------------------------ | ------------------------------------------------------------------------------------------------------------------- |
| USB is missing from Android          | Use Still's USB reader inside an album; check access permission, cable/OTG connection, and FAT32/exFAT formatting   |
| Upload page will not open            | Start a session, use its current address, use the same Wi-Fi, allow local-network access, and avoid guest isolation |
| Code is rejected                     | Use the current session's code or QR link                                                                           |
| Photos do not play                   | Enter the destination album and check device direction / Same direction only                                        |
| Fifth cover photo cannot be selected | Deselect one of the existing four first                                                                             |
| Settings field does not apply        | Correct the inline validation message; incomplete fields retain their previous valid values                         |
| Nightstand does not enter            | Check the Portal's time, enabled schedule, lux reading, dim-light delay, and temporary wake override                |
| Weather shows a nearby city          | IP location is approximate; use manual coordinates/place name                                                       |

## Development

See [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) for architecture, toolchains, tests, and device validation, and [CONTRIBUTING.md](CONTRIBUTING.md) for contributions.

```sh
python3 scripts/test.py
npm ci
npm run format:check
```

The hardware-independent suite covers album isolation/migration/covers, photo fitting, shuffle, settings validation, sleep timing, read-only USB filesystems, import progress/discovery, and upload validation. Device suites exercise actual decoding, browser uploads, progress, and settings persistence. Test photos are generated synthetic fixtures; personal photos and device backups are never committed.

## License

[MIT](LICENSE). Bundled third-party notices are listed in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
