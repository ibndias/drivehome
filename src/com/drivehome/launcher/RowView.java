package com.drivehome.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/**
 * Flat row for sheets (menus and the app picker): optional icon or "remove" mark, a single-line
 * label, and an optional right-aligned note or check mark. Pressed state via background.
 */
final class RowView extends View {
    /** Height of every sheet row, in dp (the 76dp minimum touch target for cars). */
    static final int HEIGHT = Ui.TOUCH;

    private final int iconSize;
    private final int pad;
    private final int gap;
    private final int markSize;
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint notePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint platePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path check = new Path();
    private final Rect dst = new Rect();
    private final float textCenter;
    private final float noteCenter;

    private Sheet.Row row;
    private String shown = "";
    private float noteWidth;

    RowView(Context c) {
        super(c);
        iconSize = Ui.dp(c, 48);
        pad = Ui.dp(c, 20);
        gap = Ui.dp(c, 20);
        markSize = Ui.dp(c, 28);
        float size = Ui.dp(c, Ui.T_BODY);
        textPaint.setTextSize(size);
        textPaint.setTypeface(Ui.regular());
        textPaint.setColor(Ui.TEXT);
        notePaint.setTextSize(size);
        notePaint.setTypeface(Ui.regular());
        notePaint.setColor(Ui.TEXT2);
        notePaint.setTextAlign(Paint.Align.RIGHT);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        textCenter = (fm.ascent + fm.descent) / 2f;
        fm = notePaint.getFontMetrics();
        noteCenter = (fm.ascent + fm.descent) / 2f;
        platePaint.setColor(Ui.BUTTON);
        markPaint.setStyle(Paint.Style.STROKE);
        markPaint.setStrokeCap(Paint.Cap.ROUND);
        markPaint.setStrokeJoin(Paint.Join.ROUND);
        markPaint.setStrokeWidth(Ui.dp(c, 3));
        markPaint.setColor(Ui.ACCENT);
        setBackground(Ui.pressable(0, Ui.BUTTON_PRESSED, Ui.dp(c, Ui.RADIUS_SMALL)));
    }

    void bind(Sheet.Row r) {
        row = r;
        textPaint.setColor(r.color);
        noteWidth = r.note == null ? 0f : notePaint.measureText(r.note);
        setContentDescription(r.note == null ? r.label : r.label + ", " + r.note);
        fit();
        invalidate();
    }

    private boolean hasLead() {
        return row != null && (row.icon != null || row.iconSlot || row.remove);
    }

    private int textLeft() {
        return hasLead() ? pad + iconSize + gap : pad;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fit();
        // Check mark, right-aligned and vertically centred (24-unit grid scaled to markSize).
        check.reset();
        float s = markSize / 24f;
        float ox = w - pad - markSize;
        float oy = (h - markSize) / 2f;
        check.moveTo(ox + 4f * s, oy + 12.5f * s);
        check.lineTo(ox + 9.5f * s, oy + 18f * s);
        check.lineTo(ox + 20f * s, oy + 6.5f * s);
    }

    private void fit() {
        if (row == null) return;
        float trailing = row.check ? markSize + gap : (noteWidth > 0f ? noteWidth + gap : 0f);
        float avail = getWidth() - textLeft() - pad - trailing;
        if (avail <= 0f || row.label.isEmpty()) {
            shown = row.label;
            return;
        }
        shown = TextUtils.ellipsize(row.label, textPaint, avail, TextUtils.TruncateAt.END).toString();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Sheet.Row r = row;
        if (r == null) return;
        int w = getWidth();
        int h = getHeight();
        if (r.icon != null) {
            int t = (h - iconSize) / 2;
            dst.set(pad, t, pad + iconSize, t + iconSize);
            canvas.drawBitmap(r.icon, null, dst, bitmapPaint);
        } else if (r.remove) {
            float cx = pad + iconSize / 2f;
            float cy = h / 2f;
            canvas.drawCircle(cx, cy, iconSize / 2f, platePaint);
            float arm = iconSize * 0.2f;
            canvas.drawLine(cx - arm, cy, cx + arm, cy, markPaint);
        }
        if (!shown.isEmpty()) {
            canvas.drawText(shown, textLeft(), h / 2f - textCenter, textPaint);
        }
        if (r.check) {
            canvas.drawPath(check, markPaint);
        } else if (r.note != null) {
            canvas.drawText(r.note, w - pad, h / 2f - noteCenter, notePaint);
        }
    }
}
