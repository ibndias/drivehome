package com.drivehome.launcher;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
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
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Self-update from GitHub Releases of {@link #REPO}.
 *
 * CI publishes every build as release "v&lt;versionCode&gt;" with drivehome.apk attached.
 * We poll the latest release (at most every {@link #CHECK_INTERVAL_MS}; failed checks retry after
 * {@link #RETRY_MS}), download a newer APK in the background, verify package/version/signing key,
 * then tell the UI. A downloaded update survives process death and reboots. Installing is
 * user-initiated (tap) and goes through PackageInstaller, which shows Android's one-tap
 * "Install update?" confirmation. Nothing here runs on the main thread except listener callbacks.
 */
final class Updater {
    static final String REPO = "ibndias/drivehome";
    private static final String LATEST_URL = "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final long CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000;
    private static final long RETRY_MS = 30L * 60 * 1000;
    static final String ACTION_INSTALL_STATUS = "com.drivehome.launcher.INSTALL_STATUS";
    private static final String TAG = "DriveHomeUpdater";
    private static final Pattern TAG_PATTERN = Pattern.compile("^v(\\d{1,9})$");
    private static final Pattern FILE_PATTERN = Pattern.compile("^drivehome-(\\d{1,9})\\.apk$");

    interface Listener {
        /** A newer, verified APK is on disk and ready to install. Main thread. */
        void onUpdateReady(int versionCode);
        /** Result of a manual check / install problem, for a short toast. Main thread. */
        void onCheckResult(String message);
    }

    private static Updater sInstance;

    static synchronized Updater get(Context ctx) {
        if (sInstance == null) sInstance = new Updater(ctx.getApplicationContext());
        return sInstance;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** Network checks/downloads. */
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    /** Installs: never queued behind a slow network check. */
    private final ExecutorService installExec = Executors.newSingleThreadExecutor();
    private final SharedPreferences prefs;
    private Listener listener;
    private volatile boolean busy;
    private volatile int readyCode;
    private volatile File readyApk;

    private Updater(Context app) {
        this.app = app;
        this.prefs = app.getSharedPreferences("updater", Context.MODE_PRIVATE);
        exec.execute(this::restoreDownloaded);
    }

    void setListener(Listener l) {
        listener = l;
    }

    /** Version of the verified APK waiting on disk, or 0. */
    int readyVersion() {
        File f = readyApk;
        return (f != null && f.exists()) ? readyCode : 0;
    }

    /** Cheap; call from onResume. Only hits the network when the interval/backoff has passed. */
    void maybeCheck() {
        long now = System.currentTimeMillis();
        long last = prefs.getLong("lastCheck", 0);
        long lastFail = prefs.getLong("lastFail", 0);
        // "last <= now" guards against the clock jumping (head units often boot with a wrong date).
        if (last <= now && now - last < CHECK_INTERVAL_MS) return;
        if (lastFail <= now && now - lastFail < RETRY_MS) return;
        check(false);
    }

    /** Force a check now (menu item). */
    void checkNow() {
        if (busy) {
            post("Already checking for updates…");
            return;
        }
        check(true);
    }

    private void check(final boolean manual) {
        if (busy) return;
        busy = true;
        exec.execute(() -> {
            String msg;
            try {
                msg = doCheck();
                prefs.edit().putLong("lastCheck", System.currentTimeMillis()).remove("lastFail").apply();
            } catch (Throwable t) {
                Log.w(TAG, "update check failed", t);
                prefs.edit().putLong("lastFail", System.currentTimeMillis()).apply();
                msg = "Update check failed (no internet?)";
            } finally {
                busy = false;
            }
            if (manual) post(msg);
        });
    }

    /** Background thread. Returns a human-readable status. */
    private String doCheck() throws Exception {
        JSONObject rel = new JSONObject(new String(httpGet(LATEST_URL), "UTF-8"));
        String tag = rel.optString("tag_name", "");
        int remote = parseVersion(tag);
        int local = currentVersion();
        if (remote <= 0) return "Latest release '" + tag + "' has no version number";
        if (remote <= local) return "DriveHome is up to date (v" + local + ")";
        if (remote == readyVersion()) {
            notifyReady();
            return "Update v" + remote + " already downloaded";
        }

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

        File dir = updatesDir();
        File apk = new File(dir, "drivehome-" + remote + ".apk");
        if (!(apk.exists() && apkSize > 0 && apk.length() == apkSize)) {
            // Replacing an older pending download: forget it first so the UI never offers a
            // file that is about to be deleted.
            readyApk = null;
            readyCode = 0;
            deleteAll(dir);
            download(apkUrl, apk);
        }

        String problem = verify(apk, remote);
        if (problem != null) {
            //noinspection ResultOfMethodCallIgnored
            apk.delete();
            return "Update v" + remote + " skipped: " + problem;
        }

        readyCode = remote;
        readyApk = apk;
        notifyReady();
        return "Update v" + remote + " downloaded";
    }

    /** Background thread, on startup: pick up a verified download from before a restart. */
    private void restoreDownloaded() {
        try {
            File[] files = updatesDir().listFiles();
            if (files == null) return;
            int local = currentVersion();
            for (File f : files) {
                Matcher m = FILE_PATTERN.matcher(f.getName());
                if (!m.matches()) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete(); // stale .part files etc.
                    continue;
                }
                int code = Integer.parseInt(m.group(1));
                if (code > local && verify(f, code) == null) {
                    readyCode = code;
                    readyApk = f;
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete(); // already installed or invalid
                }
            }
            if (readyVersion() > 0) notifyReady();
        } catch (Throwable t) {
            Log.w(TAG, "restore failed", t);
        }
    }

    /** @return null if the APK is our package, the expected version and signed with our key. */
    private String verify(File apk, int expectedVersion) {
        PackageManager pm = app.getPackageManager();
        PackageInfo archive = pm.getPackageArchiveInfo(apk.getPath(), PackageManager.GET_SIGNING_CERTIFICATES);
        if (archive == null) return "not a valid APK";
        if (!app.getPackageName().equals(archive.packageName)) return "wrong package";
        if ((int) archive.getLongVersionCode() != expectedVersion) return "unexpected version";
        Set<Signature> theirs = signers(archive.signingInfo);
        Set<Signature> ours = null;
        try {
            ours = signers(pm.getPackageInfo(app.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES).signingInfo);
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        // Some Android 10 builds don't fill signingInfo for archives; then the installer is the
        // final check. If both are known they must match, or the install would fail anyway.
        if (theirs != null && ours != null && !theirs.equals(ours)) return "signed with a different key";
        return null;
    }

    private static Set<Signature> signers(SigningInfo si) {
        if (si == null) return null;
        Signature[] s = si.hasMultipleSigners() ? si.getApkContentsSigners() : si.getSigningCertificateHistory();
        if (s == null || s.length == 0) return null;
        // Compare the current signer (last in the rotation history) or the full multi-signer set.
        return si.hasMultipleSigners() ? new HashSet<>(Arrays.asList(s)) : new HashSet<>(Arrays.asList(s[s.length - 1]));
    }

    /** User tapped "install". Hands the APK to PackageInstaller; Android shows the confirm dialog. */
    void install() {
        final File apk = readyApk;
        if (apk == null || !apk.exists()) {
            readyApk = null;
            readyCode = 0;
            post("Update file is gone — checking again");
            check(false);
            return;
        }
        installExec.execute(() -> {
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
                post("Install failed: " + t.getMessage());
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

    /** Release tags are exactly "v&lt;versionCode&gt;" (see .github/workflows/release.yml). */
    static int parseVersion(String tag) {
        Matcher m = TAG_PATTERN.matcher(tag == null ? "" : tag.trim());
        return m.matches() ? Integer.parseInt(m.group(1)) : 0;
    }

    private void notifyReady() {
        final int code = readyVersion();
        if (code <= 0) return;
        main.post(() -> {
            if (listener != null) listener.onUpdateReady(code);
        });
    }

    private void post(final String message) {
        main.post(() -> {
            if (listener != null) listener.onCheckResult(message);
        });
    }

    private File updatesDir() {
        File dir = new File(app.getCacheDir(), "updates");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IllegalStateException("no cache dir");
        return dir;
    }

    private static void deleteAll(File dir) {
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) //noinspection ResultOfMethodCallIgnored
            f.delete();
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
