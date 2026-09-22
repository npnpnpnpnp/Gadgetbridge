/* SPDX-License-Identifier: AGPL-3.0-or-later */
package nodomain.freeyourgadget.gadgetbridge.service.devices.fitpro;

import org.junit.Test;
import java.util.Arrays;
import java.util.List;
import java.nio.ByteBuffer;
import static org.junit.Assert.*;

public class FitProProtocolTest {
    @Test public void fragmentedHeaderAndPayloadRoundTrip() {
        byte[] file = new byte[200];
        for (int i = 0; i < file.length; i++) file[i] = (byte) i;
        byte[] packet = FitProWatchfaceTransfer.packet(0x20, 2, file);
        for (int chunk = 1; chunk <= 23; chunk++) {
            FitProPacketAssembler parser = new FitProPacketAssembler();
            java.util.ArrayList<byte[]> result = new java.util.ArrayList<>();
            for (int i = 0; i < packet.length; i += chunk) result.addAll(parser.accept(Arrays.copyOfRange(packet, i, Math.min(i + chunk, packet.length))));
            assertEquals(1, result.size());
            assertArrayEquals(packet, result.get(0));
        }
    }
    @Test public void coalescedAndAckPacketsAreSeparated() {
        byte[] first = new byte[]{(byte) 0xdc, 0, 5, 0x12, 1, 0, 9, 1};
        byte[] second = FitProWatchfaceTransfer.packet(0x20, 1, new byte[]{0, 0, 3, (byte) 0xe8});
        byte[] stream = ByteBuffer.allocate(first.length + second.length).put(first).put(second).array();
        List<byte[]> packets = new FitProPacketAssembler().accept(stream);
        assertEquals(2, packets.size()); assertArrayEquals(first, packets.get(0)); assertArrayEquals(second, packets.get(1));
    }
    @Test public void resetDropsIncompletePacket() {
        FitProPacketAssembler parser = new FitProPacketAssembler();
        assertTrue(parser.accept(new byte[]{(byte) 0xcd, 0, 20, 1}).isEmpty());
        parser.reset();
        assertEquals(1, parser.accept(FitProWatchfaceTransfer.packet(0x20, 2, new byte[0])).size());
        assertTrue(parser.accept(null).isEmpty());
    }
    @Test public void malformedLengthCanRecover() {
        FitProPacketAssembler parser = new FitProPacketAssembler();
        assertTrue(parser.accept(new byte[]{1, 2, (byte) 0xcd, 0, 0}).isEmpty());
        assertEquals(1, parser.accept(FitProWatchfaceTransfer.packet(0x20, 2, new byte[0])).size());
    }
    @Test public void knownQueryMatchesVendorFrame() {
        assertArrayEquals(new byte[]{(byte) 0xcd, 0, 5, 0x20, 1, 2, 0, 0}, FitProWatchfaceTransfer.packet(0x20, 2, new byte[0]));
    }
    @Test public void blockChecksumIncludesBigEndianSequenceAndUnsignedBytes() {
        FitProWatchfaceTransfer upload = new FitProWatchfaceTransfer(new byte[]{(byte) 255, 1, 2}, 120);
        assertArrayEquals(new byte[]{(byte) 0xcd,0,12,0x1f,1,1,0,7,0,1,(byte)255,1,2,1,3}, upload.onStatus(1000));
        assertEquals(0, upload.progress());
        assertNull(upload.onStatus(1000));
        byte[] finish = upload.onStatus(1001);
        assertArrayEquals(new byte[]{(byte) 0xcd,0,13,0x1f,1,3,0,8,0,0,0,3,0,0,1,2}, finish);
        assertFalse(upload.isComplete());
        assertNull(upload.onStatus(1001));
        upload.onStatus(2); assertTrue(upload.isComplete()); assertEquals(100, upload.progress());
    }
    @Test public void exactAndPartialBlocksRequireAllAcknowledgements() {
        for (int block : new int[]{120,200}) for (int size : new int[]{1,120,200,240,401}) {
            FitProWatchfaceTransfer upload = new FitProWatchfaceTransfer(new byte[size],block);
            int count = (size + block - 1) / block;
            for (int sequence = 0; sequence < count; sequence++) {
                byte[] packet = upload.onStatus(1000 + sequence);
                assertEquals(1, packet[5]);
                assertEquals(sequence + 1, Short.toUnsignedInt(ByteBuffer.wrap(packet,8,2).getShort()));
            }
            assertEquals(3, upload.onStatus(1000 + count)[5]);
            assertFalse(upload.isComplete()); upload.onStatus(2); assertTrue(upload.isComplete());
        }
    }
    @Test public void preservesOpaqueFaceBytesAcrossBlocks() {
        byte[] original = new byte[1027];
        new java.util.Random(7076).nextBytes(original);
        for (int block : new int[]{120, 200}) {
            FitProWatchfaceTransfer upload = new FitProWatchfaceTransfer(original, block);
            java.io.ByteArrayOutputStream received = new java.io.ByteArrayOutputStream();
            int count = (original.length + block - 1) / block;
            for (int sequence = 0; sequence < count; sequence++) {
                byte[] packet = upload.onStatus(1000 + sequence);
                received.write(packet, 10, packet.length - 12);
            }
            assertArrayEquals(original, received.toByteArray());
            assertEquals(3, upload.onStatus(1000 + count)[5]);
            upload.onStatus(2);
            assertTrue(upload.isComplete());
        }
    }
    @Test(expected=IllegalArgumentException.class) public void outOfOrderAckRejected() {
        new FitProWatchfaceTransfer(new byte[300],200).onStatus(1001);
    }
    @Test(expected=IllegalArgumentException.class) public void prematureSuccessRejected() {
        new FitProWatchfaceTransfer(new byte[300],200).onStatus(2);
    }
    @Test(expected=IllegalArgumentException.class) public void batteryFailureRejected() {
        new FitProWatchfaceTransfer(new byte[300],200).onStatus(3);
    }
    @Test(expected=IllegalArgumentException.class) public void emptyFileRejected() {
        new FitProWatchfaceTransfer(new byte[0],200);
    }
}
