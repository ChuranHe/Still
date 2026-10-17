package com.portalframe;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;

/** Small, temporary LAN uploader. No filesystem paths or photo downloads are exposed. */
public final class UploadServer implements Closeable {
  public interface Handler {
    byte[] asset(String name) throws IOException;

    String importPhoto(File file, String name, String album) throws Exception;

    default String albums() throws Exception {
      return "{\"albums\":[]}";
    }

    default String createAlbum(String name) throws Exception {
      throw new IOException("Album creation unavailable");
    }

    default String progress() throws Exception {
      return "{}";
    }

    default boolean startBatch(String album, String batch, int total) throws Exception {
      return true;
    }

    default void finishBatch(
        String batch, int done, int added, int duplicate, int skipped, boolean stopped)
        throws Exception {}

    default boolean beginUpload(String album, String batch, int index, int total, long bytes)
        throws Exception {
      return true;
    }

    default void uploadBytes(long bytes) {}

    default void endUpload(boolean success) {}
  }

  public final String code, url;
  public final long expires;
  private final ServerSocket listener;
  private final Handler handler;
  private final File cache;
  private final long maximum;
  private final Set<Socket> connections = Collections.synchronizedSet(new HashSet<>());
  private final Semaphore upload = new Semaphore(1);
  private final Map<String, long[]> attempts =
      new LinkedHashMap<String, long[]>() {
        protected boolean removeEldestEntry(Map.Entry<String, long[]> e) {
          return size() > 64;
        }
      };
  private final ThreadPoolExecutor clients =
      new ThreadPoolExecutor(
          4,
          4,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(16),
          r -> {
            Thread t = new Thread(r, "Still upload");
            t.setDaemon(true);
            return t;
          });
  private volatile boolean closed;

  public UploadServer(String address, int port, File cache, long maximum, Handler handler)
      throws IOException {
    this.cache = cache;
    this.maximum = maximum;
    this.handler = handler;
    listener = new ServerSocket();
    listener.setReuseAddress(true);
    listener.bind(new InetSocketAddress(port), 16);
    code = String.format(Locale.ROOT, "%08d", new SecureRandom().nextInt(100000000));
    url = "http://" + address + ":" + listener.getLocalPort() + "/";
    expires = System.currentTimeMillis() + 30 * 60 * 1000;
    Thread acceptor =
        new Thread(
            () -> {
              while (!closed)
                try {
                  Socket socket = listener.accept();
                  connections.add(socket);
                  try {
                    clients.execute(() -> serve(socket));
                  } catch (RejectedExecutionException e) {
                    connections.remove(socket);
                    try {
                      socket.close();
                    } catch (IOException ignored) {
                    }
                  }
                } catch (IOException e) {
                  if (!closed) close();
                }
            },
            "Still upload listener");
    acceptor.setDaemon(true);
    acceptor.start();
  }

  public boolean running() {
    return !closed;
  }

  private static final class Failure extends Exception {
    final int status;

    Failure(int status, String message) {
      super(message);
      this.status = status;
    }
  }

  private static void require(boolean ok, int status, String message) throws Failure {
    if (!ok) throw new Failure(status, message);
  }

  private static String line(InputStream input, long deadline, int maximum) throws Exception {
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    int previous = -1;
    while (true) {
      require(System.currentTimeMillis() < deadline, 408, "Request timed out");
      int b = input.read();
      require(b >= 0, 400, "Incomplete request");
      if (b == 10) {
        require(previous == 13, 400, "Invalid HTTP line ending");
        byte[] value = result.toByteArray();
        return new String(value, 0, value.length - 1, StandardCharsets.US_ASCII);
      }
      result.write(b);
      require(result.size() <= maximum, 431, "Request headers too large");
      previous = b;
    }
  }

  private boolean validCode(String value, String peer) throws Failure {
    synchronized (attempts) {
      long now = System.currentTimeMillis();
      long[] failed = attempts.get(peer);
      require(
          failed == null || now >= failed[1], 429, "Too many incorrect codes. Wait 30 seconds.");
      boolean valid =
          value != null
              && value.length() == 8
              && MessageDigest.isEqual(
                  code.getBytes(StandardCharsets.US_ASCII),
                  value.getBytes(StandardCharsets.US_ASCII));
      if (valid) attempts.remove(peer);
      else {
        if (failed == null) failed = new long[2];
        failed[0]++;
        if (failed[0] >= 5) {
          failed[1] = now + 30000;
          failed[0] = 0;
        }
        attempts.put(peer, failed);
      }
      return valid;
    }
  }

