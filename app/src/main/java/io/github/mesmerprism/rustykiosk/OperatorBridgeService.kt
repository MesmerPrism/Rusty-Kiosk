package io.github.mesmerprism.rustykiosk

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileInputStream
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale

class OperatorBridgeService : Service() {
  private lateinit var settings: OperatorBridgeSettings
  private var server: OperatorBridgeHttpServer? = null

  override fun onCreate() {
    super.onCreate()
    settings = OperatorBridgeSettings(this)
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == ACTION_STOP) {
      settings.recordRunning(false)
      settings.recordEnabled(false)
      stopBridge()
      stopSelf()
      return START_NOT_STICKY
    }
    startForeground(
      NOTIFICATION_ID,
      androidx.core.app.NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_rusty_kiosk)
        .setContentTitle("Rusty Kiosk direct link")
        .setContentText("Bounded local operator access is enabled")
        .setOngoing(true)
        .setSilent(true)
        .build(),
    )
    if (server == null) {
      runCatching {
          OperatorBridgeHttpServer(this, settings.pairingCode()).also { http ->
            http.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = http
          }
        }
        .onSuccess { settings.recordRunning(true) }
        .onFailure { throwable ->
          settings.recordRunning(false, throwable.message ?: throwable.javaClass.simpleName)
          stopSelf()
        }
    }
    return START_STICKY
  }

  override fun onDestroy() {
    stopBridge()
    settings.recordRunning(false)
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun stopBridge() {
    server?.stop()
    server = null
    stopForeground(STOP_FOREGROUND_REMOVE)
  }

  private fun createNotificationChannel() {
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(
      NotificationChannel(
        CHANNEL_ID,
        "Rusty Kiosk direct link",
        NotificationManager.IMPORTANCE_LOW,
      ).apply {
        description = "Visible while the wearer-enabled local operator link is available."
      }
    )
  }

  companion object {
    const val ACTION_START = "io.github.mesmerprism.rustykiosk.action.START_OPERATOR_BRIDGE"
    const val ACTION_STOP = "io.github.mesmerprism.rustykiosk.action.STOP_OPERATOR_BRIDGE"
    private const val CHANNEL_ID = "rusty_kiosk_operator_bridge"
    private const val NOTIFICATION_ID = 3073
  }
}

