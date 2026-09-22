/* SPDX-License-Identifier: AGPL-3.0-or-later */
package nodomain.freeyourgadget.gadgetbridge.service.devices.fitpro;

import java.nio.ByteBuffer;
import java.util.Arrays;

/** Stop-and-wait transfer. Only a watch acknowledgement advances the file offset. */
public final class FitProWatchfaceTransfer {
    private final byte[] file;
    private final int blockSize;
    private int acknowledged;
    private int sent;
    private boolean started, finishing, complete;

    public FitProWatchfaceTransfer(byte[] file, int blockSize) {
        if (file.length == 0 || (blockSize != 120 && blockSize != 200) ||
                (file.length + blockSize - 1) / blockSize > 65535) {
            throw new IllegalArgumentException("Invalid watch-face size or block size");
        }
        this.file = Arrays.copyOf(file, file.length);
        this.blockSize = blockSize;
    }

    /** null means a duplicate/old status. Status 2 is success only after the finish command. */
    public byte[] onStatus(int status) {
        if (complete) return null;
        if (status == 2 && finishing) { complete = true; return null; }
        if (status < 1000) {
            if (status == 0) return null; // watch still preparing or verifying
            throw new IllegalArgumentException("Watch rejected upload, status " + status);
        }
        int sequence = status - 1000;
        if (finishing || (started && sequence <= acknowledged)) return null;
        if (sequence != sent) throw new IllegalArgumentException("Unexpected watch-face block acknowledgement");
        started = true;
        acknowledged = sequence;
        int offset = acknowledged * blockSize;
        if (offset >= file.length) {
            finishing = true;
            long sum = 0;
            for (byte value : file) sum += value & 255;
            return packet(0x1f, 3, ByteBuffer.allocate(8).putInt(file.length).putInt((int) sum).array());
        }
        int size = Math.min(blockSize, file.length - offset);
        byte[] payload = ByteBuffer.allocate(size + 4).putShort((short) ++sent)
                .put(file, offset, size).array();
        int sum = 0;
        for (int i = 0; i < size + 2; i++) sum += payload[i] & 255;
        ByteBuffer.wrap(payload).putShort(size + 2, (short) sum);
        return packet(0x1f, 1, payload);
    }

    public int progress() { return complete ? 100 : Math.min(99, (int) (100L * acknowledged * blockSize / file.length)); }
    public boolean isComplete() { return complete; }

    public static byte[] packet(int group, int command, byte[] payload) {
        return ByteBuffer.allocate(8 + payload.length).put((byte) 0xcd)
                .putShort((short) (5 + payload.length)).put((byte) group).put((byte) 1)
                .put((byte) command).putShort((short) payload.length).put(payload).array();
    }
}
