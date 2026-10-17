package com.portalframe;

import android.hardware.usb.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** USB Bulk-Only/SCSI transport. Only inquiry, status and READ commands are allowed. */
public final class UsbMassStorage implements UsbPhotoDisk.Source, Closeable {
  private final UsbDeviceConnection connection;
  private final UsbInterface intf;
  private final UsbEndpoint input, output;
  private volatile boolean closed;
  private int tag, blockSize;
  private long size;

  public static UsbInterface storageInterface(UsbDevice device) {
    for (int i = 0; i < device.getInterfaceCount(); i++) {
      UsbInterface value = device.getInterface(i);
      if (value.getInterfaceClass() == 8
          && value.getInterfaceSubclass() == 6
          && value.getInterfaceProtocol() == 80) return value;
    }
    return null;
  }

  public UsbMassStorage(UsbManager manager, UsbDevice device) throws IOException {
    intf = storageInterface(device);
    if (intf == null) throw new IOException("USB device does not support SCSI bulk-only storage");
    UsbEndpoint in = null, out = null;
    for (int i = 0; i < intf.getEndpointCount(); i++) {
      UsbEndpoint endpoint = intf.getEndpoint(i);
      if (endpoint.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
      if (endpoint.getDirection() == UsbConstants.USB_DIR_IN) in = endpoint;
      else out = endpoint;
    }
    if (in == null || out == null) throw new IOException("USB drive has no bulk storage endpoints");
    input = in;
    output = out;
    connection = manager.openDevice(device);
    if (connection == null)
      throw new IOException("USB access was not granted or the drive was disconnected");
    try {
      if (!connection.claimInterface(intf, true)) throw new IOException("USB drive is busy");
      command(new byte[] {0x12, 0, 0, 0, 36, 0}, 36);
      IOException notReady = null;
      for (int i = 0; i < 5; i++) {
        try {
          command(new byte[6], 0);
          notReady = null;
          break;
        } catch (IOException e) {
          notReady = e;
          try {
            command(new byte[] {3, 0, 0, 0, 18, 0}, 18);
          } catch (IOException ignored) {
          }
          try {
            Thread.sleep(200);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("USB read cancelled");
          }
        }
      }
      if (notReady != null)
        throw new IOException("USB drive is not ready. Reconnect it and retry.", notReady);
      byte[] capacity = command(new byte[] {0x25, 0, 0, 0, 0, 0, 0, 0, 0, 0}, 8);
      ByteBuffer buffer = ByteBuffer.wrap(capacity).order(ByteOrder.BIG_ENDIAN);
      long last = buffer.getInt() & 0xffffffffL;
      blockSize = buffer.getInt();
      if (last == 0xffffffffL
          || blockSize < 512
          || blockSize > 4096
          || (blockSize & (blockSize - 1)) != 0)
        throw new IOException("Unsupported USB capacity or sector size (maximum 2 TB)");
      size = (last + 1) * blockSize;
    } catch (IOException | RuntimeException e) {
      close();
      throw e;
    }
  }

  private byte[] command(byte[] cdb, int count) throws IOException {
    int opcode = cdb[0] & 255;
    if (opcode != 0 && opcode != 3 && opcode != 0x12 && opcode != 0x25 && opcode != 0x28)
      throw new IOException("Only read-only USB commands are permitted");
    if (closed || Thread.currentThread().isInterrupted())
      throw new IOException("USB drive disconnected or read cancelled");
    int currentTag = ++tag;
    byte[] wrapper = new byte[31];
    ByteBuffer b = ByteBuffer.wrap(wrapper).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(0x43425355)
        .putInt(currentTag)
        .putInt(count)
        .put((byte) 0x80)
        .put((byte) 0)
        .put((byte) cdb.length)
        .put(cdb);
    if (connection.bulkTransfer(output, wrapper, wrapper.length, 5000) != 31)
      throw new IOException("USB command failed. Reconnect the drive.");
    byte[] data = new byte[count];
    int got = 0;
    while (got < count) {
      int n = connection.bulkTransfer(input, data, got, Math.min(count - got, 16384), 5000);
      if (n <= 0) throw new IOException("USB read failed. Keep the drive connected.");
      got += n;
    }
    byte[] status = new byte[13];
    int n = connection.bulkTransfer(input, status, 13, 5000);
    if (n != 13) throw new IOException("USB response was incomplete");
    ByteBuffer response = ByteBuffer.wrap(status).order(ByteOrder.LITTLE_ENDIAN);
    if (response.getInt() != 0x53425355
        || response.getInt() != currentTag
        || response.getInt() != 0
        || status[12] != 0) throw new IOException("USB drive reported a read error");
    return data;
  }

  public long size() {
    return size;
  }

  public int blockSize() {
    return blockSize;
  }

  public synchronized void read(long offset, byte[] bytes, int start, int length)
      throws IOException {
    if (offset < 0
        || length < 0
        || offset > size - length
        || start < 0
        || start > bytes.length - length) throw new IOException("Read outside USB drive");
    while (length > 0) {
      long lba = offset / blockSize;
      int skip = (int) (offset % blockSize);
      int blocks =
          (int) Math.min(65536 / blockSize, ((long) length + skip + blockSize - 1) / blockSize);
      byte[] cdb = new byte[10];
      cdb[0] = 0x28;
      ByteBuffer command = ByteBuffer.wrap(cdb).order(ByteOrder.BIG_ENDIAN);
      command.position(2);
      command.putInt((int) lba);
      command.position(7);
      command.putShort((short) blocks);
      byte[] data = command(cdb, blocks * blockSize);
      int count = Math.min(length, data.length - skip);
      System.arraycopy(data, skip, bytes, start, count);
      offset += count;
      start += count;
      length -= count;
    }
  }

  public void close() {
    if (!closed) {
      closed = true;
      try {
        connection.releaseInterface(intf);
      } catch (RuntimeException ignored) {
      }
      connection.close();
    }
  }
}
