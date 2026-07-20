package io.github.mesmerprism.rustykiosk

import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import org.junit.Rule
import org.junit.Test

class RustyKioskNativePanelRenderTest {
  @get:Rule
  val paparazzi =
    Paparazzi(
      deviceConfig =
        DeviceConfig.PIXEL_5.copy(
          screenWidth = RustyKioskPanelGeometry.RASTER_WIDTH_PX,
          screenHeight = RustyKioskPanelGeometry.RASTER_HEIGHT_PX,
          xdpi = RustyKioskPanelGeometry.LAYOUT_DPI,
          ydpi = RustyKioskPanelGeometry.LAYOUT_DPI,
          orientation = ScreenOrientation.LANDSCAPE,
          density = Density.create(RustyKioskPanelGeometry.LAYOUT_DPI),
          softButtons = false,
        ),
      showSystemUi = false,
      useDeviceResolution = true,
      theme = "android:style/Theme.Material.NoActionBar",
    )

  @Test
  fun catalogReady() = snapshot("catalog-ready", PreviewFixtures.catalogReady())

  @Test
  fun tagFilterMissing() =
    snapshot("tag-filter-missing", PreviewFixtures.tagFilterMissing())

  @Test
  fun guardSetup() = snapshot("guard-setup", PreviewFixtures.guardSetup())

  private fun snapshot(name: String, state: KioskUiState) {
    paparazzi.snapshot(name = name) {
      RustyKioskTheme {
        RustyKioskPanel(
          state = state,
          onSearchChanged = {},
          onTagSelected = {},
          onAppSelected = {},
          onRefresh = {},
          onAddTag = {},
          onRemoveTag = {},
          onNormalLaunch = {},
          onKioskLaunch = {},
          onOpenAccessibilitySettings = {},
        )
      }
    }
  }
}

private object PreviewFixtures {
  private val browser =
    CatalogEntry(
      key = "package:com.example.browser",
      label = "Orbit Browser",
      packageName = "com.example.browser",
      target = target("com.example.browser"),
      installed = true,
      tags = setOf("onboarding", "web"),
      source = "android-launcher",
    )
  private val movement =
    CatalogEntry(
      key = "package:io.example.movement",
      label = "Movement Demo",
      packageName = "io.example.movement",
      target = target("io.example.movement"),
      installed = true,
      tags = setOf("demo", "movement"),
      source = "quest-vr",
    )
  private val gallery =
    CatalogEntry(
      key = "package:com.example.gallery",
      label = "System Gallery",
      packageName = "com.example.gallery",
      target = null,
      installed = true,
      tags = setOf("utilities"),
      source = "tag-file-installed-package",
    )
  private val planned =
    CatalogEntry(
      key = "missing-name:training library",
      label = "Training Library",
      packageName = null,
      target = null,
      installed = false,
      tags = setOf("onboarding"),
      source = "tag-file",
    )

  fun catalogReady() = state(selectedKey = browser.key, guardEnabled = true)

  fun tagFilterMissing() =
    state(selectedKey = planned.key, selectedTag = "onboarding", guardEnabled = true)

  fun guardSetup() = state(selectedKey = movement.key, guardEnabled = false)

  private fun state(
    selectedKey: String,
    selectedTag: String? = null,
    guardEnabled: Boolean,
  ) =
    KioskUiState(
      entries = listOf(browser, movement, gallery, planned),
      selectedTag = selectedTag,
      selectedKey = selectedKey,
      statusLine = "3 installed · 1 not installed · synthetic preview",
      tagFilePath =
        "/sdcard/Android/data/io.github.mesmerprism.rustykiosk/files/tags/app-tags.v1.json",
      guardEnabled = guardEnabled,
    )

  private fun target(packageName: String) =
    LaunchTarget(
      packageName = packageName,
      activityName = "$packageName.MainActivity",
      action = "android.intent.action.MAIN",
      categories = setOf("android.intent.category.LAUNCHER"),
    )
}
