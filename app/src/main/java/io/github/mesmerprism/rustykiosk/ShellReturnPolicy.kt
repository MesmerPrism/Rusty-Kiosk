package io.github.mesmerprism.rustykiosk

/** Shell-only owner admission; a start request is not an Android/pixel acknowledgement. */
internal object ShellReturnPolicy {
  const val ACTION = "io.github.mesmerprism.rustykiosk.action.SHELL_GUARD_RETURN"
  const val EXTRA_GENERATION = "shell_guard_generation"
  const val ESCAPED = "triple-shell-home-escape"
  const val ARRIVED = "shell-return-arrived"

  /** Called in the same app-main turn as the current-generation decision. No deferred helper launch. */
  fun dispatch(store: GuardStateStore, expected: ShellGuardTarget, start: (Long) -> Unit): Boolean {
    if (expected.mode != GuardMode.SHELL) return false
    val current = store.loadArmed() ?: return false
    if (current.mode != GuardMode.SHELL || current.generation != expected.generation ||
      current.target.packageName != expected.packageName || current.target.activityName != expected.activityName) return false
    store.disarm(ESCAPED)
    try { start(expected.generation) }
    catch (failure: Throwable) {
      store.disarm("shell-return-dispatch-failed")
      throw failure
    }
    return true
  }
}

/** One Activity's Shell-only arrival/resume fence; ordinary resumes have no pending generation. */
internal class ShellReturnLifecycle(private val store: GuardStateStore) {
  private var generation: Long? = null
  var rejected: Boolean = false
    private set

  fun arrive(requested: Long?): Boolean {
    val accepted = requested != null && store.acceptShellReturnArrival(requested)
    generation = requested.takeIf { accepted }
    rejected = !accepted
    return accepted
  }

  fun resumeAllowed(): Boolean {
    if (rejected) return false
    val expected = generation ?: return true
    if (!store.canResumeShellReturn(expected)) { rejected = true; return false }
    generation = null
    return true
  }
}
