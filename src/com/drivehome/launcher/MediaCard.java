package com.drivehome.launcher;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.KeyEvent;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * "Now playing" card. Owns all media-session logic: active sessions (needs the notification-listener
 * grant; without it everything degrades to a placeholder + media key events), per-controller callbacks
 * while the activity is resumed, and transport buttons.
 */
final class MediaCard extends LinearLayout {
    interface Host {
        /** Open the player app (pkg may be null), else its session activity, else the Music app. */
        void openPlayer(String pkg, PendingIntent sessionActivity);
    }

    static final String MUSIC_PKG = "com.tw.music";

    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ComponentName listenerComponent;
    private final GlyphView icon;
    private final TextView title;
    private final TextView subtitle;
    private final GlyphView playButton;
    private final ArrayList<MediaController> controllers = new ArrayList<>();
    /** Labels of session owners that are not launchable apps (e.g. the Bluetooth audio sink). */
    private final HashMap<String, String> labelCache = new HashMap<>();

    private MediaSessionManager sessionManager;
    private boolean started;
    private boolean sessionListenerRegistered;
    private boolean placeholderShown;
    private AppRepository.Snapshot snapshot;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(List<MediaController> list) {
                    if (started) setControllers(list);
                }
            };

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            if (started) refresh();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            if (started) refresh();
        }

        @Override
        public void onSessionDestroyed() {
            if (started) requery();
        }
    };

    MediaCard(Context c, Host host) {
        super(c);
        this.host = host;
        listenerComponent = new ComponentName(c, MediaListener.class);
        setOrientation(VERTICAL);
        int pad = Ui.dp(c, 20);
        setPadding(pad, pad, pad, pad);
        setBackground(Ui.pressable(Ui.CARD, Ui.CARD_PRESSED, Ui.dp(c, Ui.RADIUS)));
        setOnClickListener(v -> openPlayer());

        LinearLayout info = new LinearLayout(c);
        info.setOrientation(HORIZONTAL);
        info.setGravity(Gravity.CENTER_VERTICAL);
        icon = new GlyphView(c, GlyphView.NOTE, Ui.dp(c, 32), Ui.ACCENT);
        icon.setPlate(Ui.BUTTON, Ui.dp(c, 14));
        int iconSize = Ui.dp(c, 64);
        info.addView(icon, new LayoutParams(iconSize, iconSize));

        LinearLayout texts = new LinearLayout(c);
        texts.setOrientation(VERTICAL);
        title = Ui.text(c, Ui.dp(c, 23), Ui.TEXT, Ui.medium());
        subtitle = Ui.text(c, Ui.dp(c, 17), Ui.TEXT2, Ui.regular());
        texts.addView(title, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        LayoutParams slp = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        slp.topMargin = Ui.dp(c, 7);
        texts.addView(subtitle, slp);
        LayoutParams tlp = new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f);
        tlp.setMarginStart(Ui.dp(c, 16));
        info.addView(texts, tlp);
        addView(info, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        addView(new Space(c), new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout buttons = new LinearLayout(c);
        buttons.setOrientation(HORIZONTAL);
        GlyphView prev = button(c, GlyphView.PREV, Ui.BUTTON, Ui.BUTTON_PRESSED, Ui.TEXT, 30, "Previous");
        playButton = button(c, GlyphView.PLAY_PAUSE, Ui.ACCENT, Ui.ACCENT_PRESSED, Ui.WHITE, 34, "Play or pause");
        GlyphView next = button(c, GlyphView.NEXT, Ui.BUTTON, Ui.BUTTON_PRESSED, Ui.TEXT, 30, "Next");
        prev.setOnClickListener(v -> skip(false));
        playButton.setOnClickListener(v -> playPause());
        next.setOnClickListener(v -> skip(true));
        int gap = Ui.dp(c, 12);
        buttons.addView(prev, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
        LayoutParams plp = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
        plp.setMarginStart(gap);
        buttons.addView(playButton, plp);
        LayoutParams nlp = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
        nlp.setMarginStart(gap);
        buttons.addView(next, nlp);
        addView(buttons, new LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(c, 76)));

        showPlaceholder();
    }

    private static GlyphView button(Context c, int type, int normal, int pressed, int color, int glyphDp,
            String description) {
        GlyphView g = new GlyphView(c, type, Ui.dp(c, glyphDp), color);
        g.setBackground(Ui.pressable(normal, pressed, Ui.dp(c, 14)));
        g.setContentDescription(description);
        return g;
    }

    // ---------------------------------------------------------------- lifecycle (called from onResume/onPause)

    void start() {
        if (started) return;
        started = true;
        List<MediaController> list = null;
        try {
            if (sessionManager == null) {
                sessionManager = (MediaSessionManager) getContext().getSystemService(Context.MEDIA_SESSION_SERVICE);
            }
            if (sessionManager != null) {
                sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler);
                sessionListenerRegistered = true;
                list = sessionManager.getActiveSessions(listenerComponent);
            }
        } catch (SecurityException e) {
            // Notification-listener access not granted: placeholder + media keys only.
            list = null;
        } catch (RuntimeException e) {
            list = null;
        }
        setControllers(list);
    }

    void stop() {
        if (!started) return;
        started = false;
        if (sessionListenerRegistered && sessionManager != null) {
            try {
                sessionManager.removeOnActiveSessionsChangedListener(sessionsListener);
            } catch (RuntimeException ignored) {
            }
        }
        sessionListenerRegistered = false;
        clearControllers(); // keep the last shown content; it is refreshed on the next start()
    }

    void setSnapshot(AppRepository.Snapshot s) {
        snapshot = s;
        // While stopped, only the placeholder (Music app icon) needs updating; keep real content as is.
        if (started || placeholderShown) refresh();
    }

    // ---------------------------------------------------------------- sessions

    private void requery() {
        List<MediaController> list = null;
        try {
            if (sessionManager != null) list = sessionManager.getActiveSessions(listenerComponent);
        } catch (RuntimeException ignored) {
            list = null;
        }
        setControllers(list);
    }

    private void clearControllers() {
        for (MediaController c : controllers) {
            try {
                c.unregisterCallback(callback);
            } catch (RuntimeException ignored) {
            }
        }
        controllers.clear();
    }

    private void setControllers(List<MediaController> list) {
        clearControllers();
        if (list != null && started) {
            for (MediaController c : list) {
                if (c == null) continue;
                try {
                    c.registerCallback(callback, handler);
                    controllers.add(c);
                } catch (RuntimeException ignored) {
                }
            }
        }
        refresh();
    }

    /** The playing session if any, else the highest-priority one. */
    private MediaController activeController() {
        MediaController first = null;
        for (MediaController c : controllers) {
            try {
                if (isPlaying(c.getPlaybackState())) return c;
            } catch (RuntimeException ignored) {
            }
            if (first == null) first = c;
        }
        return first;
    }

    private static boolean isPlaying(PlaybackState st) {
        if (st == null) return false;
        switch (st.getState()) {
            case PlaybackState.STATE_PLAYING:
            case PlaybackState.STATE_BUFFERING:
            case PlaybackState.STATE_CONNECTING:
            case PlaybackState.STATE_FAST_FORWARDING:
            case PlaybackState.STATE_REWINDING:
            case PlaybackState.STATE_SKIPPING_TO_NEXT:
            case PlaybackState.STATE_SKIPPING_TO_PREVIOUS:
            case PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM:
                return true;
            default:
                return false;
        }
    }

    private AppEntry lookup(String pkg) {
        AppRepository.Snapshot s = snapshot;
        return (s == null || pkg == null) ? null : s.byPackage.get(pkg);
    }

    private void refresh() {
        MediaController c = activeController();
        if (c == null) {
            showPlaceholder();
            return;
        }
        String pkg;
        MediaMetadata md;
        PlaybackState st;
        try {
            pkg = c.getPackageName();
            md = c.getMetadata();
            st = c.getPlaybackState();
        } catch (RuntimeException e) {
            showPlaceholder();
            return;
        }
        AppEntry app = lookup(pkg);
        String appLabel = app != null ? app.label : labelFor(pkg);
        String t = firstNonEmpty(md, MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        String a = firstNonEmpty(md, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
                MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ALBUM);
        if (t == null) {
            t = appLabel;
            if (a == null) a = "Tap to open";
        } else if (a == null) {
            a = appLabel;
        }
        placeholderShown = false;
        Ui.setText(title, t);
        Ui.setText(subtitle, a);
        icon.setBitmap(app != null ? app.gridIcon : null);
        playButton.setType(isPlaying(st) ? GlyphView.PAUSE : GlyphView.PLAY);
    }

    /** One PackageManager lookup per unknown package, cached (only for non-launchable session owners). */
    private String labelFor(String pkg) {
        if (pkg == null) return "Music";
        String cached = labelCache.get(pkg);
        if (cached != null) return cached;
        String label = pkg;
        try {
            PackageManager pm = getContext().getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence cs = pm.getApplicationLabel(ai);
            if (cs != null && cs.length() > 0) label = cs.toString();
        } catch (PackageManager.NameNotFoundException | RuntimeException ignored) {
        }
        labelCache.put(pkg, label);
        return label;
    }

    private static String firstNonEmpty(MediaMetadata md, String... keys) {
        if (md == null) return null;
        for (String k : keys) {
            try {
                String v = md.getString(k);
                if (v != null) {
                    v = v.trim();
                    if (!v.isEmpty()) return v;
                }
            } catch (RuntimeException ignored) {
            }
        }
        return null;
    }

    private void showPlaceholder() {
        placeholderShown = true;
        Ui.setText(title, "Music");
        Ui.setText(subtitle, "Tap to open");
        AppEntry m = lookup(MUSIC_PKG);
        icon.setBitmap(m != null ? m.gridIcon : null);
        playButton.setType(GlyphView.PLAY_PAUSE);
    }

    // ---------------------------------------------------------------- actions

    private void openPlayer() {
        String pkg = null;
        PendingIntent sessionActivity = null;
        MediaController c = activeController();
        if (c != null) {
            try {
                pkg = c.getPackageName();
                sessionActivity = c.getSessionActivity();
            } catch (RuntimeException ignored) {
            }
        }
        host.openPlayer(pkg, sessionActivity);
    }

    private void skip(boolean next) {
        MediaController c = activeController();
        if (c != null) {
            try {
                MediaController.TransportControls t = c.getTransportControls();
                if (next) t.skipToNext();
                else t.skipToPrevious();
                return;
            } catch (RuntimeException ignored) {
            }
        }
        sendMediaKey(next ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PREVIOUS);
    }

    private void playPause() {
        MediaController c = activeController();
        if (c != null) {
            try {
                boolean playing = isPlaying(c.getPlaybackState());
                MediaController.TransportControls t = c.getTransportControls();
                if (playing) t.pause();
                else t.play();
                // Optimistic; the playback-state callback corrects it.
                playButton.setType(playing ? GlyphView.PLAY : GlyphView.PAUSE);
                return;
            } catch (RuntimeException ignored) {
            }
        }
        sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
    }

    private void sendMediaKey(int code) {
        try {
            AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            long now = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0));
        } catch (RuntimeException ignored) {
        }
    }
}
