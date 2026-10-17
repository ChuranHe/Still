import com.portalframe.PhotoSizing;

public class PhotoSizingTest {
  static int checks;

  static void check(boolean value) {
    checks++;
    if (!value) throw new AssertionError("Sizing check " + checks);
  }

  static void size(int w, int h, int expectedW, int expectedH) {
    PhotoSizing.Plan p = PhotoSizing.fit(w, h, 1280, 800);
    check(p.width == expectedW && p.height == expectedH);
    check(
        p.x >= 0 && p.y >= 0 && p.x + p.width <= p.decodeWidth && p.y + p.height <= p.decodeHeight);
    check(p.decodeWidth <= w && p.decodeHeight <= h);
    PhotoSizing.Plan turned = PhotoSizing.fit(w, h, 800, 1280);
    check(p.width == turned.width && p.height == turned.height);
  }

  public static void main(String[] args) {
    size(6000, 4000, 1280, 800);
    size(4000, 6000, 800, 1280);
    size(4000, 3000, 1280, 800);
    size(3000, 4000, 800, 1280);
    size(8192, 5120, 1280, 800);
    size(5120, 8192, 800, 1280);
    size(1280, 800, 1280, 800);
    size(800, 1280, 800, 1280);
    size(600, 400, 600, 375);
    size(400, 600, 375, 600);
    size(4000, 4000, 1280, 1280);
    size(2000, 1600, 1280, 1024);
    size(1600, 2000, 1024, 1280);
    size(3600, 1200, 1280, 427);
    size(1200, 3600, 427, 1280);
    size(700, 700, 700, 700);
    check(!PhotoSizing.fit(1280, 1280, 800, 1280).changes(1280, 1280));
    check(!PhotoSizing.fit(1280, 1024, 1280, 800).changes(1280, 1024));
    size(2397, 3602, 800, 1280);
    size(4664, 6997, 800, 1280);
    check(!PhotoSizing.fit(1280, 800, 1280, 800).changes(1280, 800));
    for (int w = 13; w < 300; w += 13) {
      PhotoSizing.Plan p = PhotoSizing.fit(w, w * 4 / 3, 1280, 800);
      check(!PhotoSizing.fit(p.width, p.height, 1280, 800).changes(p.width, p.height));
    }
    for (int[] pair : new int[][] {{0, 800}, {1280, -1}}) {
      boolean failed = false;
      try {
        PhotoSizing.fit(pair[0], pair[1], 1280, 800);
      } catch (IllegalArgumentException e) {
        failed = true;
      }
      check(failed);
    }
    System.out.println("PASS: " + checks + " display-size assertions");
  }
}
