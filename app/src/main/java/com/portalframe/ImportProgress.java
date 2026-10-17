package com.portalframe;

import java.util.*;

/** Thread-safe progress snapshots. A photo counts as complete only after its import returns. */
public final class ImportProgress {
  public final String id, source, albumId, albumName;
  private boolean active = true;
  private String stage = "scanning", message = "";
  private int discovered, total, completed, current, added, duplicate, skipped;
  private long bytes, totalBytes;

  public ImportProgress(String id, String source, String albumId, String albumName) {
    this.id = id;
    this.source = source;
    this.albumId = albumId;
    this.albumName = albumName;
  }

  public synchronized void found(int count) {
    if (active) discovered = Math.max(discovered, count);
  }

  public synchronized void ready(int count) {
    if (active) {
      total = Math.max(0, count);
      stage = "waiting";
    }
  }

  public synchronized void file(int index, long length, String phase) {
    if (!active) return;
    current = Math.max(1, Math.min(total, index));
    completed = Math.max(completed, current - 1);
    bytes = 0;
    totalBytes = Math.max(0, length);
    stage = phase;
  }

  public synchronized void bytes(long value) {
    if (active) bytes = Math.max(bytes, Math.min(totalBytes, Math.max(0, value)));
  }

  public synchronized void stage(String value) {
    if (active) stage = value;
  }

  public synchronized void completed(int index, int a, int d, int s) {
    if (!active) return;
    completed = Math.max(completed, Math.min(total, index));
    added = a;
    duplicate = d;
    skipped = s;
    stage = "waiting";
    bytes = totalBytes;
  }

  public synchronized void finish(boolean success, String text) {
    if (!active) return;
    active = false;
    stage = success ? "complete" : "stopped";
    message = text == null ? "" : text;
  }

  public synchronized void result(int done, int a, int d, int s, boolean success, String text) {
    completed = Math.min(total, Math.max(0, done));
    added = a;
    duplicate = d;
    skipped = s;
    active = false;
    stage = success ? "complete" : "stopped";
    message = text;
  }

  public synchronized boolean active() {
    return active;
  }

  public synchronized int total() {
    return total;
  }

  public synchronized int completed() {
    return completed;
  }

  public synchronized int added() {
    return added;
  }

  public synchronized int duplicate() {
    return duplicate;
  }

  public synchronized int skipped() {
    return skipped;
  }

  public synchronized Map<String, Object> snapshot() {
    Map<String, Object> s = new LinkedHashMap<>();
    int percent =
        total == 0
            ? (!active && stage.equals("complete") ? 100 : 0)
            : (int) (100L * completed / total);
    if (active) percent = Math.min(99, percent);
    s.put("id", id);
    s.put("source", source);
    s.put("albumId", albumId);
    s.put("albumName", albumName);
    s.put("active", active);
    s.put("stage", stage);
    s.put("message", message);
    s.put("discovered", discovered);
    s.put("total", total);
    s.put("completed", completed);
    s.put("current", current);
    s.put("bytes", bytes);
    s.put("totalBytes", totalBytes);
    s.put("percent", percent);
    s.put("added", added);
    s.put("duplicate", duplicate);
    s.put("skipped", skipped);
    return s;
  }
}
