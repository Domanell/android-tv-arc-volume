package io.github.domanell.arcvolume;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Starts the service after boot and after an app update, where the TV delivers these. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            AppSettings.prefs(context).edit()
                    .putInt(AppSettings.KEY_BOOT_BROADCAST, AppSettings.bootCount(context))
                    .apply();
            VolumeService.start(context, action);
        } else if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)) {
            VolumeService.start(context, action);
        }
    }
}
