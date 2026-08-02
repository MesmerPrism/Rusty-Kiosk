package io.github.mesmerprism.rustykiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectUsbBootstrapContractTest {
  @Test
  fun fixedProviderAndBootstrapSchemasStayDisjoint() {
    assertEquals("rusty.kiosk.host_operator.v4", RustyKioskOperatorContract.SCHEMA)
    assertEquals(
      "rusty.kiosk.direct_usb_bootstrap.v2",
      RustyKioskOperatorContract.DIRECT_BOOTSTRAP_SCHEMA,
    )
    assertEquals("direct-status", RustyKioskOperatorContract.METHOD_DIRECT_STATUS)
    assertEquals("direct-enable", RustyKioskOperatorContract.METHOD_DIRECT_ENABLE)
    assertEquals("direct-disable", RustyKioskOperatorContract.METHOD_DIRECT_DISABLE)
    assertEquals(
      "direct-recover-disable",
      RustyKioskOperatorContract.METHOD_DIRECT_RECOVER_DISABLE,
    )
  }

  @Test
  fun wireUsesLongGenerationSessionOwnershipAndNoPairingCodeField() {
    assertEquals("expected_bridge_generation", RustyKioskOperatorContract.EXTRA_EXPECTED_BRIDGE_GENERATION)
    assertEquals("session_id", RustyKioskOperatorContract.EXTRA_SESSION_ID)
    assertEquals("operation_id", RustyKioskOperatorContract.RESULT_OPERATION_ID)
    assertEquals("session_secret_base64", RustyKioskOperatorContract.RESULT_SESSION_SECRET_BASE64)
    assertEquals(32, OperatorBridgeSessionStore.SESSION_SECRET_BYTES)
    assertEquals(4096, OperatorBridgeSessionStore.MAX_OPERATION_IDS_PER_EPOCH)
    assertEquals("rusty.kiosk.direct_operator.v2", OperatorBridgeSessionStore.CAPABILITY)
    assertTrue(
      OperatorBridgeSessionStore.CLEANUP_OWNERSHIP_LIFETIME_MS >
        OperatorBridgeSessionStore.SESSION_LIFETIME_MS
    )
    assertTrue(RustyKioskOperatorContract.RESULT_BRIDGE_GENERATION.contains("generation"))
    assertFalse(
      RustyKioskOperatorContract::class.java.declaredFields.any {
        it.name.contains("PAIRING", ignoreCase = true)
      }
    )
  }
}
