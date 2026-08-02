package io.github.mesmerprism.rustykiosk

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets

internal data class RustyKioskInstallReceipt(
  val requestId: String,
  val state: String,
  val completed: Boolean,
  val message: String,
  val sessionId: Int?,
  val packageName: String?,
  val recordedAtMs: Long,
  val commitments: List<RustyKioskInstallPartCommitment>,
  val commitmentSha256: String =
    RustyKioskInstallCommitmentManifestPolicy.canonicalSha256(commitments),
) {
  fun toJson(): JSONObject =
    JSONObject()
      .put("schema", SCHEMA)
      .put("request_id", requestId)
      .put("state", state)
      .put("completed", completed)
      .put("message", message.take(240))
      .put("session_id", sessionId ?: JSONObject.NULL)
      .put("package", packageName ?: JSONObject.NULL)
      .put("recorded_at_ms", recordedAtMs)

  fun toStoredJson(): JSONObject =
    JSONObject()
      .put("schema", STORE_SCHEMA)
      .put("request_id", requestId)
      .put("state", state)
      .put("completed", completed)
      .put("message", message.take(MAX_MESSAGE_BYTES))
      .put("session_id", sessionId ?: JSONObject.NULL)
      .put("package", packageName ?: JSONObject.NULL)
      .put("recorded_at_ms", recordedAtMs)
      .put("commitments", RustyKioskInstallCommitmentManifestPolicy.toJson(commitments))
      .put("commitment_sha256", commitmentSha256)

  companion object {
    const val SCHEMA = "rusty.kiosk.local_install.v1"
    const val STORE_SCHEMA = "rusty.kiosk.local_install_state.v2"
    const val MAX_MESSAGE_BYTES = 240
  }
}

internal sealed interface RustyKioskInstallReceiptRead {
  data object Absent : RustyKioskInstallReceiptRead
  data class Available(val receipt: RustyKioskInstallReceipt) : RustyKioskInstallReceiptRead
  data class Damaged(val message: String) : RustyKioskInstallReceiptRead
}

internal object RustyKioskInstallAdmissionPolicy {
  fun canCreate(read: RustyKioskInstallReceiptRead): Boolean =
    read == RustyKioskInstallReceiptRead.Absent
}

internal object RustyKioskInstallReceiptCodec {
  fun inspect(bytes: ByteArray?, expectedRequestId: String): RustyKioskInstallReceiptRead {
    if (bytes == null) return RustyKioskInstallReceiptRead.Absent
    if (bytes.isEmpty() || bytes.size > MAX_STORED_RECEIPT_BYTES) {
      return RustyKioskInstallReceiptRead.Damaged("The existing install receipt has an invalid size.")
    }
    return runCatching {
      val json = JSONObject(bytes.toString(StandardCharsets.UTF_8))
      require(json.keys().asSequence().toSet() == STORED_FIELDS) {
        "The existing install receipt has an expanded or incomplete shape."
      }
      require(json.get("schema") == RustyKioskInstallReceipt.STORE_SCHEMA) {
        "The existing install receipt schema is invalid."
      }
      val requestId = json.get("request_id")
      require(requestId is String && requestId == expectedRequestId &&
        RustyKioskInstallStore.REQUEST_ID.matches(requestId)
      ) { "The existing install receipt request id is invalid." }
      val state = json.get("state")
      require(state is String && state in STATES) {
        "The existing install receipt state is invalid."
      }
      val completed = json.get("completed")
      require(completed is Boolean && completed == (state in TERMINAL_STATES)) {
        "The existing install receipt completion state is invalid."
      }
      val message = json.get("message")
      require(message is String && message.length <= RustyKioskInstallReceipt.MAX_MESSAGE_BYTES) {
        "The existing install receipt message is invalid."
      }
      val sessionId = strictNullableInt(json, "session_id")
      require(state == "needs-wearer-permission" || sessionId != null) {
        "The existing install receipt is missing its installer session id."
      }
      val packageName = strictNullableString(json, "package")
      val recordedAtMs = strictNonNegativeLong(json.get("recorded_at_ms"), "recorded_at_ms")
      val commitments = RustyKioskInstallCommitmentManifestPolicy.parse(
        json.get("commitments") as? JSONArray
          ?: throw IllegalArgumentException("The existing install commitments are invalid.")
      )
      val commitmentSha256 = json.get("commitment_sha256")
      require(commitmentSha256 is String &&
        RustyKioskInstallPartCommitmentPolicy.SHA256.matches(commitmentSha256) &&
        commitmentSha256 == RustyKioskInstallCommitmentManifestPolicy.canonicalSha256(commitments)
      ) { "The existing ordered install commitment digest is invalid." }
      RustyKioskInstallReceiptRead.Available(
        RustyKioskInstallReceipt(
          requestId,
          state,
          completed,
          message,
          sessionId,
          packageName,
          recordedAtMs,
          commitments,
          commitmentSha256,
        )
      )
    }.getOrElse { throwable ->
      RustyKioskInstallReceiptRead.Damaged(
        throwable.message ?: "The existing install receipt is unreadable."
      )
    }
  }

