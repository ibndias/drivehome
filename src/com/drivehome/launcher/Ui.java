package com.drivehome.launcher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.widget.TextView;

/**
 * Design tokens (palette, type scale, radii) and small view/drawable helpers for the "Cockpit" UI.
 * Everything is built in code (no R class).
 *
 * Language: information (speed, time, date) sits bare on the window background; anything touchable
 * is one colour step up (CARD) and its controls one more (BUTTON). Depth comes from these steps and
 * 1px strokes only - no shadows, gradients, alpha or animation. One accent colour.
 */
final class Ui {
    static final int BG = 0xFF0B0E13;
    static final int CARD = 0xFF151A21;
    static final int CARD_STROKE = 0xFF212833;
    static final int CARD_PRESSED = 0xFF232B36;
    static final int CARD_HALF_PRESSED = 0xFF181E26;
    static final int BUTTON = 0xFF1E252F;
    static final int BUTTON_PRESSED = 0xFF2E3846;
    /** The primary control of a group (play/pause): one more step up, still neutral. */
    static final int BUTTON_HI = 0xFF2B3441;
    static final int BUTTON_HI_PRESSED = 0xFF3D495A;
    static final int TEXT = 0xFFF2F5F8;
    /** Secondary text: 6.5:1 on BG, 5.9:1 on CARD. */
    static final int TEXT2 = 0xFF939DAB;
    /** Placeholders / inactive marks only (never for text that must be read while driving). */
    static final int TEXT3 = 0xFF566170;
    static final int ACCENT = 0xFF3D8BFD;
    static final int ACCENT_PRESSED = 0xFF2F6FD8;
    /** Gauge: unlit arc and tick marks. */
    static final int TRACK = 0xFF1F2731;
    static final int TICK = 0xFF465264;
    static final int DIALOG = 0xFF1A2029;
    static final int DIALOG_STROKE = 0xFF364152;
    static final int WHITE = 0xFFFFFFFF;

    // Type scale in dp (1dp = 1px on the head unit). Primary text 32 / secondary 24 follow the
    // Android-for-cars visual principles; labels that only repeat an icon may be 18.
    static final int T_SPEED = 160;
    static final int T_TIME = 96;
    static final int T_TITLE = 32;
    static final int T_BODY = 24;
    static final int T_LABEL = 18;

    /** Smallest touch target in dp (Android-for-cars minimum is 76 x 76). */
    static final int TOUCH = 76;

    /** Card corner radius in dp. */
    static final int RADIUS = 24;
    /** Radius of pressed plates inside cards / lists, in dp. */
    static final int RADIUS_SMALL = 16;

    private static Typeface sLight;
    private static Typeface sMedium;
    private static Typeface sRegular;

    private Ui() {}

    static Typeface light() {
        if (sLight == null) sLight = Typeface.create("sans-serif-light", Typeface.NORMAL);
        return sLight;
    }

    static Typeface medium() {
        if (sMedium == null) sMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        return sMedium;
    }

    static Typeface regular() {
        if (sRegular == null) sRegular = Typeface.create("sans-serif", Typeface.NORMAL);
        return sRegular;
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable rounded(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    static GradientDrawable rounded(int color, float radius, int strokeColor, int strokeWidth) {
        GradientDrawable d = rounded(color, radius);
        d.setStroke(strokeWidth, strokeColor);
        return d;
    }

    /** Standard touchable surface: CARD with a 1px stroke. */
    static GradientDrawable card(Context c) {
        return rounded(CARD, dp(c, RADIUS), CARD_STROKE, Math.max(1, dp(c, 1)));
    }

    /**
     * Plain pressed-state background (no ripple, no fade). normal == 0 means "draw nothing"
     * when not pressed (a fully transparent ColorDrawable skips drawing).
     */
    static StateListDrawable pressable(int normal, int pressed, float radius) {
        return states(rounded(pressed, radius),
                normal == 0 ? new ColorDrawable(Color.TRANSPARENT) : rounded(normal, radius));
    }

    /** Touchable card: stroked CARD that steps to CARD_PRESSED while pressed. */
    static StateListDrawable pressableCard(Context c) {
        float r = dp(c, RADIUS);
        int sw = Math.max(1, dp(c, 1));
        return states(rounded(CARD_PRESSED, r, CARD_STROKE, sw), rounded(CARD, r, CARD_STROKE, sw));
    }

    /**
     * Touchable card that contains its own buttons: pressed is only a half step lighter plus a
     * brighter outline, so the buttons inside stay above the surface instead of sinking into it.
     */
    static StateListDrawable pressableCardWithControls(Context c) {
        float r = dp(c, RADIUS);
        return states(rounded(CARD_HALF_PRESSED, r, TICK, Math.max(2, dp(c, 2))),
                rounded(CARD, r, CARD_STROKE, Math.max(1, dp(c, 1))));
    }

    /** Nothing when idle; a pressed plate inset from the view bounds (cells inside a shared card). */
    static StateListDrawable pressablePlate(int pressed, float radius, int inset) {
        return states(new InsetDrawable(rounded(pressed, radius), inset), new ColorDrawable(Color.TRANSPARENT));
    }

    private static StateListDrawable states(Drawable pressed, Drawable normal) {
        StateListDrawable s = new StateListDrawable();
        s.setEnterFadeDuration(0);
        s.setExitFadeDuration(0);
        s.addState(new int[] {android.R.attr.state_pressed}, pressed);
        s.addState(new int[0], normal);
        return s;
    }

    /** Single-line, ellipsized TextView with an exact pixel size (ignores font scale so the layout fits). */
    static TextView text(Context c, float px, int color, Typeface tf) {
        TextView t = new TextView(c);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, px);
        t.setTextColor(color);
        t.setTypeface(tf);
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    /** Avoids needless relayout/invalidate when the text did not change. */
    static void setText(TextView tv, CharSequence s) {
        if (!TextUtils.equals(tv.getText(), s)) tv.setText(s);
    }
}
