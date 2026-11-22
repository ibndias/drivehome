package com.drivehome.launcher;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.AdaptiveIconDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.DisplayMetrics;
import android.util.Log;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Process-wide app list + icon cache. All PackageManager work and icon decoding/scaling runs on one
 * low-priority background thread; results are published to the main thread as immutable snapshots.
 * Reloads happen only on package add/remove/change (and locale change, for labels).
 */
final class AppRepository {
    private static final String TAG = "DriveHome";

    interface Listener {
        void onAppsChanged(Snapshot snapshot);
    }

    static final class Snapshot {
        /** All launchable apps except this one, sorted by label. */
        final List<AppEntry> apps;
        final Map<String, AppEntry> byKey;
        /** Preferred entry per package (the package's launch activity for the default packages). */
        final Map<String, AppEntry> byPackage;
        /** False while some icons are still being loaded. */
        final boolean complete;

        Snapshot(List<AppEntry> apps, Map<String, AppEntry> byKey, Map<String, AppEntry> byPackage, boolean complete) {
            this.apps = apps;
            this.byKey = byKey;
            this.byPackage = byPackage;
            this.complete = complete;
        }
    }

    /**
     * Default home tiles in order: package, plus the component verified on the device (used only if the
     * package exposes no launch intent). Waze is intentionally not a default.
     */
    static final String[][] DEFAULT_TILES = {
        {"com.google.android.apps.maps", "com.google.android.maps.MapsActivity"},
        {"com.tw.music", "com.tw.music.MusicActivity"},
        {"com.tw.radio", "com.tw.radio.RadioActivity"},
        {"com.tw.bt", "com.tw.bt.ATBluetoothActivity"},
        {"com.zjinnova.zlink", "com.zjinnova.android.zlink.features.main.MainActivity"},
        {"com.google.android.youtube", "com.google.android.youtube.app.honeycomb.Shell$HomeActivity"},
        {"com.baony.tw360", "com.baony.ui.activity.TopwayBVActivity"},
        {"com.tw.video", "com.tw.video.VideoActivity"},
        {"com.dofun.carsetting", "com.dofun.carsetting.ui.MainActivity"},
        {"com.tw.eq", "com.tw.eq.DSPActivity"},
    };

    private static AppRepository sInstance;

    /** Main thread only. */
    static AppRepository get(Context c) {
        if (sInstance == null) sInstance = new AppRepository(c.getApplicationContext());
        return sInstance;
    }