  private fun strictNullableInt(json: JSONObject, key: String): Int? {
    if (json.isNull(key)) return null
    val value = strictNonNegativeLong(json.get(key), key)
    require(value <= Int.MAX_VALUE) { "The existing install receipt $key is outside range." }
    return value.toInt()
  }

  private fun strictNullableString(json: JSONObject, key: String): String? {
    if (json.isNull(key)) return null
    val value = json.get(key)
    require(value is String && value.length <= MAX_PACKAGE_LENGTH) {
      "The existing install receipt $key is invalid."
    }
    return value
  }

  private fun strictNonNegativeLong(value: Any, field: String): Long {
    require(value is Number && NON_NEGATIVE_INTEGER.matches(value.toString())) {
      "The existing install receipt $field is invalid."
    }
    return value.toString().toLongOrNull()
      ?: throw IllegalArgumentException("The existing install receipt $field is outside range.")
  }

  const val MAX_STORED_RECEIPT_BYTES = 32 * 1024
  private const val MAX_PACKAGE_LENGTH = 255
  private val NON_NEGATIVE_INTEGER = Regex("0|[1-9][0-9]*")
  private val STORED_FIELDS = setOf(
    "schema",
    "request_id",
    "state",
    "completed",
    "message",
    "session_id",
    "package",
    "recorded_at_ms",
    "commitments",
    "commitment_sha256",
  )
  private val STATES = setOf(
    "needs-wearer-permission",
    "staging-session",
    "pending-android",
    "pending-wearer-confirmation",
    "installed",
    RustyKioskInstallCleanupPolicy.STATE_FAILED,
    RustyKioskInstallCleanupPolicy.STATE_CLEANUP_REQUIRED,
  )
  private val TERMINAL_STATES = setOf("installed", RustyKioskInstallCleanupPolicy.STATE_FAILED)
}

internal data class RustyKioskInstallCleanupDecision(
  val state: String,
  val completed: Boolean,
  val cleanupConfirmed: Boolean,
)

internal object RustyKioskInstallCleanupPolicy {
  fun afterAbandonAttempt(
    abandonReturned: Boolean,
    sessionStillPresent: Boolean?,
  ): RustyKioskInstallCleanupDecision =
    if (abandonReturned || sessionStillPresent == false) {
      RustyKioskInstallCleanupDecision(STATE_FAILED, completed = true, cleanupConfirmed = true)
    } else {
      RustyKioskInstallCleanupDecision(
        STATE_CLEANUP_REQUIRED,
        completed = false,
        cleanupConfirmed = false,
      )
    }

  const val STATE_FAILED = "failed"
  const val STATE_CLEANUP_REQUIRED = "cleanup-required"
}

internal class RustyKioskInstallStore(context: Context) {
  private val directory = File(context.applicationContext.filesDir, "operator-installs")

