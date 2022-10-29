/*
 *  Copyright (C) Ivan Gorinov, 2017
 *  SPDX-License-Identifier: Apache-2.0
 */

package info.altimeter.variometer.common;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.Paint.Align;
import android.graphics.Paint.FontMetrics;
import android.graphics.Paint.Style;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.WindowManager;

public class VerticalSpeedIndicator extends View
{
	Bitmap dialPlate = null;
	Bitmap dialArrow = null;
	DisplayMetrics metrics;
	int side = 320;
	float scale = 1f;
	int headFillColor = Color.rgb(238, 238, 238);
	int plateColor = Color.rgb(51, 51, 51);
	int frameColor = Color.rgb(68, 68, 68);
	int tailFillColor = Color.rgb(68, 68, 68);
	int tailStrokeColor = Color.rgb(85, 85, 85);
	float unit = 1f;
	float unitR = 1f;
	int scaleLimit = 5;
	float scaleLimitR = 1f / scaleLimit;
	float vspeed = Float.NaN;
	String typeName = "";
	String unitName = "";

	public VerticalSpeedIndicator(Context context) {
		this(context, null);
	}

	public VerticalSpeedIndicator(Context context, AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public VerticalSpeedIndicator(Context context, AttributeSet attrs, int defView) {
		super(context, attrs, defView);

		WindowManager wm;
		metrics = new DisplayMetrics();
		wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
		wm.getDefaultDisplay().getMetrics(metrics);
	}

	@Override
	protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
		int widthMode = MeasureSpec.getMode(widthMeasureSpec);
		int widthSize = MeasureSpec.getSize(widthMeasureSpec);
		int heightMode = MeasureSpec.getMode(heightMeasureSpec);
		int heightSize = MeasureSpec.getSize(heightMeasureSpec);

		//  Request largest possible square (width = height), assuming square pixels
		int maxSide = Math.min(metrics.widthPixels, metrics.heightPixels);

		switch (widthMode) {
		case MeasureSpec.UNSPECIFIED:
			widthSize = maxSide;
			break;

		case MeasureSpec.AT_MOST:
			if (widthSize > maxSide) {
				widthSize = maxSide;
			}
			break;
		}

		switch (heightMode) {
		case MeasureSpec.UNSPECIFIED:
			heightSize = maxSide;
			break;

		case MeasureSpec.AT_MOST:
			if (heightSize > maxSide) {
				heightSize = maxSide;
			}
			break;
		}

		setMeasuredDimension(widthSize, heightSize);
	}

	@Override
	protected void onDraw(Canvas canvas) {
		Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		paint.setFilterBitmap(true);

		float cx = getWidth() / 2;
		float cy = getHeight() / 2;
		float indication = vspeed * unitR;

		canvas.drawBitmap(dialPlate, cx - dialPlate.getWidth() / 2, cy - dialPlate.getHeight() / 2, paint);

		if (!Float.isNaN(vspeed)) {
			canvas.save();
			canvas.rotate((float) (indication * 180f * scaleLimitR), cx, cy);
			canvas.drawBitmap(dialArrow, cx - dialArrow.getWidth() / 2, cy - dialArrow.getHeight() / 2, paint);
			canvas.restore();
		}
	}

