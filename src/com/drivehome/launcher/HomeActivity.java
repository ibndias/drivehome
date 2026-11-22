package com.drivehome.launcher;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * The home screen. Entire UI is built in code. Left: clock/speed card + media card. Right: 4x3 tile grid
 * (11 app tiles + "All apps"). App drawer is an in-activity overlay.
 */
public class HomeActivity extends Activity
        implements AppRepository.Listener, DrawerView.Host, MediaCard.Host, Updater.Listener {
    private static final String TAG = "DriveHome";
    private static final String PREFS = "drivehome";
    private static final String KEY_TILE = "tile_";
    private static final int APP_TILES = 11;
    private static final int ALL_APPS_TILE = 11;
    private static final long SPEED_STALE_MS = 5000L;

    private static final String ORIGINAL_LAUNCHER_PKG = "com.dofun.variety";
    private static final ComponentName ORIGINAL_LAUNCHER =
            new ComponentName(ORIGINAL_LAUNCHER_PKG, "com.dofun.overseasvariety.Launcher");

    /** Never used as default/backfill tiles (still assignable by long-press if installed). */
    private static final String[] NEVER_DEFAULT = {
        ORIGINAL_LAUNCHER_PKG,
        "com.waze",
        "com.google.android.googlequicksearchbox",
        "com.google.android.projection.gearhead",
    };

    /** Tried first when a default package is missing, before plain alphabetical backfill. */
    private static final String[] PREFERRED_BACKFILL = {
        "com.spotify.music",
        "com.google.android.apps.youtube.music",
        "com.android.chrome",
        "com.android.settings",
    };

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final AppCellView[] tiles = new AppCellView[APP_TILES + 1];
    private final AppEntry[] tileEntries = new AppEntry[APP_TILES];

    private AppRepository repo;
    private AppRepository.Snapshot snapshot;
    private SharedPreferences prefs;

    private LinearLayout content;
    private LinearLayout leftColumn;
    private TileGrid tileGrid;
    private TextView timeView;
    private TextView dateView;
    private TextView speedView;
    private View speedRow;
    private TextView updateChip;
    private MediaCard mediaCard;
    private DrawerView drawer;
    private Dialog dialog;

    private int layoutMode = -1; // 0 = landscape, 1 = portrait
    private boolean timeReceiverRegistered;
    private boolean locationActive;
    private boolean closeDrawerWhenHidden;
    private LocationManager locationManager;

    private final BroadcastReceiver timeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateClock();
        }
    };

    private final Runnable speedStale = () -> setSpeed("--");

    @SuppressWarnings("deprecation")
    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            onLocation(location);
        }

        // All four methods are abstract on API 29 and must be implemented.
        @Override
        public void onStatusChanged(String provider, int status, Bundle extras) {
        }

        @Override
        public void onProviderEnabled(String provider) {
        }

        @Override
        public void onProviderDisabled(String provider) {
            handler.removeCallbacks(speedStale);
            setSpeed("--");
        }
    };

    // ------------------------------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setContentView(buildUi());
        applyLayoutMode(getResources().getConfiguration());
        updateClock();

        repo = AppRepository.get(this);
        repo.addListener(this);
        repo.setPriorityPackages(priorityPackages());
        AppRepository.Snapshot s = repo.snapshot();
        if (s != null) onAppsChanged(s);
        repo.ensureLoaded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (closeDrawerWhenHidden) closeDrawer();
        registerTimeReceiver();
        updateClock();
        startLocation();
        mediaCard.start();
        Updater updater = Updater.get(this);
        updater.setListener(this);
        syncUpdateChip();
        updater.maybeCheck();
    }

    @Override
    protected void onPause() {
        unregisterTimeReceiver();
        stopLocation();
        mediaCard.stop();
        Updater.get(this).setListener(null);
        super.onPause();
    }

    // ------------------------------------------------------------------------------------------ self-update

    @Override
    public void onUpdateReady(int versionCode) {
        syncUpdateChip();
    }

    /** Chip visible only while a verified, newer APK is actually on disk. */
    private void syncUpdateChip() {
        if (updateChip == null) return;
        Updater u = Updater.get(this);
        int ready = u.readyVersion();
        if (ready > u.currentVersion()) {
            updateChip.setText("Update v" + ready + " ready · tap to install");
            updateChip.setVisibility(View.VISIBLE);
        } else {
            updateChip.setVisibility(View.GONE);
        }
    }

    @Override
    public void onCheckResult(String message) {
        toast(message);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // An app was launched from the drawer: close it now that we are hidden (no visible flash).
        if (closeDrawerWhenHidden) closeDrawer();
    }

    @Override
    protected void onDestroy() {
        if (repo != null) repo.removeListener(this);
        dismissDialog();
        unregisterTimeReceiver();
        stopLocation();
        if (mediaCard != null) mediaCard.stop();
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // HOME pressed: back to the plain home screen.
        dismissDialog();
        closeDrawer();
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (isDrawerOpen()) closeDrawer();
        // Otherwise do nothing: a home screen has nowhere to go back to.
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        applyLayoutMode(newConfig);
        updateClock();
    }

    // ------------------------------------------------------------------------------------------ UI

    private int dp(float v) {
        return Ui.dp(this, v);
    }

    private View buildUi() {
        int pad = dp(16);
        int gap = dp(12);

        FrameLayout root = new FrameLayout(this);
        root.setFitsSystemWindows(true);

        content = new LinearLayout(this);
        content.setPadding(pad, pad, pad, pad);

        leftColumn = new LinearLayout(this);
        leftColumn.setOrientation(LinearLayout.VERTICAL);
        leftColumn.addView(buildClockCard(), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 55f));
        mediaCard = new MediaCard(this, this);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 45f);
        mlp.topMargin = gap;
        leftColumn.addView(mediaCard, mlp);

        tileGrid = new TileGrid(this, gap);
        int radius = dp(Ui.RADIUS);
        for (int i = 0; i < tiles.length; i++) {
            AppCellView tile = new AppCellView(this, dp(72), dp(18), Ui.medium(), true);
            tile.setBackground(Ui.pressable(Ui.CARD, Ui.CARD_PRESSED, radius));
            final int index = i;
            tile.setOnClickListener(v -> onTileClick(index));
            if (i != ALL_APPS_TILE) {
                tile.setOnLongClickListener(v -> {
                    showTileChooser(index);
                    return true;
                });
            }
            tiles[i] = tile;
            tileGrid.addView(tile);
        }
        tiles[ALL_APPS_TILE].setAllApps("All apps");

        content.addView(leftColumn);
        content.addView(tileGrid);
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        drawer = new DrawerView(this, this);
        drawer.setVisibility(View.GONE);
        root.addView(drawer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    private View buildClockCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(Ui.rounded(Ui.CARD, dp(Ui.RADIUS)));
        card.setPadding(dp(28), dp(22), dp(28), dp(24));
        card.setOnLongClickListener(v -> {
            showSystemMenu();
            return true;
        });

        timeView = Ui.text(this, dp(96), Ui.TEXT, Ui.light());
        timeView.setLetterSpacing(-0.02f);
        card.addView(timeView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        dateView = Ui.text(this, dp(22), Ui.TEXT2, Ui.regular());
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(8);
        card.addView(dateView, dlp);

        // Shown only when Updater has a newer, verified APK downloaded. Tap -> Android's install prompt.
        updateChip = Ui.text(this, dp(17), Ui.WHITE, Ui.medium());
        updateChip.setBackground(Ui.pressable(Ui.ACCENT, Ui.ACCENT_PRESSED, dp(10)));
        updateChip.setPadding(dp(14), dp(8), dp(14), dp(8));
        updateChip.setSingleLine(true);
        updateChip.setVisibility(View.GONE);
        updateChip.setOnClickListener(v -> {
            // Hide immediately: a double tap must not create two install sessions.
            // onResume re-syncs it if the install was cancelled or failed.
            v.setVisibility(View.GONE);
            toast("Installing update…");
            Updater.get(this).install();
        });
        LinearLayout.LayoutParams ulp0 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ulp0.topMargin = dp(12);
        card.addView(updateChip, ulp0);

        card.addView(new Space(this), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBaselineAligned(true);
        speedView = Ui.text(this, dp(44), Ui.TEXT, Ui.light());
        speedView.setText("--");
        speedView.setSingleLine(true);
        speedView.setGravity(android.view.Gravity.END);
        // Fixed width (fits "188"): the 1 Hz speed text changes then only redraw this view
        // instead of re-laying out the whole window while driving.
        int speedWidth = (int) Math.ceil(speedView.getPaint().measureText("188")) + dp(2);
        row.addView(speedView, new LinearLayout.LayoutParams(speedWidth, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView unit = Ui.text(this, dp(18), Ui.TEXT2, Ui.regular());
        unit.setText("km/h");
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ulp.setMarginStart(dp(8));
        row.addView(unit, ulp);
        row.setVisibility(View.GONE);
        speedRow = row;
        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return card;
    }

    /** Landscape (normal): 440px left column + 4x3 grid. Portrait fallback: stacked, 3x4 grid. */
    private void applyLayoutMode(Configuration c) {
        boolean landscape = c == null || c.screenWidthDp == 0 || c.screenHeightDp == 0
                || c.screenWidthDp >= c.screenHeightDp;
        int mode = landscape ? 0 : 1;
        if (mode == layoutMode) return;
        layoutMode = mode;
        int gap = dp(12);
        if (landscape) {
            content.setOrientation(LinearLayout.HORIZONTAL);
            leftColumn.setLayoutParams(new LinearLayout.LayoutParams(dp(440), ViewGroup.LayoutParams.MATCH_PARENT));
            LinearLayout.LayoutParams g = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            g.setMarginStart(gap);
            tileGrid.setLayoutParams(g);
            tileGrid.setGrid(4, 3);
            drawer.setColumns(8);
        } else {
            content.setOrientation(LinearLayout.VERTICAL);
            leftColumn.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 42f));
            LinearLayout.LayoutParams g = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 58f);
            g.topMargin = gap;
            tileGrid.setLayoutParams(g);
            tileGrid.setGrid(3, 4);
            drawer.setColumns(5);
        }
    }

    // ------------------------------------------------------------------------------------------ clock

    private void registerTimeReceiver() {
        if (timeReceiverRegistered) return;
        try {
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_TIME_TICK);
            f.addAction(Intent.ACTION_TIME_CHANGED);
            f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            f.addAction(Intent.ACTION_DATE_CHANGED);
            registerReceiver(timeReceiver, f);
            timeReceiverRegistered = true;
        } catch (RuntimeException e) {
            Log.w(TAG, "time receiver", e);
        }
    }

    private void unregisterTimeReceiver() {
        if (!timeReceiverRegistered) return;
        timeReceiverRegistered = false;
        try {
            unregisterReceiver(timeReceiver);
        } catch (RuntimeException ignored) {
        }
    }

    private void updateClock() {
        if (timeView == null) return;
        try {
            Date now = new Date();
            Locale loc = Locale.getDefault();
            boolean is24 = DateFormat.is24HourFormat(this);
            Ui.setText(timeView, new SimpleDateFormat(is24 ? "HH:mm" : "h:mm", loc).format(now));
            Ui.setText(dateView, new SimpleDateFormat("EEEE, d MMMM", loc).format(now));
        } catch (RuntimeException e) {
            Log.w(TAG, "clock", e);
        }
    }

    // ------------------------------------------------------------------------------------------ GPS speed

    private boolean hasFineLocation() {
        try {
            return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void startLocation() {
        boolean granted = hasFineLocation();
        speedRow.setVisibility(granted ? View.VISIBLE : View.GONE);
        if (!granted || locationActive) return;
        setSpeed("--");
        try {
            LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (lm == null) return;
            lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, locationListener, Looper.getMainLooper());
            locationManager = lm;
            locationActive = true;
        } catch (SecurityException e) {
            Log.w(TAG, "location permission", e);
        } catch (RuntimeException e) {
            // No GPS provider on this device, or the service is unavailable.
            Log.w(TAG, "location", e);
        }
    }

    private void stopLocation() {
        handler.removeCallbacks(speedStale);
        if (!locationActive) return;
        locationActive = false;
        try {
            if (locationManager != null) locationManager.removeUpdates(locationListener);
        } catch (RuntimeException ignored) {
        }
    }

    private void onLocation(Location l) {
        if (l == null) return;
        String s;
        if (l.hasSpeed()) {
            s = Integer.toString(Math.max(0, Math.round(l.getSpeed() * 3.6f)));
        } else {
            s = "--";
        }
        setSpeed(s);
        handler.removeCallbacks(speedStale);
        handler.postDelayed(speedStale, SPEED_STALE_MS); // fix lost -> "--"
    }

    private void setSpeed(String s) {
        if (speedView != null) Ui.setText(speedView, s);
    }

    // ------------------------------------------------------------------------------------------ apps & tiles

    @Override
    public void onAppsChanged(AppRepository.Snapshot s) {
        snapshot = s;
        bindTiles();
        drawer.setApps(s.apps);
        mediaCard.setSnapshot(s);
    }

    private HashSet<String> priorityPackages() {
        HashSet<String> set = new HashSet<>();
        for (String[] d : AppRepository.DEFAULT_TILES) set.add(d[0]);
        for (String p : PREFERRED_BACKFILL) set.add(p);
        for (int i = 0; i < APP_TILES; i++) {
            String v = prefs.getString(KEY_TILE + i, null);
            ComponentName cn = v == null ? null : ComponentName.unflattenFromString(v);
            if (cn != null) set.add(cn.getPackageName());
        }
        return set;
    }

    private static boolean neverDefault(String pkg) {
        for (String p : NEVER_DEFAULT) {
            if (p.equals(pkg)) return true;
        }
        return false;
    }

    /** Default app per tile slot: the default packages that are installed, then backfill. */
    private List<AppEntry> computeDefaults(AppRepository.Snapshot s) {
        ArrayList<AppEntry> out = new ArrayList<>(APP_TILES);
        HashSet<String> usedPkgs = new HashSet<>();
        for (String[] d : AppRepository.DEFAULT_TILES) {
            AppEntry e = s.byPackage.get(d[0]);
            if (e != null && usedPkgs.add(e.packageName)) out.add(e);
        }
        for (String p : PREFERRED_BACKFILL) {
            if (out.size() >= APP_TILES) break;
            AppEntry e = s.byPackage.get(p);
            if (e != null && usedPkgs.add(e.packageName)) out.add(e);
        }
        for (AppEntry e : s.apps) {
            if (out.size() >= APP_TILES) break;
            if (neverDefault(e.packageName)) continue;
            if (usedPkgs.add(e.packageName)) out.add(e);
        }
        return out;
    }

    private void bindTiles() {
        AppRepository.Snapshot s = snapshot;
        if (s == null) return;
        List<AppEntry> defaults = computeDefaults(s);
        for (int i = 0; i < APP_TILES; i++) {
            AppEntry e = null;
            String saved = prefs.getString(KEY_TILE + i, null);
            if (saved != null) e = s.byKey.get(saved); // null if uninstalled -> default below
            if (e == null && i < defaults.size()) e = defaults.get(i);
            tileEntries[i] = e;
            tiles[i].bind(e);
        }
    }

    private void onTileClick(int index) {
        if (index == ALL_APPS_TILE) {
            openDrawer();
            return;
        }
        AppEntry e = tileEntries[index];
        if (e == null) {
            showTileChooser(index); // empty tile: let the user pick one
            return;
        }
        launch(e.component);
    }

    private void showTileChooser(final int index) {
        final AppRepository.Snapshot s = snapshot;
        if (s == null || s.apps.isEmpty()) {
            toast("Apps are still loading");
            return;
        }
        List<AppEntry> defaults = computeDefaults(s);
        AppEntry def = index < defaults.size() ? defaults.get(index) : null;
        final String resetLabel = def != null ? "Reset to default (" + def.label + ")" : "Reset to default";
        final List<AppEntry> apps = s.apps;
        final float textSize = dp(20);
        final int rowHeight = dp(64);

        BaseAdapter adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return apps.size() + 1;
            }

            @Override
            public Object getItem(int position) {
                return position == 0 ? null : apps.get(position - 1);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                RowView row;
                if (convertView instanceof RowView) {
                    row = (RowView) convertView;
                } else {
                    row = new RowView(HomeActivity.this, textSize);
                    row.setLayoutParams(new AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, rowHeight));
                }
                if (position == 0) {
                    row.bind(null, true, resetLabel, Ui.ACCENT);
                } else {
                    AppEntry e = apps.get(position - 1);
                    row.bind(e.gridIcon, true, e.label, Ui.TEXT);
                }
                return row;
            }
        };

        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
                .setTitle("Choose app for this tile")
                .setAdapter(adapter, (dlg, which) -> {
                    String key = KEY_TILE + index;
                    if (which == 0) {
                        prefs.edit().remove(key).apply();
                    } else if (which - 1 < apps.size()) {
                        prefs.edit().putString(key, apps.get(which - 1).key).apply();
                    }
                    repo.setPriorityPackages(priorityPackages());
                    bindTiles();
                })
                .create();
        showDialog(d);
    }

    // ------------------------------------------------------------------------------------------ escape hatch

    private void showSystemMenu() {
        final String[] items = {"Original launcher", "Choose home app", "Android settings",
                "Check for updates (v" + Updater.get(this).currentVersion() + ")"};
        final float textSize = dp(20);
        final int rowHeight = dp(64);
        BaseAdapter adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return items.length;
            }

            @Override
            public Object getItem(int position) {
                return items[position];
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                RowView row;
                if (convertView instanceof RowView) {
                    row = (RowView) convertView;
                } else {
                    row = new RowView(HomeActivity.this, textSize);
                    row.setLayoutParams(new AbsListView.LayoutParams(AbsListView.LayoutParams.MATCH_PARENT, rowHeight));
                }
                row.bind(null, false, items[position], Ui.TEXT);
                return row;
            }
        };
        AlertDialog d = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog)
                .setTitle("DriveHome")
                .setAdapter(adapter, (dlg, which) -> {
                    if (which == 0) openOriginalLauncher();
                    else if (which == 1) openHomeSettings();
                    else if (which == 2) openAndroidSettings();
                    else {
                        toast("Checking for updates…");
                        Updater.get(this).checkNow();
                    }
                })
                .create();
        showDialog(d);
    }

    private void openOriginalLauncher() {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_HOME);
        i.setComponent(ORIGINAL_LAUNCHER);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!tryStart(i)) {
            Intent li = launchIntentFor(ORIGINAL_LAUNCHER_PKG);
            if (li == null || !tryStart(li)) toast("Original launcher not available");
        }
    }

    private void openHomeSettings() {
        Intent i = new Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!tryStart(i)) openAndroidSettings();
    }

    private void openAndroidSettings() {
        Intent i = new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!tryStart(i)) toast("Settings not available");
    }

    // ------------------------------------------------------------------------------------------ dialogs

    private void showDialog(AlertDialog d) {
        dismissDialog();
        if (isFinishing() || isDestroyed()) return;
        try {
            Window w = d.getWindow();
            if (w != null) {
                w.setWindowAnimations(0);                                // no open/close animation
                w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND); // no (animated) dim layer
                w.setBackgroundDrawable(Ui.rounded(Ui.DIALOG, dp(Ui.RADIUS), Ui.DIALOG_STROKE, Math.max(1, dp(1))));
            }
            ListView lv = d.getListView();
            if (lv != null) {
                lv.setSelector(new ColorDrawable(Color.TRANSPARENT)); // no ripple
                lv.setDivider(null);
                lv.setOverScrollMode(View.OVER_SCROLL_NEVER);
                lv.setVerticalFadingEdgeEnabled(false);
                lv.setScrollbarFadingEnabled(false);
            }
            d.setOnDismissListener((DialogInterface di) -> {
                if (dialog == di) dialog = null;
            });
            dialog = d;
            d.show();
        } catch (RuntimeException e) {
            dialog = null;
            Log.w(TAG, "dialog", e);
        }
    }

    private void dismissDialog() {
        Dialog d = dialog;
        dialog = null;
        if (d != null) {
            try {
                d.dismiss();
            } catch (RuntimeException ignored) {
            }
        }
    }

    // ------------------------------------------------------------------------------------------ drawer

    private boolean isDrawerOpen() {
        return drawer != null && drawer.getVisibility() == View.VISIBLE;
    }

    private void openDrawer() {
        closeDrawerWhenHidden = false;
        if (isDrawerOpen()) return;
        dismissDialog();
        drawer.setVisibility(View.VISIBLE);
        content.setVisibility(View.INVISIBLE); // keeps its layout, but is not drawn under the drawer
    }

    private void closeDrawer() {
        closeDrawerWhenHidden = false;
        if (!isDrawerOpen()) return;
        drawer.setVisibility(View.GONE);
        content.setVisibility(View.VISIBLE);
        drawer.scrollToTop();
    }

    @Override
    public void onDrawerLaunch(AppEntry e) {
        if (launch(e.component)) closeDrawerWhenHidden = true;
    }

    @Override
    public void onDrawerDetails(AppEntry e) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", e.packageName, null));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!tryStart(i)) toast("Can't open app info");
    }

    @Override
    public void onDrawerSettings() {
        openAndroidSettings();
    }

    @Override
    public void onDrawerClose() {
        closeDrawer();
    }

    // ------------------------------------------------------------------------------------------ launching

    @Override
    @SuppressWarnings("deprecation")
    public void openPlayer(String pkg, PendingIntent sessionActivity) {
        if (pkg != null && launchPackageQuietly(pkg)) return;
        if (sessionActivity != null) {
            try {
                sessionActivity.send();
                overridePendingTransition(0, 0);
                return;
            } catch (PendingIntent.CanceledException | RuntimeException ignored) {
            }
        }
        if (!MediaCard.MUSIC_PKG.equals(pkg) && launchPackageQuietly(MediaCard.MUSIC_PKG)) return;
        toast("Player not available");
    }

    /** Launches a package's main activity; false (no toast) if it has none or the start fails. */
    private boolean launchPackageQuietly(String pkg) {
        AppRepository.Snapshot s = snapshot;
        AppEntry e = s == null ? null : s.byPackage.get(pkg);
        Intent i;
        if (e != null) {
            i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(e.component);
        } else {
            i = launchIntentFor(pkg); // only on tap, for players not in the app list
            if (i == null) return false;
        }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        return tryStart(i);
    }

    private Intent launchIntentFor(String pkg) {
        try {
            return getPackageManager().getLaunchIntentForPackage(pkg);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private boolean launch(ComponentName cn) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setComponent(cn);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        if (tryStart(i)) return true;
        toast("Can't open this app");
        return false;
    }

    /** startActivity without transition animation; never throws. */
    @SuppressWarnings("deprecation")
    private boolean tryStart(Intent i) {
        try {
            startActivity(i);
            overridePendingTransition(0, 0);
            return true;
        } catch (ActivityNotFoundException e) {
            Log.w(TAG, "not found: " + i);
        } catch (SecurityException e) {
            Log.w(TAG, "not allowed: " + i);
        } catch (RuntimeException e) {
            Log.w(TAG, "start failed: " + i, e);
        }
        return false;
    }

    private void toast(String msg) {
        try {
            Toast.makeText(getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException ignored) {
        }
    }
}
