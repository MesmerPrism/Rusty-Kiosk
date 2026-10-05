package io.github.mesmerprism.rustykiosk.launcher.lite

import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.WindowManager
import com.meta.spatial.core.Entity
import com.meta.spatial.core.Pose
import com.meta.spatial.core.Quaternion
import com.meta.spatial.core.SpatialFeature
import com.meta.spatial.core.SpatialSDKExperimentalAPI
import com.meta.spatial.core.Vector2
import com.meta.spatial.core.Vector3
import com.meta.spatial.runtime.ReferenceSpace
import com.meta.spatial.toolkit.AppSystemActivity
import com.meta.spatial.toolkit.DpPerMeterDisplayOptions
import com.meta.spatial.toolkit.Grabbable
import com.meta.spatial.toolkit.GrabbableType
import com.meta.spatial.toolkit.LayoutXMLPanelRegistration
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
import com.meta.spatial.vr.VRFeature
import com.meta.spatial.vr.VrInputSystemType

/** Immersive host for the same native Lite panel; owns only scene/presentation resources. */
class RustyLauncherLiteSpatialActivity : AppSystemActivity(), LitePresentationHost {
  private var panel: LitePanelController? = null
  private var panelRoot: View? = null
  private var panelEntity: Entity? = null
  private var resumed = false
  private var shuttingDown = false

  override fun registerFeatures(): List<SpatialFeature> =
    listOf(VRFeature(this, LocomotionControls.Right, false, VrInputSystemType.INTERACTION_SDK))

  override fun registerPanels(): List<PanelRegistration> = listOf(
    LayoutXMLPanelRegistration(
      R.id.lite_spatial_panel,
      layoutIdCreator = { R.layout.activity_rusty_launcher_lite },
      settingsCreator = {
        UIPanelSettings(
          shape = QuadShapeOptions(width = WIDTH_METERS, height = HEIGHT_METERS),
          display = DpPerMeterDisplayOptions(dpPerMeter = 800f),
          style = PanelStyleOptions(themeResourceId = R.style.AppTheme),
          rendering = UIPanelRenderOptions(PanelRenderMode.Layer()),
        )
      },
      panelSetupWithRootView = { root, _, _ ->
        if (!shuttingDown) {
          panel?.release()
          panelRoot = root
          panel = LitePanelController(this, root, this)
          if (resumed) panel?.onResume()
          Log.i(TAG, "event=panel-bound mode=immersive")
        }
      },
    )
  )

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    // Same supported keyboard-window compatibility route as the main Kiosk panel.
    window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
  }

  @OptIn(SpatialSDKExperimentalAPI::class)
  override fun onSceneReady() {
    super.onSceneReady()
    if (shuttingDown || panelEntity != null) return
    scene.setReferenceSpace(ReferenceSpace.LOCAL_FLOOR)
    scene.enablePassthrough(true)
    val viewer = runCatching { scene.getViewerPose() }.getOrNull()
    val forward = viewer?.forward()
    val placement = LiteSpatialPlacement.resolve(
      viewer?.t?.x ?: 0f, viewer?.t?.y ?: 1.6f, viewer?.t?.z ?: 0f,
      forward?.x ?: 0f, forward?.z ?: -1f,
    )
    panelEntity = Entity.createPanelEntity(
      R.id.lite_spatial_panel,
      Transform(Pose(Vector3(placement.x, placement.y, placement.z),
        Quaternion.fromDirection(Vector3(placement.forwardX, 0f, placement.forwardZ), Vector3(0f, 1f, 0f)))),
      PanelDimensions(Vector2(WIDTH_METERS, HEIGHT_METERS)),
      Scale(Vector3(1f, 1f, 1f)),
      Grabbable(enabled = true, type = GrabbableType.PIVOT_Y, minHeight = 0.5f, maxHeight = 2.5f),
      Visible(true),
    )
    Log.i(TAG, "event=scene-ready mode=immersive viewerRelative=true grabbable=true")
  }

  override fun onResume() {
    super.onResume()
    resumed = true
    panel?.onResume()
  }

  override fun onPause() {
    resumed = false
    panel?.onPause()
    super.onPause()
  }

  override fun onWindowFocusChanged(hasFocus: Boolean) {
    super.onWindowFocusChanged(hasFocus)
    if (!hasFocus) panel?.onFocusLost()
  }

  override fun onVRPause() {
    panel?.onFocusLost()
    super.onVRPause()
  }

  private fun releasePresentation() {
    if (shuttingDown) return
    shuttingDown = true
    resumed = false
    panel?.release()
    panel = null
    panelRoot = null
    panelEntity?.destroy()
    panelEntity = null
  }

  override fun onSpatialShutdown() {
    releasePresentation()
    super.onSpatialShutdown()
  }

  override fun onDestroy() {
    releasePresentation()
    super.onDestroy()
  }

  override fun isImmersive(): Boolean = true
  override fun mode(): String = "immersive"
  override fun switchPresentation() = LiteHybridNavigator.launchWindow(this)

  companion object {
    private const val TAG = "RustyLauncherLite"
    private const val WIDTH_METERS = 1.3f
    private const val HEIGHT_METERS = 0.775f
  }
}
