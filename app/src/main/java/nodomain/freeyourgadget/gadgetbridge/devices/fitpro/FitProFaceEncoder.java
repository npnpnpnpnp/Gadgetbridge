/* SPDX-License-Identifier: AGPL-3.0-or-later */
package nodomain.freeyourgadget.gadgetbridge.devices.fitpro;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.PriorityQueue;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Offline LJ736 format-1 encoder. Layout documented in tools/FITPRO_FACE_FORMAT.md. */
public final class FitProFaceEncoder {
    public static final int MAX_FRAMES = 80;
    public enum PaletteMode { CUBE_216, ADAPTIVE_255, DIRECT_RGB565 }
    private final FitProWatchfaceInfo info;
    private final List<byte[]> frames = new ArrayList<>();
    private final int delay;
    private final boolean clock;
    private final PaletteMode paletteMode;

    public FitProFaceEncoder(FitProWatchfaceInfo info, int delay, boolean clock) {
        this(info, delay, clock, PaletteMode.CUBE_216);
    }

    public FitProFaceEncoder(FitProWatchfaceInfo info, int delay, boolean clock, PaletteMode paletteMode) {
        requireSupported(info);
        if (delay < 1 || delay > 255) throw new IllegalArgumentException("Invalid animation interval");
        this.info = info;
        this.delay = delay;
        this.clock = clock;
        this.paletteMode = paletteMode;
    }

    public static void requireSupported(FitProWatchfaceInfo info) {
        if (info == null || info.algorithm != 3 || info.version != 1 || info.config != 0 ||
                info.screenType != 0 || info.grade != 0 || info.width != 240 || info.height != 286 ||
                !"LJ736".equals(info.mainModel) || !"K75".equals(info.matchModel) || !info.customer.isEmpty()) {
            throw new IllegalArgumentException("Offline conversion currently supports the LJ736/K75 240 × 286 display only. Read this watch's display details first.");
        }
    }

    public void addFrame(int[] argb) {
        if (frames.size() >= MAX_FRAMES) throw new IllegalArgumentException("Too many animation frames");
        if (argb.length != info.width * info.height) throw new IllegalArgumentException("Incorrect image dimensions");
        int[] pixels = argb.clone();
        if (clock) java.util.Arrays.fill(pixels, info.width * (info.height - 52), pixels.length, 0xff000000);
        frames.add(paletteMode == PaletteMode.DIRECT_RGB565 ? directRgb565Image(pixels) : indexedImage(pixels, paletteMode));
    }

    /** 216-colour RGB cube, RGB565 little-endian palette, followed by top-down indices. */
    static byte[] indexedImage(int[] pixels) {
        return indexedImage(pixels, PaletteMode.CUBE_216);
    }

    static byte[] indexedImage(int[] pixels, PaletteMode paletteMode) {
        if (paletteMode == PaletteMode.ADAPTIVE_255) return adaptiveIndexedImage(pixels);
        ByteArrayOutputStream out = new ByteArrayOutputStream(pixels.length + 434);
        le16(out, 216);
        for (int r = 0; r < 6; r++) for (int g = 0; g < 6; g++) for (int b = 0; b < 6; b++) {
            le16(out, rgb565(r * 51, g * 51, b * 51));
        }
        for (int pixel : pixels) {
            int alpha = pixel >>> 24;
            int r = (((pixel >>> 16) & 255) * alpha / 255 + 25) / 51;
            int g = (((pixel >>> 8) & 255) * alpha / 255 + 25) / 51;
            int b = ((pixel & 255) * alpha / 255 + 25) / 51;
            out.write(r * 36 + g * 6 + b);
        }
        return out.toByteArray();
    }

