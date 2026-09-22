/* SPDX-License-Identifier: AGPL-3.0-or-later */
package nodomain.freeyourgadget.gadgetbridge.devices.fitpro;

import org.junit.Test;
import java.io.*;
import static org.junit.Assert.*;

public class FitProGifInfoTest {
    private byte[] gif(int delay, int frames) throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        out.write(new byte[]{'G','I','F','8','9','a',1,0,1,0,(byte)128,0,0,0,0,0,(byte)255,(byte)255,(byte)255});
        for(int i=0;i<frames;i++) {
            out.write(new byte[]{0x21,(byte)0xf9,4,0,(byte)delay,(byte)(delay>>8),0,0});
            out.write(new byte[]{0x2c,0,0,0,0,1,0,1,0,0,2,2,0x44,1,0});
        }
        out.write(0x3b);return out.toByteArray();
    }
    @Test public void readsAnimationDurationAndDimensions() throws Exception {
        FitProGifInfo g=new FitProGifInfo(gif(12,3));assertEquals(3,g.frames);assertEquals(360,g.durationMs);assertEquals(1,g.width);assertEquals(1,g.height);
    }
    @Test public void normalizesZeroFrameDelay() throws Exception { assertEquals(200,new FitProGifInfo(gif(0,2)).durationMs); }
    @Test public void detectsEveryTruncation() throws Exception {
        byte[] data=gif(10,2);
        for(int size=0;size<data.length;size++)try{new FitProGifInfo(java.util.Arrays.copyOf(data,size));fail("accepted length "+size);}catch(IOException expected){}
    }
    @Test(expected=IOException.class) public void rejectsOverlongAnimation() throws Exception {new FitProGifInfo(gif(2000,2));}
    @Test(expected=IOException.class) public void rejectsInvalidFrameBounds() throws Exception {byte[] b=gif(10,1);b[28]=2;new FitProGifInfo(b);}
    @Test(expected=IOException.class) public void rejectsHugeCanvas() throws Exception {byte[] b=gif(10,1);b[6]=b[7]=b[8]=b[9]=(byte)255;new FitProGifInfo(b);}
}
