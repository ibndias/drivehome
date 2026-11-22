package com.drivehome.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.view.View;

/**
 * Draws simple vector glyphs (media controls, close, check, plus/minus, dots, note) with a Path, so
 * nothing depends on font coverage of symbol code points. Can alternatively show a bitmap (used for
 * the media app icon). Glyph geometry is defined on a 24x24 grid and scaled to glyphSize, centred in
 * the view. The path is rebuilt only when the type or the view size changes.
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
    static final int CHECK = 9;
    static final int PLUS = 10;
    static final int MINUS = 11;

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint soften = new Paint(Paint.ANTI_ALIAS_FLAG);
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
        // Filled glyphs also get a thin round-joined outline so their corners are slightly soft.
        // (Drawn as a second pass: FILL_AND_STROKE hollows out contours of mixed winding.)
        soften.setStyle(Paint.Style.STROKE);
        soften.setStrokeJoin(Paint.Join.ROUND);
        soften.setColor(color);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
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

    private static boolean isLine(int type) {
        return type == CLOSE || type == CHECK || type == PLUS || type == MINUS;
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
        soften.setStrokeWidth(1.2f * sx);
        switch (type) {
            case PLAY:
                tri(8.5f, 5.5f, 19f, 12f, 8.5f, 18.5f);
                break;
            case PAUSE:
                rect(6.5f, 5.5f, 9.5f, 18.5f);
                rect(14.5f, 5.5f, 17.5f, 18.5f);
                break;
            case PREV:
                rect(5.5f, 6.5f, 7f, 17.5f);
                tri(18.5f, 6.5f, 9.5f, 12f, 18.5f, 17.5f);
                break;
            case NEXT:
                tri(5.5f, 6.5f, 14.5f, 12f, 5.5f, 17.5f);
                rect(17f, 6.5f, 18.5f, 17.5f);
                break;
            case PLAY_PAUSE:
                tri(3f, 6.5f, 11f, 12f, 3f, 17.5f);
                rect(14.5f, 6.5f, 16.1f, 17.5f);
                rect(19.5f, 6.5f, 21.1f, 17.5f);
                break;
            case CLOSE:
                path.moveTo(x(6f), y(6f));
                path.lineTo(x(18f), y(18f));
                path.moveTo(x(18f), y(6f));
                path.lineTo(x(6f), y(18f));
                break;
            case CHECK:
                path.moveTo(x(5f), y(12.5f));
                path.lineTo(x(10f), y(17.5f));
                path.lineTo(x(19.5f), y(7f));
                break;
            case PLUS:
                path.moveTo(x(12f), y(5f));
                path.lineTo(x(12f), y(19f));
                path.moveTo(x(5f), y(12f));
                path.lineTo(x(19f), y(12f));
                break;
            case MINUS:
                path.moveTo(x(6f), y(12f));
                path.lineTo(x(18f), y(12f));
                break;
            case DOTS:
                for (int r = 0; r < 3; r++) {
                    for (int c = 0; c < 3; c++) {
                        path.addCircle(x(5f + 7f * c), y(5f + 7f * r), 1.8f * sx, Path.Direction.CW);
                    }
                }
                break;
            case NOTE:
                path.addCircle(x(8.5f), y(17f), 3.2f * sx, Path.Direction.CW);
                rect(10.6f, 4.5f, 11.8f, 17f);
                path.moveTo(x(11.8f), y(4.5f));
                path.lineTo(x(18.5f), y(7f));
                path.lineTo(x(18.5f), y(10f));
                path.lineTo(x(11.8f), y(7.5f));
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
        if (isLine(type)) {
            canvas.drawPath(path, stroke);
        } else {
            canvas.drawPath(path, fill);
            canvas.drawPath(path, soften);
        }
    }
}
