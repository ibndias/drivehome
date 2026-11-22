package com.drivehome.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;
import android.widget.Toast;

/**
 * Self-update plumbing:
 *  - PackageInstaller session results (explicit PendingIntent from {@link Updater#install()}):
 *    launches Android's install confirmation, reports failures.
 *  - {@link Intent#ACTION_MY_PACKAGE_REPLACED}: after an update the system has fallen back to
 *    another home app (ours was being replaced), so bring DriveHome back to the front.
 */
public final class InstallStatusReceiver extends BroadcastReceiver {
    private static final String TAG = "DriveHomeUpdater";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
            bringHomeToFront(context);
            return;
        }
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @SuppressWarnings("deprecation") // the typed overload is API 33+
            Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(confirm);
                } catch (RuntimeException e) {
                    Toast.makeText(context, "Can't show install prompt", Toast.LENGTH_SHORT).show();
                }
            }
        } else if (status == PackageInstaller.STATUS_SUCCESS) {
            bringHomeToFront(context);
        } else {
            String msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
            Toast.makeText(context, "Update not installed" + (msg != null ? ": " + msg : ""), Toast.LENGTH_LONG).show();
        }
    }

    private static void bringHomeToFront(Context context) {
        Intent home = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setClass(context, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(home);
        } catch (RuntimeException e) {
            Log.w(TAG, "could not bring home to front", e);
        }
    }
}
