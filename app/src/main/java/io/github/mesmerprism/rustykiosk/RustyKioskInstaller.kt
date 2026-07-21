package io.github.mesmerprism.rustykiosk

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
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

  companion object {
    const val SCHEMA = "rusty.kiosk.local_install.v1"

    fun fromJson(json: JSONObject): RustyKioskInstallReceipt =
      RustyKioskInstallReceipt(
        requestId = json.getString("request_id"),
        state = json.getString("state"),
        completed = json.getBoolean("completed"),
        message = json.getString("message"),
        sessionId = json.optInt("session_id").takeIf { !json.isNull("session_id") },
        packageName = json.optString("package").takeIf { !json.isNull("package") },
        recordedAtMs = json.getLong("recorded_at_ms"),
      )
  }
}

internal class RustyKioskInstallStore(context: Context) {
  private val directory = File(context.applicationContext.filesDir, "operator-installs")

  fun record(receipt: RustyKioskInstallReceipt) {
    directory.mkdirs()
    val destination = receiptFile(receipt.requestId)
    val temporary = File(directory, "${receipt.requestId}.json.tmp")
    temporary.writeText(receipt.toJson().toString(), StandardCharsets.UTF_8)
    check(temporary.renameTo(destination) || runCatching {
      destination.writeText(receipt.toJson().toString(), StandardCharsets.UTF_8)
      temporary.delete()
      true
    }.getOrDefault(false)) { "Could not record the install receipt." }
  }

  fun read(requestId: String): RustyKioskInstallReceipt? {
    if (!REQUEST_ID.matches(requestId)) return null
    val file = receiptFile(requestId)
    if (!file.isFile || file.length() > MAX_RECEIPT_BYTES) return null
    return runCatching {
        RustyKioskInstallReceipt.fromJson(JSONObject(file.readText(StandardCharsets.UTF_8)))
      }
      .getOrNull()
  }

  private fun receiptFile(requestId: String) = File(directory, "$requestId.json")

  companion object {
    val REQUEST_ID = Regex("[A-Za-z0-9_-]{8,64}")
    private const val MAX_RECEIPT_BYTES = 16 * 1024L
  }
}

internal class RustyKioskInstaller(private val context: Context) {
  private val appContext = context.applicationContext
  private val installer = appContext.packageManager.packageInstaller
  private val store = RustyKioskInstallStore(appContext)

  fun install(requestId: String, apkFiles: List<File>): RustyKioskInstallReceipt {
    require(RustyKioskInstallStore.REQUEST_ID.matches(requestId)) { "A valid install request id is required." }
    require(store.read(requestId) == null) { "That install request id was already used." }
    require(apkFiles.isNotEmpty()) { "At least one APK is required." }
    require(apkFiles.size <= MAX_APK_PARTS) { "The APK set exceeds the fixed part limit." }
    apkFiles.forEach { file ->
      require(file.isFile && file.extension.equals("apk", ignoreCase = true)) {
        "Every install part must be a staged APK file."
      }
      require(file.length() in 1..MAX_APK_BYTES) { "A staged APK is empty or too large." }
    }
    if (!appContext.packageManager.canRequestPackageInstalls()) {
      return receipt(
          requestId,
          "needs-wearer-permission",
          false,
          "Allow Rusty Kiosk to install unknown apps in the headset before retrying.",
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
      )
    store.record(pending)
    installer.openSession(sessionId).use { session ->
      apkFiles.forEachIndexed { index, file ->
        val sessionName = "%03d-%s".format(index, file.name)
        file.inputStream().use { input ->
          session.openWrite(sessionName, 0, file.length()).use { output ->
            input.copyTo(output)
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
          "Android accepted the session; wearer confirmation is pending.",
          sessionId,
        )
      )
      session.commit(callback.intentSender)
    }
    return requireNotNull(store.read(requestId))
  }

  private fun receipt(
    requestId: String,
    state: String,
    completed: Boolean,
    message: String,
    sessionId: Int? = null,
    packageName: String? = null,
  ) = RustyKioskInstallReceipt(
    requestId,
    state,
    completed,
    message,
    sessionId,
    packageName,
    System.currentTimeMillis(),
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
    val receipt =
      when (status) {
        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
          @Suppress("DEPRECATION")
          val confirmation = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
          val launched =
            confirmation != null && runCatching {
              context.startActivity(confirmation.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
          RustyKioskInstallReceipt(
            requestId,
            "pending-wearer-confirmation",
            false,
            if (launched) {
              "Confirm or cancel the Android installation in the headset."
            } else {
              "Open Rusty Kiosk and retry so Android can show the wearer confirmation."
            },
            sessionId,
            packageName,
            System.currentTimeMillis(),
          )
        }
        PackageInstaller.STATUS_SUCCESS ->
          RustyKioskInstallReceipt(
            requestId,
            "installed",
            true,
            "Android confirmed that the APK package was installed.",
            sessionId,
            packageName,
            System.currentTimeMillis(),
          )
        else ->
          RustyKioskInstallReceipt(
            requestId,
            "failed",
            true,
            (platformMessage ?: "Android rejected the APK installation.").take(240),
            sessionId,
            packageName,
            System.currentTimeMillis(),
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
