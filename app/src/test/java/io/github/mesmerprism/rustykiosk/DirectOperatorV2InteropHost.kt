package io.github.mesmerprism.rustykiosk

import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import java.util.Base64

/** Binary framed test transport; all HTTP parsing, crypto and snapshot JSON use production code. */
object DirectOperatorV2InteropHost {
  const val NOW = 1_900_000_000_000L
  private const val CODE = "0123-4567-89AB-CDEF-GHJK-MNPQ-RS"
  private var scenario = ""
  private var profile = initialProfile()
  private var generation = true
  private var stage = 0
  private var path = ""
  private var sessions = manager()
  private var endpoint = endpoint()

  @JvmStatic
  fun main(args: Array<String>) {
    val input = DataInputStream(System.`in`)
    val output = DataOutputStream(System.out)
    while (true) {
      val size = try { input.readInt() } catch (_: java.io.EOFException) { break }
      require(size in 1..3_000_000)
      val frame = ByteArray(size).also(input::readFully)
      val request = JSONObject(frame.toString(Charsets.UTF_8))
      val selected = request.getString("scenario")
      if (selected != scenario || request.optBoolean("reset")) {
        sessions.clear()
        scenario = selected
        profile = initialProfile()
        generation = true
        sessions = manager()
        endpoint = endpoint()
      }
      stage = 0
      path = request.getString("path")
      val result = endpoint.handle(request.getString("method"), path,
        Base64.getDecoder().decode(request.getString("body")))
      val bytes = JSONObject().put("status", result.status)
        .put("length", result.body.size)
        .put("body", Base64.getEncoder().encodeToString(result.body)).toString().toByteArray()
      output.writeInt(bytes.size)
      output.write(bytes)
      output.flush()
    }
  }

  private fun manager() = DirectOperatorV2SessionManager(object : SecureRandom() {
    private var value = 17
    override fun nextBytes(bytes: ByteArray) {
      bytes.indices.forEach { index ->
        value = value * 1664525 + 1013904223
        bytes[index] = (value ushr 24).toByte()
      }
    }
  }, { NOW })

  private fun initialProfile() = DirectOperatorV2Profile(
    "PairingIdentity_0000000001", "KeyEpoch_0000000000000001", "OwnerEpoch_0000000000000001",
    DirectOperatorV2Base64.encode(ByteArray(32) { it.toByte() }),
    DirectOperatorV2Grant(true, "SummaryGrant_000000000001"),
    DirectOperatorV2Grant(false, "DetailGrant_0000000000001"),
  )

  private fun endpoint() = DirectOperatorV2Endpoint(sessions,
    profile = { profile }, pairingCode = { CODE }, snapshot = ::snapshot,
    currentGeneration = { generation },
    locked = { block ->
      stage++
      val summary = path == DirectOperatorV2RoutePolicy.CATALOG_SUMMARY_PATH
      if ((scenario == "absent" && path == DirectOperatorV2RoutePolicy.CONTRACT_PATH) ||
        (scenario == "revoke-open" && path == DirectOperatorV2RoutePolicy.SESSION_OPEN_PATH) ||
        (summary && scenario == "revoke-admission" && stage == 1) ||
        (summary && scenario == "revoke-dispatch" && stage == 2) ||
        (summary && scenario == "revoke-seal" && stage == 3)) {
        profile = profile.copy(summaryGrant = DirectOperatorV2Grant(false, "RevokedGrant_000000000001"))
      }
      if (summary && stage == 2) {
        when (scenario) {
          "generation" -> generation = false
          "rotation" -> profile = profile.copy(keyEpoch = "RotatedKey_00000000000001")
          "owner-rotation" -> profile = profile.copy(ownerEpoch = "RotatedOwner_000000000001")
          "restart" -> sessions.clear()
        }
      }
      block()
    },
  )

  private fun snapshot(epoch: String): KioskCatalogSnapshot {
    val entries = (0 until if (scenario == "truncated") 501 else 1).map { index ->
      CatalogEntry("package:com.example.visible$index", "Visible App $index", "com.example.visible$index",
        LaunchTarget("com.example.visible$index", "com.example.Main", "android.intent.action.MAIN",
          setOf("android.intent.category.LAUNCHER")), true, setOf("demo"), "android-launcher",
        signingEvidence = if (scenario == "signer-missing") null else AppSigningEvidence(listOf("a".repeat(64))))
    }
    val shapedEntries = when (scenario) {
      "label-control" -> entries.map { it.copy(label = "One\nTwo") }
      "many-tags" -> {
        val app = entries.single()
        val installed = InstalledApp(app.label, app.packageName!!, app.target!!, app.source, app.signingEvidence)
        CatalogAssembler.assemble(InstalledSnapshot(listOf(installed), listOf(InstalledPackage(app.label, app.packageName, app.signingEvidence))),
          listOf(TagRecord(app.label, app.packageName, (0 until 40).map { "first-$it" }.toSet()),
            TagRecord(app.label, null, (0 until 40).map { "second-$it" }.toSet())))
      }
      else -> entries
    }
    val observedAt = when (scenario) {
      "stale-snapshot" -> NOW - 300_001
      "future-snapshot" -> NOW + 5_001
      else -> NOW
    }
    return KioskCatalogSnapshotFactory.create(epoch, shapedEntries, observedAt, "b".repeat(64), "c".repeat(64))
  }
}
