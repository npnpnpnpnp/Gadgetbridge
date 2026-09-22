/* SPDX-License-Identifier: AGPL-3.0-or-later */
package nodomain.freeyourgadget.gadgetbridge.devices.fitpro;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Packages a downloaded native catalogue binary for the normal local install flow. */
public final class FitProWatchfacePackage {
    private FitProWatchfacePackage() { }
    public static void write(OutputStream output, byte[] data, FitProWatchfaceInfo info, String name) throws IOException {
        if (data.length == 0 || data.length > FitProWatchfaceFile.MAX_SIZE) throw new IOException("Invalid catalogue face size");
        Properties p=new Properties(); p.setProperty("format","fitpro-watchface-1"); p.setProperty("name",name);
        p.setProperty("width",Integer.toString(info.width)); p.setProperty("height",Integer.toString(info.height));
        p.setProperty("mainModel",info.mainModel); p.setProperty("matchModel",info.matchModel); p.setProperty("algorithm",Integer.toString(info.algorithm));
        p.setProperty("config",Integer.toString(info.config)); p.setProperty("version",Integer.toString(info.version)); p.setProperty("screenType",Integer.toString(info.screenType));
        p.setProperty("grade",Integer.toString(info.grade)); p.setProperty("customer",info.customer); p.setProperty("slot","1"); p.setProperty("position","0"); p.setProperty("custom","0");
        try { StringBuilder hash=new StringBuilder(); for(byte b:MessageDigest.getInstance("SHA-256").digest(data)) hash.append(String.format(Locale.ROOT,"%02x",b&255)); p.setProperty("sha256",hash.toString()); }
        catch(Exception e) { throw new IOException(e); }
        try(ZipOutputStream zip=new ZipOutputStream(output)) { zip.putNextEntry(new ZipEntry("watchface.properties")); OutputStreamWriter writer=new OutputStreamWriter(zip, StandardCharsets.UTF_8); p.store(writer,"FitPro catalogue face"); writer.flush(); zip.closeEntry(); zip.putNextEntry(new ZipEntry("watchface.bin")); zip.write(data); zip.closeEntry(); }
    }
}
