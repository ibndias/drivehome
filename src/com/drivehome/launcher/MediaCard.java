package com.drivehome.launcher;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * "Now playing" card. Owns all media-session logic: active sessions (needs the notification-listener
 * grant; without it everything degrades to a placeholder + media key events), per-controller callbacks
 * while the activity is resumed, and transport buttons.
 *
 * View side: one flat ViewGroup with four children (app icon + three large transport buttons). Title
 * and artist are drawn directly (pre-ellipsized), so a track change is a single invalidate - no
 * TextView, no layout pass.
 */
final class MediaCard extends ViewGroup {
    interface Host {
        /** Open the player app (pkg may be null), else its session activity, else the Music app. */
        void openPlayer(String pkg, PendingIntent sessionActivity);
    }

    static final String MUSIC_PKG = "com.tw.music";

    private final Host host;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ComponentName listenerComponent;
    private final GlyphView icon;
    private final GlyphView prevButton;
    private final GlyphView playButton;
    private final GlyphView nextButton;
    private final ArrayList<Tracked> tracked = new ArrayList<>();
    /** Labels of session owners that are not launchable apps (e.g. the Bluetooth audio sink). */
    private final HashMap<String, String> labelCache = new HashMap<>();

    // Drawing state (all allocated once).
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint subtitlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final float titleHeight;
    private final float subtitleHeight;
    private final int pad;
    private final int iconSize;
    private final int iconGap;
    private final int lineGap;
    private final int buttonGap;
    private final int buttonHeight;
    private String title = "";
    private String subtitle = "";
    private String shownTitle = "";
    private String shownSubtitle = "";
    private int textLeft;
    private float titleBaseline;
    private float subtitleBaseline;

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

    /**
     * One callback per session. Keeps the latest state/metadata the callbacks deliver, so a
     * playback tick never costs extra binder calls (getPlaybackState/getMetadata carry album art)
     * on the main thread. The session is only queried directly when the session list changes.
     */
    private final class Tracked extends MediaController.Callback {
        final MediaController controller;
        String pkg;
        PlaybackState state;
        MediaMetadata metadata;

        Tracked(MediaController c) {
            controller = c;
            try {
                pkg = c.getPackageName();
                state = c.getPlaybackState();
                metadata = c.getMetadata();
            } catch (RuntimeException ignored) {
            }
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState s) {
            state = s;
            if (started) refresh();
        }

        @Override
        public void onMetadataChanged(MediaMetadata m) {
            metadata = m;
            if (started) refresh();
        }

        @Override
        public void onSessionDestroyed() {
            if (started) requery();
        }
    }

    MediaCard(Context c, Host host) {
        super(c);
        this.host = host;
        listenerComponent = new ComponentName(c, MediaListener.class);
        pad = Ui.dp(c, 28);
        iconSize = Ui.dp(c, 72);
        iconGap = Ui.dp(c, 20);
        lineGap = Ui.dp(c, 16);
        buttonGap = Ui.dp(c, 12);
        buttonHeight = Ui.dp(c, 96);

        titlePaint.setTextSize(Ui.dp(c, Ui.T_TITLE));
        titlePaint.setTypeface(Ui.medium());
        titlePaint.setColor(Ui.TEXT);
        subtitlePaint.setTextSize(Ui.dp(c, Ui.T_BODY));
        subtitlePaint.setTypeface(Ui.regular());
        subtitlePaint.setColor(Ui.TEXT2);
        Rect r = new Rect();
        titlePaint.getTextBounds("H", 0, 1, r);
        titleHeight = r.height();
        subtitlePaint.getTextBounds("H", 0, 1, r);
        subtitleHeight = r.height();

        setWillNotDraw(false);
        setBackground(Ui.pressableCardWithControls(c));
        setOnClickListener(v -> openPlayer());

        icon = new GlyphView(c, GlyphView.NOTE, Ui.dp(c, 36), Ui.ACCENT);
        icon.setPlate(Ui.BUTTON, Ui.dp(c, 18));
        addView(icon);

        prevButton = button(c, GlyphView.PREV, Ui.BUTTON, Ui.BUTTON_PRESSED, Ui.TEXT, 36, "Previous");
        playButton = button(c, GlyphView.PLAY_PAUSE, Ui.BUTTON_HI, Ui.BUTTON_HI_PRESSED, Ui.WHITE, 44, "Play or pause");
        nextButton = button(c, GlyphView.NEXT, Ui.BUTTON, Ui.BUTTON_PRESSED, Ui.TEXT, 36, "Next");
        prevButton.setOnClickListener(v -> skip(false));
        playButton.setOnClickListener(v -> playPause());
        nextButton.setOnClickListener(v -> skip(true));
        addView(prevButton);
        addView(playButton);
        addView(nextButton);

        showPlaceholder();
    }

