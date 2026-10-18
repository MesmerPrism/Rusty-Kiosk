package io.github.mesmerprism.rustykiosk

/** CRCS edges admit closes; window metadata only qualifies the selected target. */
internal class VisibilityMenuPolicy(
  private val targetPackage: String,
  private val targetActivity: String,
  private val armedAt: Long,
) {
  enum class Action { IGNORE, TARGET, CLOSED, CLOSE, ESCAPE, DISARM }
  private val openings = ArrayDeque<Long>()
  var armed = true; private set
  var qualified = false; private set
  var pending = false; private set
  var originalKnown = false; private set
  var value: Boolean? = null; private set
  var lastOpening = -1L; private set
  var lastSignal = -1L; private set
  private var lastSequence = -1L
  var falseObserved = -1L; private set
  var serial = 0L; private set
  var reason = "awaiting-false-baseline-and-target"; private set
  private var sawFalse = false
  private var lastMetadata = -1L

  fun disarm(why: String) {
    if (!armed) return
    armed = false; reason = why; serial++
  }

  fun metadata(type: Int, source: Long, now: Long, pkg: String, cls: String): Action {
    if (!armed || type != 32 || source < armedAt || source <= lastMetadata ||
      source > now || now - source > TTL_MS) return Action.IGNORE
    lastMetadata = source
    if (pkg == targetPackage) {
      if (cls != targetActivity) return Action.IGNORE // Own virtual panels are not a departure.
      qualified = true; reason = "selected-target-metadata-qualified"
      return Action.TARGET
    }
    if (pkg !in META_PACKAGES) {
      disarm("other-app-top-level-window")
      return Action.DISARM
    }
    return Action.IGNORE
  }

  fun signal(next: Boolean, observed: Long, now: Long, sequence: Long = observed): Action {
    if (!armed || observed < armedAt || observed < lastSignal || sequence <= lastSequence || observed > now ||
      now - observed > TTL_MS) return Action.IGNORE
    val prior = value
    value = next; lastSignal = observed; lastSequence = sequence
    if (!next) {
      sawFalse = true
      if (pending) { falseObserved = observed; serial++ }
      reason = "crcs-false-not-focus-or-pixels"
      return Action.CLOSED
    }
    if (prior != false || !sawFalse || !qualified) return Action.IGNORE
    while (openings.isNotEmpty() && observed - openings.first() > WINDOW_MS) openings.removeFirst()
    openings.addLast(observed); lastOpening = observed
    if (openings.size >= 3) {
      disarm("triple-visibility-opening-escape")
      return Action.ESCAPE
    }
    if (pending) {
      disarm("new-opening-while-prior-effect-pending-no-retry")
      return Action.DISARM
    }
    pending = true; originalKnown = false; falseObserved = -1L; serial++
    reason = "one-fixed-broadcast-pending"
    return Action.CLOSE
  }

  fun canSend(observed: Long, now: Long): Boolean = armed && qualified && pending &&
    !originalKnown && value == true && lastOpening == observed && now >= observed &&
    now - observed <= TTL_MS

  fun originalReturned() { originalKnown = true }

  fun settle(expectedSerial: Long, now: Long): Boolean {
    if (!armed || !qualified || !pending || !originalKnown || value != false ||
      falseObserved < 0L || serial != expectedSerial || now - falseObserved < QUIET_MS) return false
    pending = false; serial++
    reason = "known-send-and-crcs-false-quiet-not-focus-or-pixels"
    return true
  }

  companion object {
    const val TTL_MS = 2_000L
    const val QUIET_MS = 160L
    const val WINDOW_MS = 5_000L
    private val META_PACKAGES = setOf(
      "com.meta.xr.shell", "com.meta.systemui", "com.oculus.home", "com.oculus.horizon",
      "com.oculus.shellenv", "com.oculus.systemux", "com.oculus.vrshell",
      "com.oculus.panelapp.library", "com.oculus.systembar", "com.oculus.panelapp.settings",
      "com.oculus.store",
    )
  }
}

/** Published on the callback thread, before its main-loop work can be queued. */
internal class VisibilityCallbackFence {
  private var observed = -1L
  private var version = 0L
  private var value: Boolean? = null
  private var faulted = false
  @Synchronized fun observe(next: Boolean, time: Long): Long {
    if (faulted || time < observed) return 0L
    observed = time
    if (value != next) { version++; value = next }
    return version
  }
  @Synchronized fun invalidate(time: Long) {
    faulted = true; observed = maxOf(observed, time); version++; value = false
  }
  @Synchronized fun matchesTrue(token: Long): Boolean =
    !faulted && token != 0L && token == version && value == true
  @Synchronized fun matchesFalseQuiet(token: Long, now: Long): Boolean =
    !faulted && token != 0L && token == version && value == false &&
      now >= observed && now - observed >= VisibilityMenuPolicy.QUIET_MS
}
