package io.github.mesmerprism.rustykiosk

/**
 * Linearizes in-process wearer-grant/key transitions with sensitive v2 response sealing. This is
 * not authority by itself; persisted epochs remain the authority and are re-read inside the lock.
 */
internal object DirectOperatorV2GrantMutex {
  private val monitor = Any()

  fun <T> locked(block: () -> T): T = synchronized(monitor, block)
}

/** Process-only keys and replay windows. Never persisted or revived by START. */
internal object ProcessDirectOperatorV2Sessions {
  val manager = DirectOperatorV2SessionManager()
}
