package com.portalframe;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.usage.StorageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ColorSpace;
import android.graphics.ImageDecoder;
import android.graphics.Point;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.display.DisplayManager;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.os.storage.StorageManager;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.OrientationEventListener;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.TextView;
import java.io.*;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONArray;
import org.json.JSONObject;

public class MainActivity extends Activity {
  private static final String ORIGIN = "https://frame.local/";
  private static final String USB_PERMISSION = "com.portalframe.USB_PERMISSION";
  private static final long MAX_PHOTO_BYTES = 100L * 1024 * 1024;
  // Portal firmware does not reliably apply a zero override. Its reported panel minimum is 1/255.
  private static final float NIGHT_BRIGHTNESS = 1f / 255f;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final ExecutorService weatherWorker = Executors.newSingleThreadExecutor();
  private final AtomicBoolean importing = new AtomicBoolean(false);
  private volatile ImportProgress importProgress;
  private final Object progressLock = new Object();
  private boolean progressPending;
  private final Runnable batchIdleCheck =
      () -> {
        ImportProgress p = importProgress;
        if (p != null && p.source.equals("Computer upload") && p.active()) {
          if (importing.get()) scheduleBatchTimeout();
          else {
            p.finish(false, "Upload stopped before all photos finished");
            pushImportProgress();
          }
        }
      };
  private WebView frame;
  private FrameLayout root;
  private AlertDialog frameDialog;
  private UsbManager usbManager;
  private volatile UsbMassStorage activeUsb;
  private String usbDeviceName, pendingUsbName;
  private boolean usbOpening;
  private volatile UploadServer uploads;
  private boolean uploadStarting;
  private String uploadError = "";
  private final Runnable uploadExpiry =
      () -> stopUploads("Upload session ended. Open Computer upload to start again.");
  private final BroadcastReceiver usbReceiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
          if (device == null) return;
          if (USB_PERMISSION.equals(intent.getAction())) {
            if (!device.getDeviceName().equals(pendingUsbName)) return;
            pendingUsbName = null;
            if (usbManager.hasPermission(device)) openUsb(device);
            else notice("USB access was not granted. Tap USB drive to try again.");
          } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(intent.getAction())) {
            if (device.getDeviceName().equals(usbDeviceName)) {
              closeUsb();
              if (frameDialog != null) frameDialog.cancel();
              notice("USB drive disconnected. Photos already copied stay on the frame.");
            }
            publish();
          } else if (UsbMassStorage.storageInterface(device) != null) {
            notice("USB drive detected. Open Photos → USB drive to import.");
            publish();
          }
        }
      };
  private AlbumStore albumStore;
  private volatile AlbumStore.Album selectedAlbum;
  private long deviceCapacity;
  private volatile JSONObject settings = new JSONObject();
  private volatile JSONObject weather = new JSONObject();
  private volatile JSONObject weatherLocation = new JSONObject();
  private volatile JSONObject weatherStatus = new JSONObject();
  private boolean weatherInFlight, weatherRefreshQueued;
  private volatile JSONObject report = new JSONObject();
  private volatile JSONObject sleepState = new JSONObject();
  private SensorManager sensorManager;
  private Sensor lightSensor;
  private boolean lightRegistered;
  private float ambientLux = Float.NaN;
  private final SleepRules.Darkness darkness = new SleepRules.Darkness();
  private FrameLayout nightOverlay;
  private TextView nightClock;
  private volatile boolean nightActive;
  private long wakeUntil, previewUntil;
  private float previousBrightness = -1, previousButtonBrightness = -1;
  private final SensorEventListener lightListener =
      new SensorEventListener() {
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}

        public void onSensorChanged(SensorEvent event) {
          if (event.values.length > 0 && Float.isFinite(event.values[0]) && event.values[0] >= 0)
            ambientLux = event.values[0];
        }
      };
  private final Runnable sleepTick =
      new Runnable() {
        public void run() {
          updateSleep();
          main.postDelayed(this, 1000);
        }
      };

  private static final class DisplayState {
    final int width, height, rotation;
    final boolean naturalPortrait;

    DisplayState(int width, int height, int rotation) {
      this.width = width;
      this.height = height;
      this.rotation = rotation;
      naturalPortrait = rotation % 180 == 0 ? height > width : width > height;
    }
  }

  private volatile DisplayState display = new DisplayState(1280, 800, 90);
  private DisplayManager displayManager;
  private OrientationEventListener directionListener;
  private boolean canDetectDirection;
  private volatile String deviceDirection = "unknown";
  private final DisplayManager.DisplayListener displayListener =
      new DisplayManager.DisplayListener() {
        public void onDisplayAdded(int id) {}

        public void onDisplayRemoved(int id) {}

        public void onDisplayChanged(int id) {
          if (id == getWindowManager().getDefaultDisplay().getDisplayId() && updateDisplay())
            publish();
        }
      };
  private volatile boolean destroyed;
  private String pendingBrowse;
  private final Runnable weatherTick =
      new Runnable() {
        public void run() {
          refreshWeather();
          main.postDelayed(this, 15 * 60 * 1000);
        }
      };

  @Override
  public void onCreate(Bundle saved) {
    super.onCreate(saved);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    getWindow()
        .setFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
    immersive();
    try {
      albumStore = new AlbumStore(getFilesDir());
    } catch (IOException error) {
      throw new IllegalStateException(error);
    }
    try {
      settings = new JSONObject(getPreferences(0).getString("settings", "{}"));
    } catch (Exception ignored) {
    }
    try {
      weather = new JSONObject(getPreferences(0).getString("weather", "{}"));
    } catch (Exception ignored) {
    }
    try {
      weatherLocation = new JSONObject(getPreferences(0).getString("weatherLocation", "{}"));
    } catch (Exception ignored) {
    }
    // Preserve an existing manually configured location when upgrading.
    if (!settings.has("weatherLocation")) {
      try {
        settings.put(
            "weatherLocation",
            !settings.optString("latitude").isEmpty() && !settings.optString("longitude").isEmpty()
                ? "manual"
                : "ip");
      } catch (Exception ignored) {
      }
      getPreferences(0).edit().putString("settings", settings.toString()).apply();
    }
    applyOrientationMode();
    updateDisplay();
    displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
    directionListener =
        new OrientationEventListener(this) {
          @Override
          public void onOrientationChanged(int degrees) {
            if (degrees == ORIENTATION_UNKNOWN)
              return; // Keep the last stable direction when laid flat.
            int quadrant = ((degrees + 45) / 90) % 4;
            int distance = Math.abs(degrees - quadrant * 90);
            distance = Math.min(distance, 360 - distance);
            if (distance > 25)
              return; // Ignore the ambiguous diagonal while the frame is being turned.
            boolean portrait = (quadrant % 2 == 0) == display.naturalPortrait;
            String next = portrait ? "portrait" : "landscape";
            if (!next.equals(deviceDirection)) {
              deviceDirection = next;
              publish();
            }
          }
        };
    canDetectDirection = directionListener.canDetectOrientation();
    sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
    lightSensor = sensorManager == null ? null : sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
    WebView.setWebContentsDebuggingEnabled(
        (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0);
    root = new FrameLayout(this);
    frame = new WebView(this);
    frame.setBackgroundColor(0xff10221d);
    frame.getSettings().setJavaScriptEnabled(true);
    frame.getSettings().setDomStorageEnabled(true);
    frame.getSettings().setAllowFileAccess(false);
    frame.getSettings().setAllowContentAccess(false);
    frame.getSettings().setMixedContentMode(android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    frame.addJavascriptInterface(new Bridge(), "Still");
    frame.setWebChromeClient(
        new WebChromeClient() {
          @Override
          public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
            android.util.Log.d(
                "StillWeb", m.messageLevel() + ": " + m.message() + " line " + m.lineNumber());
            return true;
          }
        });
    frame.setWebViewClient(
        new WebViewClient() {
          @Override
          public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
            return !r.getUrl().toString().startsWith(ORIGIN);
          }

          @Override
          public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
            return localResource(r.getUrl());
          }

          @Override
          public void onPageFinished(WebView v, String url) {
            publish();
          }
        });
    root.addView(frame, new FrameLayout.LayoutParams(-1, -1));
    nightOverlay = new FrameLayout(this);
    nightOverlay.setBackgroundColor(0xff000000);
    nightOverlay.setVisibility(View.GONE);
    nightOverlay.setContentDescription(
        "Nightstand clock. Tap to return to photos for five minutes.");
    nightOverlay.setOnClickListener(v -> wakeFromSleep());
    nightClock = new TextView(this);
    nightClock.setTextColor(0xff8c704d);
    nightClock.setTextSize(112);
    nightClock.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
    nightClock.setFontFeatureSettings("tnum");
    nightClock.setGravity(Gravity.CENTER);
    nightClock.setIncludeFontPadding(false);
    FrameLayout.LayoutParams clockLayout = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
    nightOverlay.addView(nightClock, clockLayout);
    root.addView(nightOverlay, new FrameLayout.LayoutParams(-1, -1));
    setContentView(root);
    frame.loadUrl(ORIGIN + "index.html");
    usbManager = (UsbManager) getSystemService(USB_SERVICE);
    IntentFilter usbEvents = new IntentFilter(USB_PERMISSION);
    usbEvents.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
    usbEvents.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
    registerReceiver(usbReceiver, usbEvents);
    preparePhotoStorage();
  }

  private Bitmap displayCopy(File source, int screenW, int screenH) throws IOException {
    Bitmap decoded =
        ImageDecoder.decodeBitmap(
            ImageDecoder.createSource(source),
            (decoder, info, data) -> {
              PhotoSizing.Plan plan =
                  PhotoSizing.fit(
                      info.getSize().getWidth(), info.getSize().getHeight(), screenW, screenH);
              decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
              decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
              decoder.setTargetSize(plan.decodeWidth, plan.decodeHeight);
            });
    PhotoSizing.Plan crop =
        PhotoSizing.fit(decoded.getWidth(), decoded.getHeight(), screenW, screenH);
    Bitmap result = Bitmap.createBitmap(decoded, crop.x, crop.y, crop.width, crop.height);
    if (result != decoded) decoded.recycle();
    return result;
  }

  private void writeJpeg(File target, Bitmap image, int quality) throws IOException {
    try (FileOutputStream out = new FileOutputStream(target)) {
      if (!image.compress(Bitmap.CompressFormat.JPEG, quality, out))
        throw new IOException("Encode failed");
      out.getFD().sync();
    }
  }

  private void saveThumbnail(AlbumStore.Album album, String name, Bitmap image) throws IOException {
    File thumbs = album.thumbs;
    double scale = Math.min(1, 360.0 / Math.max(image.getWidth(), image.getHeight()));
    Bitmap thumb =
        Bitmap.createScaledBitmap(
            image,
            Math.max(1, (int) Math.round(image.getWidth() * scale)),
            Math.max(1, (int) Math.round(image.getHeight() * scale)),
            true);
    File pending = new File(thumbs, name + ".pending");
    try {
      writeJpeg(pending, thumb, 82);
      if (!pending.renameTo(new File(thumbs, name)))
        throw new IOException("Thumbnail commit failed");
    } finally {
      pending.delete();
      if (thumb != image) thumb.recycle();
    }
  }

  private boolean deleteInbox(File file, File root) throws IOException {
    String path = file.getCanonicalPath(), base = root.getCanonicalPath();
    if (!path.equals(base) && !path.startsWith(base + File.separator)) return false;
    boolean ok = true;
    if (file.isDirectory()) {
      File[] children = file.listFiles();
      if (children == null) return false;
      for (File child : children) ok = deleteInbox(child, root) && ok;
    }
    return (!file.exists() || file.delete()) && ok;
  }

  private void clearImportStaging() {
    File[] files = getCacheDir().listFiles();
    if (files != null)
      for (File file : files)
        if (file.isFile()
            && (file.getName().startsWith("incoming-") || file.getName().startsWith("web-upload-")))
          file.delete();
    for (AlbumStore.Album album : albumStore.list())
      for (File dir : Arrays.asList(album.photos, album.thumbs)) {
        File[] pending = dir.listFiles((d, n) -> n.endsWith(".pending"));
        if (pending != null) for (File file : pending) file.delete();
      }
  }

  private void preparePhotoStorage() {
    importing.set(true);
    final DisplayState target = display;
    worker.execute(
        () -> {
          try {
            AlbumStore.Album legacy = albumStore.migrateLegacy("Imported photos");
            List<AlbumStore.Album> all = albumStore.list();
            String saved =
                getPreferences(0).getString("selectedAlbum", legacy == null ? "" : legacy.id);
            try {
              selectedAlbum = albumStore.get(saved);
            } catch (IOException e) {
              selectedAlbum = all.isEmpty() ? null : all.get(0);
            }
            if (selectedAlbum != null)
              getPreferences(0).edit().putString("selectedAlbum", selectedAlbum.id).apply();
            report = albumReport(selectedAlbum);
            getPreferences(0).edit().remove("report").apply();
            clearImportStaging();
            int resized = 0;
            for (AlbumStore.Album album : all)
              for (File file : album.images()) {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.toString(), bounds);
                int longSide = Math.max(target.width, target.height);
                // Existing display copies keep their crop and are never recompressed on reopening.
                if (Math.max(bounds.outWidth, bounds.outHeight) <= longSide) continue;
                Bitmap image = null;
                File pending = new File(album.photos, file.getName() + ".resize.pending");
                try {
                  image = displayCopy(file, target.width, target.height);
                  writeJpeg(pending, image, 90);
                  if (!pending.renameTo(file)) throw new IOException("Resize commit failed");
                  saveThumbnail(album, file.getName(), image);
                  resized++;
                } finally {
                  pending.delete();
                  if (image != null) image.recycle();
                }
              }
            if (!getPreferences(0).getBoolean("screenCopiesV1Complete", false)) {
              File external = getExternalFilesDir(null);
              boolean cleaned =
                  external == null
                      || deleteInbox(new File(external, "inbox"), new File(external, "inbox"));
              if (cleaned)
                getPreferences(0).edit().putBoolean("screenCopiesV1Complete", true).apply();
            }
            android.util.Log.i(
                "StillStorage",
                "Albums ready: " + all.size() + ", " + resized + " oversized copies resized");
          } catch (Exception error) {
            android.util.Log.e(
                "StillStorage", "Album preparation failed; existing files retained", error);
            notice("Album storage could not be prepared. Reopen Still to retry.");
          } finally {
            importing.set(false);
            publish();
          }
        });
  }

  private JSONArray albumState() throws Exception {
    JSONArray result = new JSONArray();
    for (AlbumStore.Album a : albumStore.list()) {
      File[] images = a.images();
      JSONObject value =
          new JSONObject().put("id", a.id).put("name", a.name).put("count", images.length);
      JSONArray covers = new JSONArray(), coverIds = new JSONArray();
      for (String photo : albumStore.coverPhotos(a.id)) {
        covers.put("albums/" + a.id + "/thumbs/" + photo);
        coverIds.put(photo);
      }
      value.put("covers", covers).put("coverIds", coverIds).put("bytes", a.bytes());
      if (covers.length() > 0) value.put("cover", covers.getString(0));
      result.put(value);
    }
    return result;
  }

  private JSONObject storageState(JSONArray albums) throws Exception {
    StatFs fs = new StatFs(getFilesDir().getAbsolutePath());
    long total = fs.getTotalBytes(), free = fs.getAvailableBytes(), used = 0;
    if (deviceCapacity == 0) {
      try {
        StorageStatsManager stats = getSystemService(StorageStatsManager.class);
        deviceCapacity = Math.max(total, stats.getTotalBytes(StorageManager.UUID_DEFAULT));
      } catch (IOException | RuntimeException e) {
        deviceCapacity = total;
      }
    }
    total = deviceCapacity;
    for (int i = 0; i < albums.length(); i++) used += albums.getJSONObject(i).getLong("bytes");
    return new JSONObject()
        .put("total", total)
        .put("free", free)
        .put("others", Math.max(0, total - free - used))
        .put("albumBytes", used);
  }

  private void chooseAlbum(AlbumStore.Album album, boolean enter) {
    selectedAlbum = album;
    report = albumReport(album);
    getPreferences(0).edit().putString("selectedAlbum", album == null ? "" : album.id).apply();
    if (enter && album != null)
      main.post(
          () -> {
            if (!destroyed)
              frame.evaluateJavascript(
                  "window.StillApp && StillApp.openAlbum(" + JSONObject.quote(album.id) + ")",
                  null);
          });
    publish();
  }

  private JSONObject albumReport(AlbumStore.Album album) {
    try {
      return album == null
          ? new JSONObject()
          : new JSONObject(getPreferences(0).getString("report." + album.id, "{}"));
    } catch (Exception e) {
      return new JSONObject();
    }
  }

  private void saveImportReport(AlbumStore.Album destination, ImportStats stats) throws Exception {
    JSONObject value = stats.json();
    getPreferences(0).edit().putString("report." + destination.id, value.toString()).apply();
    if (selectedAlbum != null && selectedAlbum.id.equals(destination.id)) report = value;
  }

  private boolean hasAlbum() {
    if (selectedAlbum != null) return true;
    notice("Create or enter an album before adding photos.");
    return false;
  }

  private void immersive() {
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
  }

  private boolean updateDisplay() {
    Point p = new Point();
    getWindowManager().getDefaultDisplay().getRealSize(p);
    int rotation = getWindowManager().getDefaultDisplay().getRotation() * 90;
    DisplayState old = display;
    display = new DisplayState(p.x, p.y, rotation);
    return p.x != old.width || p.y != old.height || rotation != old.rotation;
  }

  private void applyOrientationMode() {
    String mode = settings.optString("orientationMode", "auto");
    setRequestedOrientation(
        mode.equals("portrait")
            ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            : mode.equals("landscape")
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
  }

  @Override
  public void onConfigurationChanged(Configuration c) {
    super.onConfigurationChanged(c);
    updateDisplay();
    publish();
  }

  @Override
  public void onWindowFocusChanged(boolean focus) {
    super.onWindowFocusChanged(focus);
    if (focus) immersive();
  }

  @Override
  public void onResume() {
    super.onResume();
    updateDisplay();
    main.removeCallbacks(weatherTick);
    main.post(weatherTick);
    if (displayManager != null) displayManager.registerDisplayListener(displayListener, main);
    if (canDetectDirection) directionListener.enable();
    ambientLux = Float.NaN;
    lightRegistered =
        lightSensor != null
            && sensorManager.registerListener(
                lightListener, lightSensor, SensorManager.SENSOR_DELAY_NORMAL, main);
    main.removeCallbacks(sleepTick);
    main.post(sleepTick);
    if (frame != null) publish();
  }

  @Override
  public void onPause() {
    super.onPause();
    main.removeCallbacks(weatherTick);
    if (displayManager != null) displayManager.unregisterDisplayListener(displayListener);
    if (directionListener != null) directionListener.disable();
    if (sensorManager != null) sensorManager.unregisterListener(lightListener);
    lightRegistered = false;
    main.removeCallbacks(sleepTick);
  }

  @Override
  public void onDestroy() {
    destroyed = true;
    main.removeCallbacksAndMessages(null);
    worker.shutdownNow();
    weatherWorker.shutdownNow();
    unregisterReceiver(usbReceiver);
    closeUsb();
    if (uploads != null) uploads.close();
    if (frame != null) {
      frame.removeJavascriptInterface("Still");
      frame.destroy();
    }
    super.onDestroy();
  }

  @Override
  public void onBackPressed() {
    if (nightActive) wakeFromSleep();
    else if (frame != null)
      frame.evaluateJavascript("window.StillApp && window.StillApp.back()", null);
  }

  private void wakeFromSleep() {
    previewUntil = 0;
    wakeUntil = SystemClock.elapsedRealtime() + 5 * 60 * 1000;
    darkness.reset();
    updateSleep();
  }

  private void updateSleep() {
    if (destroyed || nightOverlay == null) return;
    JSONObject config = settings;
    long now = SystemClock.elapsedRealtime();
    Calendar local = Calendar.getInstance();
    int minute = local.get(Calendar.HOUR_OF_DAY) * 60 + local.get(Calendar.MINUTE);
    boolean enabled = config.optBoolean("sleepEnabled", true);
    boolean scheduled =
        SleepRules.inWindow(
            minute,
            SleepRules.minutes(config.optString("sleepStart", "00:00")),
            SleepRules.minutes(config.optString("sleepEnd", "07:00")));
    double threshold = config.optDouble("sleepLux", 10);
    boolean preview = now < previewUntil;
    boolean active =
        darkness.update(
                now,
                enabled && scheduled && lightRegistered && now >= wakeUntil,
                ambientLux,
                threshold)
            || preview;
    String reason =
        preview
            ? "Preview · tap clock to exit"
            : !enabled
                ? "Sleep mode is off"
                : lightSensor == null || !lightRegistered
                    ? "Ambient light sensor unavailable"
                    : !scheduled
                        ? "Outside sleep hours"
                        : now < wakeUntil
                            ? "Awake temporarily · "
                                + ((wakeUntil - now + 59999) / 60000)
                                + " min left"
                            : !Float.isFinite(ambientLux)
                                ? "Waiting for a light reading"
                                : active
                                    ? "Nightstand active"
                                    : darkness.pending()
                                        ? "Room is dim · entering nightstand"
                                        : "Waiting for a darker room";
    if (active != nightActive) {
      WindowManager.LayoutParams attrs = getWindow().getAttributes();
      if (active) {
        if (frameDialog != null && frameDialog.isShowing()) frameDialog.cancel();
        previousBrightness = attrs.screenBrightness;
        previousButtonBrightness = attrs.buttonBrightness;
        attrs.screenBrightness = NIGHT_BRIGHTNESS;
        attrs.buttonBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_OFF;
        nightOverlay.setVisibility(View.VISIBLE);
        nightOverlay.bringToFront();
      } else {
        attrs.screenBrightness = previousBrightness;
        attrs.buttonBrightness = previousButtonBrightness;
        nightOverlay.setVisibility(View.GONE);
      }
      getWindow().setAttributes(attrs);
      nightActive = active;
    }
    if (active) {
      String text =
          new java.text.SimpleDateFormat(
                  config.optBoolean("hour24", false) ? "HH:mm" : "h:mm a", Locale.US)
              .format(local.getTime());
      if (!text.contentEquals(nightClock.getText())) {
        SpannableString styled = new SpannableString(text);
        int suffix = text.indexOf(' ');
        if (suffix >= 0)
          styled.setSpan(
              new RelativeSizeSpan(.22f), suffix, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        nightClock.setText(styled);
      }
    }
    try {
      JSONObject value = new JSONObject();
      value.put("available", lightRegistered);
      value.put("sensor", lightSensor == null ? "" : lightSensor.getVendor());
      value.put(
          "lux", Float.isFinite(ambientLux) ? Math.round(ambientLux * 10d) / 10d : JSONObject.NULL);
      value.put("active", active);
      value.put("scheduled", scheduled);
      value.put("preview", preview);
      value.put("reason", reason);
      value.put("brightness", (double) getWindow().getAttributes().screenBrightness);
      value.put(
          "localTime",
          String.format(
              Locale.ROOT,
              "%02d:%02d",
              local.get(Calendar.HOUR_OF_DAY),
              local.get(Calendar.MINUTE)));
      if (!value.toString().equals(sleepState.toString())) {
        sleepState = value;
        if (frame != null)
          frame.evaluateJavascript(
              "window.StillApp && window.StillApp.sleepChanged(" + value + ")", null);
      }
    } catch (Exception e) {
      android.util.Log.e("Still", "Sleep status unavailable", e);
    }
  }

  private WebResourceResponse localResource(Uri uri) {
    try {
      if (!"https".equals(uri.getScheme()) || !"frame.local".equals(uri.getHost()))
        return emptyResource();
      String path = uri.getPath();
      InputStream in;
      String mime;
      if (path.startsWith("/albums/")) {
        String[] parts = path.split("/");
        if (parts.length != 5
            || !parts[4].matches("[a-f0-9]{64}\\.jpg")
            || !(parts[3].equals("photos") || parts[3].equals("thumbs"))) return emptyResource();
        AlbumStore.Album owner = albumStore.get(parts[2]);
        in =
            new FileInputStream(
                new File(parts[3].equals("photos") ? owner.photos : owner.thumbs, parts[4]));
        mime = "image/jpeg";
      } else {
        if (path.contains("..")) return emptyResource();
        String name = path.equals("/") ? "index.html" : path.substring(1);
        in = getAssets().open(name);
        mime =
            name.endsWith(".css")
                ? "text/css"
                : name.endsWith(".js")
                    ? "application/javascript"
                    : name.endsWith(".svg") ? "image/svg+xml" : "text/html";
      }
      WebResourceResponse response = new WebResourceResponse(mime, "UTF-8", in);
      Map<String, String> headers = new HashMap<>();
      headers.put(
          "Content-Security-Policy",
          "default-src 'self'; img-src 'self' data:; style-src 'self'; script-src 'self';"
              + " connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'");
      headers.put("X-Content-Type-Options", "nosniff");
      response.setResponseHeaders(headers);
      return response;
    } catch (Exception e) {
      return emptyResource();
    }
  }

  private WebResourceResponse emptyResource() {
    return new WebResourceResponse(
        "text/plain",
        "UTF-8",
        404,
        "Not Found",
        Collections.emptyMap(),
        new ByteArrayInputStream(new byte[0]));
  }

  private JSONArray library(AlbumStore.Album album, DisplayState target, JSONObject config) {
    JSONArray list = new JSONArray();
    if (album == null) return list;
    File photos = album.photos;
    File[] files = album.images();
    if (files == null) return list;
    Arrays.sort(files, Comparator.comparingLong(File::lastModified).reversed());
    for (File file : files) {
      try {
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.toString(), b);
        JSONObject item = new JSONObject();
        item.put("id", file.getName());
        item.put("width", b.outWidth);
        item.put("height", b.outHeight);
        int originalW = b.outWidth, originalH = b.outHeight;
        item.put("name", "Photo");
        File meta = new File(photos, file.getName() + ".json");
        if (meta.exists()) {
          JSONObject m = new JSONObject(readText(new FileInputStream(meta), 16384));
          item.put("name", m.optString("name", "Photo"));
          item.put("source", m.optString("source", "Imported"));
          item.put("format", m.optString("format", "image/jpeg"));
          // Use original dimensions for the gate, avoiding rounding in a downsampled JPEG.
          originalW = m.optInt("width", b.outWidth);
          originalH = m.optInt("height", b.outHeight);
        }
        double allowed = config.optDouble("tolerance", 0);
        boolean crop = config.optBoolean("cropFourThree", true);
        item.put("orientation", originalW > originalH ? "landscape" : "portrait");
        item.put(
            "supported",
            PhotoRules.canImport(originalW, originalH, target.width, target.height, allowed, crop));
        item.put(
            "eligible",
            PhotoRules.accepts(
                originalW,
                originalH,
                target.width,
                target.height,
                allowed,
                crop,
                !config.optString("photoDirections", "matching").equals("all")));
        item.put(
            "crop",
            (originalW > originalH) == (target.width > target.height)
                && PhotoRules.shouldCrop(originalW, originalH, target.width, target.height));
        item.put("unrestricted", PhotoRules.isSquareish(originalW, originalH));
        item.put("url", "albums/" + album.id + "/photos/" + file.getName());
        item.put("thumbnail", "albums/" + album.id + "/thumbs/" + file.getName());
        list.put(item);
      } catch (Exception ignored) {
      }
    }
    return list;
  }

  private void publish() {
    if (destroyed || frame == null) return;
    worker.execute(
        () -> {
          try {
            DisplayState target = display;
            JSONObject config = settings;
            JSONObject state = new JSONObject();
            state.put("width", target.width);
            state.put("height", target.height);
            state.put("settings", config);
            state.put("rotation", target.rotation);
            state.put("direction", target.width > target.height ? "landscape" : "portrait");
            state.put("deviceDirection", deviceDirection);
            state.put("canDetectDirection", canDetectDirection);
            state.put("sleep", sleepState);
            AlbumStore.Album selected = selectedAlbum;
            JSONArray albums = albumState();
            state.put("albums", albums).put("storage", storageState(albums));
            state.put(
                "selectedAlbum",
                selected == null
                    ? JSONObject.NULL
                    : new JSONObject().put("id", selected.id).put("name", selected.name));
            state.put("photos", library(selected, target, config));
            state.put("weather", weather);
            state.put("report", report);
            state.put("busy", importing.get());
            state.put("usbStatus", usbStatus());
            main.post(
                () -> {
                  if (!destroyed) {
                    try {
                      state.put("sleep", sleepState);
                    } catch (org.json.JSONException ignored) {
                    }
                    try {
                      state.put("weather", weather);
                      state.put("weatherLocation", weatherLocation);
                      state.put("weatherStatus", weatherStatus);
                    } catch (org.json.JSONException ignored) {
                    }
                    try {
                      state.put("uploads", uploadState());
                    } catch (org.json.JSONException ignored) {
                    }
                    try {
                      state.put("importProgress", progressState());
                    } catch (org.json.JSONException ignored) {
                    }
                    frame.evaluateJavascript(
                        "window.StillApp && window.StillApp.receive(" + state + ")", null);
                  }
                });
          } catch (Exception e) {
            android.util.Log.e("Still", "State unavailable", e);
          }
        });
  }

  private JSONObject progressState() {
    ImportProgress p = importProgress;
    return p == null ? new JSONObject() : new JSONObject(p.snapshot());
  }

  private void pushImportProgress() {
    synchronized (progressLock) {
      if (progressPending || destroyed) return;
      progressPending = true;
    }
    main.postDelayed(
        () -> {
          synchronized (progressLock) {
            progressPending = false;
          }
          if (!destroyed && frame != null)
            frame.evaluateJavascript(
                "window.StillApp && window.StillApp.importProgress(" + progressState() + ")", null);
        },
        100);
  }

  private ImportProgress beginProgress(String source, AlbumStore.Album album) {
    ImportProgress p =
        new ImportProgress(
            UUID.randomUUID().toString().replace("-", ""), source, album.id, album.name);
    importProgress = p;
    pushImportProgress();
    return p;
  }

  private void scheduleBatchTimeout() {
    main.removeCallbacks(batchIdleCheck);
    main.postDelayed(batchIdleCheck, 60000);
  }

  private void notice(String message) {
    main.post(
        () -> {
          if (!destroyed)
            frame.evaluateJavascript(
                "window.StillApp && window.StillApp.notice(" + JSONObject.quote(message) + ")",
                null);
        });
  }

  public class Bridge {
    @JavascriptInterface
    public void refresh() {
      publish();
    }

    @JavascriptInterface
    public void save(String raw) {
      try {
        JSONObject input = new JSONObject(raw), clean = new JSONObject();
        clean.put("seconds", clamp(input.optInt("seconds", 30), 0, 86400));
        String transition = input.optString("transition", "fade");
        clean.put(
            "transition",
            Arrays.asList("fade", "slide", "none").contains(transition) ? transition : "fade");
        clean.put("transitionMs", clamp(input.optInt("transitionMs", 1200), 0, 5000));
        clean.put(
            "background",
            input.optString("background", "paper").equals("black") ? "black" : "paper");
        String mode = input.optString("orientationMode", "auto");
        clean.put(
            "orientationMode",
            Arrays.asList("auto", "portrait", "landscape").contains(mode) ? mode : "auto");
        clean.put(
            "photoDirections",
            input.optString("photoDirections", "matching").equals("all") ? "all" : "matching");
        clean.put("sleepEnabled", input.optBoolean("sleepEnabled", true));
        String sleepStart = input.optString("sleepStart", "00:00"),
            sleepEnd = input.optString("sleepEnd", "07:00");
        if (SleepRules.minutes(sleepStart) == SleepRules.minutes(sleepEnd))
          throw new IllegalArgumentException("Sleep times must differ");
        clean.put("sleepStart", sleepStart);
        clean.put("sleepEnd", sleepEnd);
        double sleepLux = input.optDouble("sleepLux", 10);
        if (!Double.isFinite(sleepLux) || sleepLux < 0 || sleepLux > 1000)
          throw new IllegalArgumentException("Invalid light threshold");
        clean.put("sleepLux", sleepLux);
        for (String key : Arrays.asList("clock", "date", "weather", "shuffle", "hour24"))
          clean.put(
              key,
              input.optBoolean(
                  key, !key.equals("weather") && !key.equals("shuffle") && !key.equals("hour24")));
        clean.put(
            "position",
            Arrays.asList("top", "bottom").contains(input.optString("position"))
                ? input.optString("position")
                : "bottom");
        clean.put("widgetSize", clamp(input.optInt("widgetSize", 100), 60, 140));
        clean.put(
            "city",
            input
                .optString("city", "")
                .substring(0, Math.min(70, input.optString("city", "").length())));
        clean.put(
            "units",
            input.optString("units", "celsius").equals("fahrenheit") ? "fahrenheit" : "celsius");
        clean.put("weatherLocation", WeatherClient.mode(input));
        // Blank coordinates are deliberately different from valid zero coordinates.
        for (String key : Arrays.asList("latitude", "longitude")) {
          String value = input.optString(key, "").trim();
          if (clean.optBoolean("weather") && WeatherClient.mode(clean).equals("manual")) {
            double d = Double.parseDouble(value);
            double limit = key.equals("latitude") ? 90 : 180;
            if (!Double.isFinite(d) || Math.abs(d) > limit)
              throw new Exception("Invalid coordinate");
          }
          clean.put(key, value);
        }
        JSONObject old = settings;
        boolean sleepChanged =
            old.optBoolean("sleepEnabled", true) != clean.optBoolean("sleepEnabled")
                || !old.optString("sleepStart", "00:00").equals(sleepStart)
                || !old.optString("sleepEnd", "07:00").equals(sleepEnd)
                || old.optDouble("sleepLux", 10) != sleepLux;
        settings = clean;
        getPreferences(0).edit().putString("settings", clean.toString()).apply();
        main.post(
            () -> {
              if (sleepChanged) {
                darkness.reset();
                wakeUntil = 0;
                previewUntil = 0;
              }
              applyOrientationMode();
              updateSleep();
              publish();
              MainActivity.this.refreshWeather();
            });
      } catch (Exception e) {
        main.post(
            () -> {
              if (!destroyed)
                frame.evaluateJavascript(
                    "window.StillApp && StillApp.settingsError(\"Check the sleep times, light"
                        + " threshold, and weather coordinates.\")",
                    null);
            });
      }
    }

    @JavascriptInterface
    public void browse(String kind) {
      main.post(() -> requestBrowse(kind));
    }

    @JavascriptInterface
    public void selectAlbum(String id) {
      worker.execute(
          () -> {
            try {
              chooseAlbum(albumStore.get(id), true);
            } catch (IOException e) {
              notice("Album unavailable. Choose another album.");
            }
          });
    }

    @JavascriptInterface
    public void createAlbum(String name) {
      worker.execute(
          () -> {
            try {
              chooseAlbum(albumStore.create(name), true);
            } catch (IOException e) {
              notice(e.getMessage());
            }
          });
    }

    @JavascriptInterface
    public void renameAlbum(String id, String name) {
      worker.execute(
          () -> {
            try {
              AlbumStore.Album a = albumStore.rename(id, name);
              if (selectedAlbum != null && selectedAlbum.id.equals(id)) selectedAlbum = a;
              publish();
            } catch (IOException e) {
              notice(e.getMessage());
            }
          });
    }

    @JavascriptInterface
    public void setAlbumCover(String id, String value) {
      worker.execute(
          () -> {
            try {
              JSONArray input = new JSONArray(value);
              List<String> photos = new ArrayList<>();
              for (int i = 0; i < input.length(); i++) photos.add(input.getString(i));
              albumStore.setCover(id, photos);
              publish();
            } catch (Exception e) {
              notice(e.getMessage() == null ? "Album cover could not be saved." : e.getMessage());
            }
          });
    }

    @JavascriptInterface
    public void deleteAlbum(String id) {
      if (importing.get()) {
        notice("Wait for the import to finish.");
        return;
      }
      worker.execute(
          () -> {
            try {
              albumStore.remove(id);
              getPreferences(0).edit().remove("report." + id).apply();
              if (selectedAlbum != null && selectedAlbum.id.equals(id)) {
                List<AlbumStore.Album> all = albumStore.list();
                chooseAlbum(all.isEmpty() ? null : all.get(0), false);
              } else publish();
            } catch (IOException e) {
              notice("Album could not be removed.");
            }
          });
    }

    @JavascriptInterface
    public void remove(String albumId, String id) {
      if (!id.matches("[a-f0-9]{64}\\.jpg")) return;
      worker.execute(
          () -> {
            try {
              AlbumStore.Album a = albumStore.get(albumId);
              new File(a.photos, id).delete();
              new File(a.photos, id + ".json").delete();
              new File(a.thumbs, id).delete();
              publish();
            } catch (IOException e) {
              notice("Photo could not be removed.");
            }
          });
    }

    @JavascriptInterface
    public void previewSleep() {
      main.post(
          () -> {
            previewUntil = SystemClock.elapsedRealtime() + 15000;
            updateSleep();
          });
    }

    @JavascriptInterface
    public void refreshWeather() {
      main.post(() -> startWeatherRefresh(true));
    }

    @JavascriptInterface
    public void startUploads() {
      main.post(() -> MainActivity.this.startUploads());
    }

    @JavascriptInterface
    public void stopUploads() {
      main.post(() -> MainActivity.this.stopUploads("Uploads are off."));
    }

    @JavascriptInterface
    public void exit() {
      main.post(() -> finish());
    }
  }

  private static int clamp(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  private String localAddress() {
    try {
      for (String name : Arrays.asList("wlan0", "eth0")) {
        NetworkInterface network = NetworkInterface.getByName(name);
        if (network == null || !network.isUp()) continue;
        for (InetAddress address : Collections.list(network.getInetAddresses()))
          if (address instanceof Inet4Address
              && !address.isLoopbackAddress()
              && address.isSiteLocalAddress()) return address.getHostAddress();
      }
    } catch (Exception ignored) {
    }
    return "";
  }

  private JSONObject uploadState() throws org.json.JSONException {
    UploadServer server = uploads;
    JSONObject value =
        new JSONObject()
            .put("running", server != null && server.running())
            .put("starting", uploadStarting)
            .put("message", uploadError);
    if (server != null && server.running())
      value.put("url", server.url).put("code", server.code).put("expires", server.expires);
    return value;
  }

  private void stopUploads(String message) {
    UploadServer server = uploads;
    uploads = null;
    if (server != null) server.close();
    ImportProgress p = importProgress;
    if (p != null && p.source.equals("Computer upload") && p.active() && !importing.get()) {
      p.finish(false, "Upload session ended");
      pushImportProgress();
    }
    main.removeCallbacks(batchIdleCheck);
    main.removeCallbacks(uploadExpiry);
    uploadError = message;
    publish();
  }

  private void startUploads() {
    if (!hasAlbum()) return;
    if (uploadStarting || uploads != null && uploads.running()) {
      publish();
      return;
    }
    String address = localAddress();
    if (address.isEmpty()) {
      uploadError = "Connect the Portal to Wi-Fi, then try again.";
      publish();
      return;
    }
    uploadStarting = true;
    uploadError = "Starting uploads…";
    publish();
    worker.execute(
        () -> {
          try {
            UploadServer server =
                new UploadServer(
                    address,
                    8080,
                    getCacheDir(),
                    MAX_PHOTO_BYTES,
                    new UploadServer.Handler() {
                      public byte[] asset(String name) throws IOException {
                        try (InputStream in = getAssets().open(name);
                            ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                          byte[] bytes = new byte[4096];
                          int count;
                          while ((count = in.read(bytes)) != -1) out.write(bytes, 0, count);
                          return out.toByteArray();
                        }
                      }

                      public String albums() throws Exception {
                        return new JSONObject().put("albums", albumState()).toString();
                      }

                      public String createAlbum(String name) throws Exception {
                        AlbumStore.Album a = albumStore.create(name);
                        publish();
                        return new JSONObject().put("id", a.id).put("name", a.name).toString();
                      }

                      private ImportProgress current;
                      private int currentIndex;
                      private boolean slotOwned, processing, endRequested;

                      private synchronized void releaseSlot() {
                        if (slotOwned) {
                          slotOwned = false;
                          importing.set(false);
                        }
                      }

                      public String progress() {
                        return progressState().toString();
                      }

                      public boolean startBatch(String album, String batch, int total)
                          throws Exception {
                        if (destroyed || importing.get()) return false;
                        AlbumStore.Album a = albumStore.get(album);
                        ImportProgress old = importProgress;
                        if (old != null
                            && old.active()
                            && old.source.equals("Computer upload")
                            && !old.id.equals(batch)) return false;
                        ImportProgress p =
                            new ImportProgress(batch, "Computer upload", a.id, a.name);
                        p.ready(total);
                        importProgress = p;
                        pushImportProgress();
                        scheduleBatchTimeout();
                        return true;
                      }

                      public void finishBatch(
                          String batch, int done, int a, int d, int s, boolean stopped) {
                        ImportProgress p = importProgress;
                        if (p != null && p.id.equals(batch) && !importing.get()) {
                          p.result(
                              done,
                              a,
                              d,
                              s,
                              !stopped && done == p.total(),
                              stopped ? "Upload stopped" : "Upload finished");
                          pushImportProgress();
                          main.removeCallbacks(batchIdleCheck);
                        }
                      }

                      public boolean beginUpload(
                          String album, String batch, int index, int total, long bytes)
                          throws Exception {
                        AlbumStore.Album a = albumStore.get(album);
                        if (destroyed || !importing.compareAndSet(false, true)) return false;
                        synchronized (this) {
                          slotOwned = true;
                          processing = false;
                          endRequested = false;
                        }
                        ImportProgress p = importProgress;
                        if (p == null
                            || !p.active()
                            || !p.id.equals(batch)
                            || !p.albumId.equals(album)
                            || p.total() != total) {
                          p = new ImportProgress(batch, "Computer upload", a.id, a.name);
                          p.ready(total);
                          importProgress = p;
                        }
                        current = p;
                        currentIndex = index;
                        p.file(index, bytes, "receiving");
                        pushImportProgress();
                        publish();
                        return true;
                      }

                      public void uploadBytes(long bytes) {
                        if (current != null) {
                          current.bytes(bytes);
                          pushImportProgress();
                        }
                      }

                      public void endUpload(boolean success) {
                        ImportProgress p = current;
                        synchronized (this) {
                          endRequested = true;
                          if (!processing) releaseSlot();
                        }
                        if (p != null) {
                          if (!success)
                            p.finish(false, "Upload interrupted; completed photos are kept");
                          else if (p.completed() == p.total()) p.finish(true, "Upload finished");
                          pushImportProgress();
                          if (p.active()) scheduleBatchTimeout();
                        }
                        publish();
                      }

                      public String importPhoto(File file, String name, String albumId)
                          throws Exception {
                        final AlbumStore.Album destination = albumStore.get(albumId);
                        final ImportProgress p = current;
                        final int index = currentIndex;
                        final DisplayState target = display;
                        final JSONObject config = settings;
                        synchronized (this) {
                          processing = true;
                        }
                        p.stage("processing");
                        pushImportProgress();
                        java.util.concurrent.Future<String> result =
                            worker.submit(
                                () -> {
                                  ImportStats stats = new ImportStats();
                                  stats.seen = 1;
                                  try {
                                    MainActivity.this.importPhoto(
                                        destination,
                                        name,
                                        file.length(),
                                        () -> new FileInputStream(file),
                                        "Computer upload",
                                        stats,
                                        target.width,
                                        target.height,
                                        config.optDouble("tolerance", 0),
                                        config.optBoolean("cropFourThree", true),
                                        p);
                                  } catch (Exception | OutOfMemoryError error) {
                                    stats.failed++;
                                    stats.detail(name + ": photo could not be read or copied");
                                  } finally {
                                    synchronized (this) {
                                      processing = false;
                                      if (endRequested) releaseSlot();
                                    }
                                  }
                                  p.completed(
                                      index,
                                      p.added() + stats.added,
                                      p.duplicate() + stats.duplicate,
                                      p.skipped() + stats.invalid + stats.failed + stats.mismatch);
                                  pushImportProgress();
                                  saveImportReport(destination, stats);
                                  publish();
                                  JSONObject response = stats.json();
                                  response.put(
                                      "message",
                                      stats.added > 0
                                          ? "Added to your Portal album"
                                          : stats.duplicate > 0
                                              ? "Already in your album"
                                              : "Photo could not be imported");
                                  return response.toString();
                                });
                        try {
                          return result.get(180, java.util.concurrent.TimeUnit.SECONDS);
                        } catch (Exception error) {
                          result.cancel(true);
                          throw error;
                        }
                      }
                    });
            main.post(
                () -> {
                  uploadStarting = false;
                  if (destroyed) {
                    server.close();
                    return;
                  }
                  uploads = server;
                  uploadError = "Uploads are on for 30 minutes.";
                  main.removeCallbacks(uploadExpiry);
                  main.postDelayed(uploadExpiry, 30 * 60 * 1000);
                  publish();
                });
          } catch (IOException error) {
            main.post(
                () -> {
                  uploadStarting = false;
                  uploadError = "Could not start uploads. Check Wi-Fi and try again.";
                  publish();
                });
          }
        });
  }

  private void showFrameDialog(AlertDialog.Builder builder) {
    AlertDialog dialog = builder.create();
    frameDialog = dialog;
    dialog.setOnDismissListener(
        d -> {
          if (frameDialog == dialog) frameDialog = null;
        });
    dialog.show();
  }

  private void requestBrowse(String kind) {
    if (!hasAlbum()) return;
    if (importing.get()) {
      notice("An import is already running.");
      return;
    }
    if (kind.equals("usb")
        && checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED) {
      showUsbDevices();
      return;
    }
    if (checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)
        != PackageManager.PERMISSION_GRANTED) {
      pendingBrowse = kind;
      requestPermissions(new String[] {Manifest.permission.READ_EXTERNAL_STORAGE}, 10);
      return;
    }
    showRoots(kind);
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
    super.onRequestPermissionsResult(requestCode, permissions, grants);
    if (requestCode == 10 && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED)
      showRoots(pendingBrowse == null ? "local" : pendingBrowse);
    else notice("Storage access was not granted. Computer imports remain available.");
  }

  private void showRoots(String kind) {
    List<File> roots = new ArrayList<>();
    List<String> labels = new ArrayList<>();
    File[] storage = new File("/storage").listFiles();
    if (storage != null)
      for (File f : storage) {
        if (!f.getName().equals("emulated") && !f.getName().equals("self") && f.isDirectory()) {
          roots.add(f);
          labels.add("USB · " + f.getName());
        }
      }
    if (kind.equals("usb") && roots.isEmpty()) {
      showUsbDevices();
      return;
    }
    if (!kind.equals("usb")) {
      for (String name : Arrays.asList("Pictures", "DCIM", "Download")) {
        File f = new File("/sdcard/" + name);
        if (f.isDirectory()) {
          roots.add(f);
          labels.add(name);
        }
      }
      File inbox = new File(getExternalFilesDir(null), "inbox");
      inbox.mkdirs();
      roots.add(inbox);
      labels.add("Computer import inbox");
    }
    showFrameDialog(
        new AlertDialog.Builder(this)
            .setTitle("Choose a photo folder")
            .setItems(
                labels.toArray(new String[0]),
                (d, n) ->
                    browseFolder(
                        roots.get(n),
                        roots.get(n),
                        kind.equals("usb") ? "USB drive" : "Local folder"))
            .setNegativeButton("Cancel", null));
  }

  private String usbStatus() {
    if (usbManager == null) return "USB detection starting…";
    List<String> names = new ArrayList<>();
    for (UsbDevice device : usbManager.getDeviceList().values())
      if (UsbMassStorage.storageInterface(device) != null) names.add(usbName(device));
    return names.isEmpty()
        ? "No USB drive connected · FAT32/exFAT direct access supported"
        : "USB detected: " + String.join(", ", names) + " · ready for direct access";
  }

  private String usbName(UsbDevice device) {
    String name = device.getProductName();
    return name == null || name.trim().isEmpty() ? "USB drive" : name.trim();
  }

  private void closeUsb() {
    UsbMassStorage session = activeUsb;
    activeUsb = null;
    usbDeviceName = null;
    if (session != null) session.close();
  }

  private void showUsbDevices() {
    if (usbOpening) {
      notice("Reading USB drive…");
      return;
    }
    closeUsb();
    List<UsbDevice> devices = new ArrayList<>();
    List<String> names = new ArrayList<>();
    for (UsbDevice device : usbManager.getDeviceList().values())
      if (UsbMassStorage.storageInterface(device) != null) {
        devices.add(device);
        names.add(usbName(device));
      }
    if (devices.isEmpty()) {
      showFrameDialog(
          new AlertDialog.Builder(this)
              .setTitle("Connect a USB drive")
              .setMessage(
                  "Plug the drive into the Portal’s USB-C port, directly or through a USB-C OTG"
                      + " adapter. Disconnect its computer USB cable first.\n\n"
                      + "Still reads FAT32/exFAT directly, even when Android does not mount the"
                      + " drive. NTFS, APFS and encrypted volumes are not supported.")
              .setPositiveButton("Try again", (d, w) -> showRoots("usb"))
              .setNegativeButton("Cancel", null));
    } else if (devices.size() == 1) requestUsb(devices.get(0));
    else
      showFrameDialog(
          new AlertDialog.Builder(this)
              .setTitle("Choose a USB drive")
              .setItems(names.toArray(new String[0]), (d, n) -> requestUsb(devices.get(n)))
              .setNegativeButton("Cancel", null));
  }

  private void requestUsb(UsbDevice device) {
    if (usbManager.hasPermission(device)) {
      openUsb(device);
      return;
    }
    pendingUsbName = device.getDeviceName();
    PendingIntent permission =
        PendingIntent.getBroadcast(
            this,
            0,
            new Intent(USB_PERMISSION).setPackage(getPackageName()),
            PendingIntent.FLAG_UPDATE_CURRENT);
    usbManager.requestPermission(device, permission);
  }

  private void openUsb(UsbDevice device) {
    if (usbOpening || destroyed) return;
    usbOpening = true;
    notice("Reading " + usbName(device) + "…");
    worker.execute(
        () -> {
          UsbMassStorage opened = null;
          try {
            opened = new UsbMassStorage(usbManager, device);
            List<UsbPhotoDisk.Volume> volumes = UsbPhotoDisk.open(opened);
            for (UsbPhotoDisk.Volume volume : volumes)
              volume.root.list(); // Validate root metadata and obtain the volume label.
            android.util.Log.i(
                "StillUsb",
                "Read-only USB opened: "
                    + volumes.size()
                    + " volume(s), "
                    + opened.size()
                    + " bytes");
            UsbMassStorage session = opened;
            main.post(
                () -> {
                  usbOpening = false;
                  if (destroyed) {
                    session.close();
                    return;
                  }
                  closeUsb();
                  activeUsb = session;
                  usbDeviceName = device.getDeviceName();
                  if (volumes.size() == 1)
                    browseUsb(volumes.get(0).root, new ArrayList<>(), session);
                  else {
                    String[] labels =
                        volumes.stream().map(v -> v.label + " · " + v.type).toArray(String[]::new);
                    showFrameDialog(
                        new AlertDialog.Builder(this)
                            .setTitle("Choose a USB volume")
                            .setItems(
                                labels,
                                (d, n) ->
                                    browseUsb(volumes.get(n).root, new ArrayList<>(), session))
                            .setNegativeButton("Cancel", (d, w) -> closeUsb())
                            .setOnCancelListener(d -> closeUsb()));
                  }
                });
          } catch (Exception | OutOfMemoryError error) {
            android.util.Log.w("StillUsb", "USB open failed", error);
            if (opened != null) opened.close();
            String message =
                error instanceof IOException
                    ? error.getMessage()
                    : "The USB drive could not be read.";
            main.post(
                () -> {
                  usbOpening = false;
                  if (!destroyed)
                    showFrameDialog(
                        new AlertDialog.Builder(this)
                            .setTitle("USB drive detected")
                            .setMessage(
                                message
                                    + "\n\n"
                                    + "The reader leaves the drive unchanged. Reconnect it, then"
                                    + " tap USB drive to retry.")
                            .setPositiveButton("OK", null));
                });
          }
        });
  }

  private void browseUsb(
      UsbPhotoDisk.Node dir, List<UsbPhotoDisk.Node> parents, UsbMassStorage session) {
    worker.execute(
        () -> {
          try {
            List<UsbPhotoDisk.Node> children = dir.list();
            List<UsbPhotoDisk.Node> folders = new ArrayList<>();
            int photos = 0;
            for (UsbPhotoDisk.Node child : children)
              if (!child.name.startsWith(".")) {
                if (child.directory) folders.add(child);
                else if (photoName(child.name)) photos++;
              }
            List<String> choices = new ArrayList<>();
            choices.add("Import this folder and subfolders");
            if (!parents.isEmpty()) choices.add("↑ Parent folder");
            for (UsbPhotoDisk.Node child : folders) choices.add("▸ " + child.name);
            int count = photos;
            main.post(
                () -> {
                  if (destroyed || activeUsb != session) return;
                  String title = dir == dir.volume.root ? dir.volume.label : dir.name;
                  showFrameDialog(
                      new AlertDialog.Builder(this)
                          .setTitle(title + " · " + dir.volume.type + " · " + count + " photos")
                          .setItems(
                              choices.toArray(new String[0]),
                              (d, n) -> {
                                if (n == 0) startUsbImport(dir, session);
                                else if (!parents.isEmpty() && n == 1) {
                                  List<UsbPhotoDisk.Node> up = new ArrayList<>(parents);
                                  UsbPhotoDisk.Node parent = up.remove(up.size() - 1);
                                  browseUsb(parent, up, session);
                                } else {
                                  List<UsbPhotoDisk.Node> down = new ArrayList<>(parents);
                                  down.add(dir);
                                  browseUsb(
                                      folders.get(n - (parents.isEmpty() ? 1 : 2)), down, session);
                                }
                              })
                          .setNegativeButton("Cancel", (d, w) -> closeUsb())
                          .setOnCancelListener(d -> closeUsb()));
                });
          } catch (Exception error) {
            android.util.Log.w("StillUsb", "USB directory read failed", error);
            main.post(
                () -> {
                  closeUsb();
                  notice("USB folder could not be read. Reconnect the drive and retry.");
                });
          }
        });
  }

  private static boolean photoName(String name) {
    return name.toLowerCase(Locale.ROOT).matches(".*\\.(jpg|jpeg|png|webp|heic|heif)$");
  }

  private void startUsbImport(UsbPhotoDisk.Node folder, UsbMassStorage session) {
    if (!hasAlbum()) return;
    final AlbumStore.Album destination = selectedAlbum;
    if (!importing.compareAndSet(false, true)) return;
    final ImportProgress p = beginProgress("USB drive", destination);
    publish();
    final DisplayState target = display;
    final JSONObject config = settings;
    worker.execute(
        () -> {
          ImportStats stats = new ImportStats();
          boolean complete = false;
          String message = "USB import finished";
          try {
            ImportScanner.Result<UsbPhotoDisk.Node> queue =
                ImportScanner.collect(
                    folder,
                    new ImportScanner.Tree<UsbPhotoDisk.Node>() {
                      public String key(UsbPhotoDisk.Node n) {
                        return Long.toString(n.cluster);
                      }

                      public String name(UsbPhotoDisk.Node n) {
                        return n.name;
                      }

                      public boolean directory(UsbPhotoDisk.Node n) {
                        return n.directory;
                      }

                      public List<UsbPhotoDisk.Node> children(UsbPhotoDisk.Node n)
                          throws IOException {
                        return n.list();
                      }

                      public void check() throws IOException {
                        if (activeUsb != session || Thread.currentThread().isInterrupted())
                          throw new IOException("USB disconnected");
                      }
                    },
                    count -> {
                      p.found(count);
                      pushImportProgress();
                    });
            stats.seen = queue.files.size();
            stats.failed = queue.errors;
            p.ready(stats.seen);
            pushImportProgress();
            if (queue.limited) stats.detail("Import limited to 5000 photos and 32 folder levels");
            int index = 0;
            for (UsbPhotoDisk.Node child : queue.files) {
              if (activeUsb != session || Thread.currentThread().isInterrupted())
                throw new IOException("USB disconnected");
              p.file(++index, child.length, "copying");
              pushImportProgress();
              try {
                importPhoto(
                    destination,
                    child.name,
                    child.length,
                    child::open,
                    "USB · " + folder.volume.label,
                    stats,
                    target.width,
                    target.height,
                    config.optDouble("tolerance", 0),
                    config.optBoolean("cropFourThree", true),
                    p);
              } catch (Exception | OutOfMemoryError error) {
                stats.failed++;
                stats.detail(child.name + ": could not copy/decode (space, format or USB read)");
              }
              p.completed(
                  index,
                  stats.added,
                  stats.duplicate,
                  stats.failed + stats.invalid + stats.mismatch);
              pushImportProgress();
            }
            complete = true;
            if (queue.limited) message = "Import finished · scan limit reached";
            else if (queue.errors > 0) message = "Import finished · some folders could not be read";
            else if (stats.seen == 0) message = "No photos found";
          } catch (Exception error) {
            stats.failed++;
            message = "USB import stopped; completed photos are kept";
            stats.detail(message);
          } finally {
            try {
              saveImportReport(destination, stats);
            } catch (Exception ignored) {
            }
            p.finish(complete, message);
            pushImportProgress();
            session.close();
            final String finished = message;
            main.post(
                () -> {
                  if (activeUsb == session) closeUsb();
                  importing.set(false);
                  publish();
                  notice(
                      finished
                          + " · "
                          + stats.added
                          + " added · "
                          + stats.duplicate
                          + " duplicates · "
                          + (stats.failed + stats.invalid + stats.mismatch)
                          + " skipped/unreadable");
                });
          }
        });
  }

  private void browseFolder(File dir, File base, String source) {
    File[] sub = dir.listFiles(File::isDirectory);
    List<File> dirs = new ArrayList<>();
    if (sub != null) for (File f : sub) if (!f.getName().startsWith(".")) dirs.add(f);
    dirs.sort(Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
    List<String> choices = new ArrayList<>();
    choices.add("Import this folder and subfolders");
    if (!dir.equals(base)) choices.add("↑ Parent folder");
    for (File f : dirs) choices.add("▸ " + f.getName());
    showFrameDialog(
        new AlertDialog.Builder(this)
            .setTitle(dir.getName())
            .setItems(
                choices.toArray(new String[0]),
                (d, n) -> {
                  if (n == 0) startImport(dir, source);
                  else if (!dir.equals(base) && n == 1)
                    browseFolder(dir.getParentFile(), base, source);
                  else browseFolder(dirs.get(n - (dir.equals(base) ? 1 : 2)), base, source);
                })
            .setNegativeButton("Cancel", null));
  }

  private static class ImportStats {
    int added, mismatch, invalid, duplicate, failed, seen;
    final JSONArray details = new JSONArray();

    void detail(String value) {
      if (details.length() < 12) details.put(value);
    }

    JSONObject json() throws Exception {
      JSONObject out = new JSONObject();
      out.put("added", added);
      out.put("mismatch", mismatch);
      out.put("invalid", invalid);
      out.put("duplicate", duplicate);
      out.put("failed", failed);
      out.put("seen", seen);
      out.put("details", details);
      return out;
    }
  }

  private void startImport(File folder, String source) {
    if (!hasAlbum()) return;
    final AlbumStore.Album destination = selectedAlbum;
    if (!importing.compareAndSet(false, true)) return;
    final ImportProgress p = beginProgress(source, destination);
    publish();
    final DisplayState target = display;
    final JSONObject config = settings;
    worker.execute(
        () -> {
          ImportStats stats = new ImportStats();
          boolean complete = false;
          String message = "Import finished";
          try {
            ImportScanner.Result<File> queue =
                ImportScanner.collect(
                    folder,
                    new ImportScanner.Tree<File>() {
                      public String key(File n) throws IOException {
                        return n.getCanonicalPath();
                      }

                      public String name(File n) {
                        return n.getName();
                      }

                      public boolean directory(File n) {
                        return n.isDirectory();
                      }

                      public List<File> children(File n) throws IOException {
                        File[] files = n.listFiles();
                        if (files == null) throw new IOException("Folder unavailable");
                        Arrays.sort(files, Comparator.comparing(File::getName));
                        return Arrays.asList(files);
                      }

                      public void check() throws IOException {
                        if (destroyed || Thread.currentThread().isInterrupted())
                          throw new IOException("Import stopped");
                      }
                    },
                    count -> {
                      p.found(count);
                      pushImportProgress();
                    });
            stats.seen = queue.files.size();
            stats.failed = queue.errors;
            p.ready(stats.seen);
            pushImportProgress();
            if (queue.limited) stats.detail("Import limited to 5000 photos and 32 folder levels");
            int index = 0;
            for (File file : queue.files) {
              if (destroyed || Thread.currentThread().isInterrupted())
                throw new IOException("Import stopped");
              p.file(++index, file.length(), "copying");
              pushImportProgress();
              try {
                importFile(
                    destination,
                    file,
                    source,
                    stats,
                    target.width,
                    target.height,
                    config.optDouble("tolerance", 0),
                    config.optBoolean("cropFourThree", true),
                    p);
              } catch (Exception | OutOfMemoryError error) {
                stats.failed++;
                stats.detail(file.getName() + ": could not copy/decode (space or format)");
              }
              p.completed(
                  index,
                  stats.added,
                  stats.duplicate,
                  stats.failed + stats.invalid + stats.mismatch);
              pushImportProgress();
            }
            complete = true;
            if (queue.limited) message = "Import finished · scan limit reached";
            else if (queue.errors > 0) message = "Import finished · some folders could not be read";
            else if (stats.seen == 0) message = "No photos found";
          } catch (Exception error) {
            stats.failed++;
            message = "Import stopped; completed photos are kept";
            stats.detail(message);
          } finally {
            try {
              saveImportReport(destination, stats);
            } catch (Exception ignored) {
            }
            p.finish(complete, message);
            pushImportProgress();
            importing.set(false);
            publish();
            notice(
                message
                    + " · "
                    + stats.added
                    + " added · "
                    + stats.duplicate
                    + " duplicates · "
                    + (stats.failed + stats.invalid + stats.mismatch)
                    + " skipped/unreadable");
          }
        });
  }

  private void importFile(
      AlbumStore.Album destination,
      File source,
      String label,
      ImportStats stats,
      int targetW,
      int targetH,
      double allowed,
      boolean crop,
      ImportProgress progress)
      throws Exception {
    try {
      importPhoto(
          destination,
          source.getName(),
          source.length(),
          () -> new FileInputStream(source),
          label,
          stats,
          targetW,
          targetH,
          allowed,
          crop,
          progress);
    } finally {
      File external = getExternalFilesDir(null);
      if (external != null) {
        String inbox = new File(external, "inbox").getCanonicalPath() + File.separator;
        if (source.getCanonicalPath().startsWith(inbox)) source.delete();
      }
    }
  }

  private interface PhotoInput {
    InputStream open() throws IOException;
  }

  private void importPhoto(
      AlbumStore.Album destination,
      String sourceName,
      long length,
      PhotoInput source,
      String label,
      ImportStats stats,
      int targetW,
      int targetH,
      double allowed,
      boolean crop,
      ImportProgress progress)
      throws Exception {
    File photos = destination.photos;
    if (length > MAX_PHOTO_BYTES || length <= 0) {
      stats.invalid++;
      stats.detail(sourceName + ": empty or larger than 100 MB");
      return;
    }
    if (getFilesDir().getUsableSpace() < length + 100L * 1024 * 1024)
      throw new IOException("Storage full");
    // Work from a private snapshot, so removal of a USB drive cannot leave a partial album image.
    File snapshot = File.createTempFile("incoming-", ".img", getCacheDir());
    Bitmap normalized = null;
    File pending = null;
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (InputStream in = source.open();
          OutputStream out = new FileOutputStream(snapshot)) {
        byte[] buf = new byte[65536];
        int n;
        long count = 0;
        while ((n = in.read(buf)) != -1) {
          count += n;
          if (Thread.currentThread().isInterrupted() || count > MAX_PHOTO_BYTES)
            throw new IOException("File too large or copy cancelled");
          digest.update(buf, 0, n);
          out.write(buf, 0, n);
          progress.bytes(count);
          pushImportProgress();
        }
        if (count != length) throw new IOException("Photo copy ended early or changed size");
      }
      progress.stage("processing");
      pushImportProgress();
      StringBuilder hash = new StringBuilder();
      for (byte b : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
      String name = hash + ".jpg";
      File dest = new File(photos, name);
      if (dest.exists()) {
        stats.duplicate++;
        return;
      }
      final int[] original = new int[2];
      final String[] format = new String[1];
      try {
        // ImageDecoder applies JPEG EXIF and HEIF container rotation/mirroring once.
        // ImageInfo dimensions include that orientation; validation happens before allocation.
        normalized =
            ImageDecoder.decodeBitmap(
                ImageDecoder.createSource(snapshot),
                (decoder, info, data) -> {
                  int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                  if (w < 1 || h < 1 || (long) w * h > 150000000L)
                    throw new RejectedPhoto(false, "invalid or oversized image");
                  if (!PhotoRules.canImport(w, h, targetW, targetH, allowed, crop))
                    throw new RejectedPhoto(true, w + " × " + h + " · invalid dimensions");
                  original[0] = w;
                  original[1] = h;
                  format[0] = info.getMimeType();
                  decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                  decoder.setTargetColorSpace(ColorSpace.get(ColorSpace.Named.SRGB));
                  PhotoSizing.Plan plan = PhotoSizing.fit(w, h, targetW, targetH);
                  decoder.setTargetSize(plan.decodeWidth, plan.decodeHeight);
                });
      } catch (RejectedPhoto e) {
        if (e.mismatch) stats.mismatch++;
        else stats.invalid++;
        stats.detail(sourceName + ": " + e.getMessage());
        return;
      } catch (ImageDecoder.DecodeException e) {
        stats.invalid++;
        stats.detail(sourceName + ": corrupt image or unsupported encoding");
        return;
      }
      PhotoSizing.Plan plan =
          PhotoSizing.fit(normalized.getWidth(), normalized.getHeight(), targetW, targetH);
      Bitmap framed = Bitmap.createBitmap(normalized, plan.x, plan.y, plan.width, plan.height);
      if (framed != normalized) {
        normalized.recycle();
        normalized = framed;
      }
      pending = new File(photos, name + ".pending");
      progress.stage("saving");
      pushImportProgress();
      writeJpeg(pending, normalized, 90);
      saveThumbnail(destination, name, normalized);
      JSONObject meta = new JSONObject();
      meta.put("name", sourceName);
      meta.put("source", label);
      meta.put("width", original[0]);
      meta.put("height", original[1]);
      meta.put("format", format[0]);
      try (OutputStream out = new FileOutputStream(new File(photos, name + ".json"))) {
        out.write(meta.toString().getBytes("UTF-8"));
      }
      if (!pending.renameTo(dest)) throw new IOException("Commit failed");
      stats.added++;
    } finally {
      snapshot.delete();
      if (pending != null) pending.delete();
      if (normalized != null) normalized.recycle();
    }
  }

  private static class RejectedPhoto extends RuntimeException {
    final boolean mismatch;

    RejectedPhoto(boolean mismatch, String reason) {
      super(reason);
      this.mismatch = mismatch;
    }
  }

  private void refreshWeather() {
    main.post(() -> startWeatherRefresh(false));
  }

  private void startWeatherRefresh(boolean force) {
    if (destroyed) return;
    final JSONObject config = settings;
    if (!config.optBoolean("weather", false)) {
      weatherStatus = new JSONObject();
      publish();
      return;
    }
    if (weatherInFlight) {
      weatherRefreshQueued |= force;
      return;
    }
    String requestKey = WeatherClient.key(config);
    long age = System.currentTimeMillis() - weather.optLong("updated");
    if (!force
        && requestKey.equals(weather.optString("requestKey"))
        && age >= 0
        && age < 15 * 60 * 1000) return;
    weatherInFlight = true;
    try {
      weatherStatus = new JSONObject().put("loading", true).put("requestKey", requestKey);
    } catch (Exception ignored) {
    }
    publish();
    final JSONObject cached = weatherLocation;
    weatherWorker.execute(
        () -> {
          WeatherClient.Result result = null;
          String error = "";
          try {
            result = WeatherClient.load(config, cached, WeatherClient::getJson);
          } catch (Exception e) {
            error = e.getMessage();
          }
          final WeatherClient.Result loaded = result;
          final String failure = error;
          main.post(
              () -> {
                if (destroyed) return;
                weatherInFlight = false;
                boolean current =
                    settings.optBoolean("weather", false)
                        && requestKey.equals(WeatherClient.key(settings));
                if (current) {
                  if (loaded != null) {
                    weather = loaded.weather;
                    weatherLocation = loaded.location;
                    getPreferences(0)
                        .edit()
                        .putString("weather", weather.toString())
                        .putString("weatherLocation", weatherLocation.toString())
                        .apply();
                  }
                  try {
                    weatherStatus =
                        new JSONObject()
                            .put("loading", false)
                            .put("requestKey", requestKey)
                            .put("error", loaded == null ? failure : loaded.warning);
                  } catch (Exception ignored) {
                  }
                  publish();
                }
                boolean queued = weatherRefreshQueued;
                weatherRefreshQueued = false;
                if (queued || !current) startWeatherRefresh(queued);
              });
        });
  }

  private static String readText(InputStream source, int limit) throws Exception {
    try (InputStream in = source;
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buf = new byte[4096];
      int n;
      while ((n = in.read(buf)) != -1) {
        if (out.size() + n > limit) throw new IOException("Response too large");
        out.write(buf, 0, n);
      }
      return out.toString("UTF-8");
    }
  }
}
