package com.portalframe;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.CRC32;

/** Bounded, read-only FAT32/exFAT reader. Source deliberately has no write operation. */
public final class UsbPhotoDisk {
  public interface Source {
    long size();

    int blockSize();

    void read(long offset, byte[] bytes, int start, int length) throws IOException;
  }

  private static int u8(byte[] b, int p) {
    return b[p] & 255;
  }

  private static int u16(byte[] b, int p) {
    return u8(b, p) | u8(b, p + 1) << 8;
  }

  private static long u32(byte[] b, int p) {
    return (long) u16(b, p) | (long) u16(b, p + 2) << 16;
  }

  private static long u64(byte[] b, int p) throws IOException {
    long high = u32(b, p + 4);
    if (high > 0x7fffffffL) throw new IOException("Disk value exceeds supported range");
    return u32(b, p) | high << 32;
  }

  private static void require(boolean value, String error) throws IOException {
    if (!value) throw new IOException(error);
  }

  private static boolean power(int n) {
    return n > 0 && (n & (n - 1)) == 0;
  }

  private static byte[] bytes(Source source, long at, int count) throws IOException {
    require(at >= 0 && count >= 0 && at <= source.size() - count, "Read outside USB drive");
    byte[] value = new byte[count];
    source.read(at, value, 0, count);
    return value;
  }

  private static String ascii(byte[] value, int at, int count) {
    return new String(value, at, count, StandardCharsets.US_ASCII);
  }

  private static boolean boot(byte[] value) {
    return ascii(value, 3, 8).equals("EXFAT   ") || ascii(value, 82, 8).equals("FAT32   ");
  }

  private static boolean safeName(String name) {
    return !name.isEmpty()
        && !name.equals(".")
        && !name.equals("..")
        && !name.matches(".*[\\x00-\\x1f/\\\\].*");
  }

  public static List<Volume> open(Source source) throws IOException {
    require(
        source.size() >= 512
            && power(source.blockSize())
            && source.blockSize() >= 512
            && source.blockSize() <= 4096,
        "Unsupported USB sector size");
    byte[] mbr = bytes(source, 0, 512);
    require(u16(mbr, 510) == 0xaa55, "No valid partition table or filesystem on USB drive");
    List<long[]> partitions = new ArrayList<>();
    if (boot(mbr)) partitions.add(new long[] {0, source.size()});
    else {
      boolean gpt = false;
      for (int i = 0; i < 4; i++) if (u8(mbr, 446 + i * 16 + 4) == 0xee) gpt = true;
      if (gpt) {
        int block = source.blockSize();
        byte[] header = bytes(source, block, block);
        require(ascii(header, 0, 8).equals("EFI PART"), "Invalid GPT header");
        int headerLength = (int) u32(header, 12);
        require(headerLength >= 92 && headerLength <= block, "Invalid GPT header size");
        long checksum = u32(header, 16);
        Arrays.fill(header, 16, 20, (byte) 0);
        CRC32 crc = new CRC32();
        crc.update(header, 0, headerLength);
        require(crc.getValue() == checksum, "Damaged GPT header checksum");
        long count = u32(header, 80), entrySize = u32(header, 84), tableBlock = u64(header, 72);
        require(
            count > 0
                && count <= 1024
                && entrySize >= 128
                && entrySize <= 1024
                && entrySize % 8 == 0,
            "Unsupported GPT table size");
        require(tableBlock <= source.size() / block, "Invalid GPT partition table");
        byte[] table = bytes(source, tableBlock * block, (int) (count * entrySize));
        crc.reset();
        crc.update(table);
        require(crc.getValue() == u32(header, 88), "Damaged GPT partition checksum");
        for (int i = 0; i < count; i++) {
          int p = (int) (i * entrySize);
          boolean empty = true;
          for (int j = 0; j < 16; j++) empty &= table[p + j] == 0;
          if (empty) continue;
          long first = u64(table, p + 32), last = u64(table, p + 40);
          require(
              first >= 2 && first <= last && last < source.size() / block,
              "GPT partition outside drive");
          partitions.add(new long[] {first * block, (last - first + 1) * block});
        }
      } else {
        for (int i = 0; i < 4; i++) {
          int p = 446 + i * 16;
          if (u8(mbr, p + 4) == 0) continue;
          long start = u32(mbr, p + 8), length = u32(mbr, p + 12), block = source.blockSize();
          require(
              start > 0 && length > 0 && start <= source.size() / block - length,
              "MBR partition outside drive");
          partitions.add(new long[] {start * block, length * block});
        }
      }
    }
    List<Volume> result = new ArrayList<>();
    for (long[] part : partitions) {
      if (part[1] < 512) continue;
      byte[] first = bytes(source, part[0], 512);
      if (boot(first)) result.add(new Volume(source, part[0], part[1], first));
    }
    require(
        !result.isEmpty(),
        "No supported FAT32/exFAT volume. NTFS, APFS and encrypted drives are not supported.");
    return result;
  }

