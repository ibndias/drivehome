package com.drivehome.launcher;

import android.Manifest;
import android.app.Activity;
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
import android.graphics.Bitmap;
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
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * The home screen ("Cockpit"). Entire UI is built in code. Left: the speed gauge. Right: time/date over
 * the now-playing card. Bottom: a dock of 8 pinned apps + "All apps". The app drawer is an in-activity
 * overlay; menus and pickers are {@link Sheet} dialogs.
 */
public class HomeActivity extends Activity
        implements AppRepository.Listener, DrawerView.Host, MediaCard.Host, Updater.Listener {
    private static final String TAG = "DriveHome";
    private static final String PREFS = "drivehome";
    /** Slot assignments: "tile_<index>" = flattened component (same storage as the old tile grid). */
    private static final String KEY_TILE = "tile_";
    /** True = speed gauge on the right edge (driver sits on the right). */
    private static final String KEY_GAUGE_RIGHT = "gauge_right";
    private static final int APP_SLOTS = 8;
    private static final long SPEED_STALE_MS = 5000L;

    private static final String ORIGINAL_LAUNCHER_PKG = "com.dofun.variety";
    private static final ComponentName ORIGINAL_LAUNCHER =
            new ComponentName(ORIGINAL_LAUNCHER_PKG, "com.dofun.overseasvariety.Launcher");

    /** Never used as default/backfill slots (still assignable by hand if installed). */
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
    private final AppCellView[] tiles = new AppCellView[APP_SLOTS];
    private final AppEntry[] tileEntries = new AppEntry[APP_SLOTS];
    /** True where the slot shows an app the user put there (not the slot's default). */
    private final boolean[] tilePinned = new boolean[APP_SLOTS];

    private AppRepository repo;
    private AppRepository.Snapshot snapshot;
    private SharedPreferences prefs;

    private HomeLayout home;
    private TileGrid dock;
    private SpeedGauge gauge;
    private ClockView clockView;
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

    private final Runnable speedStale = () -> setSpeed(SpeedGauge.UNKNOWN);

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
            setSpeed(SpeedGauge.UNKNOWN);
        }
    };

    // ------------------------------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        migrateOldTiles();
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
            Ui.setText(updateChip, "Install update v" + ready);
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
        FrameLayout root = new FrameLayout(this);
        root.setFitsSystemWindows(true);

        // Information: bare on the window background. Long-press either = system menu.
        View.OnLongClickListener systemMenu = v -> {
            showSystemMenu();
            return true;
        };
        gauge = new SpeedGauge(this);
        gauge.setOnLongClickListener(systemMenu);
        clockView = new ClockView(this);
        clockView.setOnLongClickListener(systemMenu);

        // Shown only when Updater has a newer, verified APK downloaded. Tap -> Android's install prompt.
        updateChip = Ui.text(this, dp(Ui.T_BODY), Ui.WHITE, Ui.medium());
        updateChip.setGravity(Gravity.CENTER);
        updateChip.setBackground(Ui.pressable(Ui.ACCENT, Ui.ACCENT_PRESSED, dp(Ui.TOUCH / 2f)));
        updateChip.setPadding(dp(28), 0, dp(28), 0);
        updateChip.setVisibility(View.GONE);
        updateChip.setOnClickListener(v -> {
            // Hide immediately: a double tap must not create two install sessions.
            // onResume re-syncs it if the install was cancelled or failed.
            v.setVisibility(View.GONE);
            toast("Installing update…");
            Updater.get(this).install();
        });

        mediaCard = new MediaCard(this, this);

        // Dock: one card holding the pinned apps; each cell shows its own pressed plate.
        dock = new TileGrid(this, 0);
        dock.setBackground(Ui.card(this));
        for (int i = 0; i < APP_SLOTS; i++) {
            AppCellView tile = newSlotCell(Ui.CARD_PRESSED);
            final int index = i;
            tile.setOnClickListener(v -> onTileClick(index));
            tile.setOnLongClickListener(v -> {
                showTileMenu(index);
                return true;
            });
            tiles[i] = tile;
            dock.addView(tile);
        }

        AppCellView allApps = new AppCellView(this, dp(64), dp(Ui.T_LABEL), Ui.medium(), false);
        allApps.setAllApps("All apps");
        allApps.setBackground(Ui.pressableCard(this));
        allApps.setOnClickListener(v -> openDrawer());

        home = new HomeLayout(this, gauge, clockView, updateChip, mediaCard, dock, allApps);
        home.setGaugeRight(prefs.getBoolean(KEY_GAUGE_RIGHT, false));
        root.addView(home, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        drawer = new DrawerView(this, this);
        drawer.setVisibility(View.GONE);
        root.addView(drawer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return root;
    }

    /** A dock-style cell (64dp icon + label) whose pressed plate sits 6dp inside the cell. */
    private AppCellView newSlotCell(int pressedColor) {
        AppCellView cell = new AppCellView(this, dp(64), dp(Ui.T_LABEL), Ui.medium(), false);
        cell.setBackground(Ui.pressablePlate(pressedColor, dp(Ui.RADIUS - 6), dp(6)));
        return cell;
    }

    /** Landscape (normal): gauge | clock + media over an 8x1 dock. Portrait fallback: stacked, 4x2 dock. */
    private void applyLayoutMode(Configuration c) {
        boolean landscape = c == null || c.screenWidthDp == 0 || c.screenHeightDp == 0
                || c.screenWidthDp >= c.screenHeightDp;
        int mode = landscape ? 0 : 1;
        if (mode == layoutMode) return;
        layoutMode = mode;
        home.setPortrait(!landscape);
        if (landscape) {
            dock.setGrid(APP_SLOTS, 1);
            drawer.setColumns(8);
        } else {
            dock.setGrid(APP_SLOTS / 2, 2);
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
        if (clockView == null) return;
        try {
            Date now = new Date();
            Locale loc = Locale.getDefault();
            boolean is24 = DateFormat.is24HourFormat(this);
            clockView.set(new SimpleDateFormat(is24 ? "HH:mm" : "h:mm", loc).format(now),
                    new SimpleDateFormat("EEEE", loc).format(now),
                    new SimpleDateFormat("d MMMM", loc).format(now));
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
        gauge.setAvailable(granted);
        if (!granted) {
            setSpeed(SpeedGauge.UNKNOWN);
            return;
        }
        if (locationActive) return;
        setSpeed(SpeedGauge.UNKNOWN);
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
        setSpeed(l.hasSpeed() ? Math.max(0, Math.round(l.getSpeed() * 3.6f)) : SpeedGauge.UNKNOWN);
        handler.removeCallbacks(speedStale);
        handler.postDelayed(speedStale, SPEED_STALE_MS); // fix lost -> "--"
    }

    /** At most 1 Hz; the gauge ignores unchanged values and never re-lays out. */
    private void setSpeed(int kmh) {
        if (gauge != null) gauge.setSpeed(kmh);
    }

    // ------------------------------------------------------------------------------------------ apps & slots

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
        for (int i = 0; i < APP_SLOTS; i++) {
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

    /**
     * Apps for slots the user has not assigned, in order: the default packages that are installed,
     * then backfill. Entry i is slot i's default; later entries replace defaults that are taken.
     */
    private List<AppEntry> defaultCandidates(AppRepository.Snapshot s) {
        final int max = APP_SLOTS * 3;
        ArrayList<AppEntry> out = new ArrayList<>(max);
        HashSet<String> usedPkgs = new HashSet<>();
        for (String[] d : AppRepository.DEFAULT_TILES) {
            if (out.size() >= max) break;
            AppEntry e = s.byPackage.get(d[0]);
            if (e != null && usedPkgs.add(e.packageName)) out.add(e);
        }
        for (String p : PREFERRED_BACKFILL) {
            if (out.size() >= max) break;
            AppEntry e = s.byPackage.get(p);
            if (e != null && usedPkgs.add(e.packageName)) out.add(e);
        }
        for (AppEntry e : s.apps) {
            if (out.size() >= max) break;
            if (neverDefault(e.packageName)) continue;
            if (usedPkgs.add(e.packageName)) out.add(e);
        }
        return out;
    }

    /**
     * Resolves what every slot shows, never putting an app on the home screen twice:
     * 1. the user's assignments (skipping uninstalled apps and repeats),
     * 2. each remaining slot's own default, if that app is not already placed,
     * 3. anything still empty takes the next default that is not placed.
     */
    /**
     * One-time: the pre-Cockpit layout had 11 app tiles (tile_0..tile_10); the dock has
     * {@link #APP_SLOTS}. Move user picks from the dropped slots into free dock slots (no
     * duplicates) and delete the old keys, so nothing reappears if the dock ever grows.
     */
    private void migrateOldTiles() {
        if (prefs.getBoolean("slots_v2", false)) return;
        SharedPreferences.Editor ed = prefs.edit();
        HashSet<String> kept = new HashSet<>();
        for (int i = 0; i < APP_SLOTS; i++) {
            String v = prefs.getString(KEY_TILE + i, null);
            if (v != null) kept.add(v);
        }
        int free = 0;
        for (int k = APP_SLOTS; k < 11; k++) {
            String v = prefs.getString(KEY_TILE + k, null);
            ed.remove(KEY_TILE + k);
            if (v == null || !kept.add(v)) continue;
            while (free < APP_SLOTS && prefs.getString(KEY_TILE + free, null) != null) free++;
            if (free < APP_SLOTS) ed.putString(KEY_TILE + free++, v);
        }
        ed.putBoolean("slots_v2", true).apply();
    }

    private void resolveSlots(AppRepository.Snapshot s, AppEntry[] out, boolean[] pinned) {
        HashSet<String> usedKeys = new HashSet<>();
        HashSet<String> usedPkgs = new HashSet<>();
        for (int i = 0; i < APP_SLOTS; i++) {
            out[i] = null;
            pinned[i] = false;
            String saved = prefs.getString(KEY_TILE + i, null);
            AppEntry e = saved == null ? null : s.byKey.get(saved);
            if (e != null && usedKeys.add(e.key)) {
                usedPkgs.add(e.packageName);
                out[i] = e;
                pinned[i] = true;
            }
        }
        List<AppEntry> candidates = defaultCandidates(s);
        for (int i = 0; i < APP_SLOTS && i < candidates.size(); i++) {
            if (out[i] != null) continue;
            AppEntry e = candidates.get(i);
            if (!usedKeys.contains(e.key) && usedPkgs.add(e.packageName)) {
                usedKeys.add(e.key);
                out[i] = e;
            }
        }
        int next = 0;
        for (int i = 0; i < APP_SLOTS; i++) {
            if (out[i] != null) continue;
            while (next < candidates.size()) {
                AppEntry e = candidates.get(next++);
                if (!usedKeys.contains(e.key) && usedPkgs.add(e.packageName)) {
                    usedKeys.add(e.key);
                    out[i] = e;
                    break;
                }
            }
        }
    }

    private void bindTiles() {
        AppRepository.Snapshot s = snapshot;
        if (s == null) return;
        resolveSlots(s, tileEntries, tilePinned);
        for (int i = 0; i < APP_SLOTS; i++) {
            // "+" only once loading has finished, so slots do not flash it during start-up.
            tiles[i].setEmptyLabel(tileEntries[i] == null && s.complete ? "Add app" : null);
            tiles[i].bind(tileEntries[i]);
        }
    }

    /** Home slot showing this app, or -1. */
    private int slotOf(AppEntry e) {
        if (e == null) return -1;
        for (int i = 0; i < APP_SLOTS; i++) {
            AppEntry t = tileEntries[i];
            if (t != null && t.key.equals(e.key)) return i;
        }
        return -1;
    }

    /** Stores slot i (the slot's own default is stored as "no assignment"). */
    private static void putSlot(SharedPreferences.Editor ed, List<AppEntry> candidates, int i, AppEntry e) {
        AppEntry def = i < candidates.size() ? candidates.get(i) : null;
        if (e == null || (def != null && def.key.equals(e.key))) ed.remove(KEY_TILE + i);
        else ed.putString(KEY_TILE + i, e.key);
    }

    private void applySlots(SharedPreferences.Editor ed) {
        ed.apply(); // in-memory immediately; written to disk in the background
        repo.setPriorityPackages(priorityPackages());
        bindTiles();
    }

    /**
     * Puts app into slot index. If the app is already on the home screen the two slots swap, so an
     * app is never shown twice.
     */
    private void assignSlot(int index, AppEntry app) {
        AppRepository.Snapshot s = snapshot;
        if (s == null || app == null || index < 0 || index >= APP_SLOTS) return;
        int other = slotOf(app);
        if (other == index) return;
        List<AppEntry> candidates = defaultCandidates(s);
        SharedPreferences.Editor ed = prefs.edit();
        if (other >= 0) putSlot(ed, candidates, other, tileEntries[index]);
        putSlot(ed, candidates, index, app);
        applySlots(ed);
    }

    private boolean packageOnHome(String pkg) {
        for (int i = 0; i < APP_SLOTS; i++) {
            AppEntry t = tileEntries[i];
            if (t != null && t.packageName.equals(pkg)) return true;
        }
        return false;
    }

    /** What "Remove from home" would put into the slot: its default, or the next default not on home. */
    private AppEntry replacementFor(AppRepository.Snapshot s, int index) {
        List<AppEntry> candidates = defaultCandidates(s);
        if (index < candidates.size() && !packageOnHome(candidates.get(index).packageName)) {
            return candidates.get(index);
        }
        for (AppEntry e : candidates) {
            if (!packageOnHome(e.packageName)) return e;
        }
        return null;
    }

    private void removeFromHome(int index) {
        AppRepository.Snapshot s = snapshot;
        if (s == null || index < 0 || index >= APP_SLOTS) return;
        SharedPreferences.Editor ed = prefs.edit();
        putSlot(ed, defaultCandidates(s), index, replacementFor(s, index));
        applySlots(ed);
    }

    private void onTileClick(int index) {
        AppEntry e = tileEntries[index];
        if (e == null) {
            showTileMenu(index); // empty slot: let the user pick an app
            return;
        }
        launch(e.component);
    }

    // ------------------------------------------------------------------------------------------ sheets

    /** Long-press on a home slot: remove it from home, or choose another app for the slot. */
    private void showTileMenu(final int index) {
        final AppRepository.Snapshot s = snapshot;
        if (s == null || s.apps.isEmpty()) {
            toast("Apps are still loading");
            return;
        }
        final AppEntry current = tileEntries[index];
        ArrayList<Sheet.Row> rows = new ArrayList<>(s.apps.size());
        for (final AppEntry e : s.apps) {
            Sheet.Row r = new Sheet.Row(e.label, () -> assignSlot(index, e));
            r.icon = e.gridIcon;
            r.iconSlot = true;
            if (current != null && current.key.equals(e.key)) {
                r.note = "This slot";
                r.enabled = false;
            } else if (slotOf(e) >= 0) {
                r.note = "On home"; // choosing it swaps the two slots
            }
            rows.add(r);
        }
        final int columns = 2;
        GridView grid = Sheet.rows(this, rows, columns, this::pick);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        int extra = 0;
        if (current != null && tilePinned[index]) {
            AppEntry back = replacementFor(s, index);
            Sheet.Row remove = new Sheet.Row("Remove from home", null);
            remove.remove = true;
            remove.color = Ui.ACCENT;
            if (back != null) {
                List<AppEntry> candidates = defaultCandidates(s);
                boolean ownDefault = index < candidates.size() && candidates.get(index).key.equals(back.key);
                remove.note = ownDefault ? "Back to " + back.label : back.label + " instead";
            }
            RowView rv = new RowView(this);
            rv.bind(remove);
            final String currentKey = current.key;
            rv.setOnClickListener(v -> {
                dismissDialog();
                // Only if the slot still holds the app this menu was opened for.
                AppEntry nowThere = tileEntries[index];
                if (nowThere != null && currentKey.equals(nowThere.key) && tilePinned[index]) removeFromHome(index);
            });
            int rowH = dp(RowView.HEIGHT);
            int gap = dp(8);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowH);
            rlp.bottomMargin = gap;
            body.addView(rv, rlp);
            extra = rowH + gap;
        }
        int gridHeight = listHeight(rows.size(), columns, extra);
        body.addView(grid, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, gridHeight));

        String title = current != null ? current.label : "Empty slot";
        showDialog(Sheet.create(this, current != null ? current.gridIcon : null, title,
                "Choose the app for this home slot", body, gridHeight + extra), dp(960));
    }

    /** Long-press on an app in the drawer. */
    @Override
    public void onDrawerMenu(final AppEntry e) {
        if (e == null) return;
        ArrayList<Sheet.Row> rows = new ArrayList<>(3);
        final int slot = slotOf(e);
        if (slot >= 0) {
            Sheet.Row on = new Sheet.Row("On home screen", null);
            on.check = true;
            on.enabled = false;
            on.color = Ui.TEXT2;
            rows.add(on);
            if (tilePinned[slot]) {
                rows.add(new Sheet.Row("Remove from home", () -> {
                    // Re-resolve at tap time: a package change while the menu was open may
                    // have moved apps between slots.
                    int now = slotOf(e);
                    if (now >= 0 && tilePinned[now]) removeFromHome(now);
                    closeDrawer(); // the home screen itself confirms the change
                }));
            }
        } else {
            rows.add(new Sheet.Row("Pin to home screen", () -> showPinChooser(e)));
        }
        rows.add(new Sheet.Row("App info", () -> openAppInfo(e)));
        showMenu(e.gridIcon, e.label, null, rows);
    }

    /** "Pin to home screen": the dock is shown as it is on the home screen; tap the slot to use. */
    private void showPinChooser(final AppEntry app) {
        if (snapshot == null) return;
        if (slotOf(app) >= 0) {
            toast(app.label + " is already on home");
            return;
        }
        boolean hasEmpty = false;
        TileGrid strip = new TileGrid(this, 0);
        strip.setGrid(APP_SLOTS, 1);
        strip.setBackground(Ui.card(this));
        for (int i = 0; i < APP_SLOTS; i++) {
            AppCellView cell = newSlotCell(Ui.BUTTON_PRESSED);
            if (tileEntries[i] == null) {
                hasEmpty = true;
                cell.setEmptyLabel("Empty");
            }
            cell.bind(tileEntries[i]);
            final int index = i;
            cell.setOnClickListener(v -> {
                dismissDialog();
                assignSlot(index, app);
                closeDrawer(); // the home screen itself confirms the change
            });
            strip.addView(cell);
        }
        String hint = hasEmpty ? "Tap an empty slot, or the app to replace" : "Tap the app to replace";
        showDialog(Sheet.create(this, app.gridIcon, "Pin " + app.label + " to home", hint, strip, dp(128)),
                dp(APP_SLOTS * 138 + 24));
    }

    private void showSystemMenu() {
        ArrayList<Sheet.Row> rows = new ArrayList<>(4);
        rows.add(new Sheet.Row("Original launcher", this::openOriginalLauncher));
        rows.add(new Sheet.Row("Choose home app", this::openHomeSettings));
        rows.add(new Sheet.Row("Android settings", this::openAndroidSettings));
        Sheet.Row update = new Sheet.Row("Check for updates", () -> {
            toast("Checking for updates…");
            Updater.get(this).checkNow();
        });
        update.note = "v" + Updater.get(this).currentVersion();
        rows.add(update);
        // The gauge belongs on the driver's side of the screen (left- or right-hand drive).
        final boolean right = prefs.getBoolean(KEY_GAUGE_RIGHT, false);
        rows.add(new Sheet.Row(right ? "Move speed to the left" : "Move speed to the right", () -> {
            prefs.edit().putBoolean(KEY_GAUGE_RIGHT, !right).apply();
            home.setGaugeRight(!right);
        }));
        showMenu(null, "DriveHome", null, rows);
    }

    private void showMenu(Bitmap icon, String title, String subtitle, List<Sheet.Row> rows) {
        GridView list = Sheet.rows(this, rows, 1, this::pick);
        int height = listHeight(rows.size(), 1, 0);
        showDialog(Sheet.create(this, icon, title, subtitle, list, height), dp(600));
    }

    private void pick(Sheet.Row row) {
        dismissDialog();
        if (row.action != null) row.action.run();
    }

    /**
     * Height for a row list inside a sheet: all rows if they fit the screen, otherwise a whole
     * number of rows plus half a row (the cut-off row shows that the list scrolls).
     */
    private int listHeight(int count, int columns, int extra) {
        int rowH = dp(RowView.HEIGHT);
        int lines = (count + columns - 1) / columns;
        int screen = home != null && home.getHeight() > 0 ? home.getHeight()
                : getResources().getDisplayMetrics().heightPixels - dp(116);
        // Screen minus a margin, the sheet header (close button + padding) and the body's bottom margin.
        int avail = screen - dp(24) - dp(Ui.TOUCH + 24) - dp(12) - extra;
        if (lines * rowH <= avail) return lines * rowH;
        int whole = Math.max(1, (avail - rowH / 2) / rowH);
        return whole * rowH + rowH / 2;
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

    private void openAppInfo(AppEntry e) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", e.packageName, null));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!tryStart(i)) toast("Can't open app info");
    }

    /** Shows a sheet: no open/close animation, no dim layer, no shadow; only one at a time. */
    private void showDialog(Dialog d, int width) {
        dismissDialog();
        if (isFinishing() || isDestroyed()) return;
        try {
            Window w = d.getWindow();
            if (w != null) {
                w.setWindowAnimations(0);
                w.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                w.setDimAmount(0f);
                w.setElevation(0f);
                w.setBackgroundDrawable(Ui.rounded(Ui.DIALOG, dp(Ui.RADIUS), Ui.DIALOG_STROKE, Math.max(1, dp(1))));
                int max = home != null && home.getWidth() > 0 ? home.getWidth() - dp(32) : width;
                w.setLayout(Math.max(dp(240), Math.min(width, max)), ViewGroup.LayoutParams.WRAP_CONTENT);
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
        home.setVisibility(View.INVISIBLE); // keeps its layout, but is not drawn under the drawer
    }

    private void closeDrawer() {
        closeDrawerWhenHidden = false;
        if (!isDrawerOpen()) return;
        drawer.setVisibility(View.GONE);
        home.setVisibility(View.VISIBLE);
        drawer.scrollToTop();
    }

    @Override
    public void onDrawerLaunch(AppEntry e) {
        if (launch(e.component)) closeDrawerWhenHidden = true;
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
