package io.github.mesmerprism.rustykiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Debug-only admission adapter for one exact watchdog Home transition. */
class RustyKioskGuardCliReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != GuardDebugContract.ACTION_EXTERNAL_HOME_TRANSITION) return
    val requestId = intent.getStringExtra(GuardDebugContract.EXTRA_REQUEST_ID) ?: return
    if (!GuardDebugContract.validRequestId(requestId)) return
    context.sendBroadcast(
      Intent(GuardDebugContract.ACTION_INTERNAL_HOME_TRANSITION)
        .setPackage(context.packageName)
        .putExtra(GuardDebugContract.EXTRA_REQUEST_ID, requestId)
    )
  }
}
