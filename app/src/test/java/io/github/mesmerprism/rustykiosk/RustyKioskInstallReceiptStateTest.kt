package io.github.mesmerprism.rustykiosk

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

class RustyKioskInstallReceiptStateTest {
  @Test
  fun onlyTrulyAbsentReceiptPermitsAdmission() {
    assertTrue(
      RustyKioskInstallAdmissionPolicy.canCreate(RustyKioskInstallReceiptRead.Absent)
    )
    assertFalse(
      RustyKioskInstallAdmissionPolicy.canCreate(
        RustyKioskInstallReceiptRead.Available(receipt())
      )
    )
    assertFalse(
      RustyKioskInstallAdmissionPolicy.canCreate(
        RustyKioskInstallReceiptRead.Damaged("malformed")
      )
    )
  }

  @Test
  fun storedReceiptRequiresExactSchemaShapeAndCommitmentDigest() {
    val expected = receipt()
    val validBytes = expected.toStoredJson().toString().toByteArray(StandardCharsets.UTF_8)
    val valid = RustyKioskInstallReceiptCodec.inspect(validBytes, expected.requestId)
    assertTrue(valid is RustyKioskInstallReceiptRead.Available)
    assertEquals(
      expected.commitmentSha256,
      (valid as RustyKioskInstallReceiptRead.Available).receipt.commitmentSha256,
    )

    val damagedJson = listOf(
      JSONObject(expected.toStoredJson().toString()).put("schema", "wrong.schema"),
      JSONObject(expected.toStoredJson().toString()).remove("commitments"),
      JSONObject(expected.toStoredJson().toString()).put("commitment_sha256", "00".repeat(32)),
      JSONObject(expected.toStoredJson().toString()).put("completed", true),
      JSONObject(expected.toStoredJson().toString()).put("unknown", true),
    )
    damagedJson.forEach { json ->
      assertTrue(
        RustyKioskInstallReceiptCodec.inspect(
          json.toString().toByteArray(StandardCharsets.UTF_8),
          expected.requestId,
        ) is RustyKioskInstallReceiptRead.Damaged
      )
    }
    assertTrue(
      RustyKioskInstallReceiptCodec.inspect(
        "not-json".toByteArray(StandardCharsets.UTF_8),
        expected.requestId,
      ) is RustyKioskInstallReceiptRead.Damaged
    )
  }

  @Test
  fun processLockAllowsOnlyOneAbsentAdmissionAndNeverAdmitsDamage() {
    repeat(30) {
      var state: RustyKioskInstallReceiptRead = RustyKioskInstallReceiptRead.Absent
      val ready = CountDownLatch(2)
      val start = CountDownLatch(1)
      val winners = AtomicInteger()
      val threads = (0..1).map {
        Thread {
          ready.countDown()
          start.await()
          synchronized(RustyKioskInstallProcessLock.monitor) {
            if (RustyKioskInstallAdmissionPolicy.canCreate(state)) {
              state = RustyKioskInstallReceiptRead.Available(receipt())
              winners.incrementAndGet()
            }
          }
        }.also(Thread::start)
      }
      ready.await()
      start.countDown()
      threads.forEach(Thread::join)
      assertEquals(1, winners.get())
      state = RustyKioskInstallReceiptRead.Damaged("interrupted temporary receipt")
      assertFalse(RustyKioskInstallAdmissionPolicy.canCreate(state))
    }
  }

  private fun receipt(): RustyKioskInstallReceipt =
    RustyKioskInstallReceipt(
      requestId = "install_request_1",
      state = RustyKioskInstallCleanupPolicy.STATE_CLEANUP_REQUIRED,
      completed = false,
      message = "Cleanup still requires readback.",
      sessionId = 42,
      packageName = null,
      recordedAtMs = 1_000L,
      commitments = listOf(
        RustyKioskInstallPartCommitment("base.apk", 12L, "01".repeat(32))
      ),
    )
}
