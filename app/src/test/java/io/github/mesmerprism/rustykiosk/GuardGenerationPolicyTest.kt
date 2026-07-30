package io.github.mesmerprism.rustykiosk

import org.junit.Assert.assertEquals
import org.junit.Test

class GuardGenerationPolicyTest {
  @Test
  fun rejectsZeroAndThePreviousGeneration() {
    val candidates = ArrayDeque(listOf(0L, 42L, -7L))

    assertEquals(
      -7L,
      GuardGenerationPolicy.next(previousGeneration = 42L) {
        candidates.removeFirst()
      },
    )
  }

  @Test(expected = IllegalStateException::class)
  fun failsClosedWhenNoDistinctGenerationCanBeAllocated() {
    GuardGenerationPolicy.next(previousGeneration = 42L) { 42L }
  }
}
