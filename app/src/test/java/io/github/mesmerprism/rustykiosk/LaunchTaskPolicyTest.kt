package io.github.mesmerprism.rustykiosk

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchTaskPolicyTest {
  @Test
  fun normalLaunchMayResumeAnExistingTask() {
    assertEquals(
      Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
      LaunchTaskPolicy.initialFlags(LaunchKind.NORMAL),
    )
  }

  @Test
  fun initialKioskLaunchStartsAFreshTask() {
    assertEquals(
      Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK,
      LaunchTaskPolicy.initialFlags(LaunchKind.KIOSK),
    )
  }

  @Test
  fun guardRecoveryReusesTheExistingTaskWithoutAnimation() {
    val flags = GuardLaunchTaskPolicy.recoveryFlags()
    assertTrue(flags and Intent.FLAG_ACTIVITY_REORDER_TO_FRONT != 0)
    assertTrue(flags and Intent.FLAG_ACTIVITY_NO_ANIMATION != 0)
    assertTrue(flags and Intent.FLAG_ACTIVITY_CLEAR_TASK == 0)
  }
}
