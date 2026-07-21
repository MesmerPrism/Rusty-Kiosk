package io.github.mesmerprism.rustykiosk.setuphelper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityServiceListTest {
  private val target = SetupContract.ACCESSIBILITY_COMPONENT
  private val other = "example.other/example.other.Service"

  @Test
  fun enablePreservesOtherServicesAndIsIdempotent() {
    assertEquals("$other:$target", AccessibilityServiceList.enable(other, target))
    assertEquals("$other:$target", AccessibilityServiceList.enable("$other:$target", target))
  }

  @Test
  fun disableRemovesOnlyRustyKiosk() {
    assertEquals(other, AccessibilityServiceList.disable("$target:$other", target))
    assertEquals(other, AccessibilityServiceList.disable(other, target))
    assertEquals(null, AccessibilityServiceList.disable(target, target))
  }

  @Test
  fun parsingRejectsEmptySegmentsAndDeduplicates() {
    assertEquals(listOf(other, target), AccessibilityServiceList.parse(":$other::$target:$other:"))
    assertTrue(AccessibilityServiceList.contains("$other:$target", target))
    assertFalse(AccessibilityServiceList.contains(other, target))
  }
}
