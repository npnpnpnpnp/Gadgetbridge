/* SPDX-License-Identifier: AGPL-3.0-or-later */
package nodomain.freeyourgadget.gadgetbridge.devices.fitpro;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Payload of the 0x20/0x02 dial-capability response (without the FitPro header). */
public final class FitProWatchfaceInfo {
    public final int screenType, grade, width, height, config, algorithm, version, slots;
    public final int thumbnailPercent, thumbnailRadius;
    public final String mainModel, matchModel, customer;

    public FitProWatchfaceInfo(byte[] payload) {
        ByteBuffer b = ByteBuffer.wrap(payload);
        screenType = Byte.toUnsignedInt(b.get());
        grade = Byte.toUnsignedInt(b.get());
        width = Short.toUnsignedInt(b.getShort());
        height = Short.toUnsignedInt(b.getShort());
        if (width == 0 || height == 0 || width > 2048 || height > 2048) {
            throw new IllegalArgumentException("Invalid watch-face dimensions");
        }
        matchModel = readString(b);
        mainModel = readString(b);
        config = optionalByte(b);
        algorithm = optionalByte(b);
        int parsedVersion = 0;
        if (b.remaining() >= 3) {
            int marker = Byte.toUnsignedInt(b.get());
            int inverse = Byte.toUnsignedInt(b.get());
            int value = Byte.toUnsignedInt(b.get());
            if (marker == 255 && marker - inverse == value) parsedVersion = value;
        }
        version = parsedVersion;
        customer = b.hasRemaining() ? readString(b) : "";
        slots = optionalByte(b);
        thumbnailPercent = optionalByte(b);
        thumbnailRadius = optionalByte(b);
    }

    private static String readString(ByteBuffer b) {
        byte[] bytes = new byte[Byte.toUnsignedInt(b.get())];
        b.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static int optionalByte(ByteBuffer b) {
        return b.hasRemaining() ? Byte.toUnsignedInt(b.get()) : 0;
    }

    public int blockSize() { return (config & 2) != 0 ? 120 : 200; }
    public boolean hasThumbnail() { return (config & 32) != 0; }

    @Override
    public String toString() {
        return width + "×" + height + ", " + mainModel + "/" + matchModel +
                ", algorithm " + algorithm + ", config " + config + ", slots " + slots +
                ", customer " + customer + ", version " + version;
    }
}
