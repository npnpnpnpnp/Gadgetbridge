/* SPDX-License-Identifier: AGPL-3.0-or-later */
package nodomain.freeyourgadget.gadgetbridge.activities;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import nodomain.freeyourgadget.gadgetbridge.GBApplication;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.install.FwAppInstallerActivity;
import nodomain.freeyourgadget.gadgetbridge.devices.fitpro.FitProWatchfaceInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.fitpro.FitProWatchfaceInstallHandler;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

/** Entry point for local faces and the optional HiWatchPro-compatible catalogue. */
public class FitProWatchfaceActivity extends AbstractGBActivity {
    private GBDevice device;
    private final ActivityResultLauncher<String[]> pickFace = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                Intent intent = new Intent(this, FwAppInstallerActivity.class);
                intent.setData(uri);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                intent.putExtra(GBDevice.EXTRA_DEVICE, device);
                startActivity(intent);
            });
    private final ActivityResultLauncher<String> exportInfo = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("text/plain"), uri -> {
                if (uri == null) return;
                FitProWatchfaceInfo info = FitProWatchfaceInstallHandler.readInfo(device);
                if (info == null) return;
                Properties p = new Properties();
                p.setProperty("width", Integer.toString(info.width));
                p.setProperty("height", Integer.toString(info.height));
                p.setProperty("mainModel", info.mainModel);
                p.setProperty("matchModel", info.matchModel);
                p.setProperty("algorithm", Integer.toString(info.algorithm));
                p.setProperty("config", Integer.toString(info.config));
                p.setProperty("screenType", Integer.toString(info.screenType));
                p.setProperty("grade", Integer.toString(info.grade));
                p.setProperty("customer", info.customer);
                p.setProperty("version", Integer.toString(info.version));
                p.setProperty("slots", Integer.toString(info.slots));
                p.setProperty("thumbnailPercent", Integer.toString(info.thumbnailPercent));
                p.setProperty("thumbnailRadius", Integer.toString(info.thumbnailRadius));
                try (OutputStream output = getContentResolver().openOutputStream(uri)) {
                    if (output == null) throw new java.io.IOException("Unable to open file");
                    OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
                    p.store(writer, "FitPro watch-face capabilities");
                    writer.flush();
                } catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); }
            });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        device = getIntent().getParcelableExtra(GBDevice.EXTRA_DEVICE);
        if (device == null) { finish(); return; }
        setTitle(R.string.fitpro_watchfaces);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        TextView text = new TextView(this);
        text.setText(R.string.fitpro_watchface_help);
        layout.addView(text);
        Button create = new Button(this);
        create.setText(R.string.fitpro_create_watchface);
        create.setOnClickListener(view -> {
            Intent intent = new Intent(this, FitProCreateWatchfaceActivity.class);
            intent.putExtra(GBDevice.EXTRA_DEVICE, device);
            startActivity(intent);
        });
        layout.addView(create);
        Button catalogue = new Button(this);
        catalogue.setText(R.string.fitpro_catalogue);
        catalogue.setOnClickListener(view -> { Intent intent = new Intent(this, FitProCatalogueActivity.class); intent.putExtra(GBDevice.EXTRA_DEVICE, device); startActivity(intent); });
        layout.addView(catalogue);
        Button pick = new Button(this);
        pick.setText(R.string.fitpro_choose_watchface);
        pick.setOnClickListener(view -> pickFace.launch(new String[]{"application/zip", "application/octet-stream"}));
        layout.addView(pick);
        Button export = new Button(this);
        export.setText(R.string.fitpro_export_display_spec);
        export.setOnClickListener(view -> {
            if (FitProWatchfaceInstallHandler.readInfo(device) == null) {
                GBApplication.deviceService(device).onTestNewFunction(null);
                Toast.makeText(this, R.string.fitpro_display_spec_requested, Toast.LENGTH_LONG).show();
            } else exportInfo.launch("fitpro-display.properties");
        });
        layout.addView(export);
        setContentView(layout);
    }
}
