package com.drivehome.launcher;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Self-update from GitHub Releases of {@link #REPO}.
 *
 * CI publishes every build as release "v&lt;versionCode&gt;" with drivehome.apk attached.
 * We poll the latest release (at most every {@link #CHECK_INTERVAL_MS}), download a newer APK
 * in the background, then tell the UI. Installing is user-initiated (tap) and goes through
 * PackageInstaller, which shows Android's one-tap "Install update?" confirmation.
 * Nothing here runs on the main thread except listener callbacks.
 */
final class Updater {
    static final String REPO = "ibndias/drivehome";
    private static final String LATEST_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final long CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000;
    static final String ACTION_INSTALL_STATUS = "com.drivehome.launcher.INSTALL_STATUS";
    private static final String TAG = "DriveHomeUpdater";

    interface Listener {
        /** A newer, verified APK is downloaded and ready to install. Main thread. */
        void onUpdateReady(int versionCode);
        /** Result of a manual check, for a short toast. Main thread. */
        void onCheckResult(String message);
    }

    private static Updater sInstance;

    static synchronized Updater get(Context ctx) {
        if (sInstance == null) sInstance = new Updater(ctx.getApplicationContext());
        return sInstance;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private final SharedPreferences prefs;
    private Listener listener;
    private volatile boolean busy;
    private volatile int readyCode;
    private volatile File readyApk;

    private Updater(Context app) {
        this.app = app;
        this.prefs = app.getSharedPreferences("updater", Context.MODE_PRIVATE);
    }

    void setListener(Listener l) {
        listener = l;
        if (l != null && readyApk != null && readyApk.exists() && readyCode > currentVersion()) {
            l.onUpdateReady(readyCode);
        }
    }

    int readyVersion() { return (readyApk != null && readyApk.exists()) ? readyCode : 0; }

    /** Cheap; call from onResume. Only hits the network if the interval has passed. */
    void maybeCheck() {
        long last = prefs.getLong("lastCheck", 0);
        if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return;
        check(false);
    }

    /** Force a check now (menu item). */
    void checkNow() { check(true); }

    private void check(final boolean manual) {
        if (busy) return;
        busy = true;
        exec.execute(() -> {
            String msg;
            try {
                msg = doCheck();
                prefs.edit().putLong("lastCheck", System.currentTimeMillis()).apply();
            } catch (Throwable t) {
                Log.w(TAG, "update check failed", t);
                msg = "Update check failed: " + t.getClass().getSimpleName();
            } finally {
                busy = false;
            }
            if (manual) {
                final String m = msg;
                main.post(() -> { if (listener != null) listener.onCheckResult(m); });
            }
        });
    }

    /** Background thread. Returns a human-readable status. */
    private String doCheck() throws Exception {
        JSONObject rel = new JSONObject(new String(httpGet(LATEST_URL), "UTF-8"));
        String tag = rel.optString("tag_name", "");
        int remote = parseVersion(tag);
        int local = currentVersion();
        if (remote <= local) return "DriveHome is up to date (v" + local + ")";

        String apkUrl = null;
        long apkSize = -1;
        JSONArray assets = rel.optJSONArray("assets");
        for (int i = 0; assets != null && i < assets.length(); i++) {
            JSONObject a = assets.getJSONObject(i);
            if (a.optString("name", "").endsWith(".apk")) {
                apkUrl = a.optString("browser_download_url", null);
                apkSize = a.optLong("size", -1);
                break;
            }
        }
        if (apkUrl == null) return "Release " + tag + " has no APK";

        File dir = new File(app.getCacheDir(), "updates");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("no cache dir");
        File apk = new File(dir, "drivehome-" + remote + ".apk");
        if (!(apk.exists() && apkSize > 0 && apk.length() == apkSize)) {
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
                f.delete();
            download(apkUrl, apk);
        }

        // Sanity check: same package, the version we expect.
        PackageInfo pi = app.getPackageManager().getPackageArchiveInfo(apk.getPath(), 0);
        if (pi == null || !app.getPackageName().equals(pi.packageName) || (int) pi.getLongVersionCode() != remote) {
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
            return "Downloaded update looks wrong; skipped";
        }

        readyCode = remote;
        readyApk = apk;
        main.post(() -> { if (listener != null) listener.onUpdateReady(remote); });
        return "Update v" + remote + " downloaded";
    }

    /** User tapped "install". Hands the APK to PackageInstaller; Android shows the confirm dialog. */
    void install() {
        final File apk = readyApk;
        if (apk == null || !apk.exists()) return;
        exec.execute(() -> {
            PackageInstaller.Session session = null;
            try {
                PackageInstaller pkgInstaller = app.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams params =
                        new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                params.setAppPackageName(app.getPackageName());
                params.setSize(apk.length());
                int id = pkgInstaller.createSession(params);
                session = pkgInstaller.openSession(id);
                try (InputStream in = new FileInputStream(apk);
                     OutputStream out = session.openWrite("drivehome.apk", 0, apk.length())) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                    session.fsync(out);
                }
                Intent status = new Intent(app, InstallStatusReceiver.class).setAction(ACTION_INSTALL_STATUS);
                PendingIntent pi = PendingIntent.getBroadcast(app, id, status, PendingIntent.FLAG_UPDATE_CURRENT);
                session.commit(pi.getIntentSender());
                session.close();
                session = null;
            } catch (Throwable t) {
                Log.w(TAG, "install failed", t);
                if (session != null) session.abandon();
                main.post(() -> { if (listener != null) listener.onCheckResult("Install failed: " + t.getMessage()); });
            }
        });
    }

    int currentVersion() {
        try {
            return (int) app.getPackageManager().getPackageInfo(app.getPackageName(), 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return 0;
        }
    }

    static int parseVersion(String tag) {
        String digits = tag == null ? "" : tag.replaceAll("[^0-9]", "");
        if (digits.isEmpty() || digits.length() > 9) return 0;
        return Integer.parseInt(digits);
    }

    private static HttpURLConnection open(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "DriveHome-Updater");
        c.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream");
        return c;
    }

    private static byte[] httpGet(String url) throws Exception {
        HttpURLConnection c = open(url);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IllegalStateException("HTTP " + code);
            try (InputStream in = c.getInputStream(); ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    bo.write(buf, 0, n);
                    if (bo.size() > 2 * 1024 * 1024) throw new IllegalStateException("response too large");
                }
                return bo.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    private static void download(String url, File dest) throws Exception {
        File tmp = new File(dest.getPath() + ".part");
        HttpURLConnection c = open(url);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new IllegalStateException("download HTTP " + code);
            try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                long total = 0;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    total += n;
                    if (total > 50L * 1024 * 1024) throw new IllegalStateException("APK too large");
                }
            }
        } finally {
            c.disconnect();
        }
        if (!tmp.renameTo(dest)) throw new IllegalStateException("rename failed");
    }
}
