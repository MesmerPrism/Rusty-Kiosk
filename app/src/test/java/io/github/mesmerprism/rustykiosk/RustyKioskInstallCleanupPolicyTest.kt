package io.github.mesmerprism.rustykiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RustyKioskInstallCleanupPolicyTest {
  @Test
  fun successfulAbandonIsTerminal() {
    val decision = RustyKioskInstallCleanupPolicy.afterAbandonAttempt(
      abandonReturned = true,
      sessionStillPresent = null,
    )
    assertEquals(RustyKioskInstallCleanupPolicy.STATE_FAILED, decision.state)
    assertTrue(decision.completed)
    assertTrue(decision.cleanupConfirmed)
  }

  @Test
  fun failedAbandonIsTerminalOnlyWhenReadbackConfirmsAbsence() {
    val absent = RustyKioskInstallCleanupPolicy.afterAbandonAttempt(
      abandonReturned = false,
      sessionStillPresent = false,
    )
    assertEquals(RustyKioskInstallCleanupPolicy.STATE_FAILED, absent.state)
    assertTrue(absent.completed)
    assertTrue(absent.cleanupConfirmed)

    listOf(true, null).forEach { stillPresent ->
      val unresolved = RustyKioskInstallCleanupPolicy.afterAbandonAttempt(
        abandonReturned = false,
        sessionStillPresent = stillPresent,
      )
      assertEquals(RustyKioskInstallCleanupPolicy.STATE_CLEANUP_REQUIRED, unresolved.state)
      assertFalse(unresolved.completed)
      assertFalse(unresolved.cleanupConfirmed)
    }
  }
}
