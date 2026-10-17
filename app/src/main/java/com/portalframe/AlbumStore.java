package com.portalframe;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Private, independent album directories; source hashes deduplicate within each album. */
public final class AlbumStore {
  public static final String LEGACY_ID = "00000000000000000000000000000001";
  private final File files, root;
  private final Random coverRandom = new Random();

  public static final class Album {
    public final String id, name;
    public final File directory, photos, thumbs;

    Album(String id, String name, File directory) {
      this.id = id;
      this.name = name;
      this.directory = directory;
      photos = new File(directory, "photos");
      thumbs = new File(directory, "thumbs");
    }

    public File[] images() {
      File[] found = photos.listFiles((d, n) -> n.matches("[a-f0-9]{64}\\.jpg"));
      if (found == null) return new File[0];
      Arrays.sort(
          found,
          Comparator.comparingLong(File::lastModified).reversed().thenComparing(File::getName));
      return found;
    }

    public long bytes() {
      return size(directory);
    }
  }

  public AlbumStore(File files) throws IOException {
    this.files = files;
    root = new File(files, "albums");
    if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Album storage unavailable");
  }

  public static boolean validId(String id) {
    return id != null && id.matches("[a-f0-9]{32}");
  }

  public synchronized Album get(String id) throws IOException {
    if (!validId(id)) throw new IOException("Choose an album first");
    File dir = new File(root, id);
    Properties p = new Properties();
    try (Reader in =
        new InputStreamReader(
            new FileInputStream(new File(dir, "album.properties")), StandardCharsets.UTF_8)) {
      p.load(in);
    }
    return new Album(id, validateName(p.getProperty("name", "")), dir);
  }

  public synchronized List<Album> list() {
    List<Album> result = new ArrayList<>();
    File[] dirs = root.listFiles();
    if (dirs != null)
      for (File dir : dirs)
        try {
          if (dir.isDirectory() && validId(dir.getName())) result.add(get(dir.getName()));
        } catch (IOException ignored) {
        }
    result.sort(Comparator.comparing(a -> a.name.toLowerCase(Locale.ROOT)));
    return result;
  }

  public static String validateName(String value) throws IOException {
    String name = value == null ? "" : value.trim();
    if (name.isEmpty() || name.length() > 80 || name.matches(".*[\\x00-\\x1f\\x7f].*"))
      throw new IOException("Enter an album name (1–80 characters)");
    return name;
  }

  private Album save(String id, String name) throws IOException {
    File dir = new File(root, id);
    if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Could not create album");
    Properties p = new Properties();
    p.setProperty("name", name);
    File pending = new File(dir, "album.properties.pending"),
        target = new File(dir, "album.properties");
    try (FileOutputStream out = new FileOutputStream(pending)) {
      Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
      p.store(writer, null);
      writer.flush();
      out.getFD().sync();
    }
    if (!pending.renameTo(target)) throw new IOException("Could not save album");
    Album a = new Album(id, name, dir);
    if ((!a.photos.isDirectory() && !a.photos.mkdirs())
        || (!a.thumbs.isDirectory() && !a.thumbs.mkdirs()))
      throw new IOException("Could not prepare album");
    return a;
  }

  public synchronized Album create(String name) throws IOException {
    name = validateName(name);
    for (Album a : list()) if (a.name.equalsIgnoreCase(name)) return a;
    return save(UUID.randomUUID().toString().replace("-", ""), name);
  }

  public synchronized Album rename(String id, String name) throws IOException {
    get(id);
    name = validateName(name);
    for (Album a : list())
      if (!a.id.equals(id) && a.name.equalsIgnoreCase(name))
        throw new IOException("Another album already has this name");
    return save(id, name);
  }

