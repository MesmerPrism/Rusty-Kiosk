package io.github.mesmerprism.rustykiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RustyKioskCliProtocolTest {
  @Test
  fun commandWireNamesAreUnique() {
    val names = RustyKioskCliCommand.entries.map(RustyKioskCliCommand::wireName)
    assertEquals(names.size, names.toSet().size)
  }

  @Test
  fun parsesBoundedTypedCommand() {
    val request =
      RustyKioskCliProtocol.parse(
        requestId = "request_1234",
        command = "set-search",
        value = "browser",
      ).getOrThrow()
    assertEquals(RustyKioskCliCommand.SET_SEARCH, request.command)
    assertEquals("browser", request.value)
  }

  @Test
  fun preservesInternalSpacesInSelector() {
    val request =
      RustyKioskCliProtocol.parse(
        requestId = "request_1234",
        command = "select",
        value = "  Rusty Kiosk Example App  ",
      ).getOrThrow()
    assertEquals("Rusty Kiosk Example App", request.value)
  }

  @Test
  fun rejectsUnknownCommand() {
    assertTrue(
      RustyKioskCliProtocol.parse("request_1234", "raw-shell", "id").isFailure
    )
  }

  @Test
  fun rejectsValueForValueLessCommand() {
    assertTrue(
      RustyKioskCliProtocol.parse("request_1234", "launch-normal", "unexpected").isFailure
    )
  }

  @Test
  fun rejectsMissingRequiredValue() {
    assertTrue(RustyKioskCliProtocol.parse("request_1234", "select", null).isFailure)
  }

  @Test
  fun parsesBoundedOpaqueLaunchOption() {
    val request =
      RustyKioskCliProtocol.parse(
        requestId = "request_1234",
        command = "launch-option",
        value = "option.demo-loop",
      ).getOrThrow()
    assertEquals(RustyKioskCliCommand.LAUNCH_OPTION, request.command)
    assertEquals("option.demo-loop", request.value)
    val whitespaceBound =
      RustyKioskCliProtocol.parse(
        requestId = "request_1234",
        command = "launch-option",
        value = " playlist.with-significant-space ",
      ).getOrThrow()
    assertEquals(" playlist.with-significant-space ", whitespaceBound.value)
    assertTrue(
      RustyKioskCliProtocol.parse(
        "request_1234",
        "launch-option",
        "x".repeat(AppLaunchOptionsContract.MAX_OPTION_ID_LENGTH + 1),
      ).isFailure
    )
  }

  @Test
  fun rejectsInvalidRequestIdAndOversizedValue() {
    assertTrue(RustyKioskCliProtocol.parse("short", "status", null).isFailure)
    assertTrue(
      RustyKioskCliProtocol.parse("request_1234", "set-search", "x".repeat(161)).isFailure
    )
  }
}
