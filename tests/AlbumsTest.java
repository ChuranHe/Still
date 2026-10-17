import com.portalframe.AlbumStore;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class AlbumsTest {
  static int checks;

  static void check(boolean v) {
    checks++;
    if (!v) throw new AssertionError("Album check " + checks);
  }

  public static void main(String[] args) throws Exception {
    File root = Files.createTempDirectory(new File("build").toPath(), "album-unit-").toFile();
    AlbumStore s = new AlbumStore(root);
    check(s.list().isEmpty());
    AlbumStore.Album a = s.create("  Wedding & 家庭  ");
    check(a.name.equals("Wedding & 家庭"));
    check(s.create("Wedding & 家庭").id.equals(a.id));
    AlbumStore.Album b = s.create("Travel");
    check(!a.id.equals(b.id));
    String photo = String.join("", Collections.nCopies(64, "a")) + ".jpg";
    Files.write(new File(a.photos, photo).toPath(), new byte[] {1, 2, 3});
    Files.write(new File(b.photos, photo).toPath(), new byte[] {1, 2, 3});
    check(a.images().length == 1 && b.images().length == 1);
    check(a.bytes() >= 3 && b.bytes() >= 3);
    List<String> choices = new ArrayList<>();
    choices.add(photo);
    for (int i = 1; i <= 4; i++) {
      String next = String.join("", Collections.nCopies(64, Integer.toString(i))) + ".jpg";
      Files.write(new File(a.photos, next).toPath(), new byte[] {(byte) i});
      choices.add(next);
    }
    check(s.coverPhotos(a.id).size() == 1);
    List<String> automatic = s.coverPhotos(a.id);
    check(choices.contains(automatic.get(0)));
    check(new AlbumStore(root).coverPhotos(a.id).equals(automatic));
    List<String> collage = Arrays.asList(choices.get(3), choices.get(1), choices.get(2));
    s.setCover(a.id, collage);
    check(s.coverPhotos(a.id).equals(collage));
    check(new AlbumStore(root).coverPhotos(a.id).equals(collage));
    s.rename(a.id, "New name");
    check(s.coverPhotos(a.id).equals(collage));
    for (List<String> invalid :
        Arrays.asList(
            Collections.<String>emptyList(),
            choices,
            Arrays.asList(photo, photo),
            Arrays.asList("../album.properties"),
            Arrays.asList(String.join("", Collections.nCopies(64, "f")) + ".jpg"))) {
      boolean deniedCover = false;
      try {
        s.setCover(a.id, invalid);
      } catch (IOException e) {
        deniedCover = true;
      }
      check(deniedCover);
      check(s.coverPhotos(a.id).equals(collage));
    }
    s.setCover(a.id, Collections.singletonList(photo));
    check(s.coverPhotos(a.id).equals(Collections.singletonList(photo)));
    Files.delete(new File(a.photos, photo).toPath());
    check(s.coverPhotos(a.id).size() == 1);
    check(!s.coverPhotos(a.id).contains(photo));
    Files.write(new File(a.photos, photo).toPath(), new byte[] {1, 2, 3});
    for (int i = 1; i < choices.size(); i++)
      Files.delete(new File(a.photos, choices.get(i)).toPath());
    s.remove(b.id);
    check(new File(a.photos, photo).exists());
    check(s.list().size() == 1);
    check(s.rename(a.id, "Renamed").name.equals("Renamed"));
    check(new AlbumStore(root).get(a.id).name.equals("Renamed"));
    for (String name : new String[] {"", "   ", "bad\nname"}) {
      boolean denied = false;
      try {
        s.create(name);
      } catch (IOException e) {
        denied = true;
      }
      check(denied);
    }
    boolean denied = false;
    try {
      s.get("../photos");
    } catch (IOException e) {
      denied = true;
    }
    check(denied);
    File legacy = new File(root, "photos");
    legacy.mkdirs();
    Files.write(new File(legacy, photo).toPath(), new byte[] {7, 8, 9});
    File thumb = new File(root, "thumbs");
    thumb.mkdirs();
    Files.write(new File(thumb, photo).toPath(), new byte[] {4});
    AlbumStore.Album old = s.migrateLegacy("Imported photos");
    check(old.name.equals("Imported photos"));
    check(
        Arrays.equals(
            Files.readAllBytes(new File(old.photos, photo).toPath()), new byte[] {7, 8, 9}));
    check(!new File(legacy, photo).exists());
    check(s.migrateLegacy("Ignored").id.equals(old.id));
    check(s.get(a.id).images().length == 1);
    legacy.mkdirs();
    Files.write(new File(legacy, photo).toPath(), new byte[] {7, 8, 9});
    s.migrateLegacy("Ignored");
    check(!new File(legacy, photo).exists());
    legacy.mkdirs();
    Files.write(new File(legacy, photo).toPath(), new byte[] {0});
    boolean conflict = false;
    try {
      s.migrateLegacy("Ignored");
    } catch (IOException e) {
      conflict = true;
    }
    check(conflict);
    check(new File(legacy, photo).exists());
    check(
        Arrays.equals(
            Files.readAllBytes(new File(old.photos, photo).toPath()), new byte[] {7, 8, 9}));
    s.remove(old.id);
    s.remove(a.id);
    check(s.list().isEmpty());
    System.out.println(
        "PASS: " + checks + " album persistence, migration, copy isolation and storage checks");
  }
}
