package com.drivehome.launcher;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.widget.TextView;

/** Palette, sizes and small view/drawable helpers. Everything is built in code (no R class). */
final class Ui {
    static final int BG = 0xFF0B0E13;
    static final int CARD = 0xFF151A21;
    static final int CARD_PRESSED = 0xFF232B36;
    static final int TEXT = 0xFFF2F5F8;
    static final int TEXT2 = 0xFF8B95A3;
    static final int ACCENT = 0xFF3D8BFD;
    static final int ACCENT_PRESSED = 0xFF2F6FD8;
    static final int BUTTON = 0xFF1E252F;
    static final int BUTTON_PRESSED = 0xFF2E3846;
    static final int DIALOG = 0xFF181D25;
    static final int DIALOG_STROKE = 0xFF2A3340;
    static final int WHITE = 0xFFFFFFFF;

    /** Card corner radius in dp. */
    static final int RADIUS = 18;

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

    /**
     * Plain pressed-state background (no ripple, no fade). normal == 0 means "draw nothing"
     * when not pressed (a fully transparent ColorDrawable skips drawing).
     */
    static StateListDrawable pressable(int normal, int pressed, float radius) {
        StateListDrawable s = new StateListDrawable();
        s.setEnterFadeDuration(0);
        s.setExitFadeDuration(0);
        s.addState(new int[] {android.R.attr.state_pressed}, rounded(pressed, radius));
        s.addState(new int[0], normal == 0 ? new ColorDrawable(Color.TRANSPARENT) : rounded(normal, radius));
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
