package com.portalframe;

/** The same gate is used before copying and before every slideshow. */
public final class PhotoRules {
  public static boolean matches(int w, int h, int screenW, int screenH, double tolerance) {
    if (w <= 0
        || h <= 0
        || screenW <= 0
        || screenH <= 0
        || !Double.isFinite(tolerance)
        || tolerance < 0) return false;
    return Math.abs(((double) w / h) / ((double) screenW / screenH) - 1.0) <= tolerance + 1e-9;
  }

  public static boolean isFourThree(int w, int h) {
    // A small allowance accounts for one-pixel rounding in resized camera photos.
    return w != h && matches(Math.max(w, h), Math.min(w, h), 4, 3, 0.005);
  }

  public static boolean isCameraRatio(int w, int h) {
    return isFourThree(w, h) || w != h && matches(Math.max(w, h), Math.min(w, h), 3, 2, 0.005);
  }

  /** Preserve squarer photos; tolerate ordinary rounding of 4:3 camera dimensions. */
  public static boolean isSquareish(int w, int h) {
    return w > 0 && h > 0 && (double) Math.max(w, h) / Math.min(w, h) < (4.0 / 3) * (1 - .005);
  }

  public static boolean shouldCrop(int w, int h, int screenW, int screenH) {
    if (w <= 0 || h <= 0 || screenW <= 0 || screenH <= 0 || isSquareish(w, h)) return false;
    double photo = (double) Math.max(w, h) / Math.min(w, h),
        screen = (double) Math.max(screenW, screenH) / Math.min(screenW, screenH);
    return Math.min(photo, screen) / Math.max(photo, screen) >= .75 - 1e-9;
  }

  /** All aspect ratios are accepted; decoding and file limits are separate. */
  public static boolean canImport(
      int w, int h, int screenW, int screenH, double tolerance, boolean cropFourThree) {
    return w > 0 && h > 0 && screenW > 0 && screenH > 0;
  }

  /** Display only the orientation that fits the current frame. */
  public static boolean accepts(
      int w,
      int h,
      int screenW,
      int screenH,
      double tolerance,
      boolean cropFourThree,
      boolean sameDirectionOnly) {
    return (!sameDirectionOnly || isSquareish(w, h) || (w > h) == (screenW > screenH))
        && canImport(w, h, screenW, screenH, tolerance, cropFourThree);
  }
}
