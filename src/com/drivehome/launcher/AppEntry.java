package com.drivehome.launcher;

import android.content.ComponentName;
import android.graphics.Bitmap;

/** One launchable activity. Icons are pre-scaled bitmaps filled in by the background loader. */
final class AppEntry {
    final ComponentName component;
    /** component.flattenToString(); also the value persisted for tile assignments. */
    final String key;
    final String packageName;
    final String label;

    /** Pre-scaled to the home tile icon size. Written on the loader thread, read on main. */
    volatile Bitmap tileIcon;
    /** Pre-scaled to the drawer / list icon size. */
    volatile Bitmap gridIcon;

    AppEntry(ComponentName component, String key, String label) {
        this.component = component;
        this.key = key;
        this.packageName = component.getPackageName();
        this.label = label;
    }
}
