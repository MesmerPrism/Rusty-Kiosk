package io.github.mesmerprism.rustykiosk

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * A release-safe, ADB-shell-only adapter over Rusty Kiosk's typed operator protocol.
 *
 * The manifest requires the caller-held android.permission.DUMP permission. The provider never
 * accepts shell commands, Android components, intent actions, file paths, or setup operations
 * outside [RustyKioskCliCommand]. It only admits a bounded request and exposes the matching
 * app-private result. The host still launches [RustyKioskActivity] explicitly so Android's normal
 * Activity lifecycle owns visible execution of the request.
 */
class RustyKioskOperatorProvider : ContentProvider() {
  override fun onCreate(): Boolean = context != null

  override fun call(method: String, arg: String?, extras: Bundle?): Bundle =
    when (method) {
      RustyKioskOperatorContract.METHOD_CONTRACT -> contractResult()
      RustyKioskOperatorContract.METHOD_INVOKE -> invoke(arg, extras)
      RustyKioskOperatorContract.METHOD_REQUEST_STATUS -> requestStatus(arg)
      RustyKioskOperatorContract.METHOD_RESULT -> result(arg)
      RustyKioskOperatorContract.METHOD_CANCEL -> cancel(arg)
      RustyKioskOperatorContract.METHOD_DIRECT_STATUS -> directStatus()
      RustyKioskOperatorContract.METHOD_DIRECT_ENABLE -> directEnable(arg)
      RustyKioskOperatorContract.METHOD_DIRECT_DISABLE -> directDisable(arg, extras)
      RustyKioskOperatorContract.METHOD_TAG_READ -> readTagChunk(extras)
      RustyKioskOperatorContract.METHOD_TAG_WRITE_BEGIN -> beginTagWrite(extras)
      RustyKioskOperatorContract.METHOD_TAG_WRITE_CHUNK -> writeTagChunk(extras)
      RustyKioskOperatorContract.METHOD_TAG_WRITE_COMMIT -> commitTagWrite(extras)
      else -> failure("Unknown Rusty Kiosk operator method.")
    }

  private fun readTagChunk(extras: Bundle?): Bundle {
    val providerContext = context ?: return failure("Rusty Kiosk operator context is unavailable.")
    val offset = extras?.getInt(RustyKioskOperatorContract.EXTRA_OFFSET, 0) ?: 0
    val store = TagFileStore(providerContext)
    store.ensureExists()
    val bytes = store.tagFile.readBytes()
    if (bytes.size > TagFileCodec.MAX_BYTES || offset !in 0..bytes.size) {
      return failure("The requested tag-file chunk is outside the bounded file.")
    }
    val end = minOf(bytes.size, offset + RustyKioskOperatorContract.TAG_CHUNK_BYTES)
    return Bundle().apply {
      putBoolean(RustyKioskOperatorContract.RESULT_ACCEPTED, true)
      putBoolean(RustyKioskOperatorContract.RESULT_COMPLETED, true)
      putInt(RustyKioskOperatorContract.RESULT_TOTAL_BYTES, bytes.size)
      putInt(RustyKioskOperatorContract.RESULT_OFFSET, offset)
      putString(RustyKioskOperatorContract.RESULT_SHA256, sha256(bytes))
      putString(
        RustyKioskOperatorContract.RESULT_DATA_BASE64,
        Base64.getEncoder().encodeToString(bytes.copyOfRange(offset, end)),
      )
      putString(RustyKioskOperatorContract.RESULT_MESSAGE, "Bounded tag-file chunk ready.")
    }
  }

  private fun beginTagWrite(extras: Bundle?): Bundle {
    val providerContext = context ?: return failure("Rusty Kiosk operator context is unavailable.")
    val transferId = validTransferId(extras) ?: return failure("A valid tag transfer id is required.")
    val totalBytes = extras?.getInt(RustyKioskOperatorContract.EXTRA_TOTAL_BYTES, -1) ?: -1
    val expectedSha = extras?.getString(RustyKioskOperatorContract.EXTRA_SHA256)?.lowercase()
    if (totalBytes !in 1..TagFileCodec.MAX_BYTES ||
      expectedSha?.matches(RustyKioskOperatorContract.SHA256) != true
    ) {
      return failure("Bounded tag byte count and SHA-256 are required.")
    }
    val temp = transferFile(providerContext, transferId)
    temp.parentFile?.mkdirs()
    if (temp.exists() && !temp.delete()) return failure("Could not replace a stale tag transfer.")
    temp.createNewFile()
    return transferResult(transferId, 0, "Tag transfer admitted.")
  }

