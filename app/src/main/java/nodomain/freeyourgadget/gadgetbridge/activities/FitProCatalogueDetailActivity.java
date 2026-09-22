/* SPDX-License-Identifier: AGPL-3.0-or-later */
package nodomain.freeyourgadget.gadgetbridge.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.core.content.FileProvider;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.activities.install.FwAppInstallerActivity;
import nodomain.freeyourgadget.gadgetbridge.devices.fitpro.FitProCatalogueClient;
import nodomain.freeyourgadget.gadgetbridge.devices.fitpro.FitProWatchfaceInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.fitpro.FitProWatchfaceInstallHandler;
import nodomain.freeyourgadget.gadgetbridge.devices.fitpro.FitProWatchfacePackage;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

/** Downloads a catalogue face and hands the resulting package to the normal installer. */
public class FitProCatalogueDetailActivity extends AbstractGBActivity {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final ActivityResultLauncher<String> saveDocument = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/zip"), this::savePackage);
    private GBDevice device;
    private FitProWatchfaceInfo display;
    private long faceId;
    private FitProCatalogueClient.Detail detail;
    private File packageFile;
    private TextView status;
    private Button install;
    private Button save;
    private FitProPreviewView preview;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        device = getIntent().getParcelableExtra(GBDevice.EXTRA_DEVICE);
        display = FitProWatchfaceInstallHandler.readInfo(device);
        faceId = getIntent().getLongExtra("face_id", 0);
        if (device == null || display == null || faceId == 0) { finish(); return; }
        setTitle(R.string.fitpro_catalogue_face);
        setContentView(content());
        load();
    }

    private LinearLayout content() {
        int margin = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        preview = new FitProPreviewView(this);
        preview.setCropToFill(false);
        preview.setAspectRatio(display.width, display.height);
        root.addView(preview, new LinearLayout.LayoutParams(-1, -2));
        status = new TextView(this);
        status.setPadding(margin, margin, margin, 0);
        root.addView(status);
        install = button(R.string.fitpro_install_catalogue, this::install);
        save = button(R.string.fitpro_save_catalogue, () -> packageFace(() -> saveDocument.launch("fitpro-catalogue-" + faceId + ".zip")));
        root.addView(install, buttonParams(margin));
        root.addView(save, buttonParams(margin));
        return root;
    }

    private Button button(int text, Runnable action) {
        Button result = new Button(this);
        result.setText(text);
        result.setEnabled(false);
        result.setOnClickListener(view -> action.run());
        return result;
    }

    private LinearLayout.LayoutParams buttonParams(int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(margin, 0, margin, 0);
        return params;
    }

    private void load() {
        executor.execute(() -> {
            try {
                detail = FitProCatalogueClient.detail(faceId, display);
                byte[] image = detail.face.previewUrl == null ? null : FitProCatalogueClient.download(detail.face.previewUrl);
                runOnUiThread(() -> { if (image != null) preview.setImage(image); status.setText(detail.face.title); install.setEnabled(true); save.setEnabled(true); });
            } catch (Exception error) { showError(error); }
        });
    }

    private void packageFace(Runnable next) {
        if (packageFile != null) { next.run(); return; }
        status.setText(R.string.fitpro_catalogue_downloading);
        executor.execute(() -> {
            try {
                byte[] binary = FitProCatalogueClient.download(detail.binUrl);
                File directory = new File(getCacheDir(), "fitpro");
                if (!directory.exists() && !directory.mkdirs()) throw new java.io.IOException("Unable to create package directory");
                packageFile = new File(directory, "catalogue-" + faceId + ".zip");
                try (OutputStream output = new FileOutputStream(packageFile)) { FitProWatchfacePackage.write(output, binary, display, detail.face.title); }
                runOnUiThread(() -> { status.setText(R.string.fitpro_zip_ready); next.run(); });
            } catch (Exception error) { showError(error); }
        });
    }

    private void install() {
        packageFace(() -> {
            Uri uri = FileProvider.getUriForFile(this, getPackageName() + ".screenshot_provider", packageFile);
            Intent intent = new Intent(this, FwAppInstallerActivity.class).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.putExtra(GBDevice.EXTRA_DEVICE, device);
            startActivity(intent);
        });
    }

    private void savePackage(Uri destination) {
        if (destination == null || packageFile == null) return;
        executor.execute(() -> {
            try (InputStream input = new FileInputStream(packageFile); OutputStream output = getContentResolver().openOutputStream(destination)) {
                if (output == null) throw new java.io.IOException("Unable to save ZIP");
                byte[] buffer = new byte[8192];
                for (int read; (read = input.read(buffer)) != -1;) output.write(buffer, 0, read);
                runOnUiThread(() -> status.setText(R.string.fitpro_zip_saved));
            } catch (Exception error) { showError(error); }
        });
    }

    private void showError(Exception error) { runOnUiThread(() -> status.setText(getString(R.string.fitpro_catalogue_failed, error.getMessage()))); }
    @Override protected void onDestroy() { executor.shutdownNow(); super.onDestroy(); }
}
