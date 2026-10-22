package io.github.mesmerprism.rustykiosk

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files

class DirectOperatorV2IntegrationTest {
  @Test
  fun productionHttpAdapterUsesOnlyExactRoutesAndFixedBoundedBodies() {
    val profile = profile()
    val endpoint = DirectOperatorV2Endpoint(DirectOperatorV2SessionManager(), { profile },
      { "0123-4567-89AB-CDEF-GHJK-MNPQ-RS" }, { error("HTTP contract must not collect apps") },
      { true }, { it() })
    val http = DirectOperatorV2HttpAdapter(endpoint)
    fun request(method: String, path: String, headers: Map<String, String> = emptyMap(),
      body: String = "", query: String? = null) =
      http.serve(method, path, query, headers, ByteArrayInputStream(body.toByteArray())).status
    assertEquals(200, request("GET", "/v2/contract"))
    assertEquals(404, request("GET", "/v2/catalog/detail"))
    assertEquals(404, request("GET", "/v2/launch/reference/execute"))
    assertEquals(400, request("GET", "/v2/contract", query = "extra=true"))
    assertEquals(400, request("GET", "/v2/contract", mapOf("content-length" to "1"), "x"))
    assertEquals(400, request("GET", "/v2/contract", mapOf("transfer-encoding" to "chunked")))
    val json = mapOf("content-type" to "application/json")
    for (length in listOf("-1", "+1", " 1", "01", "1.0", "2147483648", "2097153")) {
      assertEquals(length, 400, request("POST", "/v2/session/open", json + ("content-length" to length)))
    }
    assertEquals(400, request("POST", "/v2/session/open", json))
    assertEquals(400, request("POST", "/v2/session/open", json + ("content-length" to "5"), "{}"))
    assertEquals(400, request("POST", "/v2/session/open", mapOf("content-length" to "2"), "{}"))
    assertEquals(401, request("POST", "/v2/session/open", json + ("content-length" to "2"), "{}"))
  }

  @Test
  fun strictJsonRejectsAndroidLeniencyDuplicateEscapesTypesAndTrailingBytes() {
    for (raw in listOf("{'schema':'x'}", "{schema:1}", "{\"a\":1,}",
      "{\"a\":1,\"a\":2}", "{\"a\":1,\"\\u0061\":2}", "{\"a\":01}",
      "{\"a\":NaN}", "{\"a\":true}x", "{\"a\":\"\n\"}", "{\"a\":1/*comment*/}")) {
      assertTrue(raw, runCatching { DirectOperatorV2Json.parse(raw) }.isFailure)
    }
    assertEquals(1, DirectOperatorV2Json.parse("{\"a\":1}").getInt("a"))
    val state = DirectOperatorV2SettingsState("pairing_identity_000000000001", "key_epoch_000000000000001",
      DirectOperatorV2Base64.encode(ByteArray(32)), false, "summary_epoch_000000000001", false, "detail_epoch_0000000000001")
    assertNull(DirectOperatorV2SettingsState.parseStrict(state.toJson().put("catalog_summary_granted", "true").toString()))
    assertNull(DirectOperatorV2SettingsState.parseStrict(state.toJson().put("pairing_id", 123).toString()))
  }

  @Test
  fun passiveTagAbsenceNeverCreatesFirstUseFileOrDirectoryAndV2PolicyRemainsSeparate() {
    val parent = Files.createTempDirectory("kiosk-passive-tags").toFile()
    try {
      val file = File(parent, "tags/app-tags.v1.json")
      val document = PassiveTagFileReader.load(file)
      assertTrue(document.records.isEmpty())
      assertFalse(file.exists())
      assertFalse(file.parentFile!!.exists())
      file.parentFile!!.mkdirs()
      file.writeText("""{"schema":"rusty.kiosk.app_tags.v2","apps":[{"name":"Example","package":"com.example","tags":["demo"],"requirements":["wifi-on"]}]}""")
      val original = file.readBytes()
      val readback = PassiveTagFileReader.load(file)
      assertEquals("rusty.kiosk.app_tags.v2", readback.schema)
      assertArrayEquals(original, file.readBytes())
    } finally {
      File(parent, "tags/app-tags.v1.json").delete()
      File(parent, "tags").delete()
      parent.delete()
    }
  }

  @Test
  fun snapshotIdentityTracksSignerLineageAndSummaryNeverDisclosesPackageOrCertificate() {
    val original = entry(AppSigningEvidence(listOf("a".repeat(64))))
    val rotated = entry(AppSigningEvidence(listOf("b".repeat(64)), listOf("a".repeat(64), "b".repeat(64)), true))
    assertEquals(KioskCatalogIdentity.appIdentity(original), KioskCatalogIdentity.appIdentity(rotated))
    val snapshot = KioskCatalogSnapshotFactory.create(profile().ownerEpoch,
      listOf(original, original.copy(key = "no-signer", signingEvidence = null)), 1_900_000_000_000,
      "c".repeat(64), "d".repeat(64))
    assertFalse(snapshot.complete)
    assertEquals(1, snapshot.permissionLimitedCount)
    assertEquals(300_000, snapshot.freshUntilMs - snapshot.observedAtMs)
    val json = snapshot.toSummaryJson().toString()
    assertFalse(json.contains("com.example"))
    assertFalse(json.contains("signing_evidence"))
    assertFalse(json.contains("a".repeat(64)))
    assertEquals(1, snapshot.entries.size)
  }

  private fun profile() = DirectOperatorV2Profile("pairing_identity_000000001", "key_epoch_000000000000001",
    "owner_epoch_0000000000001", DirectOperatorV2Base64.encode(ByteArray(32)),
    DirectOperatorV2Grant(false, "summary_epoch_000000000001"), DirectOperatorV2Grant(false, "detail_epoch_0000000000001"))

  private fun entry(signing: AppSigningEvidence) = CatalogEntry("package:com.example", "Example", "com.example",
    LaunchTarget("com.example", "com.example.Main", "android.intent.action.MAIN", setOf("android.intent.category.LAUNCHER")),
    true, setOf("demo"), "android-launcher", signingEvidence = signing)
}
