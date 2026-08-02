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
  private var serverGeneration: Long? = null

  override fun onCreate() {
    super.onCreate()
    settings = OperatorBridgeSettings(this)
    createNotificationChannel()
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val snapshot = settings.snapshot()
    val requestedAction = if (intent?.action == ACTION_STOP) {
      OperatorBridgeRequestedAction.STOP
    } else {
      OperatorBridgeRequestedAction.START
    }
    val expectedGeneration = intent?.getLongExtra(EXTRA_EXPECTED_GENERATION, -1L)
      ?.takeIf { it > 0L } ?: snapshot.bridgeGeneration
    if (!OperatorBridgeActionPolicy.shouldApply(
        requestedAction,
        expectedGeneration,
        snapshot.bridgeGeneration,
        snapshot.enabled,
      )) {
      return if (snapshot.enabled) START_STICKY else START_NOT_STICKY
    }
    if (requestedAction == OperatorBridgeRequestedAction.STOP) {
      stopBridge()
      settings.recordRunning(expectedGeneration, false)
      stopSelfResult(startId)
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
    if (serverGeneration != expectedGeneration) stopBridge()
    if (server == null) {
      runCatching {
          OperatorBridgeHttpServer(this, settings.pairingCode()).also { http ->
            http.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            server = http
            serverGeneration = expectedGeneration
          }
        }
        .onSuccess {
          if (!settings.recordRunning(expectedGeneration, true)) {
            stopBridge()
            stopSelfResult(startId)
          }
        }
        .onFailure { throwable ->
          settings.recordRunning(
            expectedGeneration,
            false,
            throwable.message ?: throwable.javaClass.simpleName,
          )
          stopSelfResult(startId)
        }
    }
    return START_STICKY
  }

  override fun onDestroy() {
    val stoppedGeneration = stopBridge()
    stoppedGeneration?.let { settings.recordRunning(it, false) }
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun stopBridge(): Long? {
    val stoppedGeneration = serverGeneration
    server?.stop()
    server = null
    serverGeneration = null
    stopForeground(STOP_FOREGROUND_REMOVE)
    return stoppedGeneration
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
    const val EXTRA_EXPECTED_GENERATION = "rusty_kiosk_expected_bridge_generation"
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
  private val sessionStore = OperatorBridgeSessionStore(context)
  private val bridgeSettings = OperatorBridgeSettings(context)
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
    val authKey = resolveAuthKey(auth) ?: return unsignedJson(
      Response.Status.UNAUTHORIZED,
      failure("The ephemeral direct-link session is unavailable, expired, or from another bridge generation."),
    )
    return runCatching {
        when {
          session.method == Method.GET && session.uri == PATH_STATUS ->
            withEmptyAuth(session, target, auth, authKey) { bridgeStatus(auth) }
          session.method == Method.POST && session.uri == PATH_KIOSK_INVOKE ->
            withJsonBody(session, target, auth, authKey, MAX_JSON_BYTES, ::invokeKiosk)
          session.method == Method.GET && session.uri == PATH_KIOSK_RESULT ->
            withEmptyAuth(session, target, auth, authKey) {
              val requestId = session.parameters["request_id"]?.singleOrNull()
                ?: throw IllegalArgumentException("A single Kiosk request id is required.")
              kioskResult(requestId)
            }
          session.method == Method.GET && session.uri == PATH_KIOSK_REQUEST_STATUS ->
            withEmptyAuth(session, target, auth, authKey) {
              val requestId = session.parameters["request_id"]?.singleOrNull()
                ?: throw IllegalArgumentException("A single Kiosk request id is required.")
              kioskRequestStatus(requestId)
            }
          session.method == Method.POST && session.uri == PATH_KIOSK_CANCEL ->
            withJsonBody(session, target, auth, authKey, MAX_JSON_BYTES, ::cancelKioskRequest)
          session.method == Method.GET && session.uri == PATH_TAGS ->
            authenticatedFile(session, target, auth, authKey, tagFile())
          session.method == Method.PUT && session.uri == PATH_TAGS ->
            withJsonBody(session, target, auth, authKey, TagFileCodec.MAX_BYTES.toLong(), ::replaceTags)
          session.method == Method.GET && session.uri == PATH_STAGING ->
            withEmptyAuth(session, target, auth, authKey) { stagingList() }
          session.uri.startsWith(PATH_STAGING_FILE_PREFIX) ->
            stagingFileRequest(session, target, auth, authKey)
          session.method == Method.POST && session.uri == PATH_INSTALL ->
            withJsonBody(session, target, auth, authKey, MAX_JSON_BYTES, ::install)
          session.method == Method.GET && session.uri.startsWith(PATH_INSTALL_PREFIX) ->
            withEmptyAuth(session, target, auth, authKey) {
              installResult(session.uri.removePrefix(PATH_INSTALL_PREFIX))
            }
          else -> signedJson(auth.requestId, Response.Status.NOT_FOUND, failure("Unknown bridge operation."), authKey)
        }
      }
      .getOrElse { throwable ->
        signedJson(
          auth.requestId,
          Response.Status.BAD_REQUEST,
          failure(throwable.message ?: "The bridge request could not be completed."),
          authKey,
        )
      }
  }

  private fun resolveAuthKey(auth: OperatorBridgeAuthHeaders): ByteArray? =
    if (auth.sessionId == null) {
      pairingKey.toByteArray(StandardCharsets.UTF_8)
    } else {
      sessionStore.resolve(auth.sessionId, bridgeSettings.generation())?.secret
    }

  private fun publicContract(): Response =
    unsignedJson(
      Response.Status.OK,
      JSONObject()
        .put("schema", SCHEMA)
        .put("auth", "hmac-sha256-v1")
        .put("response_auth", "hmac-sha256-response-v1")
        .put("ephemeral_session_header", "X-Rusty-Session-Id")
        .put("operator_request_lifetime_ms", RustyKioskCliStore.REQUEST_LIFETIME_MS)
        .put("port", OperatorBridgeSettings.PORT)
        .put("max_clock_skew_seconds", OperatorBridgeAuth.MAX_CLOCK_SKEW_SECONDS)
        .put("max_tag_bytes", TagFileCodec.MAX_BYTES)
        .put("max_staged_file_bytes", RustyKioskInstaller.MAX_APK_BYTES)
        .put("raw_shell", false)
        .put("arbitrary_intents", false)
        .put("arbitrary_paths", false),
    )

  private fun bridgeStatus(auth: OperatorBridgeAuthHeaders): JSONObject {
    val snapshot = OperatorBridgeSettings(context).snapshot()
    return success("Direct operator link is ready.")
      .put("schema", SCHEMA)
      .put("endpoint", snapshot.endpoint ?: JSONObject.NULL)
      .put("bridge_generation", snapshot.bridgeGeneration)
      .put("session_id", auth.sessionId ?: JSONObject.NULL)
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
      ?: return kioskRequestStatus(valid)
    return JSONObject(result)
  }

  private fun kioskRequestStatus(requestId: String): JSONObject {
    val valid = RustyKioskCliProtocol.validRequestId(requestId)
      ?: throw IllegalArgumentException("A valid Kiosk request id is required.")
    val status = cliStore.status(valid)
    return JSONObject()
      .put("accepted", status.state != OperatorRequestState.UNKNOWN)
      .put("completed", status.completed)
      .put("provider_epoch", status.providerEpoch)
      .put("request_id", status.requestId)
      .put("operation_state", status.state.wireName)
      .put("command", status.command ?: JSONObject.NULL)
      .put("enqueued_at_ms", status.enqueuedAtMs ?: JSONObject.NULL)
      .put("expires_at_ms", status.expiresAtMs ?: JSONObject.NULL)
      .put("message", status.message)
  }

  private fun cancelKioskRequest(body: ByteArray): JSONObject {
    val requestId = JSONObject(body.toString(StandardCharsets.UTF_8)).optString("request_id")
    val valid = RustyKioskCliProtocol.validRequestId(requestId)
      ?: throw IllegalArgumentException("A valid Kiosk request id is required.")
    val before = cliStore.status(valid)
    if (before.state != OperatorRequestState.PENDING) {
      return kioskRequestStatus(valid)
        .put("accepted", false)
        .put("message", "Only the exact queued request can be cancelled; applied or terminal state was preserved.")
    }
    val after = cliStore.cancel(valid)
    return kioskRequestStatus(valid).apply {
      if (after.state != OperatorRequestState.CANCELLED) {
        put("accepted", false)
        put("message", "The request was claimed or became terminal before cancellation; state was preserved.")
      }
    }
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
    authKey: ByteArray,
  ): Response {
    val fileName = safeFileName(session.uri.removePrefix(PATH_STAGING_FILE_PREFIX))
    val destination = File(stagingDirectory, fileName)
    return when (session.method) {
      Method.GET -> authenticatedFile(session, target, auth, authKey, destination)
      Method.PUT -> authenticatedUpload(session, target, auth, authKey, destination)
      Method.DELETE -> withEmptyAuth(session, target, auth, authKey) {
        require(destination.isFile) { "The staged file does not exist." }
        require(destination.delete()) { "The staged file could not be removed." }
        success("Staged file removed.").put("name", fileName)
      }
      else -> signedJson(auth.requestId, Response.Status.METHOD_NOT_ALLOWED, failure("Unsupported staging operation."), authKey)
    }
  }

  private fun authenticatedUpload(
    session: IHTTPSession,
    target: String,
    auth: OperatorBridgeAuthHeaders,
    authKey: ByteArray,
    destination: File,
  ): Response {
    preauthorizeEnvelope(session, target, auth, authKey)
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
      OperatorBridgeAuth.verifyDigest(authKey, session.method.name, target, actualSha, auth).getOrThrow()
      recordAuthenticatedSessionUse(auth)
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
        authKey,
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
    authKey: ByteArray,
    file: File,
  ): Response {
    verifyEmpty(session, target, auth, authKey)
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
    signResponse(response, auth.requestId, Response.Status.OK, sha, authKey)
    response.addHeader("X-Rusty-File-Name", file.name)
    return response
  }

  private fun install(body: ByteArray): JSONObject {
    val json = JSONObject(body.toString(StandardCharsets.UTF_8))
    require(json.keys().asSequence().toSet() == setOf("request_id", "files")) {
      "The install request must contain only request_id and files."
    }
    val rawRequestId = json.get("request_id")
    require(rawRequestId is String) { "The install request id must be a string." }
    val requestId = rawRequestId
    require(RustyKioskInstallStore.REQUEST_ID.matches(requestId)) { "A valid install request id is required." }
    val entries = json.optJSONArray("files")
      ?: throw IllegalArgumentException("A committed APK file list is required.")
    require(entries.length() in 1..RustyKioskInstaller.MAX_APK_PARTS) {
      "The APK file list is empty or too large."
    }
    val commitments = (0 until entries.length()).map { index ->
      RustyKioskInstallPartCommitmentPolicy.parse(entries.getJSONObject(index)).also { commitment ->
        require(commitment.bytes in 1..RustyKioskInstaller.MAX_APK_BYTES) {
          "An APK commitment is empty or too large."
        }
        require(commitment.name.extensionEquals("apk")) { "Every install part must be an APK." }
        requireSafeDecodedFileName(commitment.name)
      }
    }
    require(commitments.map { it.name }.distinct().size == commitments.size) {
      "Install part names must be unique."
    }
    val parts = commitments.map { commitment ->
      val file = File(stagingDirectory, commitment.name)
      require(file.isFile && file.length() == commitment.bytes) {
        "A requested APK does not match its committed staged byte count."
      }
      RustyKioskCommittedInstallPart(file, commitment)
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
    return RustyKioskInstaller(context).install(requestId, parts).toJson()
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
    authKey: ByteArray,
    maxBytes: Long,
    action: (ByteArray) -> JSONObject,
  ): Response {
    val body = readBody(session, maxBytes)
    OperatorBridgeAuth.verify(authKey, session.method.name, target, body, auth).getOrThrow()
    recordAuthenticatedSessionUse(auth)
    acceptReplay(auth.requestId)
    return signedJson(auth.requestId, Response.Status.OK, action(body), authKey)
  }

  private fun withEmptyAuth(
    session: IHTTPSession,
    target: String,
    auth: OperatorBridgeAuthHeaders,
    authKey: ByteArray,
    action: () -> JSONObject,
  ): Response {
    verifyEmpty(session, target, auth, authKey)
    acceptReplay(auth.requestId)
    return signedJson(auth.requestId, Response.Status.OK, action(), authKey)
  }

  private fun verifyEmpty(session: IHTTPSession, target: String, auth: OperatorBridgeAuthHeaders, authKey: ByteArray) {
    OperatorBridgeAuth.verify(authKey, session.method.name, target, EMPTY_BODY, auth).getOrThrow()
    recordAuthenticatedSessionUse(auth)
  }

  private fun recordAuthenticatedSessionUse(auth: OperatorBridgeAuthHeaders) {
    auth.sessionId?.let { sessionId ->
      require(sessionStore.recordAuthenticatedUse(sessionId, bridgeSettings.generation())) {
        "The ephemeral session expired or was revoked during authentication."
      }
    }
  }

  private fun preauthorizeEnvelope(
    session: IHTTPSession,
    target: String,
    auth: OperatorBridgeAuthHeaders,
    authKey: ByteArray,
  ) {
    OperatorBridgeAuth.verifyDigest(
        authKey,
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

  private fun signedJson(requestId: String, status: Response.Status, json: JSONObject, authKey: ByteArray): Response {
    val bytes = json.toString().toByteArray(StandardCharsets.UTF_8)
    val response =
      newFixedLengthResponse(
        status,
        "application/json; charset=utf-8",
        ByteArrayInputStream(bytes),
        bytes.size.toLong(),
      )
    signResponse(response, requestId, status, OperatorBridgeAuth.sha256(bytes), authKey)
    return response
  }

  private fun signResponse(response: Response, requestId: String, status: Response.Status, sha: String, authKey: ByteArray) {
    response.addHeader("X-Rusty-Request-Id", requestId)
    response.addHeader("X-Rusty-Content-Sha256", sha)
    response.addHeader(
      "X-Rusty-Signature",
      OperatorBridgeAuth.signResponse(authKey, requestId, status.requestStatus, sha),
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
    requireSafeDecodedFileName(decoded)
    return decoded
  }

  private fun requireSafeDecodedFileName(decoded: String) {
    require(SAFE_FILE_NAME.matches(decoded) && decoded != "." && decoded != "..") {
      "Only a single bounded staging filename is accepted."
    }
  }

  private fun String.extensionEquals(expected: String): Boolean =
    substringAfterLast('.', "").equals(expected, ignoreCase = true)

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
    private const val SCHEMA = "rusty.kiosk.direct_operator.v2"
    private const val PATH_CONTRACT = "/v1/contract"
    private const val PATH_STATUS = "/v1/status"
    private const val PATH_KIOSK_INVOKE = "/v1/kiosk/invoke"
    private const val PATH_KIOSK_RESULT = "/v1/kiosk/result"
    private const val PATH_KIOSK_REQUEST_STATUS = "/v1/kiosk/request-status"
    private const val PATH_KIOSK_CANCEL = "/v1/kiosk/cancel"
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
