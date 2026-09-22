/* SPDX-License-Identifier: AGPL-3.0-or-later */
package nodomain.freeyourgadget.gadgetbridge.devices.fitpro;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.util.Base64;
import androidx.annotation.NonNull;
import java.io.InputStream;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.activities.install.FwAppInstallerActivity;
import nodomain.freeyourgadget.gadgetbridge.activities.install.InstallActivity;
import nodomain.freeyourgadget.gadgetbridge.devices.InstallHandler;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.model.DeviceType;

public final class FitProWatchfaceInstallHandler implements InstallHandler {
    public static final String PREF_CAPABILITIES = "fitpro_watchface_capabilities";
    private FitProWatchfaceFile file;

    public FitProWatchfaceInstallHandler(Uri uri, Context context) {
        if (uri == null) return;
        try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
            if (stream != null) file = new FitProWatchfaceFile(stream);
        } catch (Exception ignored) { /* Other device handlers must remain able to inspect this file. */ }
    }

    public static FitProWatchfaceInfo readInfo(GBDevice device) {
        String encoded = GBApplication.getDeviceSpecificSharedPrefs(device.getAddress()).getString(PREF_CAPABILITIES, null);
        if (encoded == null) return null;
        try { return new FitProWatchfaceInfo(Base64.decode(encoded, Base64.DEFAULT)); }
        catch (RuntimeException ignored) { return null; }
    }

    @NonNull @Override public Class<? extends Activity> getInstallActivity() { return FwAppInstallerActivity.class; }
    @Override public boolean isValid() { return file != null; }
    @Override public void onStartInstall(@NonNull GBDevice device) { }

    @Override public void validateInstallation(@NonNull InstallActivity activity, @NonNull GBDevice device) {
        activity.setInstallEnabled(false);
        try {
            if (device.getType() != DeviceType.FITPRO || !device.isInitialized() || device.isBusy()) {
                throw new IllegalArgumentException("Connect the FitPro watch and wait for other transfers to finish");
            }
            file.checkCompatible(readInfo(device));
            activity.setInfoText("Install " + file.metadata.getProperty("name") + " (" + file.data.length +
                    " bytes) in watch-face slot " + file.integer("slot") + "? This replaces the face in that slot.");
            activity.setInstallEnabled(true);
        } catch (IllegalArgumentException e) { activity.setInfoText(e.getMessage()); }
    }
}