	@Override
	protected void onSizeChanged(int width, int height, int w0, int h0) {
		Canvas canvas = null;
		Paint paint = null;
		Path tailPath, headPath;
		Matrix mmm = new Matrix();
		FontMetrics metrics;
		int cx, cy;
		float textMiddle;
		float tx, ty;
		int i;

		if (width * height == 0) {
			return;
		}

		if (height > width) {
			side = width;
		} else {
			side = height;
		}
		cx = side / 2;
		cy = side / 2;

		if (side == 320) {
			scale = 1f;
		} else {
			scale = side / 320f;
		}

		if (dialPlate != null) {
			dialPlate.recycle();
		}

		if (dialArrow != null) {
			dialArrow.recycle();
		}

		dialPlate = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
		dialArrow = Bitmap.createBitmap(side, side / 4, Bitmap.Config.ARGB_8888);

		canvas = new Canvas(dialPlate);
		paint = new Paint(Paint.ANTI_ALIAS_FLAG);
		paint.setStyle(Style.FILL_AND_STROKE);
		paint.setColor(Color.BLACK);
		canvas.drawRect(0, 0, width, height, paint);
		if (scale != 1) {
			mmm.setScale(scale, scale, cx, cy);
		}
		canvas.setMatrix(mmm);
		paint.setColor(frameColor);
		canvas.drawCircle(cx, cy, 154, paint);
		paint.setColor(plateColor);
		canvas.drawCircle(cx, cy, 152, paint);

		paint.setStyle(Style.FILL_AND_STROKE);
		paint.setStrokeWidth(1);

		paint.setStyle(Style.FILL);

		paint.setTypeface(Typeface.SANS_SERIF);
		paint.setTextAlign(Align.CENTER);
		paint.setColor(Color.rgb(192, 192, 192));

		paint.setTextSize(16);

		if (scale != 1) {
			mmm.postScale(scale, scale, cx, cy);
		}
		canvas.setMatrix(mmm);

		/*
		 *  Numbers on the scale
		 */

		mmm.reset();
		if (scale != 1) {
			mmm.setScale(scale, scale, cx, cy);
		}
		canvas.setMatrix(mmm);

		paint.setTypeface(Typeface.MONOSPACE);
		paint.setTextSize(24);
		metrics = new FontMetrics();
		paint.getFontMetrics(metrics);
		textMiddle = (metrics.ascent + metrics.descent) / 2;

		int step = 1;
        int subdivs = 10;
        if (scaleLimit > 6) {
            step = 1;
            subdivs = 5;
        }
		if (scaleLimit > 8) {
			step = 2;
			subdivs = 10;
		}
		if (scaleLimit > 12) {
			subdivs = 4;
		}
		if (scaleLimit > 16) {
			step = 5;
			subdivs = 5;
		}
        if (scaleLimit > 36) {
            step = 10;
        }
        if (scaleLimit > 72) {
            step = 20;
        }

		paint.setColor(Color.WHITE);
		for (i = 0; i <= scaleLimit - 1; i += step) {
			tx = cx - 100 * (float) Math.cos((double) i * Math.PI * scaleLimitR);
			ty = 100 * (float) Math.sin((double) i * Math.PI * scaleLimitR);
			canvas.drawText(Integer.toString(Math.abs(i)), tx, cy - ty - textMiddle, paint);
			canvas.drawText(Integer.toString(Math.abs(i)), tx, cy + ty - textMiddle, paint);
		}

		String text;

		paint.setTypeface(Typeface.SANS_SERIF);
		ty = cy + 48;
		paint.setTextSize(16);
		paint.getFontMetrics(metrics);
		textMiddle = (metrics.ascent + metrics.descent) / 2;
		canvas.drawText(unitName, cx, ty - textMiddle, paint);
		if (typeName != null) {
			canvas.drawText(typeName, cx, cy - 48 - textMiddle, paint);
		}

		paint.setTextSize(12);
		paint.getFontMetrics(metrics);
		textMiddle = (metrics.ascent + metrics.descent) / 2;
		text = getResources().getString(R.string.climb);
		canvas.drawText(text, cx - 48, cy - 24 - textMiddle, paint);
		text = getResources().getString(R.string.descent);
		canvas.drawText(text, cx - 48, cy + 24 - textMiddle, paint);
		canvas.drawLine(cx - 32, cy, cx - 64, cy, paint);

		/*
		 *  Dial scale ticks
		 */

        int m = scaleLimit * subdivs / step - 1;
		for (i = - m; i <= m; i += 1) {
			mmm.setRotate(i * 180f * step * scaleLimitR / subdivs + 270f, cx, cy);
			if (scale != 1) {
				mmm.postScale(scale, scale, cx, cy);
			}
			canvas.setMatrix(mmm);
			if (i % subdivs == 0) {
				paint.setStrokeWidth(2.5f);
				canvas.drawLine(cx, cy - 140, cx, cy - 120, paint);
			} else {
				paint.setStrokeWidth(1.5f);
				if ((i * 2) % subdivs == 0) {
					canvas.drawLine(cx, cy - 140, cx, cy - 125, paint);
				} else {
					canvas.drawLine(cx, cy - 140, cx, cy - 130, paint);
				}
			}
		}

		/*
		 *  Prepare hand headPath
		 */


		tailPath = new Path();
		headPath = new Path();
		paint = new Paint(Paint.ANTI_ALIAS_FLAG);

		headPath.reset();
		headPath.moveTo(35, 40);
		headPath.lineTo(55, 45);
		headPath.lineTo(160, 45);
		headPath.lineTo(160, 35);
		headPath.lineTo(55, 35);
		headPath.lineTo(35, 40);
		headPath.close();

        tailPath.reset();
        tailPath.moveTo(35, 40);
        tailPath.lineTo(55, 35);
        tailPath.lineTo(140, 35);
        tailPath.lineTo(200, 35);
        tailPath.arcTo(new RectF(200, 30, 220, 50), -150, 300, false);
        tailPath.lineTo(200, 45);
        tailPath.lineTo(140, 45);
        tailPath.lineTo(55, 45);
        tailPath.lineTo(35, 40);
        tailPath.close();

        canvas = new Canvas(dialArrow);

		if (scale != 1) {
			mmm.setScale(scale, scale, 0, 0);
		}
		canvas.setMatrix(mmm);

		canvas.save();
		paint.setStrokeWidth(0.5f);

		paint.setStyle(Style.FILL);
		paint.setColor(tailFillColor);
		canvas.drawPath(tailPath, paint);

		float r = 40;
		int[] colors = { Color.TRANSPARENT, headFillColor };
		float[] points = { (r - 1) / r, 1 };
        paint.setStyle(Style.FILL);
        paint.setColor(headFillColor);
        paint.setShader(new RadialGradient(160, 40, r, colors, points, Shader.TileMode.CLAMP));
        canvas.drawPath(headPath, paint);
        paint.setShader(null);

        paint.setStyle(Style.STROKE);
        paint.setColor(tailStrokeColor);
        canvas.drawPath(tailPath, paint);

        paint.setStyle(Style.FILL);
		paint.setColor(tailFillColor);
		canvas.drawCircle(160, 40, 10.0f, paint);
		paint.setStyle(Style.STROKE);
		paint.setColor(tailStrokeColor);
		canvas.drawCircle(160, 40, 10.0f, paint);

		paint.setStyle(Style.FILL);
		paint.setColor(plateColor);
		canvas.drawCircle(210, 40, 1.0f, paint);
		paint.setColor(tailStrokeColor);
		canvas.drawCircle(160, 40, 2.0f, paint);

		canvas.restore();
	}

	public void setTypeName(String name) {
		typeName = name;
	}

	public void setUnit(int limit, float newUnit, String newUnitName)
	{
		scaleLimit = limit;
		scaleLimitR = 1.0f / limit;
		unit = newUnit;
		unitR = 1.0f / unit;
		unitName = newUnitName;
		onSizeChanged(getWidth(), getHeight(), getWidth(), getHeight());
		postInvalidate();
	}
	
	public void setVSpeed(float speed) {
		vspeed = speed;
		// postInvalidate();
	}
}
