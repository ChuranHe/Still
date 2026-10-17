import com.portalframe.ImportProgress;
import com.portalframe.ImportScanner;
import java.io.*;
import java.util.*;

public class ImportProgressTest {
  static int checks;

  static void check(boolean v) {
    checks++;
    if (!v) throw new AssertionError("Progress check " + checks);
  }

  static class Node {
    String name;
    boolean dir;
    List<Node> children = new ArrayList<>();
    boolean unreadable;

    Node(String n, boolean d) {
      name = n;
      dir = d;
    }

    Node add(Node n) {
      children.add(n);
      return this;
    }
  }

  static final ImportScanner.Tree<Node> tree =
      new ImportScanner.Tree<Node>() {
        public String key(Node n) {
          return Integer.toString(System.identityHashCode(n));
        }

        public String name(Node n) {
          return n.name;
        }

        public boolean directory(Node n) {
          return n.dir;
        }

        public List<Node> children(Node n) throws IOException {
          if (n.unreadable) throw new IOException("Disconnected folder");
          return n.children;
        }

        public void check() {}
      };

  public static void main(String[] args) throws Exception {
    ImportProgress p = new ImportProgress("batch", "USB drive", "album", "Wedding");
    check(p.snapshot().get("stage").equals("scanning"));
    p.found(2);
    p.found(1);
    check(p.snapshot().get("discovered").equals(2));
    p.ready(3);
    p.file(1, 1000, "copying");
    p.bytes(500);
    check(p.snapshot().get("bytes").equals(500L));
    check(p.snapshot().get("percent").equals(0));
    p.bytes(2000);
    check(p.snapshot().get("bytes").equals(1000L));
    p.bytes(1);
    check(p.snapshot().get("bytes").equals(1000L));
    p.stage("processing");
    check(p.snapshot().get("percent").equals(0));
    p.completed(1, 1, 0, 0);
    check(p.snapshot().get("percent").equals(33));
    p.file(2, 500, "copying");
    p.completed(2, 1, 1, 0);
    check(p.snapshot().get("percent").equals(66));
    p.file(3, 50, "copying");
    p.completed(3, 1, 1, 1);
    check(p.snapshot().get("percent").equals(99));
    p.finish(true, "Import finished");
    check(p.snapshot().get("percent").equals(100));
    check(!p.active());
    p.bytes(99999);
    check(p.snapshot().get("stage").equals("complete"));
    ImportProgress interrupted = new ImportProgress("b", "Computer upload", "a", "A");
    interrupted.ready(4);
    interrupted.file(1, 100, "receiving");
    interrupted.bytes(10);
    interrupted.finish(false, "Disconnected");
    check(interrupted.snapshot().get("percent").equals(0));
    check(interrupted.snapshot().get("stage").equals("stopped"));
    ImportProgress empty = new ImportProgress("e", "Local folder", "a", "A");
    empty.ready(0);
    empty.finish(true, "No photos found");
    check(empty.snapshot().get("percent").equals(100));
    p.result(3, 0, 0, 3, true, "Upload finished");
    check(p.snapshot().get("skipped").equals(3));
    check(p.snapshot().get("stage").equals("complete"));
    Node root = new Node("root", true), sub = new Node("sub", true);
    root.add(new Node("photo.JPG", false))
        .add(sub)
        .add(new Node(".hidden.jpg", false))
        .add(new Node("readme.txt", false));
    sub.add(new Node("portrait.HEIF", false)).add(new Node("square.png", false));
    List<Integer> discovered = new ArrayList<>();
    ImportScanner.Result<Node> r = ImportScanner.collect(root, tree, discovered::add);
    check(r.files.size() == 3);
    check(discovered.equals(Arrays.asList(1, 2, 3)));
    check(r.errors == 0 && !r.limited);
    sub.add(root);
    r = ImportScanner.collect(root, tree, n -> {});
    check(r.files.size() == 3 && r.errors == 1);
    Node bad = new Node("bad", true);
    bad.unreadable = true;
    root.add(bad);
    r = ImportScanner.collect(root, tree, n -> {});
    check(r.files.size() == 3 && r.errors == 2);
    Node many = new Node("root", true);
    for (int i = 0; i < 5001; i++) many.add(new Node(i + ".jpg", false));
    r = ImportScanner.collect(many, tree, n -> {});
    check(r.files.size() == 5000 && r.limited);
    Node deep = new Node("root", true), parent = deep;
    for (int i = 0; i < 34; i++) {
      Node next = new Node("sub", true);
      parent.add(next);
      parent = next;
    }
    parent.add(new Node("photo.jpg", false));
    r = ImportScanner.collect(deep, tree, n -> {});
    check(r.files.isEmpty() && r.limited);
    ImportScanner.Tree<Node> disconnected =
        new ImportScanner.Tree<Node>() {
          public String key(Node n) {
            return n.name;
          }

          public String name(Node n) {
            return n.name;
          }

          public boolean directory(Node n) {
            return n.dir;
          }

          public List<Node> children(Node n) {
            return n.children;
          }

          public void check() throws IOException {
            throw new IOException("USB disconnected");
          }
        };
    boolean failed = false;
    try {
      ImportScanner.collect(root, disconnected, n -> {});
    } catch (IOException e) {
      failed = true;
    }
    check(failed);
    System.out.println(
        "PASS: "
            + checks
            + " progress, interruption, reconciliation and bounded USB/local discovery checks");
  }
}
