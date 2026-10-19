package io.github.mesmerprism.rustykiosk

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import io.github.mesmerprism.rustyquest.navigatorvisibility.AndroidNavigatorVisibility
import io.github.mesmerprism.rustyquest.navigatorvisibility.NavigatorVisibilityObserver
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Normal application identity. CRCS is the trigger; Accessibility only scopes the selected target. */
internal object VisibilityMenuBridge {
  const val ACTION = "com.oculus.vrshell.intent.action.TOGGLE_NAVIGATOR_VISIBILITY"
  private val main = Handler(Looper.getMainLooper())
  private val journalWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "KioskVisibilityEvidence") }
  private val platformWorker = Executors.newSingleThreadExecutor { r -> Thread(r, "KioskVisibilityPlatform") }
  @Volatile private var service: KioskAccessibilityService? = null
  @Volatile private var current: Session? = null
  @Volatile private var lastStatus = JSONObject().put("phase", "idle").toString()
  @Volatile private var lastJournal = JSONObject().toString()
  @Volatile private var lastCallbackToSend = -1L

  fun isAvailable(context: Context): Boolean = capability(context) == null
  /** Read-only cross-mode admission; the existing controller still owns its full retirement. */
  fun outstandingCustody(): Boolean = current != null
  fun isReady(context: Context): Boolean = isAvailable(context) && service != null && current == null
  fun description(context: Context): String {
    if (!isAvailable(context)) return "Visibility menu closing needs compatible Horizon platform support."
    if (service == null) return "Enable Rusty Kiosk Accessibility to identify the selected app."
    val session = current ?: return if (JSONObject(lastStatus).optString("reporting_warning").isNotEmpty())
      "Ready for visibility menu closing. A previous report could not be saved."
      else "Ready for visibility menu closing; no shell companion is needed."
    if (session.held) return "An earlier operation is unresolved. Visibility closing is stopped; inspect its status before restarting."
    return if (session.active && session.reportingWarning != null)
      "Visibility menu closing is active. Its report could not be saved."
      else if (session.active && session.registrationDelayed)
      "Waiting for the original visibility registration; it has not been retried."
      else if (session.active) "Visibility menu closing is active for the selected app."
      else "Waiting for the previous visibility reader to retire."
  }

  fun statusJson(context: Context): JSONObject {
    val s = current
    return JSONObject().put("schema", "rusty.kiosk.visibility_status.v1")
      .put("available", isAvailable(context)).put("ready", isReady(context))
      .put("description", description(context)).put("capability_reason", capability(context))
      .put("state", s?.status() ?: JSONObject(lastStatus))
      .put("journal_file", VisibilityMenuJournal.FILE_NAME)
      .put("visibility_journal", s?.journal?.snapshot() ?: JSONObject(lastJournal))
      .put("timing_endpoint", "CRCS callback receipt to broadcast send; not native invocation or pixels")
  }

  fun metricsSummary(context: Context): String {
    val s = current
    val elapsed = s?.lastCallbackToSend ?: lastCallbackToSend
    return if (elapsed >= 0L)
      "Visibility callback to broadcast: ${elapsed} ms. CRCS false is not proof of hidden pixels."
    else "Visibility timings appear after a qualified CRCS opening; they do not measure pixels."
  }

  private fun capability(context: Context): String? {
    val candidate = AndroidNavigatorVisibility.create(context,
      object : NavigatorVisibilityObserver.Listener {
        override fun observed(sample: NavigatorVisibilityObserver.Observation) = Unit
        override fun fault(generation: Long, category: String) = Unit
      })
    candidate.observer?.close() // NEW closes without registering or unregistering.
    return if (candidate.available) null else candidate.reason
  }

  fun attach(connected: KioskAccessibilityService) {
    val previous = current
    if (previous != null && previous.owner !== connected) {
      previous.active = false
      previous.fail("visibility-accessibility-instance-replaced")
    }
    service = connected
    val restored = GuardStateStore(connected).loadArmed()
    if (restored?.mode == GuardMode.UNAVAILABLE)
      GuardStateStore(connected).disarm("unsupported-persisted-kiosk-mode")
    if (restored?.mode == GuardMode.VISIBILITY && current?.config?.generation != restored.generation)
      GuardStateStore(connected).disarm("visibility-reader-not-restored")
  }

  fun detach(disconnected: KioskAccessibilityService) {
    if (service !== disconnected) return
    service = null
    disarm(disconnected, "visibility-accessibility-disconnected")
    val config = GuardStateStore(disconnected).loadArmed()
    if (config?.mode == GuardMode.VISIBILITY)
      GuardStateStore(disconnected).disarm("visibility-accessibility-disconnected")
  }

  fun onTargetArmed(context: Context, config: GuardConfig) {
    check(config.mode == GuardMode.VISIBILITY && config.generation != 0L)
    check(isReady(context)) { description(context) }
    val s = Session(context.applicationContext, config)
    check(current == null)
    current = s
    main.post { s.prepare() }
  }

  /** Revocation is immediate even when the journal/registration worker has not returned. */
  fun disarm(context: Context, reason: String) {
    val s = current ?: return
    s.active = false
    main.post { s.stop(reason) }
  }

  fun observeWindow(config: GuardConfig, type: Int, source: Long, observed: Long, pkg: String, cls: String) {
    val s = current ?: return
    if (config.mode != GuardMode.VISIBILITY || s.config.generation != config.generation || !s.active) return
    when (s.policy.metadata(type, source, observed, pkg, cls)) {
      VisibilityMenuPolicy.Action.DISARM -> s.fail(s.policy.reason)
      VisibilityMenuPolicy.Action.TARGET -> if (!s.targetRecorded) {
        s.targetRecorded = true
        s.record("TARGET_METADATA_QUALIFIED", observed)
      }
      else -> Unit
    }
  }

  private class Session(val context: Context, val config: GuardConfig) {
    val owner = checkNotNull(service)
    @Volatile var active = true
    @Volatile var held = false
    @Volatile var phase = "preparing"
    val armedAt = SystemClock.uptimeMillis()
    val policy = VisibilityMenuPolicy(config.target.packageName, config.target.activityName, armedAt)
    val fence = VisibilityCallbackFence()
    val journal = VisibilityMenuJournal(context, config)
    val tickets = VisibilityTicketLedger(config.generation)
    val fixedIntent = Intent(ACTION).setComponent(ComponentName("com.oculus.vrshell", "com.oculus.vrshell.ShellControlBroadcastReceiver"))
    @Volatile var reader: NavigatorVisibilityObserver? = null
    @Volatile var observerGeneration = 0L
    var registerPending = false
    var registered = false
    var unregisterPending = false
    var unregisterAttempted = false
    var jobs = 0
    var original: String? = null
    var intentPrepared = false
    var registrationDelayed = false
    var reportingWarning: String? = null
    var falseToken = 0L
    var lastCallbackToSend = -1L
    var targetRecorded = false
    var coalescedJournalRows = 0L
    private val callbackJobs = AtomicInteger()
    private val callbackOverflow = AtomicBoolean()
    private val errorPosted = AtomicBoolean()
    private data class Signal(val value: Boolean, val observed: Long, val token: Long, val sequence: Long)
    private val early = ArrayDeque<Signal>()

    fun status(): JSONObject = JSONObject().put("generation", config.generation).put("active", active)
      .put("phase", phase).put("held", held).put("registered", registered)
      .put("observer_generation", observerGeneration)
      .put("observer_phase", reader?.status()?.phase?.name)
      .put("observer_fault", reader?.status()?.fault)
      .put("target_package", config.target.packageName).put("target_activity", config.target.activityName)
      .put("register_pending", registerPending).put("unregister_pending", unregisterPending)
      .put("registration_delayed", registrationDelayed).put("reporting_warning", reportingWarning)
      .put("pending_report_jobs", jobs).put("pending_ticket_reports", tickets.pendingReports)
      .put("pending_original", original).put("pending_effect", policy.pending)
      .put("target_metadata_qualified", policy.qualified).put("signal_value", policy.value)
      .put("reason", policy.reason).put("prepared_slots", tickets.size)
      .put("last_callback_to_send_ms", lastCallbackToSend)
      .put("coalesced_observational_journal_rows", coalescedJournalRows)
      .put("queued_callback_jobs", callbackJobs.get())

    fun prepare() {
      if (!active) { finishIfKnown(); return }
      jobs++
      journalWorker.execute {
        val result = runCatching { journal.prepare() }
        main.post {
          jobs--
          result.fold({ ids ->
            tickets.prepareDurable(ids); intentPrepared = true
            if (active) register() else finishIfKnown()
          }, { intentPrepared = false; reportingWarning = "initial-intent-durability-failed"
            fail("prepared-intent-not-durable-no-dispatch") })
        }
      }
    }

    fun register() {
      registerPending = true; phase = "registering"
      val listener = object : NavigatorVisibilityObserver.Listener {
        override fun observed(sample: NavigatorVisibilityObserver.Observation) {
          if (sample.generation != observerGeneration) return
          val value = sample.visible
          val observedUptimeMs = sample.receivedUptimeMs
          val token = fence.observe(value, observedUptimeMs)
          if (callbackJobs.incrementAndGet() > 64) {
            callbackJobs.decrementAndGet()
            fence.invalidate(observedUptimeMs)
            active = false
            if (callbackOverflow.compareAndSet(false, true))
              main.post { fail("bounded-callback-queue-overflow") }
            return
          }
          main.post {
            callbackJobs.decrementAndGet()
            if (registerPending) {
              if (early.size >= 32) fail("registration-callback-overflow")
              else early.addLast(Signal(value, observedUptimeMs, token, sample.sequence))
            } else this@Session.signal(value, observedUptimeMs, token, sample.sequence)
            finishIfKnown()
          }
        }
        override fun fault(generation: Long, category: String) {
          if (generation != observerGeneration) return
          active = false
          fence.invalidate(SystemClock.uptimeMillis())
          if (errorPosted.compareAndSet(false, true))
            main.post { fail("crcs-reader-error-${category.take(64)}") }
        }
      }
      platformWorker.execute {
        var enteredRegistration = false
        val result = runCatching {
          val candidate = AndroidNavigatorVisibility.create(context, listener)
          val instance = checkNotNull(candidate.observer) { candidate.reason }
          reader = instance
          observerGeneration = instance.status().generation
          enteredRegistration = true
          val result = instance.start()
          check(result.phase != NavigatorVisibilityObserver.Phase.HELD) { result.fault }
          result.phase == NavigatorVisibilityObserver.Phase.ACTIVE
        }
        main.post {
          registerPending = false
          registrationDelayed = false
          result.fold({ accepted ->
            registered = accepted; phase = if (accepted) "registered" else "registration-rejected"
            if (!accepted) fail("crcs-registration-rejected")
            while (early.isNotEmpty()) {
              val edge = early.removeFirst()
              if (active && accepted) signal(edge.value, edge.observed, edge.token, edge.sequence)
            }
            if (!active) retireReader()
          }, {
            if (enteredRegistration) { held = true; fail("crcs-registration-unknown-held") }
            else fail("crcs-adapter-construction-failed-no-registration")
          })
          finishIfKnown()
        }
      }
      main.postDelayed({
        if (registerPending) registrationDelayed = true // Observation, never cancellation or replay.
      }, 30_000L)
    }

    private fun signal(value: Boolean, observed: Long, token: Long, sequence: Long) {
      if (!active || !registered || token == 0L) return
      // A batched true already superseded on the callback thread is observation only.
      if (value && !fence.matchesTrue(token)) return
      val now = SystemClock.uptimeMillis()
      val prior = policy.value
      when (policy.signal(value, observed, now, sequence)) {
        VisibilityMenuPolicy.Action.CLOSE -> send(observed, token)
        VisibilityMenuPolicy.Action.ESCAPE -> {
          record("THIRD_OPENING_ESCAPE", observed)
          if (service === owner) owner.returnFromVisibility(config.generation)
          if (active) fail("triple-visibility-opening-escape")
        }
        VisibilityMenuPolicy.Action.DISARM -> fail(policy.reason)
        VisibilityMenuPolicy.Action.CLOSED -> {
          falseToken = token
          if (prior != false) record("CRCS_FALSE_NOT_FOCUS_OR_PIXELS", observed)
          settleLater()
        }
        else -> if (prior != value) record("CRCS_OBSERVATION", observed)
      }
    }

    private fun send(observed: Long, token: Long) {
      val now = SystemClock.uptimeMillis()
      if (!active || !registered || service !== owner || original != null || !intentPrepared ||
        tickets.size == 0 || !policy.canSend(observed, now) || !fence.matchesTrue(token) ||
        Process.myUid() < 10_000 || context.applicationInfo.uid != Process.myUid() ||
        Binder.getCallingUid() != Process.myUid()) {
        fail("unsent-close-admission-invalid-no-retry"); return
      }
      val id = tickets.claim(config.generation, active && intentPrepared) ?: run {
        fail("prepared-ticket-unavailable"); return
      }
      original = id
      val dispatch = SystemClock.uptimeMillis()
      if (!active || !policy.canSend(observed, dispatch) || !fence.matchesTrue(token)) {
        tickets.cancelUnsent(id); original = null; fail("unsent-callback-or-generation-changed"); return
      }
      phase = "broadcast-original"
      try {
        context.sendBroadcast(fixedIntent)
      } catch (failure: Throwable) {
        held = true
        tickets.hold(id)
        record("SEND_THROWN_UNKNOWN_HELD", SystemClock.uptimeMillis(), id, dispatch, observed)
        fail("broadcast-transport-unknown-held"); return
      }
      val returned = SystemClock.uptimeMillis()
      // The original returned now. Reporting cannot turn it back into an unknown operation.
      tickets.returnedKnown(id); original = null; policy.originalReturned()
      phase = if (active) "awaiting-false-quiet" else "retiring"
      lastCallbackToSend = dispatch - observed
      settleLater()
      jobs++
      val row = row("BROADCAST_RETURNED_NOT_EFFECT", returned, id, dispatch, observed)
        .put("send_return_uptime_ms", returned)
      journalWorker.execute {
        val result = runCatching { journal.returned(id, row) }
        main.post {
          jobs--
          result.fold({ replacement ->
            tickets.reportDurable(id, replacement)
          }, { tickets.reportFailed(id); reportingWarning = "known-send-report-not-saved" })
          publishRetiredReport(); finishIfKnown()
        }
      }
    }

    private fun settleLater() {
      if (!active || !policy.pending || policy.falseObserved < 0L) return
      val serial = policy.serial
      val token = falseToken
      main.postAtTime({
        val now = SystemClock.uptimeMillis()
        if (active && fence.matchesFalseQuiet(token, now) && policy.settle(serial, now)) {
          phase = "armed"
          record("KNOWN_SEND_AND_CRCS_FALSE_QUIET_NOT_FOCUS_OR_PIXELS", now)
        }
      }, maxOf(SystemClock.uptimeMillis(), policy.falseObserved + VisibilityMenuPolicy.QUIET_MS))
    }

    fun fail(reason: String) {
      active = false; policy.disarm(reason); phase = if (held) "held" else "retiring"
      val persisted = GuardStateStore(context).loadArmed()
      if (persisted?.mode == GuardMode.VISIBILITY && persisted.generation == config.generation)
        GuardStateStore(context).disarm(reason)
      stop(reason)
    }

    fun stop(reason: String) {
      active = false; policy.disarm(reason)
      tickets.stop()
      early.clear()
      retireReader(); finishIfKnown()
    }

    private fun retireReader() {
      if (registerPending || unregisterPending || unregisterAttempted) return
      val instance = reader ?: return
      unregisterAttempted = true; unregisterPending = true; phase = "unregistering"
      // A blocked original registration or unregister is retained, never killed or retried.
      platformWorker.execute {
        val result = runCatching {
          instance.close()
          instance.status().phase == NavigatorVisibilityObserver.Phase.CLOSED
        }
        main.post {
          unregisterPending = false
          if (result.getOrDefault(false)) {
            registered = false; reader = null
            // Exact platform retirement reconciles its hold; an uncertain send stays owned.
            if (original == null && !tickets.held) held = false
          }
          else { held = true; phase = "unregister-unknown-held" }
          finishIfKnown()
        }
      }
    }

    private fun finishIfKnown() {
      if (current !== this) return // Late retired-reader callbacks cannot overwrite a newer generation's evidence.
      if (active || held || registerPending || unregisterPending || registered || original != null || callbackJobs.get() != 0) return
      phase = "retired"
      lastStatus = status().toString()
      lastJournal = journal.snapshot().toString()
      VisibilityMenuBridge.lastCallbackToSend = lastCallbackToSend
      if (current === this) current = null
    }

    private fun publishRetiredReport() {
      if (current == null && JSONObject(lastStatus).optLong("generation") == config.generation) {
        lastStatus = status().toString(); lastJournal = journal.snapshot().toString()
      }
    }

    fun record(phase: String, at: Long, id: String? = null, dispatch: Long? = null, observed: Long? = null) {
      // Authority is processed above; only duplicate/observational evidence may be coalesced.
      if (jobs >= 16 && !phase.contains("HELD") && !phase.contains("ESCAPE")) {
        coalescedJournalRows++; return
      }
      jobs++
      val data = row(phase, at, id, dispatch, observed)
      journalWorker.execute {
        val result = runCatching { journal.record(data) }
        main.post {
          jobs--
          if (result.isFailure) reportingWarning = "observational-report-not-saved"
          publishRetiredReport()
          finishIfKnown()
        }
      }
    }

    private fun row(phase: String, at: Long, id: String? = null, dispatch: Long? = null, observed: Long? = null): JSONObject =
      JSONObject().put("phase", phase).put("uptime_ms", at).put("command_id", id)
        .put("dispatch_uptime_ms", dispatch).put("callback_observed_uptime_ms", observed)
  }
}
