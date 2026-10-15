package com.example.bledobwidget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.MotionEvent;
import android.view.View;

/** HSV color wheel with direct-touch hue and saturation, no external dependencies. */
public final class ColorWheelView extends View {
    interface OnColorChangeListener { void onColorChanged(int rgb, boolean finalChange); }

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float hue = 187f;
    private float saturation = 0.82f;
    private float radius;
    private float centerX;
    private float centerY;
    private OnColorChangeListener listener;

    public ColorWheelView(Context ctx) {
        super(ctx);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        white.setStyle(Paint.Style.FILL);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(dp(3));
        ring.setColor(Color.WHITE);
        setContentDescription("Цветовой круг: проведи пальцем для выбора цвета");
    }

    void setListener(OnColorChangeListener listener) { this.listener = listener; }

    void selectColor(int argb) {
        float[] hsv = new float[3];
        Color.colorToHSV(argb, hsv);
        hue = hsv[0];
        saturation = hsv[1];
        invalidate();
    }

    int getColor() { return Color.HSVToColor(new float[]{hue, saturation, 1f}); }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        centerX = w / 2f;
        centerY = h / 2f;
        radius = Math.max(1, Math.min(w, h) / 2f - dp(17));
        int[] spectrum = new int[13];
        for (int i = 0; i < spectrum.length; i++)
            spectrum[i] = Color.HSVToColor(new float[]{(i % 12) * 30f, 1f, 1f});
        paint.setShader(new SweepGradient(centerX, centerY, spectrum, null));
        white.setShader(new RadialGradient(centerX, centerY, radius,
                new int[]{Color.WHITE, 0x00ffffff}, new float[]{0f, 1f}, Shader.TileMode.CLAMP));
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (radius <= 0) return;
        canvas.drawCircle(centerX, centerY, radius + dp(6), fill(0xff232c3d));
        canvas.drawCircle(centerX, centerY, radius, paint);
        canvas.drawCircle(centerX, centerY, radius, white);
        float a = (float)Math.toRadians(hue);
        float x = centerX + (float)Math.cos(a) * saturation * radius;
        float y = centerY + (float)Math.sin(a) * saturation * radius;
        canvas.drawCircle(x, y, dp(13), ring);
        canvas.drawCircle(x, y, dp(9.5f), fill(getColor()));
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                float dx = event.getX() - centerX;
                float dy = event.getY() - centerY;
                hue = (float)Math.toDegrees(Math.atan2(dy, dx));
                if (hue < 0) hue += 360f;
                saturation = Math.min(1f, (float)Math.sqrt(dx * dx + dy * dy) / radius);
                invalidate();
                if (listener != null) listener.onColorChanged(getColor(), event.getActionMasked() == MotionEvent.ACTION_UP);
                return true;
            case MotionEvent.ACTION_CANCEL:
                return true;
            default: return true;
        }
    }

    private Paint fill(int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(color);
        return p;
    }
    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
}