  public static final class Node {
    public final String name;
    public final boolean directory;
    public final long length, cluster, validLength;
    private final boolean contiguous;
    public final Volume volume;

    private Node(
        Volume volume,
        String name,
        boolean directory,
        long cluster,
        long length,
        long validLength,
        boolean contiguous) {
      this.volume = volume;
      this.name = name;
      this.directory = directory;
      this.cluster = cluster;
      this.length = length;
      this.validLength = validLength;
      this.contiguous = contiguous;
    }

    public List<Node> list() throws IOException {
      return volume.list(this);
    }

    public InputStream open() throws IOException {
      require(!directory, "Cannot import a directory as a photo");
      return new BufferedInputStream(volume.stream(this), 65536);
    }
  }

  public static final class Volume {
    public final String type;
    public String label;
    public final Node root;
    private final Source source;
    private final long base, length, clusterCount, fatStart, fatLength, heapStart, clusterBytes;
    private final int sector;
    private final Map<Long, byte[]> fatCache =
        new LinkedHashMap<Long, byte[]>(64, .75f, true) {
          protected boolean removeEldestEntry(Map.Entry<Long, byte[]> e) {
            return size() > 64;
          }
        };

    private Volume(Source source, long base, long length, byte[] b) throws IOException {
      this.source = source;
      this.base = base;
      this.length = length;
      require(u16(b, 510) == 0xaa55, "Damaged filesystem boot sector");
      long rootCluster, totalSectors, fatSectors, fatOffset, heapOffset, count, clusterSize;
      int fats, active;
      if (ascii(b, 3, 8).equals("EXFAT   ")) {
        type = "exFAT";
        int shift = u8(b, 108), clusterShift = u8(b, 109);
        require(
            shift >= 9 && shift <= 12 && clusterShift <= 25 - shift,
            "Invalid exFAT sector/cluster size");
        sector = 1 << shift;
        clusterSize = 1L << (shift + clusterShift);
        totalSectors = u64(b, 72);
        fatOffset = u32(b, 80);
        fatSectors = u32(b, 84);
        heapOffset = u32(b, 88);
        count = u32(b, 92);
        rootCluster = u32(b, 96);
        fats = u8(b, 110);
        active = u16(b, 106) & 1;
        require(
            u16(b, 104) == 0x100 && (fats == 1 || fats == 2) && active < fats,
            "Unsupported exFAT revision or FAT layout");
        require(
            fatOffset >= 24 && heapOffset >= fatOffset + fats * fatSectors,
            "Invalid exFAT data offsets");
        require(
            length >= sector * 12 && totalSectors <= length / sector,
            "exFAT boot region outside partition");
        // Verify all main boot-region checksum copies before trusting volume geometry.
        byte[] region = bytes(source, base, sector * 12);
        long sum = 0;
        for (int i = 0; i < sector * 11; i++)
          if (i != 106 && i != 107 && i != 112)
            sum = (((sum & 1) << 31) + (sum >>> 1) + u8(region, i)) & 0xffffffffL;
        for (int i = sector * 11; i < region.length; i += 4)
          require(u32(region, i) == sum, "Damaged exFAT boot checksum");
      } else {
        type = "FAT32";
        sector = u16(b, 11);
        int sectorsPerCluster = u8(b, 13);
        require(
            power(sector)
                && sector >= 512
                && sector <= 4096
                && power(sectorsPerCluster)
                && sectorsPerCluster <= 128,
            "Invalid FAT32 sector/cluster size");
        clusterSize = (long) sector * sectorsPerCluster;
        totalSectors = u32(b, 32);
        fatOffset = u16(b, 14);
        fatSectors = u32(b, 36);
        fats = u8(b, 16);
        int flags = u16(b, 40);
        active = (flags & 128) != 0 ? flags & 15 : 0;
        require(
            fatOffset > 0
                && fatSectors > 0
                && (fats == 1 || fats == 2)
                && active < fats
                && u16(b, 17) == 0
                && u16(b, 22) == 0
                && u16(b, 42) == 0,
            "Invalid FAT32 layout");
        heapOffset = fatOffset + fats * fatSectors;
        count = (totalSectors - heapOffset) / sectorsPerCluster;
        rootCluster = u32(b, 44);
        require(count >= 65525 && count < 0x0ffffff5L, "Unsupported FAT32 cluster count");
      }
      require(
          totalSectors > 0 && totalSectors <= length / sector && count > 0 && count <= 0xfffffff5L,
          "Filesystem extends outside partition");
      require(
          fatSectors > 0
              && (count + 2) * 4 <= fatSectors * sector
              && heapOffset <= totalSectors
              && count <= (totalSectors - heapOffset) * sector / clusterSize,
          "Invalid filesystem cluster heap");
      this.clusterCount = count;
      clusterBytes = clusterSize;
      fatStart = (fatOffset + active * fatSectors) * sector;
      fatLength = fatSectors * sector;
      heapStart = heapOffset * sector;
      validateCluster(rootCluster);
      label = type;
      root = new Node(this, type, true, rootCluster, -1, -1, false);
    }

