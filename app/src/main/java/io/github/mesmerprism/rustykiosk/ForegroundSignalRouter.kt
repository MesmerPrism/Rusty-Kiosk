package io.github.mesmerprism.rustykiosk

import java.lang.ref.WeakReference

internal data class ForegroundSignal(
  val generation: Long,
  val callerPackage: String,
  val protocolVersion: Int,
  val source: String,
  val receivedAtMs: Long,
  val transportLatencyMs: Long?,
)

internal object ForegroundSignalRouter {
  @Volatile private var serviceReference = WeakReference<KioskAccessibilityService>(null)

  fun attach(service: KioskAccessibilityService) {
    serviceReference = WeakReference(service)
  }

  fun detach(service: KioskAccessibilityService) {
    if (serviceReference.get() === service) {
      serviceReference.clear()
    }
  }

  fun dispatch(signal: ForegroundSignal): Boolean {
    val service = serviceReference.get() ?: return false
    service.enqueueForegroundSignal(signal)
    return true
  }
}
