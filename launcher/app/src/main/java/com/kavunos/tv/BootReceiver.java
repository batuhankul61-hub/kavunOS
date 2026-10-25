package com.kavunos.tv;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * After a plain APK install the TV's own launcher stays the default Home, so the TV starts on it. When the owner
 * has handed the Home button to kavunOS (HomeKeyService), bring kavunOS up once the TV has started, as if Home
 * had been pressed. Android 10 and later block this from the background; there the install script sets the
 * default Home instead.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) return;
        if (FirstRun.isDefaultHome(context) || !FirstRun.homeKeyServiceOn(context)) return;
        context.startActivity(new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED));
    }
}
