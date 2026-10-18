package io.github.mesmerprism.rustykiosk

/** Claims only cold-path durable tickets. The ledger is never reconstructed after process death. */
internal class VisibilityTicketLedger(private val generation: Long) {
  private val prepared = ArrayDeque<String>()
  private val used = ArrayDeque<String>()
  private val awaitingReport = mutableSetOf<String>()
  var original: String? = null; private set
  var held = false; private set
  private var stopped = false
  private var initialized = false
  val size: Int get() = prepared.size
  val pendingReports: Int get() = awaitingReport.size

  fun prepareDurable(ids: List<String>) {
    check(!initialized && ids.size == 12 && ids.toSet().size == 12)
    initialized = true
    if (!stopped) prepared.addAll(ids)
  }
  fun claim(currentGeneration: Long, allowed: Boolean): String? {
    if (!initialized || stopped || held || original != null || currentGeneration != generation ||
      !allowed || prepared.isEmpty()) return null
    val id = prepared.removeFirst()
    check(id !in used)
    used.addLast(id)
    while (used.size > 64) used.removeFirst()
    original = id
    return id
  }
  /** Transport custody ends on its known return, independently of reporting. */
  fun returnedKnown(id: String) {
    check(!held && original == id)
    original = null
    check(awaitingReport.add(id))
  }
  /** A replacement becomes executable only after this separate durable acknowledgement. */
  fun reportDurable(id: String, replacement: String) {
    check(id in awaitingReport && replacement !in used && replacement !in prepared)
    awaitingReport.remove(id)
    if (!stopped) prepared.addLast(replacement)
  }
  fun reportFailed(id: String) { check(awaitingReport.remove(id)) }
  fun cancelUnsent(id: String) { check(!held && original == id); original = null }
  fun hold(id: String) { check(original == id); held = true; stopped = true }
  fun stop() { stopped = true; prepared.clear() } // Original custody is independent of admission.
}
