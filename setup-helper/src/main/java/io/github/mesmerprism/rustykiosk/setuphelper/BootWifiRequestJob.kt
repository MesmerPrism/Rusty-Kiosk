package io.github.mesmerprism.rustykiosk.setuphelper

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.PersistableBundle

/** One boot-owned job; the deadline can expire it but never relax request admission. */
internal object BootWifiRequestJob {
  const val ID = 4107
  const val BOOT_COUNT = "boot_count"
  const val BOOT_ELAPSED = "boot_elapsed"

  fun schedule(context: Context, receipt: BootRequestReceipt): Boolean {
    val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
    val job = JobInfo.Builder(ID, ComponentName(context, BootWifiRequestService::class.java))
      .setRequiredNetwork(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build())
      .setOverrideDeadline(BootRequestHandler.MAX_WAIT_MS)
      .setPersisted(false)
      .setExtras(PersistableBundle().apply {
        putInt(BOOT_COUNT, receipt.bootCount)
        putLong(BOOT_ELAPSED, receipt.elapsedRealtimeMs)
      }).build()
    return scheduler.schedule(job) == JobScheduler.RESULT_SUCCESS
  }

  fun cancel(context: Context) { context.getSystemService(JobScheduler::class.java)?.cancel(ID) }
}

class BootWifiRequestService : JobService() {
  override fun onStartJob(params: JobParameters): Boolean {
    val network = params.network
    val connectivity = getSystemService(ConnectivityManager::class.java)
    val wifiReady = network != null && connectivity?.getNetworkCapabilities(network)
      ?.let { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) } == true &&
      connectivity?.getLinkProperties(network)?.linkAddresses?.any {
        !it.address.isLoopbackAddress && !it.address.isLinkLocalAddress && !it.address.isAnyLocalAddress
      } == true
    SetupExecutor(this).dispatchBootRequest(params.extras.getInt(BootWifiRequestJob.BOOT_COUNT, -2),
      params.extras.getLong(BootWifiRequestJob.BOOT_ELAPSED, -1), wifiReady)
    return false // The fixed Settings operation completed; do not reschedule.
  }

  override fun onStopJob(params: JobParameters): Boolean = false
}
