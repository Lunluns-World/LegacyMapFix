package org.eu.lunworld.legacymapfix;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Decodes the pixel data stored by old HuskSync versions and reads vanilla map files.
 * <p>
 * Old HuskSync wrote {@code canvas_data} as a gzip stream that is often missing its trailer
 * (the stream was never closed), so {@link java.util.zip.GZIPInputStream} rejects it.
 * This class inflates the raw deflate body directly and tolerates a missing trailer.
 */
public final class CanvasDecoder {

    public static final int MAP_SIZE = 128 * 128;
    private static final byte[] COLORS_TAG = {0x07, 0x00, 0x06, 'c', 'o', 'l', 'o', 'r', 's'};
    private static final byte[] LOCKED_TAG = {0x01, 0x00, 0x06, 'l', 'o', 'c', 'k', 'e', 'd'};
    private static final byte[] SCALE_TAG = {0x01, 0x00, 0x05, 's', 'c', 'a', 'l', 'e'};

    private CanvasDecoder() {
    }

    /** Inflate a gzip stream, tolerating a truncated stream / missing trailer. */
    public static byte[] gunzipLenient(byte[] gz) throws IOException {
        if (gz.length < 10 || (gz[0] & 0xFF) != 0x1F || (gz[1] & 0xFF) != 0x8B || gz[2] != 8) {
            throw new IOException("not a gzip stream");
        }
        int flags = gz[3] & 0xFF;
        int pos = 10;
        if ((flags & 0x04) != 0) { // FEXTRA
            int xlen = (gz[pos] & 0xFF) | ((gz[pos + 1] & 0xFF) << 8);
            pos += 2 + xlen;
        }
        if ((flags & 0x08) != 0) { // FNAME
            while (pos < gz.length && gz[pos] != 0) pos++;
            pos++;
        }
        if ((flags & 0x10) != 0) { // FCOMMENT
            while (pos < gz.length && gz[pos] != 0) pos++;
            pos++;
        }
        if ((flags & 0x02) != 0) { // FHCRC
            pos += 2;
        }
        if (pos >= gz.length) {
            throw new IOException("gzip header runs past end of data");
        }

        Inflater inflater = new Inflater(true);
        inflater.setInput(gz, pos, gz.length - pos);
        ByteArrayOutputStream out = new ByteArrayOutputStream(32 * 1024);
        byte[] buf = new byte[8192];
        try {
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n > 0) {
                    out.write(buf, 0, n);
                } else if (inflater.needsInput() || inflater.needsDictionary()) {
                    break; // truncated stream: keep what we have
                }
            }
        } catch (DataFormatException e) {
            if (out.size() == 0) throw new IOException("corrupt deflate data", e);
        } finally {
            inflater.end();
        }
        return out.toByteArray();
    }

    /** Find the 16384-byte {@code colors} byte array inside uncompressed map NBT. */
    public static byte[] extractColors(byte[] nbt) throws IOException {
        int i = indexOf(nbt, COLORS_TAG, 0);
        if (i < 0) throw new IOException("no colors tag");
        int lenPos = i + COLORS_TAG.length;
        if (lenPos + 4 > nbt.length) throw new IOException("colors tag truncated");
        int len = ByteBuffer.wrap(nbt, lenPos, 4).getInt();
        if (len != MAP_SIZE) throw new IOException("colors length " + len + " != " + MAP_SIZE);
        int start = lenPos + 4;
        if (start + len > nbt.length) throw new IOException("colors data truncated");
        byte[] colors = new byte[MAP_SIZE];
        System.arraycopy(nbt, start, colors, 0, MAP_SIZE);
        return colors;
    }

    /** The {@code scale} byte (0-4) of uncompressed map NBT; 0 if missing or out of range. */
    public static int readScale(byte[] nbt) {
        int i = indexOf(nbt, SCALE_TAG, 0);
        if (i < 0) return 0;
        int v = i + SCALE_TAG.length;
        if (v >= nbt.length) return 0;
        int scale = nbt[v];
        return scale >= 0 && scale <= 4 ? scale : 0;
    }

    /** Whether uncompressed vanilla map NBT has {@code locked:1b}. Missing tag means unlocked. */
    public static boolean isLocked(byte[] nbt) {
        int i = indexOf(nbt, LOCKED_TAG, 0);
        if (i < 0) return false;
        int v = i + LOCKED_TAG.length;
        return v < nbt.length && nbt[v] != 0;
    }

    /**
     * HuskSync's MapDataAPI stores pixels column-major ({@code colors[x * 128 + y]}),
     * vanilla stores them row-major ({@code colors[x + y * 128]}). This swaps between them.
     */
    public static byte[] transpose(byte[] colors) {
        byte[] out = new byte[MAP_SIZE];
        for (int x = 0; x < 128; x++) {
            for (int y = 0; y < 128; y++) {
                out[x + y * 128] = colors[x * 128 + y];
            }
        }
        return out;
    }

    public static boolean isBlank(byte[] colors) {
        for (byte b : colors) if (b != 0) return false;
        return true;
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Read a vanilla map_N.dat file. Returns null if it is empty or unreadable. */
    public static MapFile readMapFile(Path file) {
        try {
            byte[] raw;
            try (InputStream in = Files.newInputStream(file)) {
                raw = in.readAllBytes();
            }
            if (raw.length == 0) return null;
            byte[] nbt = gunzipLenient(raw);
            return new MapFile(extractColors(nbt), isLocked(nbt));
        } catch (IOException e) {
            return null;
        }
    }

    public record MapFile(byte[] colors, boolean locked) {
    }

    private static int indexOf(byte[] data, byte[] pattern, int from) {
        outer:
        for (int i = from; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
