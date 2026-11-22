package com.drivehome.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.View;

/**
 * Draws simple vector glyphs (media controls, close, dots, note) with a Path, so nothing depends on
 * font coverage of symbol code points. Can alternatively show a bitmap (used for the media app icon).
 * Glyph geometry is defined on a 24x24 grid and scaled to glyphSize, centred in the view.
 */
final class GlyphView extends View {
    static final int PLAY = 1;
    static final int PAUSE = 2;
    static final int PREV = 3;
    static final int NEXT = 4;
    static final int CLOSE = 5;
    static final int DOTS = 6;
    static final int NOTE = 7;
    static final int PLAY_PAUSE = 8;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint plate = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Path path = new Path();
    private final Rect dst = new Rect();
    private final int glyphSize;

    private int type;
    private Bitmap bitmap;
    private int plateColor;
    private float plateRadius;

    // Scratch for path building.
    private float sx;
    private float ox;
    private float oy;

    GlyphView(Context c, int type, int glyphSizePx, int color) {
        super(c);
        this.type = type;
        this.glyphSize = glyphSizePx;
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setColor(color);
    }

    void setType(int t) {
        if (t == type) return;
        type = t;
        buildPath();
        invalidate();
    }

    int getType() {
        return type;
    }

    /** When non-null the bitmap is drawn instead of the glyph (and the plate). */
    void setBitmap(Bitmap b) {
        if (b == bitmap) return;
        bitmap = b;
        invalidate();
    }

    /** Optional rounded square drawn behind the glyph (not behind a bitmap). */
    void setPlate(int color, float radius) {
        plateColor = color;
        plateRadius = radius;
        plate.setColor(color);
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        buildPath();
    }

    private void buildPath() {
        path.reset();
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        sx = glyphSize / 24f;
        ox = (w - glyphSize) / 2f;
        oy = (h - glyphSize) / 2f;
        stroke.setStrokeWidth(2.4f * sx);
        switch (type) {
            case PLAY:
                tri(8f, 5f, 19.5f, 12f, 8f, 19f);
                break;
            case PAUSE:
                rect(6f, 5f, 10f, 19f);
                rect(14f, 5f, 18f, 19f);
                break;
            case PREV:
                rect(5f, 6f, 7.5f, 18f);
                tri(19f, 6f, 8.5f, 12f, 19f, 18f);
                break;
            case NEXT:
                tri(5f, 6f, 15.5f, 12f, 5f, 18f);
                rect(16.5f, 6f, 19f, 18f);
                break;
            case PLAY_PAUSE:
                tri(2.5f, 6f, 11.5f, 12f, 2.5f, 18f);
                rect(14f, 6f, 16.6f, 18f);
                rect(19f, 6f, 21.6f, 18f);
                break;
            case CLOSE:
                path.moveTo(x(6f), y(6f));
                path.lineTo(x(18f), y(18f));
                path.moveTo(x(18f), y(6f));
                path.lineTo(x(6f), y(18f));
                break;
            case DOTS:
                for (int r = 0; r < 3; r++) {
                    for (int c = 0; c < 3; c++) {
                        path.addCircle(x(5f + 7f * c), y(5f + 7f * r), 2.2f * sx, Path.Direction.CW);
                    }
                }
                break;
            case NOTE:
                path.addCircle(x(8.5f), y(17f), 3.6f * sx, Path.Direction.CW);
                rect(10.3f, 4f, 12.1f, 17f);
                path.moveTo(x(12.1f), y(4f));
                path.lineTo(x(19f), y(6.6f));
                path.lineTo(x(19f), y(10.2f));
                path.lineTo(x(12.1f), y(7.6f));
                path.close();
                break;
            default:
                break;
        }
    }

    private float x(float v) {
        return ox + v * sx;
    }

    private float y(float v) {
        return oy + v * sx;
    }

    private void rect(float l, float t, float r, float b) {
        path.addRect(x(l), y(t), x(r), y(b), Path.Direction.CW);
    }

    private void tri(float x1, float y1, float x2, float y2, float x3, float y3) {
        path.moveTo(x(x1), y(y1));
        path.lineTo(x(x2), y(y2));
        path.lineTo(x(x3), y(y3));
        path.close();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (bitmap != null) {
            int size = Math.min(w, h);
            int l = (w - size) / 2;
            int t = (h - size) / 2;
            dst.set(l, t, l + size, t + size);
            canvas.drawBitmap(bitmap, null, dst, bitmapPaint);
            return;
        }
        if (plateColor != 0) {
            canvas.drawRoundRect(0f, 0f, w, h, plateRadius, plateRadius, plate);
        }
        canvas.drawPath(path, type == CLOSE ? stroke : fill);
    }
}
