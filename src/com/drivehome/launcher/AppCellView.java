package com.drivehome.launcher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/**
 * One flat view = icon centred above a single-line ellipsized label. Used for the dock, the drawer
 * cells and the slot picker (it is its own view holder). Draws a pre-scaled bitmap 1:1 and a
 * pre-ellipsized string, so binding and drawing are allocation-free. The pressed state comes from
 * the background drawable.
 */
final class AppCellView extends View {
    private final int iconSize;
    private final int gap;
    private final int hPad;
    private final boolean useTileIcon;
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint markPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect dst = new Rect();
    private final float ascent;
    private final float descent;

    private AppEntry entry;
    private Bitmap icon;
    private String label = "";
    private String shown = "";
    private boolean allApps;
    /** Non-null: an unassigned slot is drawn as "+" with this label (instead of staying blank). */
    private String emptyLabel;

    AppCellView(Context c, int iconSizePx, float textSizePx, Typeface tf, boolean useTileIcon) {
        super(c);
        this.iconSize = iconSizePx;
        this.useTileIcon = useTileIcon;
        this.gap = Ui.dp(c, 10);
        this.hPad = Ui.dp(c, 10);
        textPaint.setTextSize(textSizePx);
        textPaint.setTypeface(tf);
        textPaint.setColor(Ui.TEXT);
        textPaint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        ascent = fm.ascent;
        descent = fm.descent;
        markPaint.setStrokeCap(Paint.Cap.ROUND);
        markPaint.setStrokeWidth(Math.max(2f, iconSizePx / 16f));
    }

    void bind(AppEntry e) {
        Bitmap b = e == null ? null : (useTileIcon ? e.tileIcon : e.gridIcon);
        String l = e != null ? e.label : (emptyLabel != null ? emptyLabel : "");
        if (e == entry && b == icon && l.equals(label)) return;
        entry = e;
        icon = b;
        label = l;
        textPaint.setColor(e != null ? Ui.TEXT : Ui.TEXT2);
        setContentDescription(l);
        ellipsize();
        invalidate();
    }

    /** Turns this cell into the special "All apps" tile (3x3 dots icon). */
    void setAllApps(String text) {
        allApps = true;
        entry = null;
        icon = null;
        label = text;
        textPaint.setColor(Ui.TEXT);
        setContentDescription(text);
        ellipsize();
        invalidate();
    }

    /** null = draw nothing for an unassigned slot (e.g. while apps are still loading). */
    void setEmptyLabel(String text) {
        if (TextUtils.equals(text, emptyLabel)) return;
        emptyLabel = text;
        if (entry == null && !allApps) {
            label = text == null ? "" : text;
            textPaint.setColor(Ui.TEXT2);
            setContentDescription(label);
            ellipsize();
            invalidate();
        }
    }

    AppEntry getEntry() {
        return entry;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        ellipsize();
    }

    private void ellipsize() {
        int avail = getWidth() - 2 * hPad;
        if (avail <= 0 || label.isEmpty()) {
            shown = label;
            return;
        }
        shown = TextUtils.ellipsize(label, textPaint, avail, TextUtils.TruncateAt.END).toString();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        float textH = descent - ascent;
        int top = Math.round((h - (iconSize + gap + textH)) / 2f);
        int left = (w - iconSize) / 2;
        if (allApps) {
            markPaint.setColor(Ui.ACCENT);
            float box = iconSize * 0.75f;
            float x0 = left + (iconSize - box) / 2f;
            float y0 = top + (iconSize - box) / 2f;
            float r = box / 11f;
            for (int row = 0; row < 3; row++) {
                for (int col = 0; col < 3; col++) {
                    canvas.drawCircle(x0 + box * (1 + 2 * col) / 6f, y0 + box * (1 + 2 * row) / 6f, r, markPaint);
                }
            }
        } else if (icon != null) {
            dst.set(left, top, left + iconSize, top + iconSize);
            canvas.drawBitmap(icon, null, dst, bitmapPaint);
        } else if (entry == null && emptyLabel != null) {
            markPaint.setColor(Ui.TEXT3);
            float cx = w / 2f;
            float cy = top + iconSize / 2f;
            float arm = iconSize * 0.22f;
            canvas.drawLine(cx - arm, cy, cx + arm, cy, markPaint);
            canvas.drawLine(cx, cy - arm, cx, cy + arm, markPaint);
        }
        if (!shown.isEmpty()) {
            canvas.drawText(shown, w / 2f, top + iconSize + gap - ascent, textPaint);
        }
    }
}
