package com.portalframe;

/** Local-time schedule and debounced darkness detection; no Android dependencies. */
public final class SleepRules {
  public static int minutes(String time) {
    if (time == null || !time.matches("(?:[01][0-9]|2[0-3]):[0-5][0-9]"))
      throw new IllegalArgumentException("Invalid sleep time");
    return Integer.parseInt(time.substring(0, 2)) * 60 + Integer.parseInt(time.substring(3, 5));
  }

  public static boolean inWindow(int minute, int start, int end) {
    if (minute < 0
        || minute >= 1440
        || start < 0
        || start >= 1440
        || end < 0
        || end >= 1440
        || start == end) return false;
    return start < end ? minute >= start && minute < end : minute >= start || minute < end;
  }

  public static final class Darkness {
    public static final long ENTER_MS = 5000, EXIT_MS = 3000;
    private boolean active;
    private long pendingSince = -1;

    public boolean active() {
      return active;
    }

    public boolean pending() {
      return pendingSince >= 0;
    }

    public void reset() {
      active = false;
      pendingSince = -1;
    }

    public boolean update(long now, boolean eligible, float lux, double threshold) {
      if (!eligible || !Double.isFinite(threshold) || threshold < 0) {
        reset();
        return false;
      }
      // An on-change sensor need not emit repeated samples while illumination is constant.
      if (!Float.isFinite(lux) || lux < 0) {
        pendingSince = -1;
        return active;
      }
      boolean change = active ? lux >= threshold + Math.max(2, threshold * .5) : lux <= threshold;
      if (!change) {
        pendingSince = -1;
        return active;
      }
      if (pendingSince < 0 || now < pendingSince) pendingSince = now;
      if (now - pendingSince >= (active ? EXIT_MS : ENTER_MS)) {
        active = !active;
        pendingSince = -1;
      }
      return active;
    }
  }
}
