/* SPDX-License-Identifier: AGPL-3.0-or-later */
package nodomain.freeyourgadget.gadgetbridge.devices.fitpro;

import org.junit.Test;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import static org.junit.Assert.*;

public class FitProFaceEncoderTest {
    static FitProWatchfaceInfo info() {
        return new FitProWatchfaceInfo(new byte[]{0,0,0,(byte)240,1,30,3,'K','7','5',5,'L','J','7','3','6',0,3,(byte)255,(byte)254,1,0,0,0,0});
    }
    private int[] solid(int color) { int[] p=new int[240*286];Arrays.fill(p,color);return p; }

    /** Independent reader checks all records, bounds, palettes, offsets and complete coverage. */
    private List<int[]> verify(byte[] data) {
        ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0x55aa,Short.toUnsignedInt(b.getShort()));assertEquals(1,b.get());assertEquals(0,b.getShort());
        int groups=Byte.toUnsignedInt(b.get());List<int[]> records=new ArrayList<>();TreeMap<Integer,Integer> ranges=new TreeMap<>();
        for(int g=0;g<groups;g++) {
            int kind=Byte.toUnsignedInt(b.get()), count=kind<=1 ? Byte.toUnsignedInt(b.get()) : 1;
            for(int j=0;j<count;j++) {
                int bits=Byte.toUnsignedInt(b.get());int delay=kind<=1 ? Byte.toUnsignedInt(b.get()) : 0;
                int x=Short.toUnsignedInt(b.getShort()),y=Short.toUnsignedInt(b.getShort()),w=Short.toUnsignedInt(b.getShort()),h=Short.toUnsignedInt(b.getShort());
                assertTrue(w>0 && h>0 && x+w<=240 && y+h<=286);
                int frames=Byte.toUnsignedInt(b.get());assertTrue(frames>0);
                records.add(new int[]{kind,bits,delay,x,y,w,h,frames,b.position()});
                for(int f=0;f<frames;f++) {
                    int offset=b.getInt();assertTrue(offset>0 && offset<data.length);
                    int length=w*h*bits/8;
                    if(bits==8) {
                        int colors=Short.toUnsignedInt(ByteBuffer.wrap(data,offset,2).order(ByteOrder.LITTLE_ENDIAN).getShort());
                        assertTrue(colors>0 && colors<=255);length+=2+colors*2;
                        for(int p=offset+2+colors*2;p<offset+length;p++)assertTrue((data[p]&255)<colors);
                    } else assertEquals(16,bits);
                    assertTrue(offset+length<=data.length);ranges.put(offset,offset+length);
                }
            }
        }
        int end=b.position();for(Map.Entry<Integer,Integer> entry:ranges.entrySet()){assertEquals(end,(int)entry.getKey());end=entry.getValue();}assertEquals(data.length,end);
        return records;
    }
    @Test public void staticFaceContainsLiveHourAndMinuteDigits() {
        FitProFaceEncoder encoder=new FitProFaceEncoder(info(),10,true);encoder.addFrame(solid(0xffff0000));
        List<int[]> records=verify(encoder.build());assertEquals(4,records.size());
        assertEquals(0,records.get(0)[0]);assertEquals(5,records.get(2)[0]);assertEquals(6,records.get(3)[0]);assertEquals(10,records.get(2)[7]);
    }
    @Test public void animatedFaceKeepsDistinctFramesAndDelay() {
        FitProFaceEncoder encoder=new FitProFaceEncoder(info(),8,false);encoder.addFrame(solid(0xffff0000));encoder.addFrame(solid(0xff00ff00));
        byte[] bytes=encoder.build();List<int[]> records=verify(bytes);assertEquals(1,records.size());
        int[] r=records.get(0);assertEquals(1,r[0]);assertEquals(8,r[2]);assertEquals(2,r[7]);
        ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);int first=b.getInt(r[8]),second=b.getInt(r[8]+4);
        assertEquals(180,bytes[first+434]&255);assertEquals(30,bytes[second+434]&255);
    }
    @Test public void maxAnimationFitsUploadLimit() {
        FitProFaceEncoder encoder=new FitProFaceEncoder(info(),10,true);
        for(int i=0;i<80;i++)encoder.addFrame(solid(0xff000000 | i));
        byte[] bytes=encoder.build();verify(bytes);assertTrue(bytes.length<FitProWatchfaceFile.MAX_SIZE);
    }
    @Test public void packageRoundTripsThroughInstallerChecks() throws Exception {
        FitProFaceEncoder encoder=new FitProFaceEncoder(info(),10,true);encoder.addFrame(solid(0xff000000));
        ByteArrayOutputStream out=new ByteArrayOutputStream();encoder.writeZip(out,"Photo é");
        FitProWatchfaceFile file=new FitProWatchfaceFile(new ByteArrayInputStream(out.toByteArray()));file.checkCompatible(info());
        assertArrayEquals(encoder.build(),file.data);assertEquals(0,file.integer("custom"));assertEquals("Photo é",file.metadata.getProperty("name"));
    }
    @Test public void paletteMatchesServerRgb565ColoursAndTopDownOrder() {
        byte[] data=FitProFaceEncoder.bitmapRecord(2,2,new int[]{0xffff0000,0xff00ff00,0xff0000ff,0xffffffff});
        assertEquals('B',data[0]);assertEquals('M',data[1]);assertEquals(2,data[2]);assertEquals(2,data[4]);
        ByteBuffer b=ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);assertEquals(442,Short.toUnsignedInt(b.getShort(8)));
        int[] expected={0xf800,0x07e0,0x001f,0xffff};
        for(int i=0;i<4;i++){int index=data[442+i]&255;assertEquals(expected[i],Short.toUnsignedInt(b.getShort(10+index*2)));}
    }
    @Test public void adaptivePaletteKeepsUpTo255NativeRgb565Colours() {
        int[] pixels = new int[300];
        for (int i = 0; i < pixels.length; i++) {
            int red = (i & 31) << 3, green = ((i >> 5) & 63) << 2, blue = ((i >> 11) & 31) << 3;
            pixels[i] = 0xff000000 | red << 16 | green << 8 | blue;
        }
        byte[] encoded = FitProFaceEncoder.indexedImage(pixels, FitProFaceEncoder.PaletteMode.ADAPTIVE_255);
        assertEquals(255, Short.toUnsignedInt(ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).getShort()));
        assertEquals(2 + 255 * 2 + pixels.length, encoded.length);
        for (int i = 2 + 255 * 2; i < encoded.length; i++) assertTrue((encoded[i] & 255) < 255);
    }
    @Test public void adaptivePalettePreservesFrequentlyUsedRgb565ColoursExactly() {
        int[] pixels = new int[1000];
        java.util.Arrays.fill(pixels, 0xff123456);
        for (int i = 0; i < 200; i++) pixels[i] = 0xffabcdef;
        byte[] encoded = FitProFaceEncoder.indexedImage(pixels, FitProFaceEncoder.PaletteMode.ADAPTIVE_255);
        ByteBuffer bytes = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(2, Short.toUnsignedInt(bytes.getShort()));
        assertEquals((0x12 >> 3) << 11 | (0x34 >> 2) << 5 | (0x56 >> 3), Short.toUnsignedInt(bytes.getShort(2)));
        assertEquals((0xab >> 3) << 11 | (0xcd >> 2) << 5 | (0xef >> 3), Short.toUnsignedInt(bytes.getShort(4)));
        assertTrue((encoded[6] & 255) < 2);
    }
    @Test public void directRgb565HasNoPaletteAndUsesLittleEndianPixels() {
        byte[] encoded = FitProFaceEncoder.directRgb565Image(new int[]{0xffff0000, 0xff00ff00, 0xff0000ff, 0x00000000});
        assertArrayEquals(new byte[]{0, (byte)0xf8, (byte)0xe0, 7, 31, 0, 0, 0}, encoded);
        FitProFaceEncoder encoder = new FitProFaceEncoder(info(), 10, false, FitProFaceEncoder.PaletteMode.DIRECT_RGB565);
        encoder.addFrame(solid(0xff123456));
        assertEquals(16, verify(encoder.build()).get(0)[1]);
    }
    @Test public void transparentPixelsAreCompositedOnBlack() { assertEquals(0,FitProFaceEncoder.indexedImage(new int[]{0x00ffffff})[434]); }
    @Test(expected=IllegalArgumentException.class) public void rejectsOtherDisplays() {
        byte[] cap={0,0,0,(byte)240,1,30,3,'K','7','6',5,'L','J','7','3','6',0,3,(byte)255,(byte)254,1,0,0};new FitProFaceEncoder(new FitProWatchfaceInfo(cap),10,true);
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsTooManyFrames() { FitProFaceEncoder e=new FitProFaceEncoder(info(),10,false);for(int i=0;i<81;i++)e.addFrame(solid(0)); }
    @Test(expected=IllegalArgumentException.class) public void rejectsIncorrectPixelCount() { new FitProFaceEncoder(info(),10,true).addFrame(new int[1]); }
    @Test(expected=IllegalStateException.class) public void rejectsEmptyFace() { new FitProFaceEncoder(info(),10,true).build(); }
}
