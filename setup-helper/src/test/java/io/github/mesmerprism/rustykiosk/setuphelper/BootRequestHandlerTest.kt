package io.github.mesmerprism.rustykiosk.setuphelper

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BootRequestHandlerTest {
  private class Fixture {
    var enabled = true
    var authority = true
    var scheduleAccepted = true
    var scheduled = 0
    var requested = 0
    var failRequest = false
    var throwRequest = false
    var throwReadback = false
    var failClaimCommit = false
    var dieAfterClaimCommit = false
    var dieDuringRequest = false
    var receipt: BootRequestReceipt? = null
    val handler = BootRequestHandler({ enabled }, { authority }, {
      requested++
      if (dieDuringRequest) throw AssertionError("Simulated process death after effect")
      if (throwRequest) throw SecurityException("private error text")
      SetupResult(SetupOperation.REQUEST_WIFI_ADB, !failRequest, true, true, "Request made; approval may be pending.")
    }, {
      if (throwReadback) throw SecurityException()
      true to false
    }, {
      if (it.outcome == "dispatch_started" && failClaimCommit) throw IllegalStateException("Commit failed")
      receipt = it
      if (it.outcome == "dispatch_started" && dieAfterClaimCommit) throw AssertionError("Simulated process death before effect")
    }, { scheduled++; scheduleAccepted })
    fun boot() = handler.onBoot(9, 42_000)
    fun dispatch(wifi: Boolean = true, boot: Int = 9, now: Long = 60_000) =
      handler.onDeferred(receipt!!, boot, now, wifi)
  }

  @Test fun optedOutRecordsBootWithoutSchedulingOrRequesting() {
    val f = Fixture().apply { enabled = false; boot() }
    assertEquals("opted_out", f.receipt!!.outcome)
    assertEquals(0, f.scheduled)
    assertEquals(0, f.requested)
  }

  @Test fun missingAuthorityDoesNotSchedule() {
    val f = Fixture().apply { authority = false; boot() }
    assertEquals("no_authority", f.receipt!!.outcome)
    assertEquals(0, f.scheduled)
  }

  @Test fun bootWaitsThenConnectedWifiDispatchesOnceWithBothTimes() {
    val f = Fixture().apply { boot() }
    assertEquals("waiting_for_wifi", f.receipt!!.outcome)
    assertEquals(0, f.requested)
    assertEquals(1, f.scheduled)
    f.dispatch()
    assertEquals("requested", f.receipt!!.outcome)
    assertEquals(42_000L, f.receipt!!.elapsedRealtimeMs)
    assertEquals(60_000L, f.receipt!!.dispatchElapsedRealtimeMs)
    assertTrue(f.receipt!!.wifiConnected!!)
    assertFalse(f.receipt!!.wifiSettingEnabled!!)
    f.dispatch()
    assertEquals(1, f.requested)
    assertFalse(JSONObject(f.receipt!!.encode()).has("transport_ready"))
  }

  @Test fun schedulerRejectionIsTerminalAndDoesNotRequest() {
    val f = Fixture().apply { scheduleAccepted = false; boot() }
    assertEquals("failed", f.receipt!!.outcome)
    f.dispatch()
    assertEquals(0, f.requested)
  }

  @Test fun revocationDuringWifiWaitPreventsRequest() {
    val f = Fixture().apply { boot(); enabled = false; dispatch() }
    assertEquals("cancelled", f.receipt!!.outcome)
    assertEquals(0, f.requested)
  }

  @Test fun grantLossDuringWaitPreventsRequest() {
    val f = Fixture().apply { boot(); authority = false; dispatch() }
    assertEquals("no_authority", f.receipt!!.outcome)
    assertEquals(0, f.requested)
  }

  @Test fun deadlineNeverBypassesWifiOrExpiry() {
    val f = Fixture().apply { boot(); dispatch(false, now = 42_000 + BootRequestHandler.MAX_WAIT_MS) }
    assertEquals("expired", f.receipt!!.outcome)
    assertNull(f.receipt!!.dispatchElapsedRealtimeMs)
    assertEquals(0, f.requested)
  }

  @Test fun networkLostAtCallbackDoesNotRequestOrRetry() {
    val f = Fixture().apply { boot(); dispatch(false) }
    assertEquals("network_unavailable", f.receipt!!.outcome)
    assertEquals(0, f.requested)
    assertEquals(1, f.scheduled)
  }

  @Test fun staleBootAndElapsedIdentityCannotDispatch() {
    val f = Fixture().apply { boot(); dispatch(boot = 10); dispatch(now = 41_999) }
    assertEquals(0, f.requested)
    assertEquals("waiting_for_wifi", f.receipt!!.outcome)
  }

  @Test fun requestFailureAndExceptionAreRetainedWithoutPrivateExceptionText() {
    val failed = Fixture().apply { failRequest = true; boot(); dispatch() }
    assertEquals("failed", failed.receipt!!.outcome)
    val thrown = Fixture().apply { throwRequest = true; boot(); dispatch() }
    assertEquals("failed", thrown.receipt!!.outcome)
    assertTrue(thrown.receipt!!.message.contains("SecurityException"))
    assertFalse(thrown.receipt!!.message.contains("private error text"))
  }

  @Test fun unavailableReadbackRemainsUnknown() {
    val f = Fixture().apply { throwReadback = true; boot(); dispatch() }
    assertNull(f.receipt!!.adbEnabled)
    assertNull(f.receipt!!.wifiSettingEnabled)
    assertTrue(JSONObject(f.receipt!!.encode()).isNull("wifi_setting_enabled"))
  }

  @Test fun interruptionAfterPersistedClaimBeforeEffectCannotReplay() {
    val f = Fixture().apply { dieAfterClaimCommit = true; boot() }
    assertTrue(runCatching { f.dispatch() }.isFailure)
    assertEquals("dispatch_started", f.receipt!!.outcome)
    assertEquals(0, f.requested)
    f.dieAfterClaimCommit = false
    f.dispatch()
    assertEquals(0, f.requested)
    assertEquals("dispatch_started", f.receipt!!.outcome)
  }

  @Test fun interruptionAfterEffectCannotReplayItsConsumedClaim() {
    val f = Fixture().apply { dieDuringRequest = true; boot() }
    assertTrue(runCatching { f.dispatch() }.isFailure)
    assertEquals("dispatch_started", f.receipt!!.outcome)
    assertEquals(1, f.requested)
    f.dieDuringRequest = false
    f.dispatch()
    assertEquals(1, f.requested)
  }

  @Test fun failedClaimCommitPreventsSettingsRequest() {
    val f = Fixture().apply { failClaimCommit = true; boot(); dispatch() }
    assertEquals(0, f.requested)
    assertEquals("failed", f.receipt!!.outcome)
    assertNull(f.receipt!!.dispatchElapsedRealtimeMs)
  }
}