    private void validateCluster(long cluster) throws IOException {
      require(cluster >= 2 && cluster < clusterCount + 2, "Invalid USB cluster chain");
    }

    private void read(long relative, byte[] value, int start, int count) throws IOException {
      require(relative >= 0 && relative <= length - count, "Read outside USB partition");
      source.read(base + relative, value, start, count);
    }

    private long next(long cluster) throws IOException {
      validateCluster(cluster);
      long offset = cluster * 4;
      require(offset <= fatLength - 4, "FAT entry outside table");
      long page = offset / sector;
      byte[] data = fatCache.get(page);
      if (data == null) {
        data = new byte[sector];
        read(fatStart + page * sector, data, 0, sector);
        fatCache.put(page, data);
      }
      long value = u32(data, (int) (offset % sector));
      if (type.equals("FAT32")) value &= 0x0fffffffL;
      if (type.equals("FAT32") ? value >= 0x0ffffff8L : value == 0xffffffffL) return -1;
      validateCluster(value);
      return value;
    }

    private InputStream stream(Node node) throws IOException {
      require(node.volume == this, "Wrong USB volume");
      if (node.length != 0) validateCluster(node.cluster);
      if (node.contiguous && node.length > 0)
        require(
            (node.length - 1) / clusterBytes <= clusterCount + 1 - node.cluster,
            "Contiguous USB file outside heap");
      return new InputStream() {
        long position = 0, cluster = node.cluster, within = 0;
        final Set<Long> visited = new HashSet<>();

        private boolean ready() throws IOException {
          if (node.length >= 0 && position >= node.length) return false;
          if (cluster < 0) {
            require(node.length < 0, "USB file cluster chain ended early");
            return false;
          }
          if (within == clusterBytes) {
            cluster = node.contiguous ? cluster + 1 : next(cluster);
            within = 0;
          }
          if (cluster < 0) {
            require(node.length < 0, "USB file cluster chain ended early");
            return false;
          }
          if (within == 0) {
            validateCluster(cluster);
            require(
                visited.size() < 131072 && visited.add(cluster),
                "Cyclic or oversized USB cluster chain");
          }
          return true;
        }

        public int read() throws IOException {
          byte[] one = new byte[1];
          return read(one, 0, 1) == -1 ? -1 : one[0] & 255;
        }

        public int read(byte[] b, int start, int count) throws IOException {
          if (start < 0 || count < 0 || start > b.length - count)
            throw new IndexOutOfBoundsException();
          if (count == 0) return 0;
          if (!ready()) return -1;
          int size = (int) Math.min(count, clusterBytes - within);
          if (node.length >= 0) size = (int) Math.min(size, node.length - position);
          int valid =
              node.validLength < 0
                  ? size
                  : (int) Math.max(0, Math.min(size, node.validLength - position));
          if (valid > 0)
            Volume.this.read(heapStart + (cluster - 2) * clusterBytes + within, b, start, valid);
          if (valid < size) Arrays.fill(b, start + valid, start + size, (byte) 0);
          within += size;
          position += size;
          return size;
        }
      };
    }

