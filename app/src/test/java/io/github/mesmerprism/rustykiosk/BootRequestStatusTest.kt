package io.github.mesmerprism.rustykiosk

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BootRequestStatusTest {
  private val receipt = BootRequestStatus(9, 42_000, "requested", true, false, "Request made.")

  @Test fun oldHelperWithoutReceiptRemainsCompatible() {
    assertNull(BootRequestStatus.parse(null))
  }

  @Test fun manualAndStatusRepliesRetainTheSameBootEvidence() {
    for (operation in SetupHelperOperation.entries) {
      val result = SetupHelperProtocol.parseValues(1, operation, 1, operation.wireName,
        true, true, true, "Manual operation result", receipt.toJson().toString())
      assertEquals(receipt, result.lastBootRequest)
      assertEquals("Manual operation result", result.message)
    }
    assertTrue(receipt.summary.contains("setting Off"))
    assertTrue(receipt.summary.contains("transport unverified"))
  }

  @Test fun invalidReceiptsFailClosed() {
    val invalid = listOf(
      receipt.toJson().put("outcome", "transport_ready"),
      receipt.toJson().put("boot_count", -2),
      receipt.toJson().put("elapsed_realtime_ms", -1),
      receipt.toJson().put("wifi_setting_enabled", "true"),
      receipt.toJson().put("message", "x".repeat(161)),
      receipt.toJson().put("extra", true),
    )
    for (json in invalid) {
      assertTrue(runCatching { BootRequestStatus.parse(json.toString()) }.isFailure)
    }
    assertTrue(runCatching { BootRequestStatus.parse("x".repeat(2049)) }.isFailure)
    assertTrue(runCatching { BootRequestStatus.parse(receipt.toJson().toString() + " trailing") }.isFailure)
    assertTrue(runCatching { BootRequestStatus.parse(receipt.toJson().toString() + " {}") }.isFailure)
  }

  @Test fun unknownBootCountAndReadbackRemainExplicit() {
    val json = receipt.toJson().put("boot_count", -1).put("wifi_setting_enabled", JSONObject.NULL)
    assertTrue(BootRequestStatus.parse(json.toString())!!.summary.contains("boot unknown"))
    assertNull(BootRequestStatus.parse(json.toString())!!.wifiSettingEnabled)
  }

  @Test fun communicationFailurePreservesEvidenceButAuthoritativeOldHelperClearsIt() {
    val previous = receipt.toJson().toString()
    val syntheticFailure = SetupHelperResult(1, SetupHelperOperation.STATUS, false, false, false, "Timeout")
    assertEquals(previous, SetupHelperProtocol.bootRequestForCache(previous, syntheticFailure))
    val oldHelper = SetupHelperProtocol.parseValues(1, SetupHelperOperation.STATUS, 1, "status", true, true, false, "Ready")
    assertNull(SetupHelperProtocol.bootRequestForCache(previous, oldHelper))
  }
}
