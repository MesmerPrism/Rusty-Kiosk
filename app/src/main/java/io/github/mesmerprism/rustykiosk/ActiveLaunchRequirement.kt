package io.github.mesmerprism.rustykiosk

import java.security.MessageDigest
import java.util.UUID

internal enum class ActiveRequirementEvaluationState(val wireName: String) {
  SATISFIED("satisfied"),
  UNSATISFIED("unsatisfied"),
  UNAVAILABLE("unavailable"),
  ERROR("error"),
}

internal data class ActiveRequirementEvaluation(
  val handlerId: ActiveRequirementHandlerId,
  val state: ActiveRequirementEvaluationState,
  val provenance: String,
  val reason: String,
)

internal fun interface ActiveRequirementHandler {
  fun evaluate(): ActiveRequirementEvaluation
}

internal class ActiveRequirementHandlerRegistry(
  registeredHandlers: List<Pair<ActiveRequirementHandlerId, ActiveRequirementHandler>>,
) {
  private val handlers = registeredHandlers.toMap()

  init {
    require(handlers.size == registeredHandlers.size) {
      "Active requirement handler registry contains duplicate handler IDs."
    }
  }

  fun evaluate(requirement: AppLaunchRequirement): ActiveRequirementEvaluation? {
    val handlerId = requirement.handler ?: return null
    val handler = handlers[handlerId]
      ?: return ActiveRequirementEvaluation(
        handlerId,
        ActiveRequirementEvaluationState.UNAVAILABLE,
        "rusty-kiosk.compiled-active-requirement-registry",
        "The compiled ${handlerId.wireName} handler is unavailable.",
      )
    return runCatching { handler.evaluate() }
      .fold(
        onSuccess = { evaluation ->
          if (evaluation.handlerId == handlerId) evaluation
          else ActiveRequirementEvaluation(
            handlerId,
            ActiveRequirementEvaluationState.ERROR,
            "rusty-kiosk.compiled-active-requirement-registry",
            "The compiled handler returned another handler's result.",
          )
        },
        onFailure = { throwable ->
          ActiveRequirementEvaluation(
            handlerId,
            ActiveRequirementEvaluationState.ERROR,
            "rusty-kiosk.compiled-active-requirement-registry",
            "The ${handlerId.wireName} handler failed: ${throwable.javaClass.simpleName}",
          )
        },
      )
  }
}

internal data class ActiveRequirementLaunchBinding(
  val catalogEntryKey: String,
  val packageName: String,
  val target: LaunchTarget,
  val installationIdentity: PackageInstallationIdentity,
  val launchKind: LaunchKind,
  val tagDocumentDigest: String,
  val requirementDigest: String,
)

internal data class ActiveRequirementLaunchCandidate(
  val binding: ActiveRequirementLaunchBinding,
  val requirement: AppLaunchRequirement,
)

internal object ActiveRequirementLaunchBindingFactory {
  fun create(
    entry: CatalogEntry,
    document: TagFileDocument,
    launchKind: LaunchKind,
    installationIdentity: PackageInstallationIdentity,
  ): ActiveRequirementLaunchCandidate {
    require(entry.installed) { "The requirement target is not installed." }
    val target = requireNotNull(entry.target) { "The requirement target is not launchable." }
    val packageName = requireNotNull(entry.packageName) { "The requirement target has no package." }
    require(target.packageName == packageName) { "The requirement target package changed." }
    val requirement = document.requirementFor(entry)
    return ActiveRequirementLaunchCandidate(
      binding = ActiveRequirementLaunchBinding(
        catalogEntryKey = entry.key,
        packageName = packageName,
        target = target,
        installationIdentity = installationIdentity,
        launchKind = launchKind,
        tagDocumentDigest = document.documentDigest,
        requirementDigest = stableDigest(
          "rusty.kiosk.active_launch_requirement.v2",
          packageName,
          requirement.wireName,
        ),
      ),
      requirement = requirement,
    )
  }
}

internal data class PendingRequirementLaunch(
  val pendingId: String,
  val binding: ActiveRequirementLaunchBinding,
  val requirement: AppLaunchRequirement,
  val createdAtElapsedMs: Long,
  val expiresAtElapsedMs: Long,
)