    private List<Node> list(Node dir) throws IOException {
      require(dir.directory, "USB entry is not a folder");
      List<Node> result = new ArrayList<>();
      try (InputStream input = new BufferedInputStream(stream(dir), 65536)) {
        byte[] entry = new byte[32];
        String[] lfn = new String[20];
        int expected = -1, checksum = -1;
        int records = 0;
        while (readEntry(input, entry)) {
          require(++records <= 262144, "USB directory is too large");
          int first = u8(entry, 0);
          if (first == 0) break;
          if (type.equals("exFAT")) {
            if (first == 0x83 && dir == root) {
              int chars = u8(entry, 1);
              if (chars <= 11) label = new String(entry, 2, chars * 2, StandardCharsets.UTF_16LE);
              continue;
            }
            if (first != 0x85) continue;
            int secondaries = u8(entry, 1);
            require(secondaries >= 2 && secondaries <= 32, "Invalid exFAT directory entry set");
            byte[] set = new byte[(secondaries + 1) * 32];
            System.arraycopy(entry, 0, set, 0, 32);
            for (int i = 1; i <= secondaries; i++) {
              require(readEntry(input, entry), "Truncated exFAT directory entry");
              records++;
              System.arraycopy(entry, 0, set, i * 32, 32);
            }
            int sum = 0;
            for (int i = 0; i < set.length; i++)
              if (i != 2 && i != 3) sum = (((sum & 1) << 15) + (sum >>> 1) + u8(set, i)) & 65535;
            require(sum == u16(set, 2), "Damaged exFAT directory checksum");
            require(
                u8(set, 32) == 0xc0 && (u8(set, 33) & 1) != 0, "Missing exFAT stream extension");
            int chars = u8(set, 35);
            require(chars > 0 && chars <= 255, "Invalid exFAT filename length");
            StringBuilder name = new StringBuilder();
            for (int i = 2; i <= secondaries; i++) {
              int p = i * 32;
              require((u8(set, p) & 0xc0) == 0xc0, "Invalid exFAT secondary entry");
              if (u8(set, p) == 0xc1)
                for (int j = 0; j < 15 && name.length() < chars; j++)
                  name.append((char) u16(set, p + 2 + j * 2));
            }
            require(name.length() == chars, "Truncated exFAT filename");
            long length = u64(set, 56), valid = u64(set, 40), cluster = u32(set, 52);
            boolean contiguous = (u8(set, 33) & 2) != 0;
            require(
                valid <= length && (length == 0 || cluster >= 2), "Invalid exFAT file allocation");
            if (safeName(name.toString()))
              result.add(
                  new Node(
                      this,
                      name.toString(),
                      (u16(set, 4) & 16) != 0,
                      cluster,
                      length,
                      valid,
                      contiguous));
          } else {
            if (first == 0xe5) {
              expected = -1;
              checksum = -1;
              Arrays.fill(lfn, null);
              continue;
            }
            int attr = u8(entry, 11);
            if (attr == 15) {
              int ordinal = first & 31;
              if ((first & 64) != 0) {
                Arrays.fill(lfn, null);
                expected = ordinal;
                checksum = u8(entry, 13);
              }
              if (ordinal < 1 || ordinal > 20 || ordinal != expected || checksum != u8(entry, 13)) {
                expected = -1;
                checksum = -1;
                Arrays.fill(lfn, null);
                continue;
              }
              StringBuilder part = new StringBuilder();
              for (int p : new int[] {1, 3, 5, 7, 9, 14, 16, 18, 20, 22, 24, 28, 30}) {
                int c = u16(entry, p);
                if (c == 0 || c == 65535) break;
                part.append((char) c);
              }
              lfn[ordinal - 1] = part.toString();
              expected--;
              continue;
            }
            if ((attr & 8) != 0) {
              expected = -1;
              checksum = -1;
              Arrays.fill(lfn, null);
              if (dir == root) label = ascii(entry, 0, 11).trim();
              continue;
            }
            int shortSum = 0;
            for (int i = 0; i < 11; i++)
              shortSum = (((shortSum & 1) << 7) + (shortSum >>> 1) + u8(entry, i)) & 255;
            String name;
            if (expected == 0 && checksum == shortSum && lfn[0] != null) {
              StringBuilder full = new StringBuilder();
              for (String part : lfn) {
                if (part == null) break;
                full.append(part);
              }
              name = full.toString();
            } else {
              name = ascii(entry, 0, 8).trim();
              String ext = ascii(entry, 8, 3).trim();
              if (!ext.isEmpty()) name += "." + ext;
            }
            expected = -1;
            checksum = -1;
            Arrays.fill(lfn, null);
            long cluster = ((long) u16(entry, 20) << 16) | u16(entry, 26), length = u32(entry, 28);
            boolean directory = (attr & 16) != 0;
            if (safeName(name))
              result.add(
                  new Node(
                      this,
                      name,
                      directory,
                      cluster,
                      directory ? -1 : length,
                      directory ? -1 : length,
                      false));
          }
          require(result.size() <= 10000, "USB folder contains more than 10000 entries");
        }
      }
      result.sort(Comparator.comparing(n -> n.name, String.CASE_INSENSITIVE_ORDER));
      return result;
    }

    private static boolean readEntry(InputStream input, byte[] entry) throws IOException {
      int position = 0;
      while (position < 32) {
        int count = input.read(entry, position, 32 - position);
        if (count < 0) {
          require(position == 0, "Truncated USB directory");
          return false;
        }
        position += count;
      }
      return true;
    }
  }
}
