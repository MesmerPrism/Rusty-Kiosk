package io.github.mesmerprism.rustykiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RustyKioskPassthroughLutTest {
  @Test
  fun naturalStylePreservesRgbEndpoints() {
    assertEquals(
      RustyKioskPassthroughLut.Color(0, 0, 0),
      RustyKioskPassthroughLut.mapping(KioskPassthroughStyle.NATURAL, 0, 0, 0),
    )
    assertEquals(
      RustyKioskPassthroughLut.Color(255, 255, 255),
      RustyKioskPassthroughLut.mapping(KioskPassthroughStyle.NATURAL, 15, 15, 15),
    )
    assertEquals(
      RustyKioskPassthroughLut.Color(255, 0, 0),
      RustyKioskPassthroughLut.mapping(KioskPassthroughStyle.NATURAL, 15, 0, 0),
    )
  }

  @Test
  fun contourStyleCreatesHardLuminanceBands() {
    val dark =
      RustyKioskPassthroughLut.mapping(KioskPassthroughStyle.CONTOUR_LUT, 0, 0, 0)
    val mid =
      RustyKioskPassthroughLut.mapping(KioskPassthroughStyle.CONTOUR_LUT, 8, 8, 8)
    val bright =
      RustyKioskPassthroughLut.mapping(KioskPassthroughStyle.CONTOUR_LUT, 15, 15, 15)

    assertEquals(RustyKioskPassthroughLut.Color(0, 0, 0), dark)
    assertNotEquals(dark, mid)
    assertNotEquals(mid, bright)
  }

  @Test
  fun unknownStoredStyleFallsBackToNatural() {
    assertEquals(KioskPassthroughStyle.NATURAL, KioskPassthroughStyle.parse("future-style"))
  }
}
