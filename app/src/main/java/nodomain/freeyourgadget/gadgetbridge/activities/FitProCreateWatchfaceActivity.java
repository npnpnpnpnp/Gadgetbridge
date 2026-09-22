/* SPDX-License-Identifier: AGPL-3.0-or-later */
package nodomain.freeyourgadget.gadgetbridge.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import nodomain.freeyourgadget.gadgetbridge.R;
import nodomain.freeyourgadget.gadgetbridge.devices.fitpro.FitProFaceEncoder;
import nodomain.freeyourgadget.gadgetbridge.devices.fitpro.FitProImageConverter;
import nodomain.freeyourgadget.gadgetbridge.devices.fitpro.FitProWatchfaceFile;
import nodomain.freeyourgadget.gadgetbridge.devices.fitpro.FitProWatchfaceInfo;
import nodomain.freeyourgadget.gadgetbridge.devices.fitpro.FitProWatchfaceInstallHandler;
import nodomain.freeyourgadget.gadgetbridge.impl.GBDevice;

/** Generates a local ZIP without connecting to the watch or any server. */
public class FitProCreateWatchfaceActivity extends AbstractGBActivity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private FitProWatchfaceInfo info;
    private File prepared;
    private TextView status;
    private Button choose, save;
    private CheckBox clock;
    private RadioGroup palette;
    private RadioButton palette216, palette255, paletteDirect;
    private final ActivityResultLauncher<String[]> pickImage = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> { if (uri != null) convert(uri); });
    private final ActivityResultLauncher<String> saveZip = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/zip"), uri -> { if (uri != null && prepared != null) export(uri); });

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        GBDevice device = getIntent().getParcelableExtra(GBDevice.EXTRA_DEVICE);
        if (device == null) { finish(); return; }
        info = FitProWatchfaceInstallHandler.readInfo(device);
        setTitle(R.string.fitpro_create_watchface);
        LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int)(24 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        TextView help = new TextView(this); help.setText(R.string.fitpro_create_help); layout.addView(help);
        clock = new CheckBox(this); clock.setText(R.string.fitpro_show_clock);
        clock.setChecked(state == null || state.getBoolean("clock", true)); layout.addView(clock);
        TextView paletteLabel = new TextView(this); paletteLabel.setText(R.string.fitpro_palette_label); layout.addView(paletteLabel);
        palette = new RadioGroup(this);
        palette255 = new RadioButton(this); palette255.setId(View.generateViewId()); palette255.setText(R.string.fitpro_palette_255);
        palette216 = new RadioButton(this); palette216.setId(View.generateViewId()); palette216.setText(R.string.fitpro_palette_216);
        paletteDirect = new RadioButton(this); paletteDirect.setId(View.generateViewId()); paletteDirect.setText(R.string.fitpro_palette_direct);
        palette.addView(palette255); palette.addView(palette216); palette.addView(paletteDirect);
        int paletteSelection = state == null ? 255 : state.getInt("palette", 255);
        palette.check(paletteSelection == 0 ? paletteDirect.getId() : paletteSelection == 255 ? palette255.getId() : palette216.getId());
        layout.addView(palette);
        choose = new Button(this); choose.setText(R.string.fitpro_choose_image);
        choose.setOnClickListener(v -> pickImage.launch(new String[]{"image/png", "image/jpeg", "image/gif"})); layout.addView(choose);
        save = new Button(this); save.setText(R.string.fitpro_save_zip);
        save.setOnClickListener(v -> saveZip.launch("my-watchface.fitpro.zip")); layout.addView(save);
        status = new TextView(this); layout.addView(status);
        if (state != null) {
            String name = state.getString("prepared");
            if (name != null && name.startsWith("fitpro-face-") && new File(name).getName().equals(name)) {
                File cached = new File(getCacheDir(), name);
                if (cached.isFile()) prepared = cached;
            }
        }
        setBusy(false);
        clock.setOnCheckedChangeListener((button, checked) -> {
            invalidatePreparedFace();
        });
        palette.setOnCheckedChangeListener((group, checkedId) -> invalidatePreparedFace());
        try { FitProFaceEncoder.requireSupported(info); }
        catch (IllegalArgumentException e) { choose.setEnabled(false); status.setText(e.getMessage()); }
        setContentView(layout);
    }

    private void setBusy(boolean busy) {
        choose.setEnabled(!busy); clock.setEnabled(!busy); palette.setEnabled(!busy);
        palette216.setEnabled(!busy); palette255.setEnabled(!busy); paletteDirect.setEnabled(!busy); save.setEnabled(!busy && prepared != null);
    }

    private void invalidatePreparedFace() {
        if (prepared != null) { prepared.delete(); prepared = null; }
        save.setEnabled(false);
        status.setText(R.string.fitpro_choose_again);
    }

    private void convert(Uri uri) {
        boolean showClock = clock.isChecked();
        FitProFaceEncoder.PaletteMode paletteMode = palette.getCheckedRadioButtonId() == paletteDirect.getId()
                ? FitProFaceEncoder.PaletteMode.DIRECT_RGB565
                : palette.getCheckedRadioButtonId() == palette255.getId()
                        ? FitProFaceEncoder.PaletteMode.ADAPTIVE_255 : FitProFaceEncoder.PaletteMode.CUBE_216;
        setBusy(true); status.setText(R.string.fitpro_converting);
        worker.execute(() -> {
            File result = null;
            try (InputStream input = getContentResolver().openInputStream(uri)) {
                if (input == null) throw new java.io.IOException("Unable to open image");
                FitProFaceEncoder encoder = FitProImageConverter.convert(input, info, showClock, paletteMode);
                result = File.createTempFile("fitpro-face-", ".zip", getCacheDir());
                try (OutputStream output = new FileOutputStream(result)) { encoder.writeZip(output, "My offline watch face"); }
                // Exercise exactly the same integrity/compatibility checks as installation.
                try (InputStream check = new FileInputStream(result)) { new FitProWatchfaceFile(check).checkCompatible(info); }
                File complete = result;
                runOnUiThread(() -> {
                    if (isDestroyed() || isFinishing()) { complete.delete(); return; }
                    if (prepared != null) prepared.delete();
                    prepared = complete; setBusy(false); status.setText(R.string.fitpro_zip_ready);
                });
            } catch (Exception e) {
                if (result != null) result.delete();
                showError(e);
            }
        });
    }

    private void export(Uri uri) {
        File source = prepared;
        setBusy(true); status.setText(R.string.fitpro_saving);
        worker.execute(() -> {
            try (InputStream input = new FileInputStream(source); OutputStream output = getContentResolver().openOutputStream(uri)) {
                if (output == null) throw new java.io.IOException("Unable to save ZIP");
                byte[] buffer = new byte[8192]; int n;
                while ((n = input.read(buffer)) != -1) output.write(buffer, 0, n);
                output.flush();
            } catch (Exception e) {
                try { DocumentsContract.deleteDocument(getContentResolver(), uri); } catch (Exception ignored) { }
                showError(e); return;
            }
            runOnUiThread(() -> { if (!isDestroyed()) { setBusy(false); status.setText(R.string.fitpro_zip_saved); } });
        });
    }
    private void showError(Exception e) {
        runOnUiThread(() -> { if (!isDestroyed()) { setBusy(false); status.setText(getString(R.string.fitpro_conversion_failed, e.getMessage())); } });
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("clock", clock.isChecked());
        state.putInt("palette", palette.getCheckedRadioButtonId() == paletteDirect.getId() ? 0
                : palette.getCheckedRadioButtonId() == palette255.getId() ? 255 : 216);
        if (prepared != null) state.putString("prepared", prepared.getName());
    }
    @Override protected void onDestroy() {
        worker.shutdownNow();
        if (isFinishing() && prepared != null) prepared.delete();
        // Keep the package across rotations and document-picker recreation.
        super.onDestroy();
    }
}
