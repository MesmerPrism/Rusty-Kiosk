package io.github.mesmerprism.rustykiosk

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuardWindowEventPolicyTest {
  @Test
  fun stateChangesAlwaysRemainObservable() {
    assertTrue(
      GuardWindowEventPolicy.shouldObserve(
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
        0,
      )
    )
  }

  @Test
  fun activeAndFocusedWindowChangesRemainObservable() {
    assertTrue(
      GuardWindowEventPolicy.shouldObserve(
        AccessibilityEvent.TYPE_WINDOWS_CHANGED,
        AccessibilityEvent.WINDOWS_CHANGE_ACTIVE,
      )
    )
    assertTrue(
      GuardWindowEventPolicy.shouldObserve(
        AccessibilityEvent.TYPE_WINDOWS_CHANGED,
        AccessibilityEvent.WINDOWS_CHANGE_FOCUSED,
      )
    )
  }

  @Test
  fun zeroFlagsStayConservativeButBookkeepingOnlyChangesAreIgnored() {
    assertTrue(
      GuardWindowEventPolicy.shouldObserve(
        AccessibilityEvent.TYPE_WINDOWS_CHANGED,
        0,
      )
    )
    assertFalse(
      GuardWindowEventPolicy.shouldObserve(
        AccessibilityEvent.TYPE_WINDOWS_CHANGED,
        AccessibilityEvent.WINDOWS_CHANGE_TITLE or AccessibilityEvent.WINDOWS_CHANGE_BOUNDS,
      )
    )
    assertFalse(
      GuardWindowEventPolicy.shouldObserve(
        AccessibilityEvent.TYPE_WINDOWS_CHANGED,
        AccessibilityEvent.WINDOWS_CHANGE_ADDED or AccessibilityEvent.WINDOWS_CHANGE_REMOVED,
      )
    )
  }

  @Test
  fun unrelatedAccessibilityEventsStayOutsideTheGuard() {
    assertFalse(
      GuardWindowEventPolicy.shouldObserve(
        AccessibilityEvent.TYPE_VIEW_CLICKED,
        0,
      )
    )
  }
}
