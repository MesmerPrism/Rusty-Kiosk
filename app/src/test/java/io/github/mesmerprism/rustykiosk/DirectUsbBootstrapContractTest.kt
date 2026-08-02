package io.github.mesmerprism.rustykiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectUsbBootstrapContractTest {
  @Test
  fun fixedProviderAndBootstrapSchemasStayDisjoint() {
    assertEquals("rusty.kiosk.host_operator.v3", RustyKioskOperatorContract.SCHEMA)
    assertEquals(
      "rusty.kiosk.direct_usb_bootstrap.v1",
      RustyKioskOperatorContract.DIRECT_BOOTSTRAP_SCHEMA,
    )
    assertEquals("direct-status", RustyKioskOperatorContract.METHOD_DIRECT_STATUS)
    assertEquals("direct-enable", RustyKioskOperatorContract.METHOD_DIRECT_ENABLE)
    assertEquals("direct-disable", RustyKioskOperatorContract.METHOD_DIRECT_DISABLE)
  }

  @Test
  fun wireUsesLongGenerationSessionOwnershipAndNoPairingCodeField() {
    assertEquals("expected_bridge_generation", RustyKioskOperatorContract.EXTRA_EXPECTED_BRIDGE_GENERATION)
    assertEquals("session_id", RustyKioskOperatorContract.EXTRA_SESSION_ID)
    assertEquals("operation_id", RustyKioskOperatorContract.RESULT_OPERATION_ID)
    assertEquals("session_secret_base64", RustyKioskOperatorContract.RESULT_SESSION_SECRET_BASE64)
    assertEquals(32, OperatorBridgeSessionStore.SESSION_SECRET_BYTES)
    assertTrue(RustyKioskOperatorContract.RESULT_BRIDGE_GENERATION.contains("generation"))
    assertFalse(
      RustyKioskOperatorContract::class.java.declaredFields.any {
        it.name.contains("PAIRING", ignoreCase = true)
      }
    )
  }
}
