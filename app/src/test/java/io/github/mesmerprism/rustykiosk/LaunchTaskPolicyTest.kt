package io.github.mesmerprism.rustykiosk

import android.content.Intent
import org.junit.Assert.assertEquals
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
}