  private void serve(Socket socket) {
    File staged = null;
    boolean acquired = false, begun = false;
    try {
      socket.setSoTimeout(10000);
      InputStream input = new BufferedInputStream(socket.getInputStream(), 16384);
      OutputStream output = socket.getOutputStream();
      long deadline = System.currentTimeMillis() + 10000;
      String[] request = line(input, deadline, 2048).split(" ", -1);
      require(
          request.length == 3 && (request[2].equals("HTTP/1.1") || request[2].equals("HTTP/1.0")),
          400,
          "Invalid HTTP request");
      Map<String, String> headers = new HashMap<>();
      int total = 0;
      while (true) {
        String value = line(input, deadline, 4096);
        total += value.length();
        require(total <= 16384, 431, "Request headers too large");
        if (value.isEmpty()) break;
        int colon = value.indexOf(':');
        require(colon > 0, 400, "Invalid header");
        String key = value.substring(0, colon).toLowerCase(Locale.ROOT);
        require(!headers.containsKey(key), 400, "Repeated header");
        headers.put(key, value.substring(colon + 1).trim());
      }
      URI target = new URI(request[1]);
      require(
          request[1].startsWith("/") && !target.isAbsolute() && target.getRawAuthority() == null,
          400,
          "Invalid request target");
      URI expected = new URI(url);
      String host = headers.get("host");
      boolean loopback = socket.getInetAddress().isLoopbackAddress();
      boolean allowed =
          (expected.getHost() + ":" + expected.getPort()).equals(host)
              || loopback && ("127.0.0.1:" + expected.getPort()).equals(host);
      require(allowed, 403, "Open the address displayed on your Portal");
      String origin = headers.get("origin");
      require(
          origin == null || origin.equals("http://" + host),
          403,
          "This page cannot upload to the Portal");
      String path = target.getPath();
      if (path.equals("/progress") || path.equals("/batch")) {
        require(
            validCode(headers.get("x-still-code"), socket.getInetAddress().getHostAddress()),
            403,
            "Enter the upload code shown on the Portal");
        if (path.equals("/progress")) {
          require(request[0].equals("GET"), 405, "Method not allowed");
          respond(
              output, 200, "application/json", handler.progress().getBytes(StandardCharsets.UTF_8));
          return;
        }
        require(request[0].equals("POST"), 405, "Method not allowed");
        String batch = query(target, "batch"), action = query(target, "action");
        require(AlbumStore.validId(batch), 400, "Invalid upload batch");
        int count = number(target, "count", 1, 5000);
        if ("start".equals(action)) {
          String album = query(target, "album");
          require(AlbumStore.validId(album), 400, "Choose or create an album first");
          require(handler.startBatch(album, batch, count), 409, "Another import is running");
        } else {
          require("finish".equals(action), 400, "Invalid batch action");
          int done = number(target, "done", 0, count),
              a = number(target, "added", 0, done),
              d = number(target, "duplicate", 0, done),
              s = number(target, "skipped", 0, done);
          require(a + d + s == done, 400, "Invalid batch totals");
          String stopped = query(target, "stopped");
          require("0".equals(stopped) || "1".equals(stopped), 400, "Invalid batch result");
          handler.finishBatch(batch, done, a, d, s, "1".equals(stopped));
        }
        respond(output, 200, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
        return;
      }
      if (path.equals("/albums")) {
        require(
            validCode(headers.get("x-still-code"), socket.getInetAddress().getHostAddress()),
            403,
            "Enter the upload code shown on the Portal");
        String response;
        if (request[0].equals("GET")) response = handler.albums();
        else {
          require(request[0].equals("POST"), 405, "Method not allowed");
          String name = query(target, "name");
          require(
              name != null
                  && !name.trim().isEmpty()
                  && name.length() <= 80
                  && !name.matches(".*[\\x00-\\x1f\\x7f].*"),
              400,
              "Enter an album name (1–80 characters)");
          response = handler.createAlbum(name);
        }
        respond(output, 200, "application/json", response.getBytes(StandardCharsets.UTF_8));
        return;
      }
      if (request[0].equals("GET")) {
        String asset =
            path.equals("/")
                ? "upload/index.html"
                : path.equals("/upload.js")
                    ? "upload/upload.js"
                    : path.equals("/upload.css")
                        ? "upload/upload.css"
                        : path.equals("/import-progress.js")
                            ? "import-progress.js"
                            : path.equals("/import-progress.css") ? "import-progress.css" : null;
        require(asset != null, 404, "Page not found");
        byte[] body = handler.asset(asset);
        respond(
            output,
            200,
            asset.endsWith(".js")
                ? "application/javascript"
                : asset.endsWith(".css") ? "text/css" : "text/html",
            body);
        return;
      }
      require(request[0].equals("POST") && path.equals("/upload"), 405, "Method not allowed");
      require(
          validCode(headers.get("x-still-code"), socket.getInetAddress().getHostAddress()),
          403,
          "Enter the upload code shown on the Portal");
      String album = query(target, "album");
      require(AlbumStore.validId(album), 400, "Choose or create an album first");
      require(!headers.containsKey("transfer-encoding"), 400, "Chunked uploads are not supported");
      long length;
      try {
        length = Long.parseLong(headers.getOrDefault("content-length", "-1"));
      } catch (NumberFormatException e) {
        length = -1;
      }
      require(length > 0, 411, "The file size is required");
      require(length <= maximum, 413, "The file exceeds the 100 MB limit");
      require(
          headers
              .getOrDefault("content-type", "")
              .split(";")[0]
              .trim()
              .equals("application/octet-stream"),
          415,
          "Choose an image using this upload page");
      String name = query(target, "name");
      require(
          name != null
              && name.length() <= 255
              && !name.matches(".*[\\x00-\\x1f/\\\\].*")
              && name.toLowerCase(Locale.ROOT).matches(".*\\.(jpg|jpeg|heic|heif|png|webp)$"),
          415,
          "Choose a JPEG, HEIF/HEIC, PNG or WebP photo");
      require(upload.tryAcquire(), 409, "Another upload is in progress. Try again shortly.");
      acquired = true;
      require(cache.getUsableSpace() >= length + 150L * 1024 * 1024, 507, "Portal storage is low");
      String batch = query(target, "batch");
      int index = 1, count = 1;
      if (batch == null) {
        require(
            query(target, "index") == null && query(target, "count") == null,
            400,
            "Invalid upload batch");
        batch = UUID.randomUUID().toString().replace("-", "");
      } else {
        require(AlbumStore.validId(batch), 400, "Invalid upload batch");
        count = number(target, "count", 1, 5000);
        index = number(target, "index", 1, count);
      }
      require(
          handler.beginUpload(album, batch, index, count, length),
          409,
          "Another import is running");
      begun = true;
      staged = File.createTempFile("web-upload-", ".part", cache);
      socket.setSoTimeout(60000);
      deadline = System.currentTimeMillis() + 300000;
      try (OutputStream file = new FileOutputStream(staged)) {
        byte[] buffer = new byte[65536];
        long remaining = length;
        while (remaining > 0) {
          require(!closed && System.currentTimeMillis() < deadline, 408, "Upload timed out");
          int n = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
          require(n > 0, 400, "Upload ended early");
          file.write(buffer, 0, n);
          remaining -= n;
          handler.uploadBytes(length - remaining);
        }
      }
      String json = handler.importPhoto(staged, name, album);
      begun = false;
      handler.endUpload(true);
      respond(output, 200, "application/json", json.getBytes(StandardCharsets.UTF_8));
    } catch (Failure error) {
      try {
        respond(
            socket.getOutputStream(),
            error.status,
            "text/plain",
            error.getMessage().getBytes(StandardCharsets.UTF_8));
      } catch (IOException ignored) {
      }
    } catch (Exception error) {
      try {
        respond(
            socket.getOutputStream(),
            500,
            "text/plain",
            "The upload could not finish. Retry this photo.".getBytes(StandardCharsets.UTF_8));
      } catch (IOException ignored) {
      }
    } finally {
      if (begun) handler.endUpload(false);
      if (staged != null) staged.delete();
      if (acquired) upload.release();
      connections.remove(socket);
      try {
        socket.close();
      } catch (IOException ignored) {
      }
    }
  }

  private static int number(URI target, String key, int min, int max) throws Exception {
    String value = query(target, key);
    int n = -1;
    try {
      if (value != null && value.matches("[0-9]{1,6}")) n = Integer.parseInt(value);
    } catch (NumberFormatException ignored) {
    }
    require(n >= min && n <= max, 400, "Invalid " + key);
    return n;
  }

  private static String query(URI uri, String key) throws Exception {
    String found = null;
    for (String part : (uri.getRawQuery() == null ? "" : uri.getRawQuery()).split("&")) {
      String[] pair = part.split("=", 2);
      if (pair.length == 2 && URLDecoder.decode(pair[0], "UTF-8").equals(key)) {
        require(found == null, 400, "Repeated query field");
        found = URLDecoder.decode(pair[1], "UTF-8");
      }
    }
    return found;
  }

  private static void respond(OutputStream out, int code, String type, byte[] body)
      throws IOException {
    String headers =
        "HTTP/1.1 "
            + code
            + " "
            + (code == 200 ? "OK" : "Error")
            + "\r\nContent-Type: "
            + type
            + "; charset=utf-8\r\nContent-Length: "
            + body.length
            + "\r\n"
            + "Connection: close\r\n"
            + "Cache-Control: no-store\r\n"
            + "X-Content-Type-Options: nosniff\r\n"
            + "Referrer-Policy: no-referrer\r\n"
            + "Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self';"
            + " img-src 'self' data: blob:; connect-src 'self'; frame-ancestors 'none'; base-uri"
            + " 'none'; form-action 'none'\r\n\r\n";
    out.write(headers.getBytes(StandardCharsets.US_ASCII));
    out.write(body);
    out.flush();
  }

  public void close() {
    if (closed) return;
    closed = true;
    try {
      listener.close();
    } catch (IOException ignored) {
    }
    synchronized (connections) {
      for (Socket socket : connections)
        try {
          socket.close();
        } catch (IOException ignored) {
        }
    }
    clients.shutdownNow();
  }
}
