package io.github.mesmerprism.rustykiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PackageSigningIdentityPolicyTest {
  @Test
  fun canonicalizesTheCompleteSigningLineageIndependentlyOfOrder() {
    val first = "first".toByteArray()
    val second = "second".toByteArray()

    assertEquals(
      PackageSigningIdentityPolicy.canonical(
        signatures = listOf(first, second),
        hasMultipleCurrentSigners = false,
      ),
      PackageSigningIdentityPolicy.canonical(
        signatures = listOf(second, first),
        hasMultipleCurrentSigners = false,
      ),
    )
  }

  @Test
  fun rejectsMissingAndMultipleCurrentSigners() {
    assertNull(
      PackageSigningIdentityPolicy.canonical(
        signatures = emptyList(),
        hasMultipleCurrentSigners = false,
      )
    )
    assertNull(
      PackageSigningIdentityPolicy.canonical(
        signatures = listOf("one".toByteArray(), "two".toByteArray()),
        hasMultipleCurrentSigners = true,
      )
    )
  }

  @Test
  fun detectsSigningLineageDrift() {
    val armed =
      PackageSigningIdentityPolicy.canonical(
        signatures = listOf("old".toByteArray()),
        hasMultipleCurrentSigners = false,
      )
    val replaced =
      PackageSigningIdentityPolicy.canonical(
        signatures = listOf("old".toByteArray(), "new".toByteArray()),
        hasMultipleCurrentSigners = false,
      )

    assertNotEquals(armed, replaced)
  }
}
