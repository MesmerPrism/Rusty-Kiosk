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
  fun rejectsInvalidRequestIdAndOversizedValue() {
    assertTrue(RustyKioskCliProtocol.parse("short", "status", null).isFailure)
    assertTrue(
      RustyKioskCliProtocol.parse("request_1234", "set-search", "x".repeat(161)).isFailure
    )
  }
}
