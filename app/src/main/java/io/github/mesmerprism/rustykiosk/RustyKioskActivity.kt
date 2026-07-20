package io.github.mesmerprism.rustykiosk

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import com.meta.spatial.compose.ComposeFeature
import com.meta.spatial.compose.ComposeViewPanelRegistration
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.Vector2
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.DpPerMeterDisplayOptions
import com.meta.spatial.toolkit.PanelDimensions
import com.meta.spatial.toolkit.PanelRegistration
import com.meta.spatial.toolkit.PanelRenderMode
import com.meta.spatial.toolkit.PanelStyleOptions
import com.meta.spatial.toolkit.QuadShapeOptions
import com.meta.spatial.toolkit.Scale
import com.meta.spatial.toolkit.Transform
import com.meta.spatial.toolkit.UIPanelRenderOptions
import com.meta.spatial.toolkit.UIPanelSettings
import com.meta.spatial.toolkit.Visible
import com.meta.spatial.toolkit.createPanelEntity
import com.meta.spatial.vr.LocomotionControls
import com.meta.spatial.vr.LocomotionSystem
import com.meta.spatial.vr.VRFeature
import com.meta.spatial.vr.VrInputSystemType

class RustyKioskActivity : AppSystemActivity() {
  private val installedApps by lazy(LazyThreadSafetyMode.NONE) { InstalledAppRepository(this) }
  private val tagStore by lazy(LazyThreadSafetyMode.NONE) { TagFileStore(this) }
  private val launchController by lazy(LazyThreadSafetyMode.NONE) { LaunchController(this) }
  private var panelEntity: Entity? = null
  private var state by mutableStateOf(KioskUiState())

  override fun registerFeatures(): List<SpatialFeature> =
    listOf(
      VRFeature(
        this,
        LocomotionControls.Right,
        false,
        VrInputSystemType.INTERACTION_SDK,
      ),
      ComposeFeature(),
    )

  override fun onCreate(savedInstanceState: Bundle?) {
    val launchIntent = intent
    super.onCreate(savedInstanceState)
    runCatching { systemManager.unregisterSystem<LocomotionSystem>() }
    launchController.disarm(
      if (launchIntent.action == GuardContract.ACTION_RETURN_TO_KIOSK) {
        "triple-home-return-created"
      } else {
        "rusty-kiosk-created"
      }
    )
    refreshCatalogue("activity-created")
    tagStore.startWatching {
      runOnUiThread { refreshCatalogue("tag-file-hotload") }
    }
  }

  override fun onNewIntent(intent: Intent) {
    if (intent.action == GuardContract.ACTION_RETURN_TO_KIOSK) {
      setIntent(mainIntent())
      launchController.disarm("triple-home-return")
      super.onNewIntent(intent)
      restartFreshSpatialTask()
      return
    }
    setIntent(intent)
    super.onNewIntent(intent)
    refreshCatalogue("new-intent")
  }

  override fun onResume() {
    super.onResume()
    launchController.disarm("rusty-kiosk-resumed")
    refreshCatalogue("activity-resumed")
  }

  override fun onDestroy() {
    tagStore.stopWatching()
    super.onDestroy()
  }

  override fun onSceneReady() {
    super.onSceneReady()
    scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
    scene.setViewOrigin(0.0f, 0.0f, VIEW_ORIGIN_Z_METERS, 180.0f)
    scene.enablePassthrough(true)
    panelEntity =
      Entity.createPanelEntity(
        R.id.kiosk_panel,
        Transform(
          Pose(
            Vector3(0.0f, PANEL_CENTER_Y_METERS, PANEL_Z_METERS),
            Quaternion(0.0f, 0.0f, 1.0f, 0.0f),
          )
        ),
        PanelDimensions(Vector2(PANEL_WIDTH_METERS, PANEL_HEIGHT_METERS)),
        Scale(Vector3(1.0f, 1.0f, 1.0f)),
        Visible(true),
      )
  }

  override fun registerPanels(): List<PanelRegistration> =
    listOf(
      ComposeViewPanelRegistration(
        R.id.kiosk_panel,
        composeViewCreator = { _, context ->
          ComposeView(context).apply {
            setBackgroundColor(AndroidColor.rgb(25, 25, 25))
            alpha = 1.0f
            setWillNotDraw(false)
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            setContent {
              MaterialTheme(colorScheme = kioskColorScheme()) {
                RustyKioskPanel(
                  state = state,
                  onSearchChanged = { query -> state = state.copy(searchQuery = query) },
                  onTagSelected = { tag -> state = state.copy(selectedTag = tag) },
                  onAppSelected = { key -> state = state.copy(selectedKey = key) },
                  onRefresh = { refreshCatalogue("panel-refresh") },
                  onAddTag = ::addTag,
                  onRemoveTag = ::removeTag,
                  onNormalLaunch = { launchSelected(LaunchKind.NORMAL) },
                  onKioskLaunch = { launchSelected(LaunchKind.KIOSK) },
                  onOpenAccessibilitySettings = ::openAccessibilitySettings,
                )
              }
            }
          }
        },
        settingsCreator = {
          UIPanelSettings(
            shape = QuadShapeOptions(width = PANEL_WIDTH_METERS, height = PANEL_HEIGHT_METERS),
            style = PanelStyleOptions(themeResourceId = R.style.PanelAppThemeOpaque),
            display = DpPerMeterDisplayOptions(dpPerMeter = PANEL_DP_PER_METER),
            rendering = UIPanelRenderOptions(PanelRenderMode.Layer()),
          )
        },
        panelSetupWithComposeView = { _, panel, _ -> panel.layer?.setZIndex(PANEL_LAYER_Z_INDEX) },
      )
    )

