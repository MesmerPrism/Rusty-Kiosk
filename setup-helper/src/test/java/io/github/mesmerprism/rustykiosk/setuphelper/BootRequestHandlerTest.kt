package io.github.mesmerprism.rustykiosk.setuphelper

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BootRequestHandlerTest {
  private fun result(success: Boolean) = SetupResult(
    SetupOperation.REQUEST_WIFI_ADB, success, true, true, "Request completed; approval may be pending.",
  )

  private fun observe(enabled: Boolean = true, authority: Boolean = true,
    request: () -> SetupResult = { result(true) },
    readback: () -> Pair<Boolean?, Boolean?> = { true to true }): BootRequestReceipt {
    var recorded: BootRequestReceipt? = null
    BootRequestHandler({ enabled }, { authority }, request, readback, { recorded = it })
      .onBoot(9, 42_000)
    return recorded!!
  }

  @Test fun optedOutRecordsDeliveryWithoutChangingSettings() {
    val receipt = observe(enabled = false, request = { error("Must not request") })
    assertEquals("opted_out", receipt.outcome)
    assertEquals(9, receipt.bootCount)
    assertEquals(42_000L, receipt.elapsedRealtimeMs)
    assertTrue(receipt.wifiSettingEnabled!!)
  }

  @Test fun missingAuthorityDoesNotCallPrivilegedRequest() {
    assertEquals("no_authority", observe(authority = false, request = { error("Must not request") }).outcome)
  }

  @Test fun successRecordsRequestAndIndependentSettingReadback() {
    val receipt = observe(readback = { true to false })
    assertEquals("requested", receipt.outcome)
    assertFalse(receipt.wifiSettingEnabled!!)
    val json = JSONObject(receipt.encode())
    assertEquals(6, json.length())
    assertFalse(json.has("transport_ready"))
  }

  @Test fun declinedOrFailedRequestIsRetained() {
    assertEquals("failed", observe(request = { result(false) }).outcome)
  }

  @Test fun executorExceptionStillRecordsActualDeliveryAndReadback() {
    val receipt = observe(request = { throw SecurityException("private exception text") })
    assertEquals("failed", receipt.outcome)
    assertTrue(receipt.message.contains("SecurityException"))
    assertFalse(receipt.message.contains("private exception text"))
  }

  @Test fun unavailableReadbackIsUnknownRatherThanOff() {
    val receipt = observe(readback = { throw SecurityException() })
    assertNull(receipt.adbEnabled)
    assertNull(receipt.wifiSettingEnabled)
    assertTrue(JSONObject(receipt.encode()).isNull("wifi_setting_enabled"))
  }
}
