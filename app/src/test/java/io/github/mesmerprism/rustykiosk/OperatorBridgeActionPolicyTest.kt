package io.github.mesmerprism.rustykiosk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OperatorBridgeActionPolicyTest {
  @Test
  fun rapidDisableThenEnableIgnoresCrossedStopAndRequiresCurrentRunningGeneration() {
    // Disable queued STOP generation 2, then enable queued START generation 3. If START arrives
    // first, the late STOP must not stop the generation-3 listener.
    assertFalse(
      OperatorBridgeActionPolicy.shouldApply(
        OperatorBridgeRequestedAction.STOP,
        expectedGeneration = 2L,
        currentGeneration = 3L,
        enabled = true,
      )
    )
    assertTrue(
      OperatorBridgeActionPolicy.shouldApply(
        OperatorBridgeRequestedAction.START,
        expectedGeneration = 3L,
        currentGeneration = 3L,
        enabled = true,
      )
    )
    assertFalse(OperatorBridgeActionPolicy.isEffectivelyRunning(true, 3L, true, 2L))
    assertTrue(OperatorBridgeActionPolicy.isEffectivelyRunning(true, 3L, true, 3L))
  }

  @Test
  fun staleStartAfterDisableAndMismatchedActionStateFailClosed() {
    assertFalse(
      OperatorBridgeActionPolicy.shouldApply(
        OperatorBridgeRequestedAction.START,
        expectedGeneration = 1L,
        currentGeneration = 2L,
        enabled = false,
      )
    )
    assertFalse(
      OperatorBridgeActionPolicy.shouldApply(
        OperatorBridgeRequestedAction.START,
        expectedGeneration = 2L,
        currentGeneration = 2L,
        enabled = false,
      )
    )
    assertTrue(
      OperatorBridgeActionPolicy.shouldApply(
        OperatorBridgeRequestedAction.STOP,
        expectedGeneration = 2L,
        currentGeneration = 2L,
        enabled = false,
      )
    )
    assertFalse(OperatorBridgeActionPolicy.isEffectivelyRunning(false, 2L, true, 2L))
  }
}
