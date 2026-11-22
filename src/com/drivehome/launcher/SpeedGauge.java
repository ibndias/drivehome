package com.drivehome.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.text.TextPaint;
import android.view.View;

/**
 * The hero of the home screen: GPS speed as a large numeral inside a 270-degree arc (0-160 km/h).
 *
 * The numeral is the precise reading; the arc and its ticks (every 10, longer every 20) only give
 * the at-a-glance impression. Without a fix it shows two dashes; without the location permission
 * the unit label reads "GPS off".
 *
 * Performance: one flat view of fixed size. All Paint/Path/RectF objects are allocated once, the
 * static geometry (arc box, tick path, baselines) is computed in onSizeChanged, and the view only
 * invalidates itself when the integer speed (or availability) really changes - at most 1 Hz, with
 * no layout pass. onDraw is two arcs, one small path and two short strings; no allocation.
 */
final class SpeedGauge extends View {
    /** No GPS fix (or no speed in the fix). */
    static final int UNKNOWN = -1;

    private static final int MAX_KMH = 160;
    private static final int MAJOR_STEP = 20;
    private static final int MINOR_STEP = 10;
    private static final float START_ANGLE = 135f;
    private static final float SWEEP_ANGLE = 270f;
    private static final float SIN45 = 0.70710678f;
    /** Ring radius the proportions were drawn for; all other sizes scale with the real radius. */
    private static final float DESIGN_RADIUS = 205f;
    /** Speed strings are created once per value and reused (0..299 km/h). */
    private static final String[] NUMBERS = new String[300];

    private final Paint trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arcPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tickPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint speedPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint unitPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final RectF oval = new RectF();
    private final Path ticks = new Path();
    private final Rect bounds = new Rect();
    private final float density;

    private int speed = UNKNOWN;
    private boolean available = true;
    private String speedText = "";
    private String unitText = "km/h";
    private float sweep;

    private float cx;
    private float speedBaseline;
    private float unitBaseline;
    private float dashY;
    private float dashLength;
    private float dashGap;

    SpeedGauge(Context c) {
        super(c);
        density = c.getResources().getDisplayMetrics().density;
        trackPaint.setStyle(Paint.Style.STROKE);
        trackPaint.setStrokeCap(Paint.Cap.ROUND);
        trackPaint.setColor(Ui.TRACK);
        arcPaint.setStyle(Paint.Style.STROKE);
        arcPaint.setStrokeCap(Paint.Cap.ROUND);
        arcPaint.setColor(Ui.ACCENT);
        tickPaint.setStyle(Paint.Style.STROKE);
        tickPaint.setColor(Ui.TICK);
        dashPaint.setStyle(Paint.Style.STROKE);
        dashPaint.setStrokeCap(Paint.Cap.ROUND);
        dashPaint.setColor(Ui.TEXT3);
        speedPaint.setTypeface(Ui.light());
        speedPaint.setTextAlign(Paint.Align.CENTER);
        speedPaint.setColor(Ui.TEXT);
        unitPaint.setTypeface(Ui.regular());
        unitPaint.setTextAlign(Paint.Align.CENTER);
        unitPaint.setColor(Ui.TEXT2);
        setContentDescription("Speed");
    }

    /** kmh >= 0, or UNKNOWN. Cheap no-op when the value did not change. */
    void setSpeed(int kmh) {
        if (kmh < 0) kmh = UNKNOWN;
        if (kmh == speed) return;
        speed = kmh;
        if (kmh == UNKNOWN) {
            speedText = "";
            sweep = 0f;
        } else {
            speedText = number(kmh);
            sweep = SWEEP_ANGLE * Math.min(kmh, MAX_KMH) / MAX_KMH;
        }
        invalidate();
    }

    /** False when the location permission is missing: the dial stays, labelled "GPS off". */
    void setAvailable(boolean on) {
        if (on == available) return;
        available = on;
        unitText = on ? "km/h" : "GPS off";
        invalidate();
    }

    private static String number(int v) {
        if (v >= NUMBERS.length) return Integer.toString(v);
        String s = NUMBERS[v];
        if (s == null) {
            s = Integer.toString(v);
            NUMBERS[v] = s;
        }
        return s;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        ticks.reset();
        if (w <= 0 || h <= 0) return;
        // Largest ring whose bounding box (2r wide, r + r*sin45 tall) fits, centred in the view.
        float margin = 10f * density;
        float r = Math.min((w - 2f * margin) / 2f, (h - 2f * margin) / (1f + SIN45));
        if (r <= 0f) return;
        float u = r / DESIGN_RADIUS; // px per design unit
        cx = w / 2f;
        float cy = (h - r * (1f + SIN45)) / 2f + r;
        oval.set(cx - r, cy - r, cx + r, cy + r);

        trackPaint.setStrokeWidth(Math.max(2f, 8f * u));
        arcPaint.setStrokeWidth(Math.max(2f, 12f * u));
        tickPaint.setStrokeWidth(Math.max(1f, 2f * u));

        float outer = r - 22f * u;
        for (int v = 0; v <= MAX_KMH; v += MINOR_STEP) {
            double a = Math.toRadians(START_ANGLE + SWEEP_ANGLE * v / MAX_KMH);
            float cos = (float) Math.cos(a);
            float sin = (float) Math.sin(a);
            float inner = outer - (v % MAJOR_STEP == 0 ? 16f : 8f) * u;
            ticks.moveTo(cx + outer * cos, cy + outer * sin);
            ticks.lineTo(cx + inner * cos, cy + inner * sin);
        }

        speedPaint.setTextSize(Ui.T_SPEED * u);
        unitPaint.setTextSize(Ui.T_BODY * Math.max(density, u)); // never below the 24dp secondary size
        speedPaint.getTextBounds("0", 0, 1, bounds);
        float digitHeight = bounds.height();
        unitPaint.getTextBounds("k", 0, 1, bounds);
        float unitHeight = bounds.height();
        // Numeral + unit as one lockup, centred on the ring centre.
        float gap = 26f * u;
        float top = cy - (digitHeight + gap + unitHeight) / 2f;
        speedBaseline = top + digitHeight;
        unitBaseline = speedBaseline + gap + unitHeight;

        dashPaint.setStrokeWidth(Math.max(2f, 6f * u));
        dashLength = 34f * u;
        dashGap = 12f * u;
        dashY = speedBaseline - digitHeight / 2f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawArc(oval, START_ANGLE, SWEEP_ANGLE, false, trackPaint);
        canvas.drawPath(ticks, tickPaint);
        if (speed == UNKNOWN) {
            // No reading: two quiet dashes instead of a number.
            canvas.drawLine(cx - dashGap - dashLength, dashY, cx - dashGap, dashY, dashPaint);
            canvas.drawLine(cx + dashGap, dashY, cx + dashGap + dashLength, dashY, dashPaint);
        } else {
            if (sweep > 0f) canvas.drawArc(oval, START_ANGLE, sweep, false, arcPaint);
            canvas.drawText(speedText, cx, speedBaseline, speedPaint);
        }
        canvas.drawText(unitText, cx, unitBaseline, unitPaint);
    }
}
