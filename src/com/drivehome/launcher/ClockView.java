package com.drivehome.launcher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.View;

/**
 * Time + date lockup, drawn bare on the window background: large light numerals with the weekday
 * and date stacked to their right (weekday aligned to the top of the digits, date to the baseline).
 * One flat view; redrawn once a minute (ACTION_TIME_TICK), never re-laid out by a text change.
 */
final class ClockView extends View {
    private final TextPaint timePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint dayPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final TextPaint datePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Rect bounds = new Rect();
    private final float digitHeight;
    private final float dayHeight;
    private final int gap;
    private final int inset;

    private String time = "";
    private String day = "";
    private String date = "";
    private String shownDay = "";
    private String shownDate = "";
    private float timeLeft;
    private float dateLeft;

    ClockView(Context c) {
        super(c);
        timePaint.setTypeface(Ui.light());
        timePaint.setTextSize(Ui.dp(c, Ui.T_TIME));
        timePaint.setColor(Ui.TEXT);
        timePaint.setLetterSpacing(-0.02f);
        dayPaint.setTypeface(Ui.regular());
        dayPaint.setTextSize(Ui.dp(c, Ui.T_TITLE));
        dayPaint.setColor(Ui.TEXT);
        datePaint.setTypeface(Ui.regular());
        datePaint.setTextSize(Ui.dp(c, Ui.T_TITLE));
        datePaint.setColor(Ui.TEXT2);
        timePaint.getTextBounds("0", 0, 1, bounds);
        digitHeight = bounds.height();
        dayPaint.getTextBounds("M", 0, 1, bounds);
        dayHeight = bounds.height();
        gap = Ui.dp(c, 28);
        inset = Ui.dp(c, 4);
    }

    void set(String newTime, String newDay, String newDate) {
        if (newTime.equals(time) && newDay.equals(day) && newDate.equals(date)) return;
        time = newTime;
        day = newDay;
        date = newDate;
        setContentDescription(time + ", " + day + " " + date);
        fit();
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        fit();
    }

    /** Positions the lockup and trims the date lines to the width (only on a change, never in onDraw). */
    private void fit() {
        if (time.isEmpty()) return;
        // Start the ink of the first digit on the left edge (plus a small optical inset).
        timePaint.getTextBounds(time, 0, time.length(), bounds);
        timeLeft = inset - bounds.left;
        dateLeft = timeLeft + timePaint.measureText(time) + gap;
        float avail = getWidth() - dateLeft;
        if (avail <= 0f) {
            shownDay = day;
            shownDate = date;
            return;
        }
        shownDay = TextUtils.ellipsize(day, dayPaint, avail, TextUtils.TruncateAt.END).toString();
        shownDate = TextUtils.ellipsize(date, datePaint, avail, TextUtils.TruncateAt.END).toString();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float baseline = (getHeight() + digitHeight) / 2f;
        canvas.drawText(time, timeLeft, baseline, timePaint);
        canvas.drawText(shownDay, dateLeft, baseline - digitHeight + dayHeight, dayPaint);
        canvas.drawText(shownDate, dateLeft, baseline, datePaint);
    }
}
