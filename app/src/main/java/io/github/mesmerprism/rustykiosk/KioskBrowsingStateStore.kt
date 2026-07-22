package io.github.mesmerprism.rustykiosk

import android.content.Context

internal data class KioskBrowsingState(
  val searchQuery: String = "",
  val selectedTag: String? = null,
  val selectedKey: String? = null,
)

internal interface KioskBrowsingPreferences {
  fun getString(key: String): String?

  fun putString(key: String, value: String?)
}

private class AndroidKioskBrowsingPreferences(context: Context) : KioskBrowsingPreferences {
  private val preferences =
    context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

  override fun getString(key: String): String? = preferences.getString(key, null)

  override fun putString(key: String, value: String?) {
    preferences.edit()
      .apply {
        if (value == null) remove(key) else putString(key, value)
      }
      .apply()
  }

  private companion object {
    const val PREFERENCES = "rusty_kiosk_browsing_state"
  }
}

internal class KioskBrowsingStateStore(
  private val preferences: KioskBrowsingPreferences,
) {
  constructor(context: Context) : this(AndroidKioskBrowsingPreferences(context))

  fun load(): KioskBrowsingState =
    KioskBrowsingState(
      searchQuery = sanitizeSearchQuery(preferences.getString(KEY_SEARCH_QUERY).orEmpty()),
      selectedTag = sanitizeTag(preferences.getString(KEY_SELECTED_TAG)),
      selectedKey = sanitizeSelectedKey(preferences.getString(KEY_SELECTED_KEY)),
    )

  fun setSearchQuery(value: String): String {
    val sanitized = sanitizeSearchQuery(value)
    preferences.putString(KEY_SEARCH_QUERY, sanitized.takeIf(String::isNotEmpty))
    return sanitized
  }

  fun setSelectedTag(value: String?): String? {
    val sanitized = sanitizeTag(value)
    preferences.putString(KEY_SELECTED_TAG, sanitized)
    return sanitized
  }

  fun setSelectedKey(value: String?): String? {
    val sanitized = sanitizeSelectedKey(value)
    preferences.putString(KEY_SELECTED_KEY, sanitized)
    return sanitized
  }

  private fun sanitizeSearchQuery(value: String): String = value.take(MAX_SEARCH_QUERY_LENGTH)

  private fun sanitizeTag(value: String?): String? =
    value?.let(::normalizeTag)?.takeIf(String::isNotEmpty)

  private fun sanitizeSelectedKey(value: String?): String? =
    value?.take(MAX_SELECTED_KEY_LENGTH)?.takeIf(String::isNotBlank)

  private companion object {
    const val KEY_SEARCH_QUERY = "search_query"
    const val KEY_SELECTED_TAG = "selected_tag"
    const val KEY_SELECTED_KEY = "selected_key"
    const val MAX_SEARCH_QUERY_LENGTH = 160
    const val MAX_SELECTED_KEY_LENGTH = 320
  }
}
