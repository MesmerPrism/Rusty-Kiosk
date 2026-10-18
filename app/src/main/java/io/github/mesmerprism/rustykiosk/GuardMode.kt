package io.github.mesmerprism.rustykiosk

internal enum class GuardMode(val id: String) {
  ACCESSIBILITY("accessibility"),
  VISIBILITY("visibility"),
  UNAVAILABLE("unavailable");

  companion object {
    fun fromId(value: String?): GuardMode =
      if (value == null) ACCESSIBILITY else entries.firstOrNull { it.id == value } ?: UNAVAILABLE
  }
}
