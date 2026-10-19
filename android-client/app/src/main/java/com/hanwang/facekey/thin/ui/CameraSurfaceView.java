package com.hanwang.facekey.thin.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.SurfaceView;

/** Camera preview surface; the preview is scaled to the detection page. */
public final class CameraSurfaceView extends SurfaceView {
    public CameraSurfaceView(Context context) {
        super(context);
    }

    public CameraSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
}
