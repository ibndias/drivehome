package com.drivehome.launcher;

import android.content.Context;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

/**
 * The whole home screen as one flat layout: every child gets an exact rectangle computed from the
 * available size (single measure pass, no weights, no nesting).
 *
 * Landscape (the head unit, 1280x604):
 * <pre>
 *   +-----------+  time / date                    [update chip]
 *   |   speed   |  +------------------------------------------+
 *   |   gauge   |  | now playing + transport                  |
 *   +-----------+  +------------------------------------------+
 *   [ dock: pinned apps ...................... ]   [ All apps ]
 * </pre>
 * The gauge can be moved to the right edge (driver on the right); the dock stays as it is.
 * Portrait is only a fallback: clock, gauge, media, a 4x2 dock and the All-apps bar stacked.
 */
final class HomeLayout extends ViewGroup {
    private final View gauge;
    private final View clock;
    private final View chip;
    private final View media;
    private final View dock;
    private final View allApps;
    private final Rect gaugeRect = new Rect();
    private final Rect clockRect = new Rect();
    private final Rect chipRect = new Rect();
    private final Rect mediaRect = new Rect();
    private final Rect dockRect = new Rect();
    private final Rect allAppsRect = new Rect();
    private final int margin;
    private final int gap;
    private final int dockSize;
    private final int headerHeight;
    private final int chipHeight;
    private boolean portrait;
    private boolean gaugeRight;

    HomeLayout(Context c, View gauge, View clock, View chip, View media, View dock, View allApps) {
        super(c);
        this.gauge = gauge;
        this.clock = clock;
        this.chip = chip;
        this.media = media;
        this.dock = dock;
        this.allApps = allApps;
        margin = Ui.dp(c, 16);
        gap = Ui.dp(c, 12);
        dockSize = Ui.dp(c, 128);
        headerHeight = Ui.dp(c, 120);
        chipHeight = Ui.dp(c, Ui.TOUCH);
        addView(gauge);
        addView(clock);
        addView(media);
        addView(dock);
        addView(allApps);
        addView(chip);
    }

    void setPortrait(boolean p) {
        if (p == portrait) return;
        portrait = p;
        requestLayout();
    }

    /** Landscape only: gauge on the right (right-hand-drive) instead of the left. */
    void setGaugeRight(boolean r) {
        if (r == gaugeRight) return;
        gaugeRight = r;
        requestLayout();
    }

    private static void set(Rect rect, int l, int t, int r, int b) {
        rect.set(l, t, Math.max(l, r), Math.max(t, b));
    }

    private static void measure(View v, Rect rect) {
        v.measure(MeasureSpec.makeMeasureSpec(rect.width(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(rect.height(), MeasureSpec.EXACTLY));
    }

    private static void layout(View v, Rect rect) {
        v.layout(rect.left, rect.top, rect.right, rect.bottom);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(w, h);
        int left = margin;
        int right = Math.max(left, w - margin);
        int top = margin;
        int bottom = Math.max(top, h - margin);

        if (!portrait) {
            int dockH = Math.min(dockSize, h / 4);
            set(dockRect, left, bottom - dockH, right - dockH - gap, bottom);
            set(allAppsRect, right - dockH, bottom - dockH, right, bottom);
            int bandBottom = bottom - dockH - gap;
            // Slightly wider than tall: the 270-degree ring is 2r wide but only 1.71r tall.
            int gaugeW = Math.max(0, Math.min((bandBottom - top) * 15 / 14, (right - left) * 37 / 100));
            // The gauge goes on the driver's side; clock + media take the rest of the band.
            int gaugeLeft = gaugeRight ? right - gaugeW : left;
            int x0 = gaugeRight ? left : left + gaugeW + gap;
            int x1 = gaugeRight ? right - gaugeW - gap : right;
            set(gaugeRect, gaugeLeft, top, gaugeLeft + gaugeW, bandBottom);
            int headerH = Math.max(0, Math.min(headerHeight, (bandBottom - top) / 3));
            set(clockRect, x0, top, x1, top + headerH);
            set(mediaRect, x0, top + headerH + gap, x1, bandBottom);
        } else {
            int barH = Math.min(dockSize, h / 9);
            int dockH = Math.min(dockSize * 2, h / 4);
            int headerH = Math.min(headerHeight, h / 8);
            int gaugeH = Math.min(right - left, h * 3 / 10);
            set(allAppsRect, left, bottom - barH, right, bottom);
            int dockTop = bottom - barH - gap - dockH;
            set(dockRect, left, dockTop, right, dockTop + dockH);
            set(clockRect, left, top, right, top + headerH);
            int gaugeTop = top + headerH + gap;
            set(gaugeRect, left, gaugeTop, right, gaugeTop + gaugeH);
            set(mediaRect, left, gaugeTop + gaugeH + gap, right, dockTop - gap);
        }

        // Update chip: right-aligned in the header row at its natural width; the clock keeps the rest.
        if (chip.getVisibility() != GONE) {
            int ch = Math.min(chipHeight, clockRect.height());
            chip.measure(MeasureSpec.makeMeasureSpec(clockRect.width() / 2, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY));
            int ct = clockRect.top + (clockRect.height() - ch) / 2;
            chipRect.set(clockRect.right - chip.getMeasuredWidth(), ct, clockRect.right, ct + ch);
            clockRect.right = Math.max(clockRect.left, chipRect.left - gap);
        }

        measure(gauge, gaugeRect);
        measure(clock, clockRect);
        measure(media, mediaRect);
        measure(dock, dockRect);
        measure(allApps, allAppsRect);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        layout(gauge, gaugeRect);
        layout(clock, clockRect);
        layout(media, mediaRect);
        layout(dock, dockRect);
        layout(allApps, allAppsRect);
        if (chip.getVisibility() != GONE) layout(chip, chipRect);
    }

    @Override
    public boolean shouldDelayChildPressedState() {
        return false; // not scrollable: show pressed feedback immediately
    }
}