  fun record(receipt: RustyKioskInstallReceipt) =
    synchronized(RustyKioskInstallProcessLock.monitor) {
      directory.mkdirs()
      val destination = receiptFile(receipt.requestId)
      val temporary = temporaryReceiptFile(receipt.requestId)
      val encoded = receipt.toStoredJson().toString().toByteArray(StandardCharsets.UTF_8)
      check(
        RustyKioskInstallReceiptCodec.inspect(encoded, receipt.requestId) is
          RustyKioskInstallReceiptRead.Available
      ) { "Refusing to persist an invalid install receipt." }
      temporary.writeBytes(encoded)
      check(temporary.renameTo(destination) || runCatching {
        destination.writeBytes(encoded)
        temporary.delete()
        true
      }.getOrDefault(false)) { "Could not record the install receipt." }
    }

  fun inspect(requestId: String): RustyKioskInstallReceiptRead =
    synchronized(RustyKioskInstallProcessLock.monitor) {
      if (!REQUEST_ID.matches(requestId)) {
        return@synchronized RustyKioskInstallReceiptRead.Damaged(
          "The install receipt request id is invalid."
        )
      }
      val file = receiptFile(requestId)
      if (temporaryReceiptFile(requestId).exists()) {
        return@synchronized RustyKioskInstallReceiptRead.Damaged(
          "An interrupted temporary install receipt exists."
        )
      }
      if (!file.exists()) return@synchronized RustyKioskInstallReceiptRead.Absent
      if (!file.isFile || file.length() !in 1..RustyKioskInstallReceiptCodec.MAX_STORED_RECEIPT_BYTES) {
        return@synchronized RustyKioskInstallReceiptRead.Damaged(
          "The existing install receipt is not a bounded regular file."
        )
      }
      val bytes = runCatching { file.readBytes() }.getOrElse { throwable ->
        return@synchronized RustyKioskInstallReceiptRead.Damaged(
          throwable.message ?: "The existing install receipt could not be read."
        )
      }
      RustyKioskInstallReceiptCodec.inspect(bytes, requestId)
    }

  private fun receiptFile(requestId: String) = File(directory, "$requestId.json")
  private fun temporaryReceiptFile(requestId: String) = File(directory, "$requestId.json.tmp")

  companion object {
    val REQUEST_ID = Regex("[A-Za-z0-9_-]{8,64}")
  }
}

internal data class RustyKioskCommittedInstallPart(
  val file: File,
  val commitment: RustyKioskInstallPartCommitment,
)

/** Serializes install request-id admission, immutable copying, and receipt transitions. */
internal object RustyKioskInstallProcessLock {
  val monitor = Any()
}

internal class RustyKioskInstaller(private val context: Context) {
  private val appContext = context.applicationContext
  private val installer = appContext.packageManager.packageInstaller
  private val store = RustyKioskInstallStore(appContext)

  fun install(
    requestId: String,
    apkParts: List<RustyKioskCommittedInstallPart>,
  ): RustyKioskInstallReceipt = synchronized(RustyKioskInstallProcessLock.monitor) {
    installLocked(requestId, apkParts)
  }

  /**
   * A repeated install body may use a fresh authenticated transport request id to retry only an
   * unresolved PackageInstaller cleanup. It never reuses that install id for a second install.
   */
  fun retryCleanupIfRequired(
    requestId: String,
    incomingCommitments: List<RustyKioskInstallPartCommitment>,
  ): RustyKioskInstallReceipt? =
    synchronized(RustyKioskInstallProcessLock.monitor) {
      val existing = when (val read = store.inspect(requestId)) {
        RustyKioskInstallReceiptRead.Absent -> return@synchronized null
        is RustyKioskInstallReceiptRead.Available -> read.receipt
        is RustyKioskInstallReceiptRead.Damaged ->
          throw IllegalStateException("Existing install state is damaged; cleanup fails closed: ${read.message}")
      }
      if (existing.state != RustyKioskInstallCleanupPolicy.STATE_CLEANUP_REQUIRED) {
        return@synchronized null
      }
      require(
        RustyKioskInstallCommitmentManifestPolicy.matchesBoundManifest(
          existing.commitments,
          existing.commitmentSha256,
          incomingCommitments,
        )
      ) { "Cleanup retry commitments do not match the original ordered install body." }
      val sessionId = existing.sessionId ?: return@synchronized existing
      resolveFailedSessionCleanup(
        requestId,
        sessionId,
        "Retrying cleanup for the failed Android installer session.",
        existing.commitments,
      )
    }

