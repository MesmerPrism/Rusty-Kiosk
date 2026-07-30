package io.github.mesmerprism.rustykiosk

import android.view.accessibility.AccessibilityEvent

/**
 * Keeps the guard on top-level state/focus transitions without reacting to window bookkeeping.
 *
 * Horizon can emit title, layer, bounds, added, and removed window changes after the target is
 * already focused. Those events are useful diagnostics, but they do not prove that another window
 * became active and should not consume another recovery attempt.
 */
internal object GuardWindowEventPolicy {
  private const val FOCUS_RELEVANT_CHANGES =
    AccessibilityEvent.WINDOWS_CHANGE_ACTIVE or AccessibilityEvent.WINDOWS_CHANGE_FOCUSED

  fun shouldObserve(eventType: Int, windowChanges: Int): Boolean =
    when (eventType) {
      AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> true
      AccessibilityEvent.TYPE_WINDOWS_CHANGED ->
        windowChanges == 0 || windowChanges and FOCUS_RELEVANT_CHANGES != 0
      else -> false
    }

  fun eventTypeName(eventType: Int): String =
    when (eventType) {
      AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "window-state-changed"
      AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "windows-changed"
      else -> "other"
  }
}