  private fun writeTagChunk(extras: Bundle?): Bundle {
    val providerContext = context ?: return failure("Rusty Kiosk operator context is unavailable.")
    val transferId = validTransferId(extras) ?: return failure("A valid tag transfer id is required.")
    val offset = extras?.getInt(RustyKioskOperatorContract.EXTRA_OFFSET, -1) ?: -1
    val bytes =
      runCatching {
          Base64.getDecoder().decode(
            extras?.getString(RustyKioskOperatorContract.EXTRA_DATA_BASE64) ?: "",
          )
        }
        .getOrElse { return failure("The tag chunk is not valid Base64.") }
    if (bytes.isEmpty() || bytes.size > RustyKioskOperatorContract.TAG_CHUNK_BYTES) {
      return failure("The tag chunk is empty or exceeds the fixed chunk limit.")
    }
    val temp = transferFile(providerContext, transferId)
    if (!temp.isFile || temp.length() != offset.toLong() || offset + bytes.size > TagFileCodec.MAX_BYTES) {
      return failure("The tag chunk is out of order or exceeds the bounded transfer.")
    }
    temp.appendBytes(bytes)
    return transferResult(transferId, offset + bytes.size, "Tag chunk accepted.")
  }

  private fun commitTagWrite(extras: Bundle?): Bundle {
    val providerContext = context ?: return failure("Rusty Kiosk operator context is unavailable.")
    val transferId = validTransferId(extras) ?: return failure("A valid tag transfer id is required.")
    val totalBytes = extras?.getInt(RustyKioskOperatorContract.EXTRA_TOTAL_BYTES, -1) ?: -1
    val expectedSha = extras?.getString(RustyKioskOperatorContract.EXTRA_SHA256)?.lowercase()
    val temp = transferFile(providerContext, transferId)
    val bytes = if (temp.isFile && temp.length() <= TagFileCodec.MAX_BYTES) temp.readBytes() else byteArrayOf()
    if (bytes.size != totalBytes ||
      expectedSha?.matches(RustyKioskOperatorContract.SHA256) != true ||
      sha256(bytes) != expectedSha
    ) {
      temp.delete()
      return failure("Tag transfer length or SHA-256 did not match.")
    }
    val outcome = runCatching {
      TagFileStore(providerContext).replaceJson(bytes.toString(StandardCharsets.UTF_8))
    }
    temp.delete()
    outcome.getOrElse { throwable ->
      return failure(throwable.message ?: "The tag file did not pass schema validation.")
    }
    return transferResult(transferId, bytes.size, "Tag file validated and atomically activated.")
  }

  private fun validTransferId(extras: Bundle?): String? {
    val transferId = extras?.getString(RustyKioskOperatorContract.EXTRA_TRANSFER_ID)
    return RustyKioskCliProtocol.parse(transferId, RustyKioskCliCommand.STATUS.wireName, null)
      .getOrNull()
      ?.requestId
  }

  private fun transferFile(providerContext: android.content.Context, transferId: String): File =
    File(File(providerContext.cacheDir, "operator-tags"), "$transferId.json.part")

  private fun transferResult(transferId: String, offset: Int, message: String): Bundle =
    Bundle().apply {
      putBoolean(RustyKioskOperatorContract.RESULT_ACCEPTED, true)
      putBoolean(RustyKioskOperatorContract.RESULT_COMPLETED, true)
      putString(RustyKioskOperatorContract.RESULT_REQUEST_ID, transferId)
      putInt(RustyKioskOperatorContract.RESULT_OFFSET, offset)
      putString(RustyKioskOperatorContract.RESULT_MESSAGE, message)
    }

