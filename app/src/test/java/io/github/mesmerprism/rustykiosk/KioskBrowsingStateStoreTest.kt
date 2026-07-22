package io.github.mesmerprism.rustykiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KioskBrowsingStateStoreTest {
  @Test
  fun browsingStateSurvivesAStoreRecreation() {
    val preferences = InMemoryBrowsingPreferences()
    val firstStore = KioskBrowsingStateStore(preferences)

    assertEquals("strobe", firstStore.setSearchQuery("strobe"))
    assertEquals("visual demos", firstStore.setSelectedTag("  Visual   Demos "))
    assertEquals(
      "package:io.github.example.strobe",
      firstStore.setSelectedKey("package:io.github.example.strobe"),
    )

    assertEquals(
      KioskBrowsingState(
        searchQuery = "strobe",
        selectedTag = "visual demos",
        selectedKey = "package:io.github.example.strobe",
      ),
      KioskBrowsingStateStore(preferences).load(),
    )
  }

  @Test
  fun explicitClearRemovesBothPersistedFilters() {
    val preferences = InMemoryBrowsingPreferences()
    val store = KioskBrowsingStateStore(preferences)
    store.setSearchQuery("browser")
    store.setSelectedTag("demo")

    assertEquals("", store.setSearchQuery(""))
    assertNull(store.setSelectedTag(null))

    assertEquals(KioskBrowsingState(), KioskBrowsingStateStore(preferences).load())
    assertEquals(emptyMap<String, String>(), preferences.values)
  }

  @Test
  fun restoredValuesRemainBoundedAndNormalized() {
    val preferences =
      InMemoryBrowsingPreferences(
        mutableMapOf(
          "search_query" to "x".repeat(240),
          "selected_tag" to "  LONG   FORM   TAG  ",
          "selected_key" to "k".repeat(400),
        )
      )

    val restored = KioskBrowsingStateStore(preferences).load()

    assertEquals(160, restored.searchQuery.length)
    assertEquals("long form tag", restored.selectedTag)
    assertEquals(320, restored.selectedKey?.length)
  }

  private class InMemoryBrowsingPreferences(
    val values: MutableMap<String, String> = mutableMapOf(),
  ) : KioskBrowsingPreferences {
    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String?) {
      if (value == null) values.remove(key) else values[key] = value
    }
  }
}
