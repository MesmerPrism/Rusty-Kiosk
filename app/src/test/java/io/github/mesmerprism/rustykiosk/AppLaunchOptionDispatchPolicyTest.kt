package io.github.mesmerprism.rustykiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLaunchOptionDispatchPolicyTest {
  @Test
  fun createsOnlyExactFrontDoorAndOpaqueIdPlan() {
    val plan = AppLaunchOptionDispatchPolicy.create(entry(), binding(), option())
    assertEquals("example.app", plan.target.packageName)
    assertEquals("example.app.MainActivity", plan.target.activityName)
    assertEquals(" option.exact ", plan.optionId)
  }

  @Test
  fun rejectsPackageActivityAndOptionSubstitution() {
    assertTrue(
      runCatching {
        AppLaunchOptionDispatchPolicy.create(
          entry().copy(packageName = "attacker.app"),
          binding(),
          option(),
        )
      }.isFailure
    )
    assertTrue(
      runCatching {
        AppLaunchOptionDispatchPolicy.create(
          entry().copy(target = entry().target?.copy(activityName = "example.app.OtherActivity")),
          binding(),
          option(),
        )
      }.isFailure
    )
    assertTrue(
      runCatching {
        AppLaunchOptionDispatchPolicy.create(entry(), binding(), option().copy(optionId = " "))
      }.isFailure
    )
  }

  private fun entry() =
    CatalogEntry(
      key = "package:example.app",
      label = "Example",
      packageName = "example.app",
      target =
        LaunchTarget(
          packageName = "example.app",
          activityName = "example.app.MainActivity",
          action = "android.intent.action.MAIN",
          categories = setOf("android.intent.category.LAUNCHER"),
        ),
      installed = true,
      tags = emptySet(),
      source = "test",
    )

  private fun binding() =
    AppLaunchOptionsBinding(
      packageName = "example.app",
      uid = 10001,
      signingIdentity = "a".repeat(64),
      lastUpdateTime = 1L,
      versionCode = 1L,
      providerAuthority = "example.app.app-launch-options",
      providerClass = "example.app.OptionsProvider",
      ownerActivity = "example.app.MainActivity",
    )

  private fun option() = AppLaunchOption(1, " option.exact ", "Exact", "")
}