  private fun installLocked(
    requestId: String,
    apkParts: List<RustyKioskCommittedInstallPart>,
  ): RustyKioskInstallReceipt {
    require(RustyKioskInstallStore.REQUEST_ID.matches(requestId)) { "A valid install request id is required." }
    when (val existing = store.inspect(requestId)) {
      RustyKioskInstallReceiptRead.Absent -> Unit
      is RustyKioskInstallReceiptRead.Available ->
        throw IllegalArgumentException("That install request id was already used.")
      is RustyKioskInstallReceiptRead.Damaged ->
        throw IllegalStateException("Existing install state is damaged; admission fails closed: ${existing.message}")
    }
    require(apkParts.isNotEmpty()) { "At least one APK is required." }
    require(apkParts.size <= MAX_APK_PARTS) { "The APK set exceeds the fixed part limit." }
    require(apkParts.map { it.commitment.name }.distinct().size == apkParts.size) {
      "Install part names must be unique."
    }
    apkParts.forEach { part ->
      val file = part.file
      val commitment = part.commitment
      require(file.name == commitment.name) { "The staged APK name changed before admission." }
      require(file.isFile && file.extension.equals("apk", ignoreCase = true)) {
        "Every install part must be a staged APK file."
      }
      require(commitment.bytes in 1..MAX_APK_BYTES && file.length() == commitment.bytes) {
        "A staged APK does not match its committed byte count."
      }
      require(RustyKioskInstallPartCommitmentPolicy.SHA256.matches(commitment.sha256)) {
        "A staged APK does not have a valid lowercase SHA-256 commitment."
      }
    }
    if (!appContext.packageManager.canRequestPackageInstalls()) {
      return receipt(
          requestId,
          "needs-wearer-permission",
          false,
          "Allow Rusty Kiosk to install unknown apps in the headset before retrying.",
          commitments = apkParts.map { it.commitment },
        )
        .also(store::record)
    }

    val params =
      PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
      }
    val sessionId = installer.createSession(params)
    val pending =
      receipt(
        requestId,
        "staging-session",
        false,
        "Copying staged APK parts into Android's package installer.",
        sessionId,
        commitments = apkParts.map { it.commitment },
      )
    try {
      store.record(pending)
      installer.openSession(sessionId).use { session ->
        apkParts.forEachIndexed { index, part ->
          val sessionName = "%03d-%s".format(index, part.commitment.name)
          part.file.inputStream().use { input ->
            session.openWrite(sessionName, 0, part.commitment.bytes).use { output ->
              RustyKioskInstallPartCommitmentPolicy.copyVerified(input, output, part.commitment)
              session.fsync(output)
            }
          }
        }
        val callback =
          PendingIntent.getBroadcast(
            appContext,
            sessionId,
            Intent(appContext, RustyKioskInstallReceiver::class.java)
              .setAction(RustyKioskInstallReceiver.ACTION_INSTALL_STATUS)
              .putExtra(RustyKioskInstallReceiver.EXTRA_REQUEST_ID, requestId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
          )
        store.record(
          receipt(
            requestId,
            "pending-android",
            false,
            "Android accepted the verified immutable-byte session; wearer confirmation is pending.",
            sessionId,
            commitments = apkParts.map { it.commitment },
          )
        )
        session.commit(callback.intentSender)
      }
    } catch (throwable: Throwable) {
      return resolveFailedSessionCleanup(
        requestId,
        sessionId,
        throwable.message ?: "The verified Android installer staging operation failed.",
        apkParts.map { it.commitment },
      )
    }
    return when (val read = store.inspect(requestId)) {
      is RustyKioskInstallReceiptRead.Available -> read.receipt
      RustyKioskInstallReceiptRead.Absent ->
        throw IllegalStateException("The committed install receipt disappeared.")
      is RustyKioskInstallReceiptRead.Damaged ->
        throw IllegalStateException("The committed install receipt is damaged: ${read.message}")
    }
  }