    /** Raw top-down RGB565 little-endian pixels for a 16-bit face record. */
    static byte[] directRgb565Image(int[] pixels) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(pixels.length * 2);
        for (int pixel : pixels) le16(out, compositeRgb565(pixel));
        return out.toByteArray();
    }

    /**
     * Chooses the 255 most-used native RGB565 colours, then maps remaining
     * colours to their nearest chosen neighbour. This preserves photographic
     * detail without changing the watch's indexed-image format.
     */
    private static byte[] adaptiveIndexedImage(int[] pixels) {
        int[] frequency = new int[65536];
        for (int pixel : pixels) frequency[compositeRgb565(pixel)]++;
        PriorityQueue<Integer> palette = new PriorityQueue<>(255, Comparator
                .comparingInt((Integer colour) -> frequency[colour])
                .thenComparingInt(Integer::intValue));
        for (int colour = 0; colour < frequency.length; colour++) {
            if (frequency[colour] == 0) continue;
            if (palette.size() < 255) palette.add(colour);
            else if (frequency[colour] > frequency[palette.peek()]) {
                palette.remove();
                palette.add(colour);
            }
        }
        List<Integer> colours = new ArrayList<>(palette);
        colours.sort(Comparator.comparingInt((Integer colour) -> frequency[colour]).reversed()
                .thenComparingInt(Integer::intValue));
        ByteArrayOutputStream out = new ByteArrayOutputStream(pixels.length + 2 + colours.size() * 2);
        le16(out, colours.size());
        HashMap<Integer, Integer> exact = new HashMap<>();
        for (int i = 0; i < colours.size(); i++) {
            le16(out, colours.get(i));
            exact.put(colours.get(i), i);
        }
        int[] closest = new int[65536];
        Arrays.fill(closest, -1);
        for (int pixel : pixels) {
            int source = compositeRgb565(pixel);
            int index = closest[source];
            if (index < 0) {
                Integer direct = exact.get(source);
                index = direct == null ? nearestRgb565(source, colours) : direct;
                closest[source] = index;
            }
            out.write(index);
        }
        return out.toByteArray();
    }

    private static int compositeRgb565(int pixel) {
        int alpha = pixel >>> 24;
        int red = ((pixel >>> 16) & 255) * alpha / 255;
        int green = ((pixel >>> 8) & 255) * alpha / 255;
        int blue = (pixel & 255) * alpha / 255;
        return rgb565(red, green, blue);
    }

    private static int nearestRgb565(int source, List<Integer> palette) {
        int red = source >>> 11, green = (source >>> 5) & 63, blue = source & 31;
        int closest = 0, best = Integer.MAX_VALUE;
        for (int i = 0; i < palette.size(); i++) {
            int candidate = palette.get(i);
            int dr = red - (candidate >>> 11);
            int dg = green - ((candidate >>> 5) & 63);
            int db = blue - (candidate & 31);
            int distance = dr * dr + 2 * dg * dg + db * db;
            if (distance < best) { best = distance; closest = i; }
        }
        return closest;
    }

    /** The server's custom background record; useful for independent format verification. */
    static byte[] bitmapRecord(int width, int height, int[] pixels) {
        if (width <= 0 || height <= 0 || (long) width * height != pixels.length) throw new IllegalArgumentException("Invalid dimensions");
        byte[] indexed = indexedImage(pixels);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write('B'); out.write('M'); le16(out, width); le16(out, height);
        le16(out, 216); le16(out, 10 + 216 * 2);
        out.write(indexed, 2, indexed.length - 2);
        return out.toByteArray();
    }

    private static final class Record {
        final int kind, bits, delay, x, y, width, height;
        final List<byte[]> images;
        Record(int kind, int bits, int delay, int x, int y, int width, int height, List<byte[]> images) {
            this.kind=kind; this.bits=bits; this.delay=delay; this.x=x; this.y=y; this.width=width; this.height=height; this.images=images;
        }
        int size() { return 10 + ((kind == 0 || kind == 1) ? 1 : 0) + 4 * images.size(); }
    }

    public byte[] build() {
        if (frames.isEmpty()) throw new IllegalStateException("No image selected");
        List<Record> records = new ArrayList<>();
        boolean animated = frames.size() > 1;
        int backgroundBits = paletteMode == PaletteMode.DIRECT_RGB565 ? 16 : 8;
        records.add(new Record(animated ? 1 : 0, backgroundBits, animated ? delay : 0, 0, 0, info.width, info.height, frames));
        if (clock) {
            int x = (info.width - 110) / 2, y = info.height - 46;
            records.add(new Record(1, 8, 0, x + 50, y, 10, 40, java.util.Collections.singletonList(colon())));
            List<byte[]> digits = new ArrayList<>();
            for (int i = 0; i < 10; i++) digits.add(digit(i));
            records.add(new Record(5, 8, 0, x, y, 24, 40, digits));
            records.add(new Record(6, 8, 0, x + 62, y, 24, 40, digits));
        }
        int groups = 0, previous = -1, headerSize = 6;
        for (Record r : records) {
            if (r.kind != previous) { groups++; headerSize += (r.kind <= 1 ? 2 : 1); previous = r.kind; }
            headerSize += r.size();
        }
        ByteArrayOutputStream header = new ByteArrayOutputStream();
        header.write(0xaa); header.write(0x55); header.write(1); header.write(0); header.write(0); header.write(groups);
        ByteArrayOutputStream images = new ByteArrayOutputStream();
        previous = -1;
        for (Record r : records) {
            if (r.kind != previous) {
                header.write(r.kind);
                if (r.kind <= 1) {
                    int count = 0;
                    for (Record other : records) if (other.kind == r.kind) count++;
                    header.write(count);
                }
                previous = r.kind;
            }
            header.write(r.bits);
            if (r.kind <= 1) header.write(r.delay);
            le16(header, r.x); le16(header, r.y); le16(header, r.width); le16(header, r.height);
            header.write(r.images.size());
            for (byte[] image : r.images) {
                le32(header, headerSize + images.size());
                images.write(image, 0, image.length);
                if (headerSize + images.size() > FitProWatchfaceFile.MAX_SIZE) throw new IllegalArgumentException("Face exceeds the upload size limit");
            }
        }
        if (header.size() != headerSize) throw new IllegalStateException("Invalid face header");
        byte[] body = images.toByteArray(); header.write(body, 0, body.length);
        return header.toByteArray();
    }

    public void writeZip(OutputStream output, String name) throws IOException {
        byte[] binary = build();
        Properties p = new Properties();
        p.setProperty("format", "fitpro-watchface-1"); p.setProperty("name", name);
        p.setProperty("width", Integer.toString(info.width)); p.setProperty("height", Integer.toString(info.height));
        p.setProperty("mainModel", info.mainModel); p.setProperty("matchModel", info.matchModel);
        p.setProperty("algorithm", "3"); p.setProperty("config", "0"); p.setProperty("version", "1");
        p.setProperty("screenType", "0"); p.setProperty("grade", "0"); p.setProperty("customer", info.customer);
        p.setProperty("slot", "1"); p.setProperty("position", "0"); p.setProperty("custom", "0");
        try {
            StringBuilder hash = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(binary)) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
            p.setProperty("sha256", hash.toString());
        } catch (NoSuchAlgorithmException e) { throw new IOException(e); }
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("watchface.properties"));
            OutputStreamWriter writer = new OutputStreamWriter(zip, StandardCharsets.UTF_8);
            p.store(writer, "Offline FitPro face"); writer.flush(); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("watchface.bin")); zip.write(binary); zip.closeEntry();
        }
    }

    private static int rgb565(int r, int g, int b) { return (r >> 3) << 11 | (g >> 2) << 5 | (b >> 3); }
    private static void le16(ByteArrayOutputStream out, int n) { out.write(n); out.write(n >>> 8); }
    private static void le32(ByteArrayOutputStream out, int n) { le16(out, n); le16(out, n >>> 16); }

    // Original seven-segment glyphs; no vendor fonts or graphics are bundled.
    private static byte[] digit(int digit) {
        int[] masks = {0x3f,0x06,0x5b,0x4f,0x66,0x6d,0x7d,0x07,0x7f,0x6f};
        int[][] rects = {{4,2,20,6},{18,4,22,20},{18,20,22,36},{4,34,20,38},{2,20,6,36},{2,4,6,20},{4,18,20,22}};
        boolean[] pixels = new boolean[24 * 40];
        for (int s=0;s<7;s++) if ((masks[digit] & (1<<s)) != 0) {
            int[] r=rects[s]; for(int y=r[1];y<r[3];y++) for(int x=r[0];x<r[2];x++) pixels[y*24+x]=true;
        }
        return monochrome(pixels);
    }
    private static byte[] colon() {
        boolean[] pixels = new boolean[10 * 40];
        for(int y=10;y<30;y++) if(y<15 || y>=25) for(int x=3;x<7;x++) pixels[y*10+x]=true;
        return monochrome(pixels);
    }
    private static byte[] monochrome(boolean[] pixels) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        le16(out, 2); le16(out, 0); le16(out, 65535);
        for(boolean white:pixels) out.write(white ? 1 : 0);
        return out.toByteArray();
    }
}
