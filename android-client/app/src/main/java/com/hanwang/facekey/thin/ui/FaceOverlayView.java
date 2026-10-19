package com.hanwang.facekey.thin.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** Draws the same green corner brackets as the source APK. */
public final class FaceOverlayView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int[] facePos;
    private int sourceWidth;
    private int sourceHeight;

    public FaceOverlayView(Context context) {
        super(context);
        paint.setColor(Color.GREEN);
        paint.setStrokeWidth(3f);
        paint.setStyle(Paint.Style.STROKE);
    }

    public void setFace(int[] facePos, int width, int height) {
        this.facePos = facePos == null ? null : facePos.clone();
        this.sourceWidth = width;
        this.sourceHeight = height;
        postInvalidate();
    }

    public void clearFace() {
        this.facePos = null;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (facePos == null || facePos.length < 4 || sourceWidth <= 0 || sourceHeight <= 0) return;
        int canvasWidth = getWidth();
        int canvasHeight = getHeight();
        int left1 = (int) ((facePos[2] / (float) sourceWidth) * canvasWidth);
        int right1 = (int) ((facePos[3] / (float) sourceWidth) * canvasWidth);
        int left = canvasWidth - right1;
        int right = canvasWidth - left1;
        int top = (int) ((facePos[0] / (float) sourceHeight) * canvasHeight);
        int bottom = (int) ((facePos[1] / (float) sourceHeight) * canvasHeight);
        int arm = Math.max(18, Math.min(42, Math.min(right - left, bottom - top) / 4));

        canvas.drawLine(left, top, left + arm, top, paint);
        canvas.drawLine(left, top, left, top + arm, paint);
        canvas.drawLine(right - arm, top, right, top, paint);
        canvas.drawLine(right, top, right, top + arm, paint);
        canvas.drawLine(left, bottom - arm, left, bottom, paint);
        canvas.drawLine(left, bottom, left + arm, bottom, paint);
        canvas.drawLine(right, bottom - arm, right, bottom, paint);
        canvas.drawLine(right - arm, bottom, right, bottom, paint);
    }
}
