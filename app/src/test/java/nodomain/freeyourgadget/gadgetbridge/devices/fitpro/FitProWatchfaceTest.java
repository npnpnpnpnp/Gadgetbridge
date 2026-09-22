/* SPDX-License-Identifier: AGPL-3.0-or-later */
package nodomain.freeyourgadget.gadgetbridge.devices.fitpro;

import org.junit.Test;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.zip.*;
import static org.junit.Assert.*;

public class FitProWatchfaceTest {
    private byte[] capabilities() {
        return new byte[]{1,2,1,64,1,104,3,'L','J','7',3,'L','S','7',34,2,(byte)255,(byte)252,3,0,2,25,4};
    }
    @Test public void parsesCapabilityFieldsAndOptionalTail() {
        FitProWatchfaceInfo info = new FitProWatchfaceInfo(capabilities());
        assertEquals(320,info.width); assertEquals(360,info.height);
        assertEquals("LS7",info.mainModel); assertEquals("LJ7",info.matchModel);
        assertEquals(120,info.blockSize()); assertTrue(info.hasThumbnail());
        assertEquals(2,info.slots); assertEquals(3,info.version); assertEquals(25,info.thumbnailPercent);
        info = new FitProWatchfaceInfo(java.util.Arrays.copyOf(capabilities(),14));
        assertEquals(0,info.algorithm); assertEquals(0,info.slots); assertEquals(200,info.blockSize());
    }
    @Test(expected=RuntimeException.class) public void rejectsTruncatedModelName() { new FitProWatchfaceInfo(new byte[]{1,1,1,1,1,1,100}); }
    @Test(expected=IllegalArgumentException.class) public void rejectsZeroWidth() { byte[] b=capabilities();b[2]=b[3]=0;new FitProWatchfaceInfo(b); }
    private byte[] archive(String hash, String extras) throws Exception {
        byte[] binary = {1,2,3};
        if (hash == null) {
            StringBuilder s = new StringBuilder();
            for(byte b:MessageDigest.getInstance("SHA-256").digest(binary))s.append(String.format("%02x",b&255));
            hash=s.toString();
        }
        String manifest="format=fitpro-watchface-1\nname=Test\nwidth=320\nheight=360\nmainModel=LS7\nmatchModel=LJ7\nalgorithm=2\nconfig=34\nversion=3\nscreenType=1\ngrade=2\ncustomer=\nslot=1\nposition=0\ncustom=0\nsha256="+hash+"\n"+extras;
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        try(ZipOutputStream zip=new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("watchface.properties"));zip.write(manifest.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            zip.putNextEntry(new ZipEntry("watchface.bin"));zip.write(binary);zip.closeEntry();
        }
        return output.toByteArray();
    }
    @Test public void validatesPackageAndSlotPayload() throws Exception {
        FitProWatchfaceFile face=new FitProWatchfaceFile(new ByteArrayInputStream(archive(null,"")));
        FitProWatchfaceInfo info=new FitProWatchfaceInfo(capabilities());face.checkCompatible(info);
        assertArrayEquals(new byte[]{0,0,(byte)255,(byte)255,(byte)255,1},face.startPayload(info));
    }
    @Test public void acceptsLastOneBasedSlot() throws Exception {
        FitProWatchfaceFile face = new FitProWatchfaceFile(new ByteArrayInputStream(archive(null,"slot=2\n")));
        FitProWatchfaceInfo info = new FitProWatchfaceInfo(capabilities());
        face.checkCompatible(info);
        assertEquals(2, face.startPayload(info)[5]);
    }
    @Test public void defaultDestinationOmitsSlotByte() throws Exception {
        byte[] data = capabilities(); data[20] = 0;
        FitProWatchfaceInfo info = new FitProWatchfaceInfo(data);
        FitProWatchfaceFile face = new FitProWatchfaceFile(new ByteArrayInputStream(archive(null,"")));
        face.checkCompatible(info);
        assertEquals(5, face.startPayload(info).length);
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsZeroSlot() throws Exception {
        new FitProWatchfaceFile(new ByteArrayInputStream(archive(null,"slot=0\n"))).checkCompatible(new FitProWatchfaceInfo(capabilities()));
    }
    @Test(expected=IOException.class) public void rejectsCorruptBinary() throws Exception {
        new FitProWatchfaceFile(new ByteArrayInputStream(archive("bad","")));
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsIncompatibleFace() throws Exception {
        FitProWatchfaceFile face=new FitProWatchfaceFile(new ByteArrayInputStream(archive(null,"algorithm=5\n")));
        face.checkCompatible(new FitProWatchfaceInfo(capabilities()));
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsMissingDeviceCapabilities() throws Exception {
        new FitProWatchfaceFile(new ByteArrayInputStream(archive(null,""))).checkCompatible(null);
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsUnavailableSlot() throws Exception {
        new FitProWatchfaceFile(new ByteArrayInputStream(archive(null,"slot=3\n"))).checkCompatible(new FitProWatchfaceInfo(capabilities()));
    }
}