internal interface PendingRequirementLaunchStore {
  fun load(): PendingRequirementLaunch?
  fun replace(pending: PendingRequirementLaunch)
  fun clear()
}

internal object ProcessPendingRequirementLaunchStore : PendingRequirementLaunchStore {
  private var pending: PendingRequirementLaunch? = null

  @Synchronized override fun load(): PendingRequirementLaunch? = pending
  @Synchronized override fun replace(pending: PendingRequirementLaunch) { this.pending = pending }
  @Synchronized override fun clear() { pending = null }
}

internal enum class PendingRequirementClearReason {
  CANCELLED,
  EXPIRED,
  STALE_BINDING,
  UNAVAILABLE,
  ERROR,
}

internal sealed interface RequirementLaunchDecision {
  data class LaunchNow(val candidate: ActiveRequirementLaunchCandidate) : RequirementLaunchDecision
  data class RemediationRequired(
    val pending: PendingRequirementLaunch,
    val evaluation: ActiveRequirementEvaluation,
  ) : RequirementLaunchDecision
  data class Waiting(
    val pending: PendingRequirementLaunch,
    val evaluation: ActiveRequirementEvaluation,
  ) : RequirementLaunchDecision
  data class Blocked(
    val reason: String,
    val clearReason: PendingRequirementClearReason,
  ) : RequirementLaunchDecision
  data class Cleared(val reason: PendingRequirementClearReason, val message: String) : RequirementLaunchDecision
  data object NoPending : RequirementLaunchDecision
}