  public synchronized List<String> coverPhotos(String id) throws IOException {
    Album a = get(id);
    List<String> result = new ArrayList<>();
    Properties p = new Properties();
    File saved = new File(a.directory, "cover.properties");
    if (saved.isFile())
      try (Reader in = new InputStreamReader(new FileInputStream(saved), StandardCharsets.UTF_8)) {
        p.load(in);
      }
    for (String photo : p.getProperty("photos", "").split(","))
      if (photo.matches("[a-f0-9]{64}\\.jpg")
          && new File(a.photos, photo).isFile()
          && !result.contains(photo)
          && result.size() < 4) result.add(photo);
    if (result.isEmpty()) {
      String automatic = p.getProperty("automatic", "");
      File[] images = a.images();
      if (p.getProperty("count", "").equals(Integer.toString(images.length))
          && automatic.matches("[a-f0-9]{64}\\.jpg")
          && new File(a.photos, automatic).isFile()) result.add(automatic);
      else if (images.length > 0) {
        automatic = images[coverRandom.nextInt(images.length)].getName();
        Properties fallback = new Properties();
        fallback.setProperty("automatic", automatic);
        fallback.setProperty("count", Integer.toString(images.length));
        saveCover(a, fallback);
        result.add(automatic);
      }
    }
    return result;
  }

  public synchronized void setCover(String id, List<String> photos) throws IOException {
    Album a = get(id);
    if (photos == null || photos.isEmpty() || photos.size() > 4)
      throw new IOException("Choose 1–4 photos for the cover");
    Set<String> unique = new HashSet<>();
    for (String photo : photos)
      if (photo == null
          || !photo.matches("[a-f0-9]{64}\\.jpg")
          || !new File(a.photos, photo).isFile()
          || !unique.add(photo)) throw new IOException("Choose existing photos from this album");
    Properties p = new Properties();
    p.setProperty("photos", String.join(",", photos));
    saveCover(a, p);
  }

  private void saveCover(Album a, Properties p) throws IOException {
    File pending = new File(a.directory, "cover.properties.pending");
    try (FileOutputStream out = new FileOutputStream(pending)) {
      Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
      p.store(writer, null);
      writer.flush();
      out.getFD().sync();
    }
    if (!pending.renameTo(new File(a.directory, "cover.properties")))
      throw new IOException("Could not save album cover");
  }

  public synchronized void remove(String id) throws IOException {
    Album a = get(id);
    delete(a.directory);
  }

  private static void delete(File file) throws IOException {
    File[] children = file.listFiles();
    if (children != null) for (File child : children) delete(child);
    if (file.exists() && !file.delete()) throw new IOException("Could not remove album");
  }

  public static long size(File file) {
    if (file.isFile()) return file.length();
    long result = 0;
    File[] children = file.listFiles();
    if (children != null) for (File child : children) result += size(child);
    return result;
  }

  private static byte[] digest(File file) throws Exception {
    MessageDigest d = MessageDigest.getInstance("SHA-256");
    try (InputStream in = new FileInputStream(file)) {
      byte[] b = new byte[65536];
      int n;
      while ((n = in.read(b)) != -1) d.update(b, 0, n);
    }
    return d.digest();
  }

  private static void moveContents(File from, File to) throws Exception {
    File[] contents = from.listFiles();
    if (contents == null) return;
    for (File file : contents) {
      if (!file.isFile()) throw new IOException("Unexpected legacy album folder");
      File dest = new File(to, file.getName());
      if (dest.exists()) {
        if (!Arrays.equals(digest(file), digest(dest)))
          throw new IOException("Conflicting legacy photo copies");
        if (!file.delete()) throw new IOException("Could not finish album move");
      } else if (!file.renameTo(dest)) throw new IOException("Could not move legacy photo");
    }
    from.delete();
  }

  public synchronized Album migrateLegacy(String name) throws Exception {
    File oldPhotos = new File(files, "photos"), oldThumbs = new File(files, "thumbs");
    File[] legacy = oldPhotos.listFiles();
    if ((legacy == null || legacy.length == 0) && !new File(root, LEGACY_ID).isDirectory())
      return null;
    Album album;
    try {
      album = get(LEGACY_ID);
    } catch (IOException e) {
      album = save(LEGACY_ID, validateName(name));
    }
    moveContents(oldPhotos, album.photos);
    moveContents(oldThumbs, album.thumbs);
    return album;
  }
}
