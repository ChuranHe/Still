import com.portalframe.UploadServer;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class UploadServerTest {
  static int checks;

  static void check(boolean value) {
    checks++;
    if (!value) throw new AssertionError("Upload assertion " + checks);
  }

  static String request(
      UploadServer server, String method, String path, Map<String, String> supplied, byte[] body)
      throws Exception {
    if (path.startsWith("/upload?") && !path.contains("album="))
      path += "&album=11111111111111111111111111111111";
    URI target = new URI(server.url);
    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("Host", target.getAuthority());
    headers.putAll(supplied);
    try (Socket socket = new Socket("127.0.0.1", target.getPort())) {
      socket.setSoTimeout(10000);
      OutputStream out = socket.getOutputStream();
      StringBuilder head = new StringBuilder(method + " " + path + " HTTP/1.1\r\n");
      headers.forEach((k, v) -> head.append(k).append(": ").append(v).append("\r\n"));
      head.append("\r\n");
      out.write(head.toString().getBytes(StandardCharsets.US_ASCII));
      out.write(body);
      out.flush();
      socket.shutdownOutput();
      return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }
  }

  static Map<String, String> upload(UploadServer s, int size) {
    Map<String, String> h = new HashMap<>();
    h.put("X-Still-Code", s.code);
    h.put("Content-Length", Integer.toString(size));
    h.put("Content-Type", "application/octet-stream");
    return h;
  }

  static void status(String response, int expected) {
    check(response.startsWith("HTTP/1.1 " + expected + " "));
  }

  public static void main(String[] args) throws Exception {
    File cache = new File("build/upload-unit-cache");
    cache.mkdirs();
    AtomicInteger imported = new AtomicInteger();
    byte[] content = {1, 2, 3, 4, 5};
    UploadServer.Handler handler =
        new UploadServer.Handler() {
          public byte[] asset(String name) {
            return name.getBytes(StandardCharsets.UTF_8);
          }

          public String importPhoto(File file, String name, String album) throws Exception {
            check(name.equals("photo.JPG"));
            check(album.equals("11111111111111111111111111111111"));
            check(Arrays.equals(Files.readAllBytes(file.toPath()), content));
            imported.incrementAndGet();
            return "{\"added\":1}";
          }
        };
    UploadServer server = new UploadServer("127.0.0.1", 0, cache, 1024, handler);
    try {
      check(server.code.matches("[0-9]{8}"));
      check(server.running());
      check(server.expires > System.currentTimeMillis());
      String page = request(server, "GET", "/", Map.of(), new byte[0]);
      status(page, 200);
      check(page.endsWith("upload/index.html"));
      check(page.contains("frame-ancestors 'none'"));
      check(!page.contains("Access-Control-Allow-Origin"));
      status(request(server, "GET", "/upload.js", Map.of(), new byte[0]), 200);
      status(request(server, "GET", "/upload.css", Map.of(), new byte[0]), 200);
      status(request(server, "GET", "/../../data", Map.of(), new byte[0]), 404);
      status(request(server, "GET", "/", Map.of("Host", "attacker.example"), new byte[0]), 403);
      status(request(server, "OPTIONS", "/upload", Map.of(), new byte[0]), 405);
      Map<String, String> good = upload(server, content.length);
      status(request(server, "GET", "/albums", Map.of(), new byte[0]), 403);
      status(
          request(server, "GET", "/albums", Map.of("X-Still-Code", server.code), new byte[0]), 200);
      status(request(server, "POST", "/upload?name=photo.JPG&album=", good, content), 400);
      status(request(server, "POST", "/upload?name=photo.JPG", good, content), 200);
      check(imported.get() == 1);
      check(cache.list().length == 0);
      Map<String, String> wrong = new HashMap<>(good);
      wrong.put("X-Still-Code", "0000000");
      status(request(server, "POST", "/upload?name=photo.JPG", wrong, content), 403);
      check(imported.get() == 1);
      Map<String, String> cross = new HashMap<>(good);
      cross.put("Origin", "https://attacker.example");
      status(request(server, "POST", "/upload?name=photo.JPG", cross, content), 403);
      status(request(server, "POST", "/upload?name=../photo.JPG", good, content), 415);
      status(request(server, "POST", "/upload?name=photo.html", good, content), 415);
      status(request(server, "POST", "/upload?name=photo.JPG", upload(server, 1025), content), 413);
      status(
          request(server, "POST", "/upload?name=photo.JPG", upload(server, 0), new byte[0]), 411);
      Map<String, String> chunked = new HashMap<>(good);
      chunked.put("Transfer-Encoding", "chunked");
      status(request(server, "POST", "/upload?name=photo.JPG", chunked, content), 400);
      Map<String, String> multipart = new HashMap<>(good);
      multipart.put("Content-Type", "multipart/form-data");
      status(request(server, "POST", "/upload?name=photo.JPG", multipart, content), 415);
      status(request(server, "POST", "/upload?name=photo.JPG", upload(server, 10), content), 400);
      check(cache.list().length == 0);
      check(imported.get() == 1);
      status(request(server, "POST", "/upload?name=photo.JPG", good, content), 200);
      check(imported.get() == 2);
      for (int i = 0; i < 5; i++)
        status(request(server, "POST", "/upload?name=photo.JPG", wrong, content), 403);
      status(request(server, "POST", "/upload?name=photo.JPG", good, content), 429);
    } finally {
      server.close();
    }
    List<Long> received = Collections.synchronizedList(new ArrayList<>());
    AtomicInteger ended = new AtomicInteger(), started = new AtomicInteger();
    UploadServer progress =
        new UploadServer(
            "127.0.0.1",
            0,
            cache,
            1024,
            new UploadServer.Handler() {
              public byte[] asset(String n) {
                return new byte[0];
              }

              public String importPhoto(File f, String n, String album) {
                return "{}";
              }

              public String progress() {
                return "{\"active\":true}";
              }

              public boolean startBatch(String album, String batch, int total) {
                check(total == 2);
                started.incrementAndGet();
                return true;
              }

              public void finishBatch(
                  String batch, int done, int a, int d, int s, boolean stopped) {
                check(done == 2 && a == 1 && s == 1 && !stopped);
                ended.incrementAndGet();
              }

              public boolean beginUpload(String a, String b, int i, int count, long bytes) {
                check(i == 1 && count == 2 && bytes == 5);
                return true;
              }

              public void uploadBytes(long n) {
                received.add(n);
              }

              public void endUpload(boolean success) {
                ended.addAndGet(success ? 10 : 100);
              }
            });
    try {
      String batch = "22222222222222222222222222222222", album = "11111111111111111111111111111111";
      Map<String, String> auth = Map.of("X-Still-Code", progress.code);
      status(request(progress, "GET", "/progress", Map.of(), new byte[0]), 403);
      String snapshot = request(progress, "GET", "/progress", auth, new byte[0]);
      status(snapshot, 200);
      check(snapshot.endsWith("{\"active\":true}"));
      status(
          request(
              progress,
              "POST",
              "/batch?action=start&album=" + album + "&batch=" + batch + "&count=2",
              auth,
              new byte[0]),
          200);
      check(started.get() == 1);
      String path =
          "/upload?name=photo.JPG&album=" + album + "&batch=" + batch + "&index=1&count=2";
      status(request(progress, "POST", path, upload(progress, 5), content), 200);
      check(received.get(received.size() - 1) == 5L);
      check(ended.get() == 10);
      status(request(progress, "POST", path, upload(progress, 5), new byte[] {1}), 400);
      check(ended.get() == 110);
      status(
          request(
              progress,
              "POST",
              "/batch?action=finish&batch="
                  + batch
                  + "&count=2&done=2&added=1&duplicate=0&skipped=1&stopped=0",
              auth,
              new byte[0]),
          200);
      check(ended.get() == 111);
      for (String query :
          new String[] {
            "batch=bad&index=1&count=2",
            "batch=" + batch + "&index=3&count=2",
            "batch=" + batch + "&index=1&count=5001",
            "index=1"
          })
        status(
            request(
                progress,
                "POST",
                "/upload?name=photo.JPG&album=" + album + "&" + query,
                upload(progress, 5),
                content),
            400);
      status(
          request(
              progress,
              "POST",
              "/batch?action=finish&batch="
                  + batch
                  + "&count=2&done=1&added=1&duplicate=1&skipped=0&stopped=0",
              auth,
              new byte[0]),
          400);
      check(cache.list().length == 0);
    } finally {
      progress.close();
    }
    check(!server.running());
    check(cache.list().length == 0);
    System.out.println("PASS: " + checks + " upload-server assertions");
  }
}
