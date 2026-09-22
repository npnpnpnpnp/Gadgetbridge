/* SPDX-License-Identifier: AGPL-3.0-or-later */
package nodomain.freeyourgadget.gadgetbridge.service.devices.fitpro;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

/** FitPro frames may span several GATT notifications, including a split header. */
public final class FitProPacketAssembler {
    private final ByteArrayOutputStream pending = new ByteArrayOutputStream();
    private int expected = -1;

    public void reset() {
        pending.reset();
        expected = -1;
    }

    public List<byte[]> accept(byte[] fragment) {
        List<byte[]> packets = new ArrayList<>();
        if (fragment == null) return packets;
        for (byte value : fragment) {
            if (pending.size() == 0 && value != (byte) 0xcd && value != (byte) 0xdc) continue;
            pending.write(value);
            if (pending.size() == 3) {
                byte[] header = pending.toByteArray();
                expected = 3 + ((header[1] & 255) << 8) + (header[2] & 255);
                if (expected < 5 || expected > 65538) {
                    reset();
                    continue;
                }
            }
            if (pending.size() == expected) {
                packets.add(pending.toByteArray());
                reset();
            }
        }
        return packets;
    }
}
