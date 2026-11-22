package com.drivehome.launcher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;

/**
 * Full-screen app drawer overlay (toggled with setVisibility by the activity, no animation).
 * Draws no background of its own: the activity hides the home content while it is shown, so the
 * window background shows through with no overdraw.
 */
final class DrawerView extends LinearLayout {
    interface Host {
        void onDrawerLaunch(AppEntry e);

        void onDrawerDetails(AppEntry e);

        void onDrawerSettings();

        void onDrawerClose();
    }

    private final GridView grid;
    private final AppsAdapter adapter = new AppsAdapter();
    private final int cellHeight;
    private final int iconSize;
    private final int textSize;
    private final int cellRadius;
    private List<AppEntry> apps = Collections.emptyList();

    DrawerView(Context c, final Host host) {
        super(c);
        setOrientation(VERTICAL);
        int pad = Ui.dp(c, 16);
        setPadding(pad, pad, pad, pad);
        cellHeight = Ui.dp(c, 140);
        iconSize = Ui.dp(c, 64);
        textSize = Ui.dp(c, 16);
        cellRadius = Ui.dp(c, Ui.RADIUS);

        LinearLayout header = new LinearLayout(c);
        header.setOrientation(HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = Ui.text(c, Ui.dp(c, 30), Ui.TEXT, Ui.medium());
        title.setText("All apps");
        LayoutParams tlp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMarginStart(Ui.dp(c, 8));
        header.addView(title, tlp);

        TextView settings = Ui.text(c, Ui.dp(c, 20), Ui.TEXT, Ui.medium());
        settings.setText("Settings");
        settings.setGravity(Gravity.CENTER);
        int sp = Ui.dp(c, 28);
        settings.setPadding(sp, 0, sp, 0);
        settings.setBackground(Ui.pressable(Ui.CARD, Ui.CARD_PRESSED, cellRadius));
        settings.setOnClickListener(v -> host.onDrawerSettings());
        header.addView(settings, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT));

        GlyphView close = new GlyphView(c, GlyphView.CLOSE, Ui.dp(c, 30), Ui.TEXT);
        close.setBackground(Ui.pressable(Ui.CARD, Ui.CARD_PRESSED, cellRadius));
        close.setContentDescription("Close");
        close.setOnClickListener(v -> host.onDrawerClose());
        LayoutParams clp = new LayoutParams(Ui.dp(c, 104), LayoutParams.MATCH_PARENT);
        clp.setMarginStart(Ui.dp(c, 12));
        header.addView(close, clp);

        addView(header, new LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(c, 68)));

        grid = new GridView(c);
        grid.setNumColumns(8);
        grid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        grid.setHorizontalSpacing(Ui.dp(c, 8));
        grid.setVerticalSpacing(Ui.dp(c, 8));
        grid.setSelector(new ColorDrawable(Color.TRANSPARENT)); // no ripple; cells draw their own pressed state
        grid.setDrawSelectorOnTop(false);
        grid.setCacheColorHint(Color.TRANSPARENT);
        grid.setOverScrollMode(View.OVER_SCROLL_NEVER);       // no glow animation
        grid.setVerticalFadingEdgeEnabled(false);
        grid.setScrollbarFadingEnabled(false);               // no scrollbar fade animation
        grid.setScrollingCacheEnabled(false);
        grid.setFastScrollEnabled(false);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener((parent, view, position, id) -> {
            AppEntry e = item(position);
            if (e != null) host.onDrawerLaunch(e);
        });
        grid.setOnItemLongClickListener((parent, view, position, id) -> {
            AppEntry e = item(position);
            if (e != null) host.onDrawerDetails(e);
            return true;
        });
        LayoutParams glp = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
        glp.topMargin = Ui.dp(c, 12);
        addView(grid, glp);
    }

    void setApps(List<AppEntry> list) {
        apps = list == null ? Collections.<AppEntry>emptyList() : list;
        adapter.notifyDataSetChanged();
    }

    void setColumns(int n) {
        grid.setNumColumns(n);
    }

    void scrollToTop() {
        if (grid.getFirstVisiblePosition() != 0) grid.setSelection(0);
    }

    private AppEntry item(int position) {
        return position >= 0 && position < apps.size() ? apps.get(position) : null;
    }

    private final class AppsAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return apps.size();
        }

        @Override
        public Object getItem(int position) {
            return item(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            AppCellView cell;
            if (convertView instanceof AppCellView) {
                cell = (AppCellView) convertView; // the cell is its own view holder
            } else {
                cell = new AppCellView(getContext(), iconSize, textSize, Ui.regular(), false);
                cell.setBackground(Ui.pressable(0, Ui.CARD_PRESSED, cellRadius));
                cell.setLayoutParams(new AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, cellHeight));
            }
            cell.bind(item(position));
            return cell;
        }
    }
}
