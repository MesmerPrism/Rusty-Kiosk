package io.github.mesmerprism.questautobootexample;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootEvents extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent != null && Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            AutobootController.get(context).beginBoot();
        }
    }
}
