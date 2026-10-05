package io.github.mesmerprism.questautobootexample;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class RetryEvent extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent != null && AutobootController.validTickAction(intent.getAction())) {
            AutobootController.get(context).tick();
        }
    }
}
