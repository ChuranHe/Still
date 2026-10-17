package com.portalframe;

import java.io.IOException;
import java.util.*;

/** Discover a bounded import queue before copying, for USB and local folders alike. */
public final class ImportScanner {
  public interface Tree<T> {
    String key(T node) throws IOException;

    String name(T node);

    boolean directory(T node);

    List<T> children(T node) throws IOException;

    void check() throws IOException;
  }

  public interface Listener {
    void found(int count);
  }

  public static final class Result<T> {
    public final List<T> files = new ArrayList<>();
    public int errors;
    public boolean limited;
  }

  public static <T> Result<T> collect(T root, Tree<T> tree, Listener listener) throws IOException {
    Result<T> result = new Result<>();
    walk(root, tree, listener, result, new HashSet<>(), 0);
    return result;
  }

  private static <T> void walk(
      T node, Tree<T> tree, Listener listener, Result<T> result, Set<String> visited, int depth)
      throws IOException {
    tree.check();
    if (depth > 32 || result.files.size() >= 5000) {
      result.limited = true;
      return;
    }
    if (!visited.add(tree.key(node))) {
      result.errors++;
      return;
    }
    List<T> children;
    try {
      children = tree.children(node);
    } catch (IOException e) {
      tree.check();
      result.errors++;
      return;
    }
    for (T child : children) {
      tree.check();
      if (result.files.size() >= 5000) {
        result.limited = true;
        return;
      }
      String name = tree.name(child);
      if (name.startsWith(".")) continue;
      if (tree.directory(child)) walk(child, tree, listener, result, visited, depth + 1);
      else if (name.toLowerCase(Locale.ROOT).matches(".*\\.(jpg|jpeg|heic|heif|png|webp)$")) {
        result.files.add(child);
        listener.found(result.files.size());
      }
    }
  }
}
