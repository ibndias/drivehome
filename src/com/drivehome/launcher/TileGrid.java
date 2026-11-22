package com.drivehome.launcher;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;

/**
 * Fixed cols x rows grid of equally sized children with a gap, filling all available space.
 * Single measure pass (no weights), remainder pixels distributed so edges line up exactly.
 */
final class TileGrid extends ViewGroup {
    private final int gap;
    private int cols = 4;
    private int rows = 3;

    TileGrid(Context c, int gapPx) {
        super(c);
        gap = gapPx;
    }

    void setGrid(int columns, int rowCount) {
        if (columns == cols && rowCount == rows) return;
        cols = Math.max(1, columns);
        rows = Math.max(1, rowCount);
        requestLayout();
    }

    /** Start offset of cell i out of n, for a span of total px. */
    private int edge(int i, int n, int total) {
        return i * (total + gap) / n;
    }

    private int size(int i, int n, int total) {
        return Math.max(0, edge(i + 1, n, total) - edge(i, n, total) - gap);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        int aw = Math.max(0, w - getPaddingLeft() - getPaddingRight());
        int ah = Math.max(0, h - getPaddingTop() - getPaddingBottom());
        int n = getChildCount();
        for (int i = 0; i < n; i++) {
            View child = getChildAt(i);
            int col = i % cols;
            int row = i / cols;
            int cw = row < rows ? size(col, cols, aw) : 0;
            int ch = row < rows ? size(row, rows, ah) : 0;
            child.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int aw = Math.max(0, r - l - getPaddingLeft() - getPaddingRight());
        int ah = Math.max(0, b - t - getPaddingTop() - getPaddingBottom());
        int n = getChildCount();
        for (int i = 0; i < n; i++) {
            View child = getChildAt(i);
            int col = i % cols;
            int row = i / cols;
            if (row >= rows) {
                child.layout(0, 0, 0, 0);
                continue;
            }
            int x = getPaddingLeft() + edge(col, cols, aw);
            int y = getPaddingTop() + edge(row, rows, ah);
            child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
        }
    }

    @Override
    public boolean shouldDelayChildPressedState() {
        // Not scrollable: show pressed feedback immediately.
        return false;
    }
}