    private GlyphView button(Context c, int type, int normal, int pressed, int color, int glyphDp,
            String description) {
        GlyphView g = new GlyphView(c, type, Ui.dp(c, glyphDp), color);
        g.setBackground(Ui.pressable(normal, pressed, buttonHeight / 2f));
        g.setContentDescription(description);
        return g;
    }

    // ---------------------------------------------------------------- layout & drawing

    /** Full height when the card is tall enough (always on the head unit); shrinks only in tiny layouts. */
    private int buttonHeightFor(int h) {
        int room = h - 2 * pad - iconSize - lineGap; // what is left under the title block
        return Math.max(0, Math.min(buttonHeight, Math.max(room, h / 3)));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        int bh = buttonHeightFor(h);
        int inner = Math.max(0, w - 2 * pad);
        int bw = Math.max(0, (inner - 2 * buttonGap) / 3);
        int bwSpec = MeasureSpec.makeMeasureSpec(bw, MeasureSpec.EXACTLY);
        int bhSpec = MeasureSpec.makeMeasureSpec(Math.max(0, bh), MeasureSpec.EXACTLY);
        prevButton.measure(bwSpec, bhSpec);
        nextButton.measure(bwSpec, bhSpec);
        // The middle button takes the rounding remainder so the row ends exactly on the padding.
        playButton.measure(MeasureSpec.makeMeasureSpec(Math.max(0, inner - 2 * buttonGap - 2 * bw),
                MeasureSpec.EXACTLY), bhSpec);
        int iconSpec = MeasureSpec.makeMeasureSpec(iconSize, MeasureSpec.EXACTLY);
        icon.measure(iconSpec, iconSpec);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        int w = r - l;
        int h = b - t;
        int bh = prevButton.getMeasuredHeight();
        int top = h - pad - bh;
        int x = pad;
        prevButton.layout(x, top, x + prevButton.getMeasuredWidth(), top + bh);
        x += prevButton.getMeasuredWidth() + buttonGap;
        playButton.layout(x, top, x + playButton.getMeasuredWidth(), top + bh);
        x += playButton.getMeasuredWidth() + buttonGap;
        nextButton.layout(x, top, x + nextButton.getMeasuredWidth(), top + bh);

        icon.layout(pad, pad, pad + iconSize, pad + iconSize);
        textLeft = pad + iconSize + iconGap;
        // Title + artist as one block, centred on the icon.
        float block = titleHeight + lineGap + subtitleHeight;
        titleBaseline = pad + iconSize / 2f - block / 2f + titleHeight;
        subtitleBaseline = titleBaseline + lineGap + subtitleHeight;
        fitTexts(w);
    }

    private void fitTexts(int w) {
        float avail = w - textLeft - pad;
        if (avail <= 0f) {
            shownTitle = title;
            shownSubtitle = subtitle;
            return;
        }
        shownTitle = TextUtils.ellipsize(title, titlePaint, avail, TextUtils.TruncateAt.END).toString();
        shownSubtitle = TextUtils.ellipsize(subtitle, subtitlePaint, avail, TextUtils.TruncateAt.END).toString();
    }

    private void setTexts(String t, String s) {
        if (t == null) t = "";
        if (s == null) s = "";
        if (t.equals(title) && s.equals(subtitle)) return;
        title = t;
        subtitle = s;
        setContentDescription(t + ", " + s);
        fitTexts(getWidth());
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawText(shownTitle, textLeft, titleBaseline, titlePaint);
        canvas.drawText(shownSubtitle, textLeft, subtitleBaseline, subtitlePaint);
    }

    @Override
    public boolean shouldDelayChildPressedState() {
        return false; // not scrollable: show pressed feedback immediately
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
        for (Tracked t : tracked) {
            try {
                t.controller.unregisterCallback(t);
            } catch (RuntimeException ignored) {
            }
        }
        tracked.clear();
    }

    private void setControllers(List<MediaController> list) {
        clearControllers();
        if (list != null && started) {
            for (MediaController c : list) {
                if (c == null) continue;
                Tracked t = new Tracked(c);
                try {
                    c.registerCallback(t, handler);
                    tracked.add(t);
                } catch (RuntimeException ignored) {
                }
            }
        }
        refresh();
    }

    /** The playing session if any, else the highest-priority one. No binder calls. */
    private Tracked activeTracked() {
        Tracked first = null;
        for (Tracked t : tracked) {
            if (isPlaying(t.state)) return t;
            if (first == null) first = t;
        }
        return first;
    }

    private MediaController activeController() {
        Tracked t = activeTracked();
        return t == null ? null : t.controller;
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
        Tracked active = activeTracked();
        if (active == null) {
            showPlaceholder();
            return;
        }
        String pkg = active.pkg;
        MediaMetadata md = active.metadata;
        PlaybackState st = active.state;
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
        setTexts(t, a);
        icon.setBitmap(app != null ? app.tileIcon : null);
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
        setTexts("Music", "Tap to open");
        AppEntry m = lookup(MUSIC_PKG);
        icon.setBitmap(m != null ? m.tileIcon : null);
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
