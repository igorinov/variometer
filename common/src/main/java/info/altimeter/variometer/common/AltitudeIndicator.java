package info.altimeter.variometer.common;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

public class AltitudeIndicator extends View {

    Paint drawPaint = null;
    Bitmap singleDigitTape = null;
    Bitmap doubleDigitTape = null;

    Rect src = new Rect();
    Rect dst = new Rect();

    int singleDigitHeight = 0;

    int viewWidth = 0;

    float reading = 12345;
    public AltitudeIndicator(Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    protected void onSizeChanged(int width, int height, int w0, int h0) {
        if (null == drawPaint) {
            drawPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        }

        viewWidth = width;

        String digits = "0123456789";
        Rect bounds = new Rect();
        drawPaint.getTextBounds(digits, 0, 1, bounds);
        singleDigitHeight = bounds.height();
        singleDigitTape = Bitmap.createBitmap(width, singleDigitHeight * 10, Bitmap.Config.ARGB_8888);
        doubleDigitTape = Bitmap.createBitmap(width, singleDigitHeight * 10, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(singleDigitTape);
        for (int i = 0; i < 10; i += 1) {
            canvas.drawText(digits.substring(i, i + 1), 0, (9 - i) * singleDigitHeight, drawPaint);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Paint paint = drawPaint;
        if (null == paint) {
            return;
        }
        paint.setFilterBitmap(true);

        int d = Math.round(reading * singleDigitHeight);
        int q = singleDigitHeight * 10;

        int off = d % q;

        src.left = 0;
        src.right = singleDigitTape.getWidth();
        src.top = singleDigitHeight * 9 - off;
        src.bottom = singleDigitHeight * 10 - off;

        dst.top = 0;
        dst.bottom = singleDigitHeight;
        dst.left = viewWidth / 2;
        dst.right = dst.left + singleDigitTape.getWidth();

        if (off > singleDigitHeight * 9) {
            src.top = 0;
            dst.top = off - singleDigitHeight * 9;
            dst.bottom = singleDigitHeight;
            canvas.drawBitmap(singleDigitTape, src, dst, paint);

            src.top = singleDigitHeight * 10 - off;
            src.bottom = singleDigitHeight * 10;
            dst.top = 0;
            dst.bottom = off - singleDigitHeight * 9;
            canvas.drawBitmap(singleDigitTape, src, dst, paint);
        } else {
            canvas.drawBitmap(singleDigitTape, src, dst, paint);
        }

        canvas.drawBitmap(singleDigitTape, src, dst, paint);

    }
}
