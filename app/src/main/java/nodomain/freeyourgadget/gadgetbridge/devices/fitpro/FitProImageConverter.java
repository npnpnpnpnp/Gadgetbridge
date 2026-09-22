/* SPDX-License-Identifier: AGPL-3.0-or-later */
package nodomain.freeyourgadget.gadgetbridge.devices.fitpro;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Movie;
import android.graphics.Paint;
import android.graphics.Rect;
import androidx.exifinterface.media.ExifInterface;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Local-only image decoding, centre crop and face encoding. Call on a worker thread. */
public final class FitProImageConverter {
    private FitProImageConverter() { }

    public static FitProFaceEncoder convert(InputStream input, FitProWatchfaceInfo info, boolean clock,
                                             FitProFaceEncoder.PaletteMode paletteMode) throws IOException {
        FitProFaceEncoder.requireSupported(info);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] block = new byte[8192]; int n;
        while ((n = input.read(block)) != -1) {
            if (buffer.size() + n > 12 * 1024 * 1024) throw new IOException("Choose an image smaller than 12 MiB");
            buffer.write(block, 0, n);
        }
        byte[] bytes = buffer.toByteArray();
        boolean gif = bytes.length >= 6 && bytes[0]=='G' && bytes[1]=='I' && bytes[2]=='F';
        if (gif) return convertGif(bytes, info, clock, paletteMode);
        BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0 || (long) options.outWidth * options.outHeight > 100_000_000) throw new IOException("Image cannot be decoded or is too large");
        options.inJustDecodeBounds = false;
        options.inSampleSize = 1;
        while (options.outWidth / options.inSampleSize > 1024 || options.outHeight / options.inSampleSize > 1024) options.inSampleSize *= 2;
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (decoded == null) throw new IOException("Image cannot be decoded");
        try {
            ExifInterface exif = new ExifInterface(new ByteArrayInputStream(bytes));
            Matrix orientation = new Matrix();
            if (exif.isFlipped()) orientation.postScale(-1, 1);
            orientation.postRotate(exif.getRotationDegrees());
            Bitmap oriented = Bitmap.createBitmap(decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), orientation, true);
            if (oriented != decoded) { decoded.recycle(); decoded = oriented; }
            FitProFaceEncoder encoder = new FitProFaceEncoder(info, 10, clock, paletteMode);
            Bitmap frame = Bitmap.createBitmap(info.width, info.height, Bitmap.Config.ARGB_8888);
            try {
                Canvas canvas = new Canvas(frame); canvas.drawColor(Color.BLACK);
                int w = decoded.getWidth(), h = decoded.getHeight();
                float scale = Math.max((float)info.width / w, (float)info.height / h);
                int cropWidth = Math.min(w, Math.round(info.width / scale));
                int cropHeight = Math.min(h, Math.round(info.height / scale));
                Rect crop = new Rect((w-cropWidth)/2, (h-cropHeight)/2, (w+cropWidth)/2, (h+cropHeight)/2);
                canvas.drawBitmap(decoded, crop, new Rect(0, 0, info.width, info.height), new Paint(Paint.FILTER_BITMAP_FLAG));
                addFrame(encoder, frame);
            } finally { frame.recycle(); }
            return encoder;
        } finally { decoded.recycle(); }
    }

    @SuppressWarnings("deprecation") // Movie provides offline GIF decoding/compositing on supported Android versions.
    private static FitProFaceEncoder convertGif(byte[] bytes, FitProWatchfaceInfo info, boolean clock,
                                                FitProFaceEncoder.PaletteMode paletteMode) throws IOException {
        FitProGifInfo gif = new FitProGifInfo(bytes);
        Movie movie = Movie.decodeByteArray(bytes, 0, bytes.length);
        if (movie == null || movie.width() != gif.width || movie.height() != gif.height) throw new IOException("GIF cannot be decoded");
        int duration = movie.duration();
        if (duration <= 0) duration = gif.durationMs;
        if (duration > 30_000) throw new IOException("Use a GIF no longer than 30 seconds");
        // At most 80 frames and 10 fps. Duration field is inferred as centiseconds;
        // relative motion is preserved approximately, exact watch timing is unverified.
        int interval = Math.max(100, ((duration + FitProFaceEncoder.MAX_FRAMES * 10 - 1) / (FitProFaceEncoder.MAX_FRAMES * 10)) * 10);
        FitProFaceEncoder encoder = new FitProFaceEncoder(info, interval / 10, clock, paletteMode);
        Bitmap frame = Bitmap.createBitmap(info.width, info.height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(frame);
        float scale = Math.max((float)info.width / gif.width, (float)info.height / gif.height);
        try {
            for (int time = 0; time < duration; time += interval) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Conversion cancelled");
                canvas.drawColor(Color.BLACK);
                movie.setTime(time);
                canvas.save();
                canvas.translate((info.width - gif.width*scale)/2, (info.height - gif.height*scale)/2);
                canvas.scale(scale, scale); movie.draw(canvas, 0, 0); canvas.restore();
                addFrame(encoder, frame);
                if (gif.frames == 1) break;
            }
        } finally { frame.recycle(); }
        return encoder;
    }

    private static void addFrame(FitProFaceEncoder encoder, Bitmap frame) {
        int[] pixels = new int[frame.getWidth() * frame.getHeight()];
        frame.getPixels(pixels, 0, frame.getWidth(), 0, 0, frame.getWidth(), frame.getHeight());
        encoder.addFrame(pixels);
    }
}