  private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte) }

  private fun invoke(command: String?, extras: Bundle?): Bundle {
    val providerContext = context ?: return failure("Rusty Kiosk operator context is unavailable.")
    val requestId = extras?.getString(RustyKioskOperatorContract.EXTRA_REQUEST_ID)
    val decodedValue =
      runCatching {
          extras?.getString(RustyKioskOperatorContract.EXTRA_VALUE_BASE64)?.let { encoded ->
            Base64.getDecoder().decode(encoded).toString(StandardCharsets.UTF_8)
          }
        }
        .getOrElse { return failure("The operator value is not valid UTF-8 Base64.") }
    val request =
      RustyKioskCliProtocol.parse(requestId, command, decodedValue)
        .getOrElse { throwable ->
          return failure(throwable.message ?: "The operator request is invalid.")
        }
    if (!RustyKioskCliStore(providerContext).enqueue(request)) {
      return failure("Another Rusty Kiosk operator request is pending or this request was already used.")
    }
    return Bundle().apply {
      putBoolean(RustyKioskOperatorContract.RESULT_ACCEPTED, true)
      putBoolean(RustyKioskOperatorContract.RESULT_COMPLETED, false)
      putString(RustyKioskOperatorContract.RESULT_REQUEST_ID, request.requestId)
      putString(RustyKioskOperatorContract.RESULT_MESSAGE, "Typed operator request admitted.")
    }
  }

  private fun result(requestId: String?): Bundle {
    val providerContext = context ?: return failure("Rusty Kiosk operator context is unavailable.")
    val validRequestId =
      RustyKioskCliProtocol.parse(requestId, RustyKioskCliCommand.STATUS.wireName, null)
        .getOrNull()
        ?.requestId
        ?: return failure("A valid operator request id is required.")
    val store = RustyKioskCliStore(providerContext)
    val json = store.readResult(validRequestId)
      ?: return requestStatusBundle(store.status(validRequestId))
    val parsed = org.json.JSONObject(json)
    return Bundle().apply {
      putBoolean(RustyKioskOperatorContract.RESULT_ACCEPTED, true)
      putBoolean(RustyKioskOperatorContract.RESULT_COMPLETED, parsed.optBoolean("completed"))
      putString(RustyKioskOperatorContract.RESULT_REQUEST_ID, validRequestId)
      putString(
        RustyKioskOperatorContract.RESULT_OPERATION_STATE,
        parsed.optString("operation_state", OperatorRequestState.UNKNOWN.wireName),
      )
      putString(
        RustyKioskOperatorContract.RESULT_PROVIDER_EPOCH,
        parsed.optString("provider_epoch", store.providerEpoch()),
      )
      putString(
        RustyKioskOperatorContract.RESULT_BASE64,
        Base64.getEncoder().encodeToString(json.toByteArray(StandardCharsets.UTF_8)),
      )
      putString(RustyKioskOperatorContract.RESULT_MESSAGE, "Matching operator result is ready.")
    }
  }

  private fun requestStatus(requestId: String?): Bundle {
    val providerContext = context ?: return failure("Rusty Kiosk operator context is unavailable.")
    val validRequestId = RustyKioskCliProtocol.validRequestId(requestId)
      ?: return failure("A valid operator request id is required.")
    return requestStatusBundle(RustyKioskCliStore(providerContext).status(validRequestId))
  }

  private fun cancel(requestId: String?): Bundle {
    val providerContext = context ?: return failure("Rusty Kiosk operator context is unavailable.")
    val validRequestId = RustyKioskCliProtocol.validRequestId(requestId)
      ?: return failure("A valid operator request id is required.")
    val before = RustyKioskCliStore(providerContext).status(validRequestId)
    if (before.state != OperatorRequestState.PENDING) {
      return requestStatusBundle(before).apply {
        putBoolean(RustyKioskOperatorContract.RESULT_ACCEPTED, false)
        putString(
          RustyKioskOperatorContract.RESULT_MESSAGE,
          "Only the exact queued request can be cancelled; applied or terminal state was preserved.",
        )
      }
    }
    val after = RustyKioskCliStore(providerContext).cancel(validRequestId)
    return requestStatusBundle(after).apply {
      if (after.state != OperatorRequestState.CANCELLED) {
        putBoolean(RustyKioskOperatorContract.RESULT_ACCEPTED, false)
        putString(
          RustyKioskOperatorContract.RESULT_MESSAGE,
          "The request was claimed or became terminal before cancellation; state was preserved.",
        )
      }
    }
  }

  private fun requestStatusBundle(status: OperatorRequestStatus): Bundle = Bundle().apply {
    putBoolean(RustyKioskOperatorContract.RESULT_ACCEPTED, status.state != OperatorRequestState.UNKNOWN)
    putBoolean(RustyKioskOperatorContract.RESULT_COMPLETED, status.completed)
    putString(RustyKioskOperatorContract.RESULT_REQUEST_ID, status.requestId)
    putString(RustyKioskOperatorContract.RESULT_PROVIDER_EPOCH, status.providerEpoch)
    putString(RustyKioskOperatorContract.RESULT_OPERATION_STATE, status.state.wireName)
    status.command?.let { putString(RustyKioskOperatorContract.RESULT_COMMAND, it) }
    status.enqueuedAtMs?.let { putLong(RustyKioskOperatorContract.RESULT_ENQUEUED_AT_MS, it) }
    status.expiresAtMs?.let { putLong(RustyKioskOperatorContract.RESULT_EXPIRES_AT_MS, it) }
    putString(RustyKioskOperatorContract.RESULT_MESSAGE, status.message.take(240))
  }

  private fun directStatus(): Bundle {
    val providerContext = context ?: return failure("Rusty Kiosk operator context is unavailable.")
    return directStatusBundle(OperatorBridgeSettings(providerContext).snapshot())
  }

  private fun directEnable(operationIdArg: String?): Bundle {
    val providerContext = context ?: return failure("Rusty Kiosk operator context is unavailable.")
    val operationId = RustyKioskCliProtocol.validRequestId(operationIdArg)
      ?: return failure("A valid direct-link operation id is required in the provider arg.")
    val settings = OperatorBridgeSettings(providerContext)
    val enabledByRequest = runCatching { settings.setEnabled(true) }
      .getOrElse { return failure("The direct link could not be enabled.") }
    val snapshot = settings.snapshot()
    val session = runCatching {
      OperatorBridgeSessionStore(providerContext).issue(operationId, snapshot.bridgeGeneration)
    }.getOrElse { throwable ->
      if (enabledByRequest) settings.setEnabled(false)
      return failure(throwable.message ?: "An ephemeral direct-link session could not be issued.")
    }
    return directStatusBundle(snapshot).apply {
      putBoolean(RustyKioskOperatorContract.RESULT_ACCEPTED, true)
      putBoolean(RustyKioskOperatorContract.RESULT_COMPLETED, snapshot.running)
      putString(RustyKioskOperatorContract.RESULT_OPERATION_ID, operationId)
      putString(RustyKioskOperatorContract.RESULT_SESSION_ID, session.sessionId)
      putString(
        RustyKioskOperatorContract.RESULT_SESSION_SECRET_BASE64,
        session.sessionSecretBase64,
      )
      putLong(RustyKioskOperatorContract.RESULT_EXPIRES_AT_MS, session.expiresAtMs)
      putString(RustyKioskOperatorContract.RESULT_SESSION_CAPABILITY, OperatorBridgeSessionStore.CAPABILITY)
      putBoolean(RustyKioskOperatorContract.RESULT_ENABLED_BY_REQUEST, enabledByRequest)
      putString(
        RustyKioskOperatorContract.RESULT_MESSAGE,
        "A bounded direct-link session was issued for this bridge generation.",
      )
    }
  }

  private fun directDisable(operationIdArg: String?, extras: Bundle?): Bundle {
    val providerContext = context ?: return failure("Rusty Kiosk operator context is unavailable.")
    val operationId = RustyKioskCliProtocol.validRequestId(operationIdArg)
      ?: return failure("A valid originating operation id is required in the provider arg.")
    val sessionId = extras?.getString(RustyKioskOperatorContract.EXTRA_SESSION_ID).orEmpty()
    val expectedGeneration = extras?.getLong(
      RustyKioskOperatorContract.EXTRA_EXPECTED_BRIDGE_GENERATION,
      -1L,
    ) ?: -1L
    val settings = OperatorBridgeSettings(providerContext)
    val before = settings.snapshot()
    if (expectedGeneration <= 0L || before.bridgeGeneration != expectedGeneration) {
      return failure("The direct-link bridge generation changed; no state was changed.")
    }
    if (!OperatorBridgeSessionStore(providerContext).owns(
        operationId,
        sessionId,
        expectedGeneration,
      )
    ) {
      return failure("The originating operation/session does not own this bridge generation.")
    }
    settings.setEnabled(false)
    return directStatusBundle(settings.snapshot()).apply {
      putBoolean(RustyKioskOperatorContract.RESULT_ACCEPTED, true)
      putString(RustyKioskOperatorContract.RESULT_OPERATION_ID, operationId)
      putString(
        RustyKioskOperatorContract.RESULT_MESSAGE,
        "Direct-link disable was dispatched for the owned generation; status readback determines completion.",
      )
    }
  }

  private fun directStatusBundle(snapshot: OperatorBridgeSnapshot): Bundle = Bundle().apply {
    putBoolean(RustyKioskOperatorContract.RESULT_ACCEPTED, true)
    putBoolean(RustyKioskOperatorContract.RESULT_COMPLETED, snapshot.running == snapshot.enabled)
    putString(RustyKioskOperatorContract.RESULT_SCHEMA, RustyKioskOperatorContract.DIRECT_BOOTSTRAP_SCHEMA)
    putString(RustyKioskOperatorContract.RESULT_PRODUCT_CHANNEL, BuildConfig.PRODUCT_CHANNEL)
    putString(RustyKioskOperatorContract.RESULT_PACKAGE, BuildConfig.APPLICATION_ID)
    putBoolean(RustyKioskOperatorContract.RESULT_DIRECT_ENABLED, snapshot.enabled)
    putBoolean(RustyKioskOperatorContract.RESULT_DIRECT_RUNNING, snapshot.running)
    putString(RustyKioskOperatorContract.RESULT_DIRECT_ENDPOINT, snapshot.endpoint)
    putLong(RustyKioskOperatorContract.RESULT_BRIDGE_GENERATION, snapshot.bridgeGeneration)
    putInt(
      RustyKioskOperatorContract.RESULT_ACTIVE_SESSION_COUNT,
      OperatorBridgeSessionStore(requireNotNull(context)).activeSessionCount(snapshot.bridgeGeneration),
    )
    putString(
      RustyKioskOperatorContract.RESULT_OPERATION_STATE,
      if (snapshot.running == snapshot.enabled) OperatorRequestState.CONFIRMED.wireName
      else OperatorRequestState.PENDING.wireName,
    )
    putString(RustyKioskOperatorContract.RESULT_MESSAGE, "Direct-link status read without issuing a credential.")
  }

  private fun contractResult(): Bundle =
    Bundle().apply {
      putBoolean(RustyKioskOperatorContract.RESULT_ACCEPTED, true)
      putBoolean(RustyKioskOperatorContract.RESULT_COMPLETED, true)
      putString(RustyKioskOperatorContract.RESULT_SCHEMA, RustyKioskOperatorContract.SCHEMA)
      putString(RustyKioskOperatorContract.RESULT_PACKAGE, RustyKioskOperatorContract.PACKAGE_NAME)
      putString(RustyKioskOperatorContract.RESULT_ACTIVITY, RustyKioskOperatorContract.ACTIVITY_NAME)
      putString(RustyKioskOperatorContract.RESULT_TAG_FILE, RustyKioskOperatorContract.TAG_FILE_PATH)
      putString(RustyKioskOperatorContract.RESULT_PROVIDER_EPOCH, context?.let { RustyKioskCliStore(it).providerEpoch() })
      putString(RustyKioskOperatorContract.RESULT_PRODUCT_CHANNEL, BuildConfig.PRODUCT_CHANNEL)
      putString(
        RustyKioskOperatorContract.RESULT_DIRECT_BOOTSTRAP_SCHEMA,
        RustyKioskOperatorContract.DIRECT_BOOTSTRAP_SCHEMA,
      )
      putLong(RustyKioskOperatorContract.RESULT_SESSION_LIFETIME_MS, OperatorBridgeSessionStore.SESSION_LIFETIME_MS)
      putInt(RustyKioskOperatorContract.RESULT_MAX_CONCURRENT_SESSIONS, OperatorBridgeSessionStore.MAX_CONCURRENT_SESSIONS)
      putString(RustyKioskOperatorContract.RESULT_MESSAGE, "Rusty Kiosk host operator is available.")
    }

  private fun failure(message: String): Bundle =
    Bundle().apply {
      putBoolean(RustyKioskOperatorContract.RESULT_ACCEPTED, false)
      putBoolean(RustyKioskOperatorContract.RESULT_COMPLETED, true)
      putString(RustyKioskOperatorContract.RESULT_MESSAGE, message.take(240))
    }

  override fun getType(uri: Uri): String? = null

  override fun query(
    uri: Uri,
    projection: Array<out String>?,
    selection: String?,
    selectionArgs: Array<out String>?,
    sortOrder: String?,
  ): Cursor? = throw UnsupportedOperationException("Rusty Kiosk operator supports call() only.")

  override fun insert(uri: Uri, values: ContentValues?): Uri? =
    throw UnsupportedOperationException("Rusty Kiosk operator is not a data provider.")

  override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
    throw UnsupportedOperationException("Rusty Kiosk operator is not a data provider.")

  override fun update(
    uri: Uri,
    values: ContentValues?,
    selection: String?,
    selectionArgs: Array<out String>?,
  ): Int = throw UnsupportedOperationException("Rusty Kiosk operator is not a data provider.")
}

