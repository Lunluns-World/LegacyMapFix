package org.eu.lunworld.legacymapfix;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.*;

class CanvasDecoderTest {

    /** Same shape as HuskSync's canvas NBT: root compound "data" with xCenter/zCenter, colors, locked. */
    private static byte[] mapNbt(byte[] colors, boolean locked) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(10); out.writeUTF("data");
        out.writeByte(3); out.writeUTF("zCenter"); out.writeInt(0);
        out.writeByte(1); out.writeUTF("unlimitedTracking"); out.writeByte(0);
        out.writeByte(7); out.writeUTF("colors"); out.writeInt(colors.length); out.write(colors);
        if (locked) { out.writeByte(1); out.writeUTF("locked"); out.writeByte(1); }
        out.writeByte(0);
        return bytes.toByteArray();
    }

    private static byte[] gzip(byte[] data) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            gz.write(data);
        }
        return bytes.toByteArray();
    }

    private static byte[] sampleColors() {
        byte[] c = new byte[CanvasDecoder.MAP_SIZE];
        for (int i = 0; i < c.length; i++) c[i] = (byte) ((i * 31 + 7) % 200);
        return c;
    }

    @Test
    void decodesCompleteGzip() throws IOException {
        byte[] colors = sampleColors();
        byte[] nbt = CanvasDecoder.gunzipLenient(gzip(mapNbt(colors, true)));
        assertArrayEquals(colors, CanvasDecoder.extractColors(nbt));
        assertTrue(CanvasDecoder.isLocked(nbt));
    }

    @Test
    void decodesGzipWithoutTrailer() throws IOException {
        // Old HuskSync never closed the stream: the 8-byte CRC/size trailer is missing.
        byte[] colors = sampleColors();
        byte[] full = gzip(mapNbt(colors, false));
        byte[] truncated = Arrays.copyOf(full, full.length - 8);
        byte[] nbt = CanvasDecoder.gunzipLenient(truncated);
        assertArrayEquals(colors, CanvasDecoder.extractColors(nbt));
        assertFalse(CanvasDecoder.isLocked(nbt));
    }

    @Test
    void transposeMatchesMapDataApiLayout() {
        // MapDataAPI.fromPixels flattens pixels[x][y] row by row => index x * 128 + y.
        int[][] pixels = new int[128][128];
        for (int x = 0; x < 128; x++) for (int y = 0; y < 128; y++) pixels[x][y] = (x * 3 + y * 5) % 120;
        byte[] stored = new byte[CanvasDecoder.MAP_SIZE];
        int i = 0;
        for (int[] row : pixels) for (int p : row) stored[i++] = (byte) p;

        byte[] vanilla = CanvasDecoder.transpose(stored);
        for (int x = 0; x < 128; x++) {
            for (int y = 0; y < 128; y++) {
                assertEquals((byte) pixels[x][y], vanilla[x + y * 128], "pixel " + x + "," + y);
            }
        }
        assertArrayEquals(stored, CanvasDecoder.transpose(vanilla), "transpose is its own inverse");
    }

    @Test
    void readsScale() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeByte(10); out.writeUTF("data");
        out.writeByte(1); out.writeUTF("scale"); out.writeByte(2);
        out.writeByte(0);
        assertEquals(2, CanvasDecoder.readScale(bytes.toByteArray()));
        assertEquals(0, CanvasDecoder.readScale(mapNbt(sampleColors(), true)), "missing scale defaults to 0");
    }

    @Test
    void blankDetection() {
        assertTrue(CanvasDecoder.isBlank(new byte[CanvasDecoder.MAP_SIZE]));
        assertFalse(CanvasDecoder.isBlank(sampleColors()));
    }
}