    private final Context context;
    private final PackageManager pm;
    private final String selfPackage;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(() -> {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            } catch (Throwable ignored) {
            }
            r.run();
        }, "DriveHome-apps");
        t.setDaemon(true);
        return t;
    });

    final int tileIconSize;
    final int gridIconSize;
    private final int iconDensity;

    // Main thread state.
    private final ArrayList<Listener> listeners = new ArrayList<>();
    private Snapshot snapshot;
    private boolean started;

    private volatile Set<String> priorityPackages = Collections.emptySet();

    // Pending invalidation, guarded by "this".
    private final HashSet<String> dirtyPackages = new HashSet<>();
    private boolean dirtyAll = true;
    private boolean dirtyLabels;

    // Loader-thread only.
    private final HashMap<String, AppEntry> cache = new HashMap<>();

    private final Runnable reloadRunnable = new Runnable() {
        @Override
        public void run() {
            executor.execute(AppRepository.this::loadSafely);
        }
    };

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            String action = intent == null ? null : intent.getAction();
            if (Intent.ACTION_LOCALE_CHANGED.equals(action)) {
                synchronized (AppRepository.this) {
                    dirtyLabels = true;
                }
            } else if (Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE.equals(action)
                    || Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE.equals(action)) {
                String[] pkgs = null;
                try {
                    pkgs = intent.getStringArrayExtra(Intent.EXTRA_CHANGED_PACKAGE_LIST);
                } catch (RuntimeException ignored) {
                }
                synchronized (AppRepository.this) {
                    if (pkgs == null) {
                        dirtyAll = true;
                    } else {
                        Collections.addAll(dirtyPackages, pkgs);
                    }
                }
            } else {
                Uri data = intent == null ? null : intent.getData();
                String pkg = data == null ? null : data.getSchemeSpecificPart();
                synchronized (AppRepository.this) {
                    if (pkg == null) dirtyAll = true;
                    else dirtyPackages.add(pkg);
                }
            }
            // Debounce bursts (an update sends REMOVED + ADDED + REPLACED).
            main.removeCallbacks(reloadRunnable);
            main.postDelayed(reloadRunnable, 600);
        }
    };

    private AppRepository(Context app) {
        context = app;
        pm = app.getPackageManager();
        selfPackage = app.getPackageName();
        tileIconSize = Ui.dp(app, 72);
        gridIconSize = Ui.dp(app, 64);
        // Pick a density bucket that provides at least the tile size for a 48dp launcher icon.
        int needed = (int) Math.ceil(tileIconSize * 160f / 48f);
        int[] buckets = {DisplayMetrics.DENSITY_MEDIUM, DisplayMetrics.DENSITY_HIGH, DisplayMetrics.DENSITY_XHIGH,
                DisplayMetrics.DENSITY_XXHIGH, DisplayMetrics.DENSITY_XXXHIGH};
        int chosen = DisplayMetrics.DENSITY_XXXHIGH;
        for (int b : buckets) {
            if (b >= needed) {
                chosen = b;
                break;
            }
        }
        iconDensity = chosen;
    }

    // ---------------------------------------------------------------- main-thread API

    void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    Snapshot snapshot() {
        return snapshot;
    }

    /** Packages whose icons are loaded first so the home tiles appear quickly. */
    void setPriorityPackages(Set<String> pkgs) {
        priorityPackages = pkgs == null ? Collections.<String>emptySet() : new HashSet<>(pkgs);
    }

    /** Starts the first load and the package receivers (once per process). */
    void ensureLoaded() {
        if (started) return;
        started = true;
        try {
            IntentFilter pf = new IntentFilter();
            pf.addAction(Intent.ACTION_PACKAGE_ADDED);
            pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
            pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
            pf.addAction(Intent.ACTION_PACKAGE_REPLACED);
            pf.addDataScheme("package");
            context.registerReceiver(receiver, pf);

            IntentFilter of = new IntentFilter();
            of.addAction(Intent.ACTION_LOCALE_CHANGED);
            of.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE);
            of.addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE);
            context.registerReceiver(receiver, of);
        } catch (RuntimeException e) {
            Log.w(TAG, "registerReceiver failed", e);
        }
        executor.execute(this::loadSafely);
    }

    // ---------------------------------------------------------------- loader thread

    private void loadSafely() {
        try {
            load();
        } catch (Throwable t) {
            // Never let the loader thread die with an uncaught exception (that would kill the launcher).
            Log.w(TAG, "app load failed", t);
        }
    }

    private void load() {
        Set<String> dirty;
        boolean all;
        boolean labels;
        synchronized (this) {
            dirty = new HashSet<>(dirtyPackages);
            dirtyPackages.clear();
            all = dirtyAll;
            dirtyAll = false;
            labels = dirtyLabels;
            dirtyLabels = false;
        }
        if (all) {
            cache.clear();
        } else if (!dirty.isEmpty()) {
            Iterator<AppEntry> it = cache.values().iterator();
            while (it.hasNext()) {
                if (dirty.contains(it.next().packageName)) it.remove();
            }
        }

        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = null;
        try {
            infos = pm.queryIntentActivities(launcher, 0);
        } catch (RuntimeException e) {
            Log.w(TAG, "queryIntentActivities failed", e);
        }
        if (infos == null) infos = Collections.emptyList();

        LinkedHashMap<String, AppEntry> found = new LinkedHashMap<>();
        HashMap<String, ActivityInfo> activityInfos = new HashMap<>();
        for (ResolveInfo ri : infos) {
            ActivityInfo ai = ri == null ? null : ri.activityInfo;
            if (ai == null || ai.packageName == null || ai.name == null) continue;
            if (selfPackage.equals(ai.packageName)) continue;
            ComponentName cn = new ComponentName(ai.packageName, ai.name);
            String key = cn.flattenToString();
            if (found.containsKey(key)) continue;
            AppEntry e = cache.get(key);
            if (e == null || labels) {
                CharSequence raw = null;
                try {
                    raw = ri.loadLabel(pm);
                } catch (RuntimeException ignored) {
                }
                e = fresh(cn, key, raw, e);
            }
            found.put(key, e);
            activityInfos.put(key, ai);
        }

        HashMap<String, AppEntry> byPackage = new HashMap<>();
        for (String[] def : DEFAULT_TILES) {
            AppEntry e = resolveDefault(def[0], def[1], found, activityInfos, labels);
            if (e != null) byPackage.put(def[0], e);
        }
        for (AppEntry e : found.values()) {
            if (!byPackage.containsKey(e.packageName)) byPackage.put(e.packageName, e);
        }
        cache.keySet().retainAll(found.keySet());

        ArrayList<AppEntry> list = new ArrayList<>(found.values());
        final Collator collator = Collator.getInstance();
        collator.setStrength(Collator.PRIMARY);
        Collections.sort(list, (a, b) -> {
            int c = collator.compare(a.label, b.label);
            return c != 0 ? c : a.key.compareTo(b.key);
        });

        // Icons: home-tile packages first, publish, then the rest.
        Set<String> prio = priorityPackages;
        for (AppEntry e : list) {
            if (e.tileIcon == null && prio.contains(e.packageName)) loadIcons(e, activityInfos.get(e.key));
        }
        int missing = 0;
        for (AppEntry e : list) {
            if (e.tileIcon == null) missing++;
        }
        if (missing > 6) {
            publish(list, byPackage, false);
        }
        if (missing > 0) {
            for (AppEntry e : list) {
                if (e.tileIcon == null) loadIcons(e, activityInfos.get(e.key));
            }
        }
        publish(list, byPackage, true);
    }

    /** New entry (label (re)loaded); keeps icons of the previous entry for label-only refreshes. */
    private AppEntry fresh(ComponentName cn, String key, CharSequence rawLabel, AppEntry previous) {
        String label = rawLabel == null ? "" : rawLabel.toString().replace('\n', ' ').trim();
        if (label.isEmpty()) label = cn.getPackageName();
        AppEntry e = new AppEntry(cn, key, label);
        if (previous != null) {
            e.gridIcon = previous.gridIcon;
            e.tileIcon = previous.tileIcon;
        }
        cache.put(key, e);
        return e;
    }

    /** Resolves a default package via its launch intent, falling back to the known component. */
    private AppEntry resolveDefault(String pkg, String cls, Map<String, AppEntry> found,
            Map<String, ActivityInfo> activityInfos, boolean labels) {
        ComponentName cn = null;
        try {
            Intent li = pm.getLaunchIntentForPackage(pkg);
            if (li != null) cn = li.getComponent();
        } catch (RuntimeException ignored) {
        }
        if (cn != null) {
            AppEntry e = found.get(cn.flattenToString());
            if (e != null) return e;
        } else {
            cn = new ComponentName(pkg, cls);
        }
        String key = cn.flattenToString();
        AppEntry e = found.get(key);
        if (e != null) return e;

        ActivityInfo ai = null;
        try {
            ai = pm.getActivityInfo(cn, 0);
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        if (ai == null || !ai.exported || !ai.enabled
                || (ai.applicationInfo != null && !ai.applicationInfo.enabled)) {
            for (AppEntry x : found.values()) {
                if (pkg.equals(x.packageName)) return x;
            }
            return null;
        }
        e = cache.get(key);
        if (e == null || labels) {
            CharSequence raw = null;
            try {
                raw = ai.loadLabel(pm);
            } catch (RuntimeException ignored) {
            }
            e = fresh(cn, key, raw, e);
        }
        found.put(key, e);
        activityInfos.put(key, ai);
        return e;
    }

    private void loadIcons(AppEntry e, ActivityInfo ai) {
        Drawable d = null;
        if (ai != null) {
            try {
                int res = ai.getIconResource();
                if (res != 0 && ai.applicationInfo != null) {
                    Resources r = pm.getResourcesForApplication(ai.applicationInfo);
                    d = r.getDrawableForDensity(res, iconDensity, null);
                }
            } catch (Throwable t) {
                d = null;
            }
            if (d == null) {
                try {
                    d = ai.loadIcon(pm);
                } catch (Throwable t) {
                    d = null;
                }
            }
        }
        if (d == null) {
            try {
                d = pm.getDefaultActivityIcon();
            } catch (Throwable t) {
                return;
            }
        }
        try {
            e.gridIcon = render(d, gridIconSize);
            e.tileIcon = render(d, tileIconSize); // written last: non-null tileIcon means "loaded"
        } catch (Throwable t) {
            Log.w(TAG, "icon render failed for " + e.key, t);
        }
    }

    private static Bitmap render(Drawable d, int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        int w = size;
        int h = size;
        int iw = d.getIntrinsicWidth();
        int ih = d.getIntrinsicHeight();
        if (iw > 0 && ih > 0 && iw != ih) {
            if (iw > ih) h = Math.max(1, size * ih / iw);
            else w = Math.max(1, size * iw / ih);
        }
        int inset = 0;
        if (d instanceof AdaptiveIconDrawable) {
            // Adaptive icons fill their bounds edge to edge; shrink slightly to match legacy icons.
            inset = Math.round(size * 0.04f);
        }
        int l = (size - w) / 2 + inset;
        int t = (size - h) / 2 + inset;
        Rect old = d.copyBounds();
        d.setBounds(l, t, l + w - 2 * inset, t + h - 2 * inset);
        d.draw(c);
        d.setBounds(old);
        c.setBitmap(null);
        b.prepareToDraw();
        return b;
    }

    private void publish(List<AppEntry> list, Map<String, AppEntry> byPackage, boolean complete) {
        HashMap<String, AppEntry> byKey = new HashMap<>();
        for (AppEntry e : list) byKey.put(e.key, e);
        final Snapshot s = new Snapshot(
                Collections.unmodifiableList(new ArrayList<>(list)),
                Collections.unmodifiableMap(byKey),
                Collections.unmodifiableMap(new HashMap<>(byPackage)),
                complete);
        main.post(() -> deliver(s));
    }

    private void deliver(Snapshot s) {
        snapshot = s;
        for (Listener l : new ArrayList<>(listeners)) {
            try {
                l.onAppsChanged(s);
            } catch (RuntimeException e) {
                Log.w(TAG, "listener failed", e);
            }
        }
    }
}
