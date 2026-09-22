/* SPDX-License-Identifier: AGPL-3.0-or-later */
package nodomain.freeyourgadget.gadgetbridge.devices.fitpro;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import org.junit.Test;

public class FitProWatchfacePackageTest {
    private static FitProWatchfaceInfo display() {
        return new FitProWatchfaceInfo(new byte[]{0, 0, 0, (byte) 240, 1, 30, 3, 'K', '7', '5', 5, 'L', 'J', '7', '3', '6', 0, 3, (byte) 255, (byte) 254, 1, 0, 0, 0, 0});
    }

    @Test public void packagesNativeCatalogueBinaryForTheMatchingWatch() throws Exception {
        byte[] binary = {1, 2, 3, 4};
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        FitProWatchfacePackage.write(archive, binary, display(), "Catalogue face");
        FitProWatchfaceFile face = new FitProWatchfaceFile(new ByteArrayInputStream(archive.toByteArray()));
        face.checkCompatible(display());
        assertArrayEquals(binary, face.data);
        assertEquals("Catalogue face", face.metadata.getProperty("name"));
    }
}
