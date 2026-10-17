package com.portalframe;

/** Bound display copies, retaining uncropped detail for either frame direction. Never upscale. */
public final class PhotoSizing {
  public static final class Plan {
    public final int decodeWidth, decodeHeight, x, y, width, height;

    Plan(int dw, int dh, int w, int h) {
      decodeWidth = dw;
      decodeHeight = dh;
      width = w;
      height = h;
      x = (dw - w) / 2;
      y = (dh - h) / 2;
    }

    public boolean changes(int w, int h) {
      return width != w || height != h;
    }
  }

  public static Plan fit(int w, int h, int screenW, int screenH) {
    if (w <= 0 || h <= 0 || screenW <= 0 || screenH <= 0)
      throw new IllegalArgumentException("Invalid image or display dimensions");
    int longSide = Math.max(screenW, screenH), shortSide = Math.min(screenW, screenH);
    int targetW = w > h ? longSide : shortSide, targetH = w > h ? shortSide : longSide;
    if (!PhotoRules.shouldCrop(w, h, screenW, screenH)) {
      double scale = Math.min(1, (double) longSide / Math.max(w, h));
      int dw = Math.max(1, (int) Math.round(w * scale)),
          dh = Math.max(1, (int) Math.round(h * scale));
      return new Plan(dw, dh, dw, dh);
    }
    // Keep a rounded display copy stable on subsequent launches (within half a pixel).
    if (w <= targetW
        && h <= targetH
        && Math.abs((long) w * targetH - (long) h * targetW) <= longSide / 2)
      return new Plan(w, h, w, h);
    double scale = Math.min(1, Math.max((double) targetW / w, (double) targetH / h));
    int dw = Math.max(1, (int) Math.round(w * scale)),
        dh = Math.max(1, (int) Math.round(h * scale));
    int cw =
        Math.min(
            targetW, Math.min(dw, Math.max(1, (int) Math.round((double) dh * targetW / targetH))));
    int ch =
        Math.min(
            targetH, Math.min(dh, Math.max(1, (int) Math.round((double) dw * targetH / targetW))));
    return new Plan(dw, dh, cw, ch);
  }
}
