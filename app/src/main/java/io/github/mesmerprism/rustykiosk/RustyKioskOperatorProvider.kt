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
      RustyKioskOperatorContract.METHOD_RESULT -> result(arg)
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
    val json = RustyKioskCliStore(providerContext).readResult(validRequestId)
      ?: return Bundle().apply {
        putBoolean(RustyKioskOperatorContract.RESULT_ACCEPTED, true)
        putBoolean(RustyKioskOperatorContract.RESULT_COMPLETED, false)
        putString(RustyKioskOperatorContract.RESULT_REQUEST_ID, validRequestId)
        putString(RustyKioskOperatorContract.RESULT_MESSAGE, "Operator result is still pending.")
      }
    return Bundle().apply {
      putBoolean(RustyKioskOperatorContract.RESULT_ACCEPTED, true)
      putBoolean(RustyKioskOperatorContract.RESULT_COMPLETED, true)
      putString(RustyKioskOperatorContract.RESULT_REQUEST_ID, validRequestId)
      putString(
        RustyKioskOperatorContract.RESULT_BASE64,
        Base64.getEncoder().encodeToString(json.toByteArray(StandardCharsets.UTF_8)),
      )
      putString(RustyKioskOperatorContract.RESULT_MESSAGE, "Matching operator result is ready.")
    }
  }

  private fun contractResult(): Bundle =
    Bundle().apply {
      putBoolean(RustyKioskOperatorContract.RESULT_ACCEPTED, true)
      putBoolean(RustyKioskOperatorContract.RESULT_COMPLETED, true)
      putString(RustyKioskOperatorContract.RESULT_SCHEMA, RustyKioskOperatorContract.SCHEMA)
      putString(RustyKioskOperatorContract.RESULT_PACKAGE, RustyKioskOperatorContract.PACKAGE_NAME)
      putString(RustyKioskOperatorContract.RESULT_ACTIVITY, RustyKioskOperatorContract.ACTIVITY_NAME)
      putString(RustyKioskOperatorContract.RESULT_TAG_FILE, RustyKioskOperatorContract.TAG_FILE_PATH)
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
  const val SCHEMA = "rusty.kiosk.host_operator.v2"
  val AUTHORITY: String = BuildConfig.OPERATOR_AUTHORITY
  val PACKAGE_NAME: String = BuildConfig.APPLICATION_ID
  const val ACTIVITY_NAME = ".RustyKioskActivity"
  val TAG_FILE_PATH: String =
    "/sdcard/Android/data/$PACKAGE_NAME/files/tags/app-tags.v1.json"
  const val METHOD_CONTRACT = "contract"
  const val METHOD_INVOKE = "invoke"
  const val METHOD_RESULT = "result"
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
  const val RESULT_ACCEPTED = "accepted"
  const val RESULT_COMPLETED = "completed"
  const val RESULT_REQUEST_ID = "request_id"
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
