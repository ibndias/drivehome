package com.drivehome.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/** Flat list row for dialogs: optional icon + single-line label. Pressed state via background. */
final class RowView extends View {
    private final int iconSize;
    private final int pad;
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Rect dst = new Rect();
    private final float ascent;
    private final float descent;

    private Bitmap icon;
    private boolean hasIconSlot;
    private String label = "";
    private String shown = "";

    RowView(Context c, float textSizePx) {
        super(c);
        iconSize = Ui.dp(c, 44);
        pad = Ui.dp(c, 20);
        textPaint.setTextSize(textSizePx);
        textPaint.setTypeface(Ui.regular());
        textPaint.setColor(Ui.TEXT);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        ascent = fm.ascent;
        descent = fm.descent;
        setBackground(Ui.pressable(0, Ui.CARD_PRESSED, Ui.dp(c, 12)));
    }

    /** iconSlot: reserve space for an icon even if the bitmap is null (keeps labels aligned). */
    void bind(Bitmap bitmap, boolean iconSlot, String text, int color) {
        icon = bitmap;
        hasIconSlot = iconSlot;
        label = text == null ? "" : text;
        textPaint.setColor(color);
        setContentDescription(label);
        ellipsize();
        invalidate();
    }

    private int textLeft() {
        return hasIconSlot ? pad + iconSize + pad : pad;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        ellipsize();
    }

    private void ellipsize() {
        int avail = getWidth() - textLeft() - pad;
        if (avail <= 0 || label.isEmpty()) {
            shown = label;
            return;
        }
        shown = TextUtils.ellipsize(label, textPaint, avail, TextUtils.TruncateAt.END).toString();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int h = getHeight();
        if (icon != null) {
            int t = (h - iconSize) / 2;
            dst.set(pad, t, pad + iconSize, t + iconSize);
            canvas.drawBitmap(icon, null, dst, bitmapPaint);
        }
        if (!shown.isEmpty()) {
            float baseline = h / 2f - (ascent + descent) / 2f;
            canvas.drawText(shown, textLeft(), baseline, textPaint);
        }
    }
}
