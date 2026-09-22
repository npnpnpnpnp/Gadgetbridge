/* SPDX-License-Identifier: AGPL-3.0-or-later */
package nodomain.freeyourgadget.gadgetbridge.activities;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Movie;
import android.graphics.Rect;
import android.view.View;

/** Displays catalogue PNGs and animates GIF previews without an image-loading dependency. */
public class FitProPreviewView extends View {
    private Bitmap bitmap;
    private Movie gif;
    private long started;
    private boolean cropToFill = true;
    private int aspectWidth;
    private int aspectHeight;

    public FitProPreviewView(Context context) {
        super(context);
        setBackgroundResource(android.R.drawable.btn_default);
    }

    public void setImage(byte[] data) {
        gif = isGif(data) ? Movie.decodeByteArray(data, 0, data.length) : null;
        bitmap = gif == null ? BitmapFactory.decodeByteArray(data, 0, data.length) : null;
        started = 0;
        invalidate();
    }

    /** Uses the source aspect ratio without cropping, for the enlarged face view. */
    public void setCropToFill(boolean cropToFill) {
        this.cropToFill = cropToFill;
        requestLayout();
        invalidate();
    }

    public void setAspectRatio(int width, int height) {
        aspectWidth = width;
        aspectHeight = height;
        requestLayout();
    }

    private static boolean isGif(byte[] data) {
        return data.length >= 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F'
                && data[3] == '8' && (data[4] == '7' || data[4] == '9') && data[5] == 'a';
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int sourceWidth = gif != null ? gif.width() : bitmap != null ? bitmap.getWidth() : 0;
        int sourceHeight = gif != null ? gif.height() : bitmap != null ? bitmap.getHeight() : 0;
        if (sourceWidth <= 0 || sourceHeight <= 0) return;
        float horizontalScale = (float) getWidth() / sourceWidth;
        float verticalScale = (float) getHeight() / sourceHeight;
        float scale = cropToFill ? Math.max(horizontalScale, verticalScale) : Math.min(horizontalScale, verticalScale);
        int width = Math.round(sourceWidth * scale), height = Math.round(sourceHeight * scale);
        int left = (getWidth() - width) / 2, top = (getHeight() - height) / 2;
        if (gif != null) {
            long now = android.os.SystemClock.uptimeMillis();
            if (started == 0) started = now;
            int duration = gif.duration();
            gif.setTime((int) ((now - started) % (duration > 0 ? duration : 1000)));
            canvas.save();
            canvas.translate(left, top);
            canvas.scale(scale, scale);
            gif.draw(canvas, 0, 0);
            canvas.restore();
            postInvalidateDelayed(16);
        } else {
            canvas.drawBitmap(bitmap, null, new Rect(left, top, left + width, top + height), null);
        }
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (aspectWidth > 0 && aspectHeight > 0 && width > 0) {
            setMeasuredDimension(width, Math.round(width * (float) aspectHeight / aspectWidth));
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }
}
