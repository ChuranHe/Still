import com.portalframe.PhotoRules;

public class PhotoRulesTest {
  static int n;

  static void check(boolean v) {
    n++;
    if (!v) throw new AssertionError("Photo rule " + n);
  }

  public static void main(String[] args) {
    for (int[] p :
        new int[][] {
          {1, 1},
          {1200, 1200},
          {1500, 1200},
          {1200, 1500},
          {1600, 1200},
          {1800, 1200},
          {3600, 1200},
          {1200, 3600},
          {1777, 1000}
        }) check(PhotoRules.canImport(p[0], p[1], 1280, 800, 0, true));
    for (int[] p : new int[][] {{1200, 1200}, {1500, 1200}, {1200, 1500}}) {
      check(PhotoRules.isSquareish(p[0], p[1]));
      check(PhotoRules.accepts(p[0], p[1], 1280, 800, 0, true, true));
      check(PhotoRules.accepts(p[0], p[1], 800, 1280, 0, true, true));
      check(!PhotoRules.shouldCrop(p[0], p[1], 1280, 800));
    }
    for (int[] p : new int[][] {{1600, 1200}, {1800, 1200}, {1777, 1000}}) {
      check(PhotoRules.shouldCrop(p[0], p[1], 1280, 800));
      check(PhotoRules.accepts(p[0], p[1], 1280, 800, 0, true, true));
      check(!PhotoRules.accepts(p[0], p[1], 800, 1280, 0, true, true));
    }
    check(!PhotoRules.shouldCrop(3600, 1200, 1280, 800));
    check(!PhotoRules.shouldCrop(1200, 3600, 800, 1280));
    check(PhotoRules.shouldCrop(6400, 3000, 1280, 800));
    check(!PhotoRules.shouldCrop(6401, 3000, 1280, 800));
    check(!PhotoRules.isSquareish(3442, 4580));
    check(PhotoRules.accepts(1600, 1200, 800, 1280, 0, true, false));
    check(!PhotoRules.canImport(0, 1, 1280, 800, 0, true));
    System.out.println("PASS: " + n + " all-ratio, crop-threshold and orientation assertions");
  }
}