internal object RustyKioskOperatorContract {
  const val SCHEMA = "rusty.kiosk.host_operator.v3"
  const val DIRECT_BOOTSTRAP_SCHEMA = "rusty.kiosk.direct_usb_bootstrap.v1"
  val AUTHORITY: String = BuildConfig.OPERATOR_AUTHORITY
  val PACKAGE_NAME: String = BuildConfig.APPLICATION_ID
  const val ACTIVITY_NAME = ".RustyKioskActivity"
  val TAG_FILE_PATH: String =
    "/sdcard/Android/data/$PACKAGE_NAME/files/tags/app-tags.v1.json"
  const val METHOD_CONTRACT = "contract"
  const val METHOD_INVOKE = "invoke"
  const val METHOD_REQUEST_STATUS = "request-status"
  const val METHOD_RESULT = "result"
  const val METHOD_CANCEL = "cancel"
  const val METHOD_DIRECT_STATUS = "direct-status"
  const val METHOD_DIRECT_ENABLE = "direct-enable"
  const val METHOD_DIRECT_DISABLE = "direct-disable"
  const val METHOD_TAG_READ = "tag-read"
  const val METHOD_TAG_WRITE_BEGIN = "tag-write-begin"
  const val METHOD_TAG_WRITE_CHUNK = "tag-write-chunk"
  const val METHOD_TAG_WRITE_COMMIT = "tag-write-commit"
  const val EXTRA_REQUEST_ID = "request_id"
  const val EXTRA_VALUE_BASE64 = "value_base64"
  const val EXTRA_TRANSFER_ID = "transfer_id"
  const val EXTRA_OFFSET = "offset"
  const val EXTRA_TOTAL_BYTES = "total_bytes"
  const val EXTRA_SHA256 = "sha256"
  const val EXTRA_DATA_BASE64 = "data_base64"
  const val EXTRA_SESSION_ID = "session_id"
  const val EXTRA_EXPECTED_BRIDGE_GENERATION = "expected_bridge_generation"
  const val RESULT_ACCEPTED = "accepted"
  const val RESULT_COMPLETED = "completed"
  const val RESULT_REQUEST_ID = "request_id"
  const val RESULT_PROVIDER_EPOCH = "provider_epoch"
  const val RESULT_OPERATION_STATE = "operation_state"
  const val RESULT_COMMAND = "command"
  const val RESULT_ENQUEUED_AT_MS = "enqueued_at_ms"
  const val RESULT_EXPIRES_AT_MS = "expires_at_ms"
  const val RESULT_OPERATION_ID = "operation_id"
  const val RESULT_PRODUCT_CHANNEL = "product_channel"
  const val RESULT_DIRECT_BOOTSTRAP_SCHEMA = "direct_bootstrap_schema"
  const val RESULT_SESSION_ID = "session_id"
  const val RESULT_SESSION_SECRET_BASE64 = "session_secret_base64"
  const val RESULT_SESSION_CAPABILITY = "session_capability"
  const val RESULT_ENABLED_BY_REQUEST = "enabled_by_request"
  const val RESULT_DIRECT_ENABLED = "direct_enabled"
  const val RESULT_DIRECT_RUNNING = "direct_running"
  const val RESULT_DIRECT_ENDPOINT = "endpoint"
  const val RESULT_BRIDGE_GENERATION = "bridge_generation"
  const val RESULT_ACTIVE_SESSION_COUNT = "active_session_count"
  const val RESULT_SESSION_LIFETIME_MS = "session_lifetime_ms"
  const val RESULT_MAX_CONCURRENT_SESSIONS = "max_concurrent_sessions"
  const val RESULT_SCHEMA = "schema"
  const val RESULT_PACKAGE = "package"
  const val RESULT_ACTIVITY = "activity"
  const val RESULT_TAG_FILE = "tag_file"
  const val RESULT_BASE64 = "result_base64"
  const val RESULT_MESSAGE = "message"
  const val RESULT_OFFSET = "offset"
  const val RESULT_TOTAL_BYTES = "total_bytes"
  const val RESULT_SHA256 = "sha256"
  const val RESULT_DATA_BASE64 = "data_base64"
  const val TAG_CHUNK_BYTES = 6 * 1024
  val SHA256 = Regex("^[0-9a-f]{64}$")
}