internal class ActiveRequirementLaunchCoordinator(
  private val store: PendingRequirementLaunchStore,
  private val registry: ActiveRequirementHandlerRegistry,
  private val elapsedNow: () -> Long,
  private val pendingId: () -> String = { UUID.randomUUID().toString() },
  private val pendingLifetimeMs: Long = DEFAULT_PENDING_LIFETIME_MS,
) {
  init {
    require(pendingLifetimeMs in 1..MAX_PENDING_LIFETIME_MS) {
      "Pending launch lifetime is outside the bounded window."
    }
  }

  @Synchronized
  fun request(candidate: ActiveRequirementLaunchCandidate): RequirementLaunchDecision {
    val existing = store.load()
    if (existing != null) {
      val now = elapsedNow()
      if (now < 0 || now >= existing.expiresAtElapsedMs) {
        store.clear()
        return RequirementLaunchDecision.Cleared(
          PendingRequirementClearReason.EXPIRED,
          "The pending requirement launch expired.",
        )
      }
      if (existing.binding != candidate.binding || existing.requirement != candidate.requirement) {
        store.clear()
        return RequirementLaunchDecision.Cleared(
          PendingRequirementClearReason.STALE_BINDING,
          "The pending launch changed; it was cancelled without launching another target.",
        )
      }
      val repeatedEvaluation = registry.evaluate(candidate.requirement)
        ?: run {
          store.clear()
          return RequirementLaunchDecision.Cleared(
            PendingRequirementClearReason.STALE_BINDING,
            "The pending app no longer has an active requirement.",
          )
        }
      return when (repeatedEvaluation.state) {
        ActiveRequirementEvaluationState.SATISFIED -> {
          store.clear()
          RequirementLaunchDecision.LaunchNow(candidate)
        }
        ActiveRequirementEvaluationState.UNSATISFIED ->
          RequirementLaunchDecision.Waiting(existing, repeatedEvaluation)
        ActiveRequirementEvaluationState.UNAVAILABLE -> {
          store.clear()
          RequirementLaunchDecision.Blocked(
            repeatedEvaluation.reason,
            PendingRequirementClearReason.UNAVAILABLE,
          )
        }
        ActiveRequirementEvaluationState.ERROR -> {
          store.clear()
          RequirementLaunchDecision.Blocked(
            repeatedEvaluation.reason,
            PendingRequirementClearReason.ERROR,
          )
        }
      }
    }
    val evaluation = registry.evaluate(candidate.requirement)
      ?: return RequirementLaunchDecision.LaunchNow(candidate)
    return when (evaluation.state) {
      ActiveRequirementEvaluationState.SATISFIED -> RequirementLaunchDecision.LaunchNow(candidate)
      ActiveRequirementEvaluationState.UNSATISFIED -> {
        val now = elapsedNow()
        require(now >= 0 && now <= Long.MAX_VALUE - pendingLifetimeMs) {
          "Invalid monotonic time for pending requirement launch."
        }
        val pending = PendingRequirementLaunch(
          pendingId = pendingId(),
          binding = candidate.binding,
          requirement = candidate.requirement,
          createdAtElapsedMs = now,
          expiresAtElapsedMs = now + pendingLifetimeMs,
        )
        store.replace(pending)
        RequirementLaunchDecision.RemediationRequired(pending, evaluation)
      }
      ActiveRequirementEvaluationState.UNAVAILABLE -> RequirementLaunchDecision.Blocked(
        evaluation.reason,
        PendingRequirementClearReason.UNAVAILABLE,
      )
      ActiveRequirementEvaluationState.ERROR -> RequirementLaunchDecision.Blocked(
        evaluation.reason,
        PendingRequirementClearReason.ERROR,
      )
    }
  }

  @Synchronized
  fun resume(candidate: ActiveRequirementLaunchCandidate?): RequirementLaunchDecision {
    val pending = store.load() ?: return RequirementLaunchDecision.NoPending
    val now = elapsedNow()
    if (now < 0 || now >= pending.expiresAtElapsedMs) {
      store.clear()
      return RequirementLaunchDecision.Cleared(
        PendingRequirementClearReason.EXPIRED,
        "The pending requirement launch expired.",
      )
    }
    if (candidate == null || candidate.binding != pending.binding || candidate.requirement != pending.requirement) {
      store.clear()
      return RequirementLaunchDecision.Cleared(
        PendingRequirementClearReason.STALE_BINDING,
        "The selected app, installed identity, launch mode, or requirement changed.",
      )
    }
    val evaluation = registry.evaluate(candidate.requirement)
      ?: run {
        store.clear()
        return RequirementLaunchDecision.Cleared(
          PendingRequirementClearReason.STALE_BINDING,
          "The pending app no longer has an active requirement.",
        )
      }
    return when (evaluation.state) {
      ActiveRequirementEvaluationState.SATISFIED -> {
        store.clear()
        RequirementLaunchDecision.LaunchNow(candidate)
      }
      ActiveRequirementEvaluationState.UNSATISFIED -> RequirementLaunchDecision.Waiting(pending, evaluation)
      ActiveRequirementEvaluationState.UNAVAILABLE -> {
        store.clear()
        RequirementLaunchDecision.Blocked(evaluation.reason, PendingRequirementClearReason.UNAVAILABLE)
      }
      ActiveRequirementEvaluationState.ERROR -> {
        store.clear()
        RequirementLaunchDecision.Blocked(evaluation.reason, PendingRequirementClearReason.ERROR)
      }
    }
  }

  @Synchronized
  fun cancel(expectedPendingId: String? = null): RequirementLaunchDecision {
    val pending = store.load() ?: return RequirementLaunchDecision.NoPending
    if (expectedPendingId != null && pending.pendingId != expectedPendingId) {
      return RequirementLaunchDecision.Blocked(
        "The pending launch changed before cancellation.",
        PendingRequirementClearReason.STALE_BINDING,
      )
    }
    store.clear()
    return RequirementLaunchDecision.Cleared(
      PendingRequirementClearReason.CANCELLED,
      "The pending requirement launch was cancelled.",
    )
  }

  fun pending(): PendingRequirementLaunch? = store.load()

  companion object {
    const val DEFAULT_PENDING_LIFETIME_MS = 2 * 60 * 1000L
    private const val MAX_PENDING_LIFETIME_MS = 5 * 60 * 1000L
  }
}

internal fun stableDigest(vararg values: String): String =
  MessageDigest.getInstance("SHA-256")
    .digest(values.joinToString("\u0000").toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte) }
