package com.drivehome.launcher;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Builds the app's dialogs ("sheets") in the Cockpit style: a header (optional app icon, title,
 * optional subtitle, a large close button) above a body. Sheets are plain Dialogs; the activity
 * shows them with no window animation, no dim and no elevation.
 */
final class Sheet {
    /** One menu / picker row. */
    static final class Row {
        final String label;
        final Runnable action;
        Bitmap icon;
        /** Reserve the icon column even without a bitmap (keeps labels aligned). */
        boolean iconSlot;
        /** Draw the "remove" mark in the icon column. */
        boolean remove;
        int color = Ui.TEXT;
        /** Right-aligned secondary text. */
        String note;
        /** Right-aligned check mark (instead of a note). */
        boolean check;
        boolean enabled = true;

        Row(String label, Runnable action) {
            this.label = label == null ? "" : label;
            this.action = action;
        }
    }

    interface OnPick {
        void onPick(Row row);
    }

    private Sheet() {}

    /** Creates (does not show) a sheet. bodyHeight is in px. */
    static Dialog create(Context c, Bitmap icon, String title, String subtitle, View body, int bodyHeight) {
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(Ui.dp(c, 28), Ui.dp(c, 12), Ui.dp(c, 12), Ui.dp(c, 8));

        if (icon != null) {
            GlyphView iv = new GlyphView(c, 0, 0, 0);
            iv.setBitmap(icon);
            int size = Ui.dp(c, 64); // the drawer icon bitmap, drawn 1:1
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(size, size);
            ilp.setMarginEnd(Ui.dp(c, 20));
            header.addView(iv, ilp);
        }

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView tv = Ui.text(c, Ui.dp(c, Ui.T_TITLE), Ui.TEXT, Ui.medium());
        tv.setText(title);
        texts.addView(tv, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (subtitle != null) {
            TextView sv = Ui.text(c, Ui.dp(c, Ui.T_BODY), Ui.TEXT2, Ui.regular());
            sv.setText(subtitle);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.topMargin = Ui.dp(c, 6);
            texts.addView(sv, slp);
        }
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMarginEnd(Ui.dp(c, 16));
        header.addView(texts, tlp);

        final Dialog d = new Dialog(c, android.R.style.Theme_Material_Dialog);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        GlyphView close = new GlyphView(c, GlyphView.CLOSE, Ui.dp(c, 26), Ui.TEXT);
        int cs = Ui.dp(c, Ui.TOUCH);
        close.setBackground(Ui.pressable(Ui.BUTTON, Ui.BUTTON_PRESSED, cs / 2f));
        close.setContentDescription("Close");
        close.setOnClickListener(v -> {
            try {
                d.dismiss();
            } catch (RuntimeException ignored) {
            }
        });
        header.addView(close, new LinearLayout.LayoutParams(cs, cs));

        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int m = Ui.dp(c, 12);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(0, bodyHeight));
        blp.setMargins(m, 0, m, m);
        root.addView(body, blp);

        d.setContentView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        d.setCanceledOnTouchOutside(true);
        return d;
    }

    /** A list (columns = 1) or multi-column grid of rows without ripple, glow or fading scrollbars. */
    static GridView rows(Context c, final List<Row> rows, int columns, final OnPick onPick) {
        final int rowHeight = Ui.dp(c, RowView.HEIGHT);
        GridView grid = new GridView(c);
        grid.setNumColumns(Math.max(1, columns));
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(Ui.dp(c, 8));
        grid.setVerticalSpacing(0);
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT)); // rows draw their own pressed state
        grid.setCacheColorHint(Color.TRANSPARENT);
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
        grid.setVerticalFadingEdgeEnabled(false);
        grid.setScrollbarFadingEnabled(false);
        grid.setScrollingCacheEnabled(false);
        grid.setAdapter(new BaseAdapter() {
            @Override
            public int getCount() {
                return rows.size();
            }

            @Override
            public Object getItem(int position) {
                return rows.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public boolean areAllItemsEnabled() {
                return false;
            }

            @Override
            public boolean isEnabled(int position) {
                return position >= 0 && position < rows.size() && rows.get(position).enabled;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                RowView v;
                if (convertView instanceof RowView) {
                    v = (RowView) convertView;
                } else {
                    v = new RowView(parent.getContext());
                    v.setLayoutParams(new AbsListView.LayoutParams(
                            AbsListView.LayoutParams.MATCH_PARENT, rowHeight));
                }
                v.bind(rows.get(position));
                return v;
            }
        });
        grid.setOnItemClickListener((parent, view, position, id) -> {
            if (position < 0 || position >= rows.size()) return;
            Row r = rows.get(position);
            if (r.enabled) onPick.onPick(r);
        });
        return grid;
    }
}