  private fun resolveFailedSessionCleanup(
    requestId: String,
    sessionId: Int,
    failureMessage: String,
    commitments: List<RustyKioskInstallPartCommitment>,
  ): RustyKioskInstallReceipt {
    val abandon = runCatching { installer.abandonSession(sessionId) }
    val sessionStillPresent = if (abandon.isSuccess) {
      false
    } else {
      runCatching { installer.mySessions.any { it.sessionId == sessionId } }.getOrNull()
    }
    val decision = RustyKioskInstallCleanupPolicy.afterAbandonAttempt(
      abandonReturned = abandon.isSuccess,
      sessionStillPresent = sessionStillPresent,
    )
    val message = if (decision.cleanupConfirmed) {
      "Android confirmed the failed installer session is absent; use a new install request id to retry. Failure: ${failureMessage.take(96)}"
    } else {
      "Android did not confirm session cleanup; repeat the same install body with a fresh authenticated transport request id. Failure: ${failureMessage.take(80)}"
    }
    return receipt(
        requestId,
        decision.state,
        decision.completed,
        message,
        sessionId,
        commitments = commitments,
      )
      .also(store::record)
  }

  private fun receipt(
    requestId: String,
    state: String,
    completed: Boolean,
    message: String,
    sessionId: Int? = null,
    packageName: String? = null,
    commitments: List<RustyKioskInstallPartCommitment>,
  ) = RustyKioskInstallReceipt(
    requestId,
    state,
    completed,
    message,
    sessionId,
    packageName,
    System.currentTimeMillis(),
    commitments,
  )

  companion object {
    const val MAX_APK_PARTS = 32
    const val MAX_APK_BYTES = 2L * 1024L * 1024L * 1024L
  }
}

class RustyKioskInstallReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    if (intent.action != ACTION_INSTALL_STATUS) return
    val requestId = intent.getStringExtra(EXTRA_REQUEST_ID)
      ?.takeIf(RustyKioskInstallStore.REQUEST_ID::matches)
      ?: return
    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
    val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1).takeIf { it >= 0 }
    val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
    val platformMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
    val store = RustyKioskInstallStore(context)
    val prior = when (val read = store.inspect(requestId)) {
      is RustyKioskInstallReceiptRead.Available -> read.receipt
      RustyKioskInstallReceiptRead.Absent -> return
      is RustyKioskInstallReceiptRead.Damaged -> return
    }
    if (sessionId != null && prior.sessionId != null && sessionId != prior.sessionId) return
    val effectiveSessionId = sessionId ?: prior.sessionId
    val receipt =
      when (status) {
        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
          @Suppress("DEPRECATION")
          val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
          val launched =
            confirmation != null && runCatching {
              context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
          prior.copy(
            state = "pending-wearer-confirmation",
            completed = false,
            message = if (launched) {
              "Confirm or cancel the Android installation in the headset."
            } else {
              "Open Rusty Kiosk and retry so Android can show the wearer confirmation."
            },
            sessionId = effectiveSessionId,
            packageName = packageName,
            recordedAtMs = System.currentTimeMillis(),
          )
        }
        PackageInstaller.STATUS_SUCCESS ->
          prior.copy(
            state = "installed",
            completed = true,
            message = "Android confirmed that the APK package was installed.",
            sessionId = effectiveSessionId,
            packageName = packageName,
            recordedAtMs = System.currentTimeMillis(),
          )
        else ->
          prior.copy(
            state = "failed",
            completed = true,
            message = (platformMessage ?: "Android rejected the APK installation.").take(240),
            sessionId = effectiveSessionId,
            packageName = packageName,
            recordedAtMs = System.currentTimeMillis(),
          )
      }
    store.record(receipt)
  }

  companion object {
    const val ACTION_INSTALL_STATUS =
      "io.github.mesmerprism.rustykiosk.action.LOCAL_INSTALL_STATUS"
    const val EXTRA_REQUEST_ID = "rusty_kiosk_install_request_id"
  }
}