  private fun refreshCatalogue(source: String) {
    val previousSelection = state.selectedKey
    val result =
      runCatching {
        val records = tagStore.load()
        val entries = CatalogAssembler.assemble(installedApps.snapshot(), records)
        val selected = previousSelection?.takeIf { key -> entries.any { it.key == key } }
          ?: entries.firstOrNull()?.key
        KioskUiState(
          entries = entries,
          searchQuery = state.searchQuery,
          selectedTag = state.selectedTag?.takeIf { selectedTag ->
            entries.any { selectedTag in it.tags }
          },
          selectedKey = selected,
          statusLine = "${entries.count { it.installed }} installed · ${entries.count { !it.installed }} not installed · $source",
          tagFilePath = tagStore.tagFile.absolutePath,
          guardEnabled = isGuardEnabled(),
        )
      }
    state =
      result.getOrElse { throwable ->
        state.copy(
          statusLine = "Catalogue reload failed: ${throwable.message ?: throwable.javaClass.simpleName}",
          tagFilePath = tagStore.tagFile.absolutePath,
          guardEnabled = isGuardEnabled(),
        )
      }
  }

  private fun addTag(value: String) {
    val entry = state.selectedEntry ?: return
    val tag = normalizeTag(value)
    if (tag.isEmpty()) {
      state = state.copy(statusLine = "Enter a tag first.")
      return
    }
    runCatching { tagStore.setTags(entry, entry.tags + tag) }
      .onSuccess { refreshCatalogue("tag-added") }
      .onFailure { throwable ->
        state = state.copy(statusLine = "Could not save tag: ${throwable.message}")
      }
  }

  private fun removeTag(tag: String) {
    val entry = state.selectedEntry ?: return
    runCatching { tagStore.setTags(entry, entry.tags - normalizeTag(tag)) }
      .onSuccess { refreshCatalogue("tag-removed") }
      .onFailure { throwable ->
        state = state.copy(statusLine = "Could not remove tag: ${throwable.message}")
      }
  }

  private fun launchSelected(kind: LaunchKind) {
    val entry = state.selectedEntry ?: return
    val result = launchController.launch(entry, kind, state.guardEnabled)
    state = state.copy(statusLine = result.message)
  }

  private fun openAccessibilitySettings() {
    runCatching {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
      }
      .onFailure {
        state = state.copy(statusLine = "Accessibility settings are unavailable here; use the attended setup helper.")
      }
  }

  private fun isGuardEnabled(): Boolean {
    val manager = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    return manager
      .getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
      .any { service ->
        val info = service.resolveInfo.serviceInfo
        info.packageName == packageName &&
          (info.name == KioskAccessibilityService::class.java.name ||
            packageName + info.name == KioskAccessibilityService::class.java.name)
      }
  }

  private fun restartFreshSpatialTask() {
    val appContext = applicationContext
    Handler(Looper.getMainLooper()).postDelayed(
      {
        appContext.startActivity(
          mainIntent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
      },
      RETURN_RESTART_DELAY_MS,
    )
    finishAndRemoveTask()
  }

  private fun mainIntent(): Intent =
    Intent(Intent.ACTION_MAIN, null, this, RustyKioskActivity::class.java)
      .addCategory(Intent.CATEGORY_LAUNCHER)

  private companion object {
    const val PANEL_WIDTH_METERS = 1.55f
    const val PANEL_HEIGHT_METERS = 1.05f
    const val PANEL_CENTER_Y_METERS = 1.32f
    const val PANEL_Z_METERS = 0.50f
    const val VIEW_ORIGIN_Z_METERS = 2.0f
    const val PANEL_DP_PER_METER = 700.0f
    const val PANEL_LAYER_Z_INDEX = 99
    const val RETURN_RESTART_DELAY_MS = 500L
  }
}

private fun kioskColorScheme() =
  darkColorScheme(
    background = Color(0xFF191919),
    surface = Color(0xFF242424),
    surfaceVariant = Color(0xFF30302E),
    primary = Color(0xFFE28B45),
    onPrimary = Color(0xFF211307),
    secondary = Color(0xFFB9B3A8),
    onSecondary = Color(0xFF1D1B18),
    onBackground = Color(0xFFF2EFE9),
    onSurface = Color(0xFFF2EFE9),
    onSurfaceVariant = Color(0xFFC9C4BA),
    error = Color(0xFFFFB4A8),
  )
