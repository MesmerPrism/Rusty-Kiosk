package io.github.mesmerprism.rustykiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLaunchOptionsValidationPolicyTest {
  @Test
  fun acceptsExactBoundedRows() {
    val options =
      AppLaunchOptionsValidationPolicy.validateRows(
        listOf(RawAppLaunchOption(1, "playlist.one", "One", "Loop once"))
      )
    assertEquals("playlist.one", options.single().optionId)
  }

  @Test
  fun rejectsSchemaDuplicatesAndOversizedFields() {
    assertTrue(
      runCatching {
        AppLaunchOptionsValidationPolicy.validateRows(
          listOf(RawAppLaunchOption(2, "playlist.one", "One", ""))
        )
      }.isFailure
    )
    assertTrue(
      runCatching {
        AppLaunchOptionsValidationPolicy.validateRows(
          List(AppLaunchOptionsContract.MAX_OPTION_COUNT + 1) { index ->
            RawAppLaunchOption(1, "playlist.$index", "Option $index", "")
          }
        )
      }.isFailure
    )
    assertTrue(
      runCatching {
        AppLaunchOptionsValidationPolicy.validateRows(
          listOf(
            RawAppLaunchOption(1, "playlist.one", "One", ""),
            RawAppLaunchOption(1, "playlist.one", "Again", ""),
          )
        )
      }.isFailure
    )
    assertTrue(
      runCatching {
        AppLaunchOptionsValidationPolicy.validateRows(
          listOf(RawAppLaunchOption(1, "x".repeat(161), "One", ""))
        )
      }.isFailure
    )
  }

  @Test
  fun metadataMustBindExactPackageAuthorityAndCurrentFrontDoor() {
    assertNull(
      AppLaunchOptionsValidationPolicy.validateMetadata(
        "example.app",
        "example.app.MainActivity",
        null,
        null,
        null,
      )
    )
    val valid =
      AppLaunchOptionsValidationPolicy.validateMetadata(
        "example.app",
        "example.app.MainActivity",
        AppLaunchOptionsContract.SCHEMA,
        "example.app.app-launch-options",
        "example.app.MainActivity",
      )
    assertEquals("example.app.MainActivity", valid?.third)
    assertTrue(
      runCatching {
        AppLaunchOptionsValidationPolicy.validateMetadata(
          "example.app",
          "example.app.MainActivity",
          AppLaunchOptionsContract.SCHEMA,
          "attacker.app.app-launch-options",
          "attacker.app.Activity",
        )
      }.isFailure
    )
  }
}