private class OperatorBridgeHttpServer(
  private val context: android.content.Context,
  private val pairingKey: String,
) : NanoHTTPD(OperatorBridgeSettings.PORT) {
  private val replayStore = OperatorBridgeReplayStore(context)
  private val tagStore = TagFileStore(context)
  private val cliStore = RustyKioskCliStore(context)
  private val installStore = RustyKioskInstallStore(context)
  private val stagingDirectory =
    (context.getExternalFilesDir("operator-staging")
        ?: File(context.filesDir, "operator-staging"))
      .also(File::mkdirs)

  override fun serve(session: IHTTPSession): Response {
    val target = requestTarget(session)
    if (session.method == Method.GET && session.uri == PATH_CONTRACT) {
      return publicContract()
    }
    val auth = OperatorBridgeAuth.parse(session.headers).getOrElse { throwable ->
      return unsignedJson(
        Response.Status.UNAUTHORIZED,
        failure(throwable.message ?: "Signed request headers are required."),
      )
    }
    return runCatching {
        when {
          session.method == Method.GET && session.uri == PATH_STATUS ->
            withEmptyAuth(session, target, auth) { bridgeStatus() }
          session.method == Method.POST && session.uri == PATH_KIOSK_INVOKE ->
            withJsonBody(session, target, auth, MAX_JSON_BYTES, ::invokeKiosk)
          session.method == Method.GET && session.uri == PATH_KIOSK_RESULT ->
            withEmptyAuth(session, target, auth) {
              val requestId = session.parameters["request_id"]?.singleOrNull()
                ?: throw IllegalArgumentException("A single Kiosk request id is required.")
              kioskResult(requestId)
            }
          session.method == Method.GET && session.uri == PATH_TAGS ->
            authenticatedFile(session, target, auth, tagFile())
          session.method == Method.PUT && session.uri == PATH_TAGS ->
            withJsonBody(session, target, auth, TagFileCodec.MAX_BYTES.toLong(), ::replaceTags)
          session.method == Method.GET && session.uri == PATH_STAGING ->
            withEmptyAuth(session, target, auth) { stagingList() }
          session.uri.startsWith(PATH_STAGING_FILE_PREFIX) ->
            stagingFileRequest(session, target, auth)
          session.method == Method.POST && session.uri == PATH_INSTALL ->
            withJsonBody(session, target, auth, MAX_JSON_BYTES, ::install)
          session.method == Method.GET && session.uri.startsWith(PATH_INSTALL_PREFIX) ->
            withEmptyAuth(session, target, auth) {
              installResult(session.uri.removePrefix(PATH_INSTALL_PREFIX))
            }
          else -> signedJson(auth.requestId, Response.Status.NOT_FOUND, failure("Unknown bridge operation."))
        }
      }
      .getOrElse { throwable ->
        signedJson(
          auth.requestId,
          Response.Status.BAD_REQUEST,
          failure(throwable.message ?: "The bridge request could not be completed."),
        )
      }
  }

  private fun publicContract(): Response =
    unsignedJson(
      Response.Status.OK,
      JSONObject()
        .put("schema", SCHEMA)
        .put("auth", "hmac-sha256-v1")
        .put("response_auth", "hmac-sha256-response-v1")
        .put("port", OperatorBridgeSettings.PORT)
        .put("max_clock_skew_seconds", OperatorBridgeAuth.MAX_CLOCK_SKEW_SECONDS)
        .put("max_tag_bytes", TagFileCodec.MAX_BYTES)
        .put("max_staged_file_bytes", RustyKioskInstaller.MAX_APK_BYTES)
        .put("raw_shell", false)
        .put("arbitrary_intents", false)
        .put("arbitrary_paths", false),
    )

  private fun bridgeStatus(): JSONObject {
    val snapshot = OperatorBridgeSettings(context).snapshot()
    return success("Direct operator link is ready.")
      .put("schema", SCHEMA)
      .put("endpoint", snapshot.endpoint ?: JSONObject.NULL)
      .put("installer_allowed", snapshot.installerAllowed)
      .put("staging_directory_kind", "app-owned")
  }

  private fun invokeKiosk(body: ByteArray): JSONObject {
    val json = JSONObject(body.toString(StandardCharsets.UTF_8))
    val request =
      RustyKioskCliProtocol.parse(
          json.optString("request_id"),
          json.optString("command"),
          json.optString("value").takeIf { json.has("value") && !json.isNull("value") },
        )
        .getOrThrow()
    require(cliStore.enqueue(request)) {
      "Another Rusty Kiosk request is pending or that request id was already used."
    }
    val launch =
      Intent(context, RustyKioskActivity::class.java)
        .addFlags(
          Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_SINGLE_TOP
        )
        .putExtra(RustyKioskCliProtocol.EXTRA_PENDING_REQUEST_ID, request.requestId)
    runCatching { context.startActivity(launch) }
    return success("Typed Kiosk request admitted; matching readback is pending.")
      .put("request_id", request.requestId)
      .put("completed", false)
  }

  private fun kioskResult(requestId: String): JSONObject {
    val valid =
      RustyKioskCliProtocol.parse(requestId, RustyKioskCliCommand.STATUS.wireName, null)
        .getOrThrow()
        .requestId
    val result = cliStore.readResult(valid)
      ?: return success("Matching Kiosk readback is still pending.")
        .put("request_id", valid)
        .put("completed", false)
    return JSONObject(result)
  }

  private fun tagFile(): File {
    tagStore.ensureExists()
    return tagStore.tagFile
  }

  private fun replaceTags(body: ByteArray): JSONObject {
    tagStore.replaceJson(body.toString(StandardCharsets.UTF_8))
    return success("Tag file validated and atomically hotloaded.")
      .put("bytes", body.size)
      .put("sha256", OperatorBridgeAuth.sha256(body))
  }

  private fun stagingList(): JSONObject {
    val files = JSONArray()
    stagingDirectory.listFiles()
      .orEmpty()
      .asSequence()
      .filter(File::isFile)
      .sortedBy { it.name.lowercase(Locale.ROOT) }
      .take(MAX_STAGED_FILES)
      .forEach { file ->
        files.put(
          JSONObject()
            .put("name", file.name)
            .put("bytes", file.length())
            .put("modified_at_ms", file.lastModified())
        )
      }
    return success("App-owned staging area listed.").put("files", files)
  }

  private fun stagingFileRequest(
    session: IHTTPSession,
    target: String,
    auth: OperatorBridgeAuthHeaders,
  ): Response {
    val fileName = safeFileName(session.uri.removePrefix(PATH_STAGING_FILE_PREFIX))
    val destination = File(stagingDirectory, fileName)
    return when (session.method) {
      Method.GET -> authenticatedFile(session, target, auth, destination)
      Method.PUT -> authenticatedUpload(session, target, auth, destination)
      Method.DELETE -> withEmptyAuth(session, target, auth) {
        require(destination.isFile) { "The staged file does not exist." }
        require(destination.delete()) { "The staged file could not be removed." }
        success("Staged file removed.").put("name", fileName)
      }
      else -> signedJson(auth.requestId, Response.Status.METHOD_NOT_ALLOWED, failure("Unsupported staging operation."))
    }
  }

  private fun authenticatedUpload(
    session: IHTTPSession,
    target: String,
    auth: OperatorBridgeAuthHeaders,
    destination: File,
  ): Response {
    preauthorizeEnvelope(session, target, auth)
    val length = contentLength(session)
    require(length in 1..MAX_STAGED_FILE_BYTES) { "The staged file is empty or exceeds the fixed size limit." }
    val temporary = File(stagingDirectory, ".${destination.name}.${auth.requestId}.part")
    if (temporary.exists()) temporary.delete()
    try {
      val digest = MessageDigest.getInstance("SHA-256")
      var remaining = length
      temporary.outputStream().buffered().use { output ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (remaining > 0) {
          val read = session.inputStream.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
          require(read > 0) { "The upload ended before its declared length." }
          digest.update(buffer, 0, read)
          output.write(buffer, 0, read)
          remaining -= read
        }
      }
      val actualSha = digest.digest().joinToString("") { "%02x".format(it) }
      OperatorBridgeAuth.verifyDigest(pairingKey, session.method.name, target, actualSha, auth).getOrThrow()
      acceptReplay(auth.requestId)
      require(temporary.renameTo(destination) || runCatching {
        temporary.copyTo(destination, overwrite = true)
        temporary.delete()
        true
      }.getOrDefault(false)) { "The verified staged file could not be activated." }
      return signedJson(
        auth.requestId,
        Response.Status.OK,
        success("File verified and activated in the app-owned staging area.")
          .put("name", destination.name)
          .put("bytes", destination.length())
          .put("sha256", actualSha),
      )
    } catch (throwable: Throwable) {
      temporary.delete()
      throw throwable
    }
  }

  private fun authenticatedFile(
    session: IHTTPSession,
    target: String,
    auth: OperatorBridgeAuthHeaders,
    file: File,
  ): Response {
    verifyEmpty(session, target, auth)
    acceptReplay(auth.requestId)
    require(file.isFile) { "The requested bounded file does not exist." }
    val sha = sha256(file)
    val response =
      newFixedLengthResponse(
        Response.Status.OK,
        "application/octet-stream",
        FileInputStream(file),
        file.length(),
      )
    signResponse(response, auth.requestId, Response.Status.OK, sha)
    response.addHeader("X-Rusty-File-Name", file.name)
    return response
  }

  private fun install(body: ByteArray): JSONObject {
    val json = JSONObject(body.toString(StandardCharsets.UTF_8))
    val requestId = json.optString("request_id")
    require(RustyKioskInstallStore.REQUEST_ID.matches(requestId)) { "A valid install request id is required." }
    val names = json.optJSONArray("files") ?: throw IllegalArgumentException("A fixed APK file list is required.")
    require(names.length() in 1..RustyKioskInstaller.MAX_APK_PARTS) { "The APK file list is empty or too large." }
    val files = (0 until names.length()).map { index ->
      File(stagingDirectory, safeFileName(names.getString(index))).also { file ->
        require(file.isFile) { "A requested APK is not present in the staging area." }
      }
    }
    runCatching {
      context.startActivity(
        Intent(context, RustyKioskActivity::class.java)
          .addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or
              Intent.FLAG_ACTIVITY_CLEAR_TOP or
              Intent.FLAG_ACTIVITY_SINGLE_TOP
          )
      )
    }
    return RustyKioskInstaller(context).install(requestId, files).toJson()
  }

  private fun installResult(requestId: String): JSONObject {
    require(RustyKioskInstallStore.REQUEST_ID.matches(requestId)) { "A valid install request id is required." }
    return installStore.read(requestId)?.toJson()
      ?: success("No matching Android install receipt is available yet.")
        .put("request_id", requestId)
        .put("completed", false)
        .put("state", "pending")
  }

  private fun withJsonBody(
    session: IHTTPSession,
    target: String,
    auth: OperatorBridgeAuthHeaders,
    maxBytes: Long,
    action: (ByteArray) -> JSONObject,
  ): Response {
    val body = readBody(session, maxBytes)
    OperatorBridgeAuth.verify(pairingKey, session.method.name, target, body, auth).getOrThrow()
    acceptReplay(auth.requestId)
    return signedJson(auth.requestId, Response.Status.OK, action(body))
  }

  private fun withEmptyAuth(
    session: IHTTPSession,
    target: String,
    auth: OperatorBridgeAuthHeaders,
    action: () -> JSONObject,
  ): Response {
    verifyEmpty(session, target, auth)
    acceptReplay(auth.requestId)
    return signedJson(auth.requestId, Response.Status.OK, action())
  }

  private fun verifyEmpty(session: IHTTPSession, target: String, auth: OperatorBridgeAuthHeaders) {
    OperatorBridgeAuth.verify(pairingKey, session.method.name, target, EMPTY_BODY, auth).getOrThrow()
  }

  private fun preauthorizeEnvelope(
    session: IHTTPSession,
    target: String,
    auth: OperatorBridgeAuthHeaders,
  ) {
    OperatorBridgeAuth.verifyDigest(
        pairingKey,
        session.method.name,
        target,
        auth.contentSha256,
        auth,
      )
      .getOrThrow()
    require(!replayStore.contains(auth.requestId)) { "That signed request id was already used." }
  }

  private fun acceptReplay(requestId: String) {
    require(replayStore.accept(requestId)) { "That signed request id was already used." }
  }

  private fun signedJson(requestId: String, status: Response.Status, json: JSONObject): Response {
    val bytes = json.toString().toByteArray(StandardCharsets.UTF_8)
    val response =
      newFixedLengthResponse(
        status,
        "application/json; charset=utf-8",
        ByteArrayInputStream(bytes),
        bytes.size.toLong(),
      )
    signResponse(response, requestId, status, OperatorBridgeAuth.sha256(bytes))
    return response
  }

  private fun signResponse(response: Response, requestId: String, status: Response.Status, sha: String) {
    response.addHeader("X-Rusty-Request-Id", requestId)
    response.addHeader("X-Rusty-Content-Sha256", sha)
    response.addHeader(
      "X-Rusty-Signature",
      OperatorBridgeAuth.signResponse(pairingKey, requestId, status.requestStatus, sha),
    )
  }

  private fun unsignedJson(status: Response.Status, json: JSONObject): Response =
    newFixedLengthResponse(status, "application/json; charset=utf-8", json.toString())

  private fun readBody(session: IHTTPSession, maxBytes: Long): ByteArray {
    val length = contentLength(session)
    require(length in 0..maxBytes) { "The request body exceeds the fixed size limit." }
    val bytes = ByteArray(length.toInt())
    var offset = 0
    while (offset < bytes.size) {
      val read = session.inputStream.read(bytes, offset, bytes.size - offset)
      require(read > 0) { "The request body ended before its declared length." }
      offset += read
    }
    return bytes
  }

  private fun contentLength(session: IHTTPSession): Long =
    session.headers["content-length"]?.toLongOrNull()
      ?: if (session.method == Method.GET || session.method == Method.DELETE) 0L
      else throw IllegalArgumentException("A fixed Content-Length is required.")

  private fun requestTarget(session: IHTTPSession): String =
    session.uri + session.queryParameterString?.takeIf(String::isNotBlank)?.let { "?$it" }.orEmpty()

  private fun safeFileName(encoded: String): String {
    val decoded = URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
    require(SAFE_FILE_NAME.matches(decoded) && decoded != "." && decoded != "..") {
      "Only a single bounded staging filename is accepted."
    }
    return decoded
  }

  private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    DigestInputStream(file.inputStream().buffered(), digest).use { input ->
      val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
      while (input.read(buffer) >= 0) Unit
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
  }

  private fun success(message: String): JSONObject =
    JSONObject().put("accepted", true).put("message", message)

  private fun failure(message: String): JSONObject =
    JSONObject().put("accepted", false).put("message", message.take(240))

  companion object {
    private const val SCHEMA = "rusty.kiosk.direct_operator.v1"
    private const val PATH_CONTRACT = "/v1/contract"
    private const val PATH_STATUS = "/v1/status"
    private const val PATH_KIOSK_INVOKE = "/v1/kiosk/invoke"
    private const val PATH_KIOSK_RESULT = "/v1/kiosk/result"
    private const val PATH_TAGS = "/v1/tags"
    private const val PATH_STAGING = "/v1/staging"
    private const val PATH_STAGING_FILE_PREFIX = "/v1/staging/files/"
    private const val PATH_INSTALL = "/v1/install"
    private const val PATH_INSTALL_PREFIX = "/v1/install/"
    private const val MAX_JSON_BYTES = 512L * 1024L
    private const val MAX_STAGED_FILE_BYTES = RustyKioskInstaller.MAX_APK_BYTES
    private const val MAX_STAGED_FILES = 256
    private val SAFE_FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._ ()+@-]{0,159}")
    private val EMPTY_BODY = ByteArray(0)
  }
}

private class OperatorBridgeReplayStore(context: android.content.Context) {
  private val preferences =
    context.applicationContext.getSharedPreferences(PREFERENCES, android.content.Context.MODE_PRIVATE)
  private val ids = LinkedHashSet<String>()

  init {
    preferences.getString(KEY_IDS, null)
      ?.split(',')
      ?.filter(String::isNotBlank)
      ?.takeLast(MAX_IDS)
      ?.forEach(ids::add)
  }

  @Synchronized
  fun contains(requestId: String): Boolean = requestId in ids

  @Synchronized
  fun accept(requestId: String): Boolean {
    if (!ids.add(requestId)) return false
    while (ids.size > MAX_IDS) ids.remove(ids.first())
    preferences.edit().putString(KEY_IDS, ids.joinToString(",")).commit()
    return true
  }

  companion object {
    private const val PREFERENCES = "rusty_kiosk_operator_replay"
    private const val KEY_IDS = "request_ids"
    private const val MAX_IDS = 512
  }
}
