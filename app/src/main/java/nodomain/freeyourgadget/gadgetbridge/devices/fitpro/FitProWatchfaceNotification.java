/* SPDX-License-Identifier: AGPL-3.0-or-later */
package nodomain.freeyourgadget.gadgetbridge.devices.fitpro;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import androidx.core.app.NotificationCompat;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.install.FwAppInstallerActivity;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;
import nodomain.freeyourgadget.gadgetbridge.util.GB;

/** Keeps watch-face transfer notifications attached to the standard installer screen. */
public final class FitProWatchfaceNotification {
    private FitProWatchfaceNotification() { }

    public static void update(Context context, GBDevice device, Uri uri, String message, int percent, boolean ongoing) {
        Intent intent = new Intent(context, FwAppInstallerActivity.class)
                .setData(uri)
                .putExtra(GBDevice.EXTRA_DEVICE, device)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        PendingIntent pending = PendingIntent.getActivity(context, 73, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, GB.NOTIFICATION_CHANNEL_ID_TRANSFER)
                .setSmallIcon(ongoing ? android.R.drawable.stat_sys_upload : android.R.drawable.stat_sys_upload_done)
                .setContentTitle(context.getString(R.string.fitpro_uploading_watchface))
                .setContentText(message)
                .setContentIntent(pending)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setProgress(100, percent, !ongoing && percent == 0);
        GB.notify(GB.NOTIFICATION_ID_INSTALL, builder.build(), context);
    }
}
