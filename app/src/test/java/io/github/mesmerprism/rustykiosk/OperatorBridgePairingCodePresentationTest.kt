package io.github.mesmerprism.rustykiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class OperatorBridgePairingCodePresentationTest {
  @Test
  fun pairingCodeIsMaskedByDefaultAndVisibleOnlyOnExplicitToggle() {
    val code = "ABCD-EFGH-JKLM"
    val masked = OperatorBridgePairingCodePresentation.render(code, visible = false)
    assertFalse(masked.contains("ABCD"))
    assertEquals("••••-••••-••••", masked)
    assertEquals(code, OperatorBridgePairingCodePresentation.render(code, visible = true))
  }
}
