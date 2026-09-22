/* SPDX-License-Identifier: AGPL-3.0-or-later */
package nodomain.freeyourgadget.gadgetbridge.devices.fitpro;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Bounded GIF metadata scan. Pixel decoding/disposal is handled by Android's GIF decoder. */
public final class FitProGifInfo {
    public final int width, height, durationMs, frames;
    private final byte[] data;
    private int at;

    public FitProGifInfo(byte[] data) throws IOException {
        this.data = data;
        if (data.length < 13) throw new IOException("Incomplete GIF");
        String magic = new String(data, 0, 6, StandardCharsets.US_ASCII);
        if (!magic.equals("GIF87a") && !magic.equals("GIF89a")) throw new IOException("Not a GIF");
        at = 6; width = word(); height = word();
        if (width == 0 || height == 0 || (long) width * height > 4_000_000) throw new IOException("GIF dimensions are too large");
        int flags = next(); skip(2);
        if ((flags & 128) != 0) skip(3 * (1 << ((flags & 7) + 1)));
        int count = 0, duration = 0, delay = 100;
        boolean ended = false;
        while (at < data.length) {
            int marker = next();
            if (marker == 0x3b) { ended = true; break; }
            if (marker == 0x21) {
                int extension = next();
                if (extension == 0xf9) {
                    if (next() != 4) throw new IOException("Invalid GIF control block");
                    next(); delay = word() * 10; next();
                    if (next() != 0) throw new IOException("Invalid GIF terminator");
                    // Android/Skia normalizes short GIF delays to 100 ms.
                    if (delay < 20) delay = 100;
                } else blocks();
            } else if (marker == 0x2c) {
                int left = word(), top = word(), w = word(), h = word();
                if (w == 0 || h == 0 || left + w > width || top + h > height) throw new IOException("Invalid GIF frame bounds");
                int packed = next();
                if ((packed & 128) != 0) skip(3 * (1 << ((packed & 7) + 1)));
                int minimumCodeSize = next();
                if (minimumCodeSize < 2 || minimumCodeSize > 8) throw new IOException("Invalid GIF pixel encoding");
                blocks(); count++; duration += delay; delay = 100;
                if (duration > 30_000 || (long) width * height * count > 16_000_000) throw new IOException("Use a shorter or smaller GIF (maximum 30 seconds)");
            } else throw new IOException("Invalid GIF block");
        }
        if (!ended || count == 0) throw new IOException("Incomplete GIF");
        frames = count; durationMs = duration;
    }
    private int next() throws IOException { if (at >= data.length) throw new IOException("Incomplete GIF"); return data[at++] & 255; }
    private int word() throws IOException { int low=next(); return low | next()<<8; }
    private void skip(int length) throws IOException { if (length > data.length-at) throw new IOException("Incomplete GIF"); at+=length; }
    private void blocks() throws IOException { int n; while ((n=next()) != 0) skip(n); }
}
