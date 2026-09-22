/* SPDX-License-Identifier: AGPL-3.0-or-later */
package nodomain.freeyourgadget.gadgetbridge.devices.fitpro;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** A prepared, device-specific binary plus metadata; never treats arbitrary firmware as a face. */
public final class FitProWatchfaceFile {
    public static final int MAX_SIZE = 7 * 1024 * 1024;
    public final Properties metadata = new Properties();
    public final byte[] data;

    public FitProWatchfaceFile(InputStream input) throws IOException {
        byte[] binary = null, manifest = null;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            int entries = 0;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 2) throw new IOException("Unexpected watch-face package entry");
                if (entry.getName().equals("watchface.properties") && manifest == null) {
                    manifest = read(zip, 16384);
                } else if (entry.getName().equals("watchface.bin") && binary == null) {
                    binary = read(zip, MAX_SIZE);
                } else throw new IOException("Unexpected watch-face package entry");
            }
        }
        if (binary == null || binary.length == 0 || manifest == null) throw new IOException("Not a FitPro watch-face package");
        metadata.load(new InputStreamReader(new ByteArrayInputStream(manifest), StandardCharsets.UTF_8));
        if (!"fitpro-watchface-1".equals(metadata.getProperty("format"))) throw new IOException("Unsupported watch-face format");
        data = binary;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            if (!hex.toString().equals(metadata.getProperty("sha256"))) throw new IOException("Watch-face checksum mismatch");
            for (String key : new String[]{"width", "height", "algorithm", "config", "version", "screenType", "grade", "slot", "position", "custom"}) integer(key);
            for (String key : new String[]{"mainModel", "matchModel", "customer", "name"}) {
                if (metadata.getProperty(key) == null) throw new IllegalArgumentException("Missing " + key);
            }
            if (integer("slot") > 255 || integer("position") > 255 || integer("custom") > 1) throw new IllegalArgumentException("Invalid upload options");
        } catch (NoSuchAlgorithmException | IllegalArgumentException e) { throw new IOException(e); }
    }

    private static byte[] read(InputStream stream, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int length;
        while ((length = stream.read(buffer)) != -1) {
            if (out.size() + length > max) throw new IOException("Watch-face file is too large");
            out.write(buffer, 0, length);
        }
        return out.toByteArray();
    }

    public int integer(String key) {
        int value = Integer.parseInt(metadata.getProperty(key));
        if (value < 0) throw new IllegalArgumentException("Negative " + key);
        return value;
    }

    public void checkCompatible(FitProWatchfaceInfo info) {
        if (info == null) throw new IllegalArgumentException("Connect the watch and read its watch-face details first");
        if (integer("width") != info.width || integer("height") != info.height ||
                integer("algorithm") != info.algorithm || integer("config") != info.config ||
                integer("version") != info.version || integer("screenType") != info.screenType || integer("grade") != info.grade ||
                !metadata.getProperty("mainModel").equals(info.mainModel) ||
                !metadata.getProperty("matchModel").equals(info.matchModel) ||
                !metadata.getProperty("customer").equals(info.customer)) {
            throw new IllegalArgumentException("This watch face was prepared for a different display or firmware format");
        }
        if (integer("slot") < 1 || integer("slot") > Math.max(1, info.slots)) throw new IllegalArgumentException("Watch-face slot is unavailable");
    }

    public byte[] startPayload(FitProWatchfaceInfo info) {
        byte[] result = new byte[info.slots > 0 ? 6 : 5];
        result[0] = (byte) integer("position");
        result[1] = (byte) integer("custom");
        result[2] = result[3] = result[4] = (byte) 255;
        if (result.length == 6) result[5] = (byte) integer("slot");
        return result;
    }
}
