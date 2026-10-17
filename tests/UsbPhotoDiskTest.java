import com.portalframe.UsbPhotoDisk;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class UsbPhotoDiskTest {
  static int assertions;

  static void check(boolean value) {
    assertions++;
    if (!value) throw new AssertionError("Assertion " + assertions);
  }

  static class Disk implements UsbPhotoDisk.Source, Closeable {
    RandomAccessFile file;
    long size;
    boolean disconnected;
    long maximum;

    Disk(String name) throws Exception {
      file = new RandomAccessFile("build/usb-fixtures/" + name, "r");
      size = file.length();
    }

    public long size() {
      return size;
    }

    public int blockSize() {
      return 512;
    }

    public void read(long at, byte[] bytes, int start, int length) throws IOException {
      if (disconnected) throw new IOException("Removed");
      if (at < 0 || at > size - length) throw new AssertionError("Parser read beyond disk");
      maximum = Math.max(maximum, at + length);
      file.seek(at);
      file.readFully(bytes, start, length);
    }

    public void close() throws IOException {
      file.close();
    }
  }

  static UsbPhotoDisk.Node named(List<UsbPhotoDisk.Node> list, String name) {
    return list.stream()
        .filter(n -> n.name.equals(name))
        .findFirst()
        .orElseThrow(() -> new AssertionError("Missing " + name));
  }

  static byte[] read(UsbPhotoDisk.Node file) throws Exception {
    try (InputStream in = file.open()) {
      return in.readAllBytes();
    }
  }

  static void fails(Action action) throws Exception {
    boolean failed = false;
    try {
      action.run();
    } catch (IOException expected) {
      failed = true;
    }
    check(failed);
  }

  interface Action {
    void run() throws Exception;
  }

  static void compare(UsbPhotoDisk.Node file, String expected) throws Exception {
    check(
        Arrays.equals(
            read(file), Files.readAllBytes(Paths.get("build/usb-fixtures/expected/" + expected))));
  }

  public static void main(String[] args) throws Exception {
    try (Disk disk = new Disk("exfat-mbr.img")) {
      List<UsbPhotoDisk.Volume> volumes = UsbPhotoDisk.open(disk);
      check(volumes.size() == 1);
      UsbPhotoDisk.Volume v = volumes.get(0);
      check(v.type.equals("exFAT"));
      List<UsbPhotoDisk.Node> root = v.root.list();
      check(v.label.equals("USBTEST"));
      check(root.size() == 5);
      compare(named(root, "Landscape long name.jpg"), "exfat-fragmented.bin");
      UsbPhotoDisk.Node album = named(root, "Albums");
      check(album.directory);
      List<UsbPhotoDisk.Node> photos = album.list();
      check(photos.size() == 1);
      compare(named(photos, "Portrait 京都.HEIC"), "exfat-contiguous.bin");
      byte[] zero = read(named(root, "Zero tail.jpg"));
      check(zero.length == 1024);
      check(zero[99] == 'A');
      check(zero[100] == 0 && zero[1023] == 0);
      check(read(named(root, "Empty.jpg")).length == 0);
      queue(v.root, 5);
      fails(() -> read(named(root, "Cycle.jpg")));
      disk.disconnected = true;
      fails(() -> read(named(root, "Landscape long name.jpg")));
    }
    for (String name : new String[] {"fat32-mbr.img", "fat32-gpt.img"})
      try (Disk disk = new Disk(name)) {
        UsbPhotoDisk.Volume v = UsbPhotoDisk.open(disk).get(0);
        check(v.type.equals("FAT32"));
        List<UsbPhotoDisk.Node> root = v.root.list();
        check(root.size() == 3);
        compare(named(root, "旅行 Portrait.jpeg"), "fat32-lfn.bin");
        compare(named(named(root, "PHOTOS").list(), "OTHER.JPG"), "fat32-lfn.bin");
        check(read(named(root, "EMPTY.JPG")).length == 0);
        queue(v.root, 3);
        fails(() -> named(root, "旅行 Portrait.jpeg").list());
        fails(() -> named(root, "PHOTOS").open());
        check(disk.maximum <= disk.size);
      }
    // Fault injection wraps a read-only source; the fixture files remain unchanged.
    try (Disk disk = new Disk("exfat-mbr.img")) {
      UsbPhotoDisk.Source badBoot = mutate(disk, 2048L * 512 + 120, (byte) 1);
      fails(() -> UsbPhotoDisk.open(badBoot));
      UsbPhotoDisk.Source badMbr = mutate(disk, 454, (byte) 255);
      fails(() -> UsbPhotoDisk.open(badMbr));
      UsbPhotoDisk.Source badEntries = mutate(disk, (2048L + 64) * 512 + 32 + 2, (byte) 1);
      fails(() -> UsbPhotoDisk.open(badEntries).get(0).root.list());
      UsbPhotoDisk.Source badFat = mutate(disk, (2048L + 24) * 512 + 4 * 4, (byte) 8);
      fails(
          () ->
              read(named(UsbPhotoDisk.open(badFat).get(0).root.list(), "Landscape long name.jpg")));
      UsbPhotoDisk.Source truncated =
          new UsbPhotoDisk.Source() {
            public long size() {
              return 8192;
            }

            public int blockSize() {
              return 512;
            }

            public void read(long at, byte[] b, int s, int n) throws IOException {
              disk.read(at, b, s, n);
            }
          };
      fails(() -> UsbPhotoDisk.open(truncated));
    }
    try (Disk disk = new Disk("fat32-gpt.img")) {
      fails(() -> UsbPhotoDisk.open(mutate(disk, 512 + 16, (byte) 1)));
      fails(() -> UsbPhotoDisk.open(mutate(disk, 1024 + 4, (byte) 1)));
    }
    System.out.println("PASS: " + assertions + " read-only USB filesystem assertions");
  }

  static void queue(UsbPhotoDisk.Node root, int expected) throws Exception {
    List<Integer> found = new ArrayList<>();
    com.portalframe.ImportScanner.Result<UsbPhotoDisk.Node> r =
        com.portalframe.ImportScanner.collect(
            root,
            new com.portalframe.ImportScanner.Tree<UsbPhotoDisk.Node>() {
              public String key(UsbPhotoDisk.Node n) {
                return Long.toString(n.cluster);
              }

              public String name(UsbPhotoDisk.Node n) {
                return n.name;
              }

              public boolean directory(UsbPhotoDisk.Node n) {
                return n.directory;
              }

              public List<UsbPhotoDisk.Node> children(UsbPhotoDisk.Node n) throws IOException {
                return n.list();
              }

              public void check() {}
            },
            found::add);
    check(r.files.size() == expected);
    check(found.get(found.size() - 1) == expected);
    check(!r.limited && r.errors == 0);
  }

  static UsbPhotoDisk.Source mutate(Disk source, long offset, byte xor) {
    return new UsbPhotoDisk.Source() {
      public long size() {
        return source.size();
      }

      public int blockSize() {
        return 512;
      }

      public void read(long at, byte[] b, int start, int n) throws IOException {
        source.read(at, b, start, n);
        if (offset >= at && offset < at + n) b[start + (int) (offset - at)] ^= xor;
      }
    };
  }
}
