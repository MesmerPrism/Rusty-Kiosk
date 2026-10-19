package io.github.mesmerprism.rustykiosk

/** Pure, app-owned policy. The shell is an observation/actuation adapter only. */
internal data class ShellCompanionIdentity(val session: String, val boot: String, val pid: Int)

internal data class ShellGuardTarget(val generation: Long, val packageName: String, val activityName: String,
  val mode: GuardMode = GuardMode.SHELL)

internal data class ShellCompanionReply(
  val status: String,
  val action: String = "ignore",
  val commandId: String? = null,
  val dueUptimeMs: Long? = null,
  val disarm: Boolean = false,
)

internal object ShellCompanionAdmissionPolicy {
  fun authorized(uid: Int, binderPid: Int, declaredPid: Int, protocol: Int): Boolean =
    uid == 2000 && binderPid > 0 && binderPid == declaredPid && protocol == 1
}

internal class ShellCompanionCoordinator(
  private val originals: ShellOriginalLedger = MemoryShellOriginalLedger(),
  private val commandId: () -> String,
) {
  companion object {
    const val HEARTBEAT_MS = 3_000L
    const val CLOSE_DELAY_MS = 80L
    const val CANDIDATE_TTL_MS = 2_000L
  }

  private data class Command(val id: String, val generation: Long, val openedMs: Long, val dueMs: Long,
    val target: ShellGuardTarget)
  private val restored = runCatching { originals.read() }.getOrDefault(ShellOriginalRecord.Damaged)
  private var helper: ShellCompanionIdentity? = null
  private var helperReady = false
  private var lastHeartbeat = -1L
  private var target: ShellGuardTarget? = null
  private var engine: GuardDecisionEngine? = null
  private var qualified = false
  private var native = false
  private var sequence = -1L
  private var lastEvent = -1L
  private var candidate: Command? = null
  private var pending: Command? = (restored as? ShellOriginalRecord.Pending)?.claim?.let {
    Command(it.commandId, it.target.generation, it.openedMs, it.dueMs, it.target)
  }
  private var originalIdentity = (restored as? ShellOriginalRecord.Pending)?.claim?.helper
  private var held = restored is ShellOriginalRecord.Pending || restored == ShellOriginalRecord.Damaged
  private var storageDamaged = restored == ShellOriginalRecord.Damaged
  private var terminal = false
  private var revokedGeneration: Long? = null
  private var observationId: String? = null
  private var explicitlyArmedGeneration: Long? = null
  private var ownFocused: Boolean? = null

  val pendingCommandId: String? get() = pending?.id
  val candidateCommandId: String? get() = candidate?.id
  val armedTarget: ShellGuardTarget? get() = target
  val originalHeld: Boolean get() = held
  val outstandingCustody: Boolean get() = pending != null || held || storageDamaged
  val originalStorageDamaged: Boolean get() = storageDamaged
  val originalTarget: ShellGuardTarget? get() = pending?.target

  fun healthy(nowMs: Long): Boolean = helperReady && !held && !storageDamaged && fresh(nowMs)
  fun ready(nowMs: Long): Boolean = pending == null && healthy(nowMs)

  fun armTarget(next: ShellGuardTarget): Boolean {
    if (next.generation == revokedGeneration || pending != null || held) return false
    explicitlyArmedGeneration = next.generation
    return synchronizeTarget(next)
  }

  /** A shell arm may not be resurrected from disk after app process death. */
  fun synchronizeTarget(next: ShellGuardTarget?): Boolean {
    if (next != null && (next.generation == revokedGeneration ||
      (next.mode == GuardMode.SHELL && next.generation != explicitlyArmedGeneration))) {
      revokedGeneration = next.generation
      disarm()
      return false
    }
    if (target == next) return true
    target = next
    engine = next?.let { GuardDecisionEngine(it.packageName, initialHandoffQuietMs = 0L) }
    qualified = false
    native = false
    candidate = null
    observationId = null
    ownFocused = null
    terminal = false
    // An admitted original belongs to its original generation until its callback is known.
    return true
  }

  fun disarm() {
    target?.let { revokedGeneration = it.generation }
    target = null
    engine = null
    qualified = false
    native = false
    candidate = null
    terminal = true
    explicitlyArmedGeneration = null
  }

  fun register(identity: ShellCompanionIdentity, isReady: Boolean, nowMs: Long): ShellCompanionReply {
    if (pending != null && originalIdentity != identity) return ShellCompanionReply("original_helper_mismatch")
    if (helper != null && helper != identity && (fresh(nowMs) || pending != null)) {
      return ShellCompanionReply("helper_already_owned")
    }
    if (helper != identity) {
      helper = identity
      sequence = -1L
      lastEvent = -1L
      candidate = null
      qualified = false
      native = false
    }
    helperReady = isReady
    lastHeartbeat = nowMs
    val revoke = !isReady && target?.mode == GuardMode.SHELL
    if (revoke) disarm()
    return ShellCompanionReply("registered", disarm = revoke)
  }

  fun heartbeat(identity: ShellCompanionIdentity, nowMs: Long): ShellCompanionReply {
    if (helper != identity) return ShellCompanionReply("helper_mismatch")
    val expired = !fresh(nowMs)
    lastHeartbeat = nowMs
    if (expired && target?.mode == GuardMode.SHELL) {
      disarm()
      return ShellCompanionReply("heartbeat_expired", disarm = true)
    }
    return ShellCompanionReply("heartbeat")
  }

  fun targetState(identity: ShellCompanionIdentity, generation: Long, ownFocused: Boolean, nowMs: Long,
    observedUptimeMs: Long = nowMs): ShellCompanionReply {
    admit(identity, generation, nowMs)?.let { return it }
    if (observedUptimeMs < 0L || observedUptimeMs < lastEvent || observedUptimeMs > nowMs ||
      nowMs - observedUptimeMs > CANDIDATE_TTL_MS) return ShellCompanionReply("stale_target_focus")
    val changed = this.ownFocused != ownFocused
    this.ownFocused = ownFocused
    if (!changed) return ShellCompanionReply("target_focus_unchanged")
    if (ownFocused) {
      if (target!!.mode != GuardMode.SHELL) return ShellCompanionReply("target_observed", "observe-only", observationId)
      engine!!.observe(target!!.packageName, nowMs)
      if (!qualified) qualified = engine!!.confirmInitialHandoff(nowMs)
    }
    return ShellCompanionReply(if (qualified) "target_qualified" else "awaiting_target_focus", commandId = observationId)
  }

  fun nativeEvent(identity: ShellCompanionIdentity, generation: Long, nextSequence: Long,
    eventUptimeMs: Long, observedUptimeMs: Long, open: Boolean, nowMs: Long): ShellCompanionReply {
    admit(identity, generation, nowMs)?.let { return it }
    if (nextSequence <= sequence || eventUptimeMs <= lastEvent || eventUptimeMs < 0L ||
      observedUptimeMs < eventUptimeMs || observedUptimeMs > nowMs || nowMs - observedUptimeMs > CANDIDATE_TTL_MS ||
      nowMs - eventUptimeMs > CANDIDATE_TTL_MS) return ShellCompanionReply("stale_native_event")
    sequence = nextSequence
    lastEvent = eventUptimeMs
    val previous = native
    native = open
    if (target!!.mode != GuardMode.SHELL) {
      if (previous == open) return ShellCompanionReply("repeated_native_state")
      if (open) observationId = commandId()
      return ShellCompanionReply("accessibility_observer", "observe-only", observationId)
    }
    if (!open) {
      val canceled = candidate?.id
      candidate = null
      return ShellCompanionReply("native_closed", commandId = canceled ?: pending?.id ?: observationId)
    }
    if (previous) return ShellCompanionReply("repeated_native_open")
    if (!qualified) return ShellCompanionReply("awaiting_target_focus")
    observationId = commandId()
    return when (engine!!.observeHomeInvocation("com.oculus.vrshell", eventUptimeMs, "native-navigator-open")) {
      GuardDecision.DisarmAndReturn -> {
        disarm()
        ShellCompanionReply("triple_home_escape", action = "escape", commandId = observationId, disarm = true)
      }
      is GuardDecision.ScheduleRecovery -> {
        val c = Command(observationId!!, generation, eventUptimeMs, eventUptimeMs + CLOSE_DELAY_MS, target!!)
        candidate = c
        ShellCompanionReply("close_scheduled", "close", c.id, c.dueMs)
      }
      else -> ShellCompanionReply("home_coalesced", commandId = observationId)
    }
  }

  fun claim(identity: ShellCompanionIdentity, generation: Long, id: String, nowMs: Long): ShellCompanionReply {
    admit(identity, generation, nowMs)?.let { return it }
    if (target!!.mode != GuardMode.SHELL) return ShellCompanionReply("accessibility_observer", "observe-only")
    val c = candidate ?: return ShellCompanionReply("no_candidate")
    if (c.id != id || c.generation != generation) return ShellCompanionReply("command_mismatch")
    if (!native || nowMs - c.openedMs > CANDIDATE_TTL_MS) {
      candidate = null
      return ShellCompanionReply("candidate_expired")
    }
    if (pending != null || nowMs < c.dueMs) return ShellCompanionReply("wait", "wait", c.id, c.dueMs)
    val original = c.asOriginal(identity)
    if (!runCatching { originals.claim(original) }.getOrDefault(false)) {
      storageDamaged = true; held = true
      val revoke = revokeOriginalGeneration(c)
      return ShellCompanionReply("original_claim_storage_held", disarm = revoke)
    }
    pending = c
    originalIdentity = identity
    candidate = null
    return ShellCompanionReply("close_claimed", "execute", c.id, c.dueMs)
  }

  fun result(identity: ShellCompanionIdentity, generation: Long, id: String, outcome: String,
    success: Boolean, dispatchMs: Long?, callbackMs: Long?, nowMs: Long,
    completionMs: Long? = null): ShellCompanionReply {
    if (helper != identity) return ShellCompanionReply("helper_mismatch")
    val c = pending ?: return ShellCompanionReply("no_original")
    if (originalIdentity != identity) return ShellCompanionReply("original_helper_mismatch")
    if (c.id != id || c.generation != generation) return ShellCompanionReply("original_mismatch")
    if (outcome == "not-executed") {
      if (success || dispatchMs != null || callbackMs != null || completionMs == null ||
        completionMs < c.dueMs || completionMs > nowMs) return ShellCompanionReply("invalid_no_execute")
      if (!retire(c, identity, ShellOriginalTerminal(outcome, success, dispatchMs, callbackMs, completionMs)))
        return ShellCompanionReply("original_retirement_storage_held", disarm = revokeOriginalGeneration(c))
      return ShellCompanionReply("known_not_executed")
    }
    if (dispatchMs == null) return ShellCompanionReply("missing_dispatch_time")
    if (completionMs != null) return ShellCompanionReply("invalid_result_time")
    if (dispatchMs < c.dueMs || dispatchMs > nowMs ||
      (callbackMs != null && (callbackMs < dispatchMs || callbackMs > nowMs))) return ShellCompanionReply("invalid_result_time")
    if (outcome == "callback" && callbackMs != null) {
      if (!retire(c, identity, ShellOriginalTerminal(outcome, success, dispatchMs, callbackMs, completionMs)))
        return ShellCompanionReply("original_retirement_storage_held", disarm = revokeOriginalGeneration(c))
      if (!success) {
        val revoke = revokeOriginalGeneration(c)
        return ShellCompanionReply("callback_failed", disarm = revoke)
      }
      return ShellCompanionReply("callback_known")
    }
    if ((outcome != "unknown" && outcome != "error") || success || callbackMs != null) return ShellCompanionReply("invalid_outcome")
    held = true
    val revoke = revokeOriginalGeneration(c)
    return ShellCompanionReply("original_unknown_held", disarm = revoke)
  }

  private fun Command.asOriginal(identity: ShellCompanionIdentity) =
    ShellOriginalClaim(identity, target, id, openedMs, dueMs)

  private fun retire(c: Command, identity: ShellCompanionIdentity, terminal: ShellOriginalTerminal): Boolean {
    if (!runCatching { originals.retire(c.asOriginal(identity), terminal) }.getOrDefault(false)) {
      held = true; storageDamaged = true
      return false
    }
    pending = null; originalIdentity = null; held = false; storageDamaged = false
    return true
  }

  private fun revokeOriginalGeneration(command: Command): Boolean {
    if (target?.generation != command.generation || target?.mode != GuardMode.SHELL) return false
    disarm()
    return true
  }

  private fun admit(identity: ShellCompanionIdentity, generation: Long, nowMs: Long): ShellCompanionReply? = when {
    helper != identity -> ShellCompanionReply("helper_mismatch")
    !helperReady || !fresh(nowMs) -> ShellCompanionReply("helper_stale", disarm = target?.mode == GuardMode.SHELL)
    held -> ShellCompanionReply("original_unknown_held", disarm = target?.mode == GuardMode.SHELL)
    terminal || target == null -> ShellCompanionReply("guard_not_armed")
    target!!.generation != generation -> ShellCompanionReply("generation_mismatch")
    else -> null
  }

  private fun fresh(nowMs: Long): Boolean = lastHeartbeat >= 0L && nowMs >= lastHeartbeat && nowMs - lastHeartbeat <= HEARTBEAT_MS
}
