package io.github.mesmerprism.rustykiosk.launcher.lite

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import app.cash.paparazzi.DeviceConfig
import app.cash.paparazzi.Paparazzi
import com.android.resources.Density
import com.android.resources.ScreenOrientation
import java.io.File
import java.security.MessageDigest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Actual production XML/resources and catalogue row adapter; selected-app state is synthetic. */
@RunWith(Parameterized::class)
class LiteNativePanelRenderTest(private val scenario: String, private val width: Int,
  private val height: Int, private val dpi: Int, private val scrollEnd: Boolean) {
  @get:Rule val paparazzi = Paparazzi(
    deviceConfig = DeviceConfig.PIXEL_5.copy(screenWidth = width, screenHeight = height,
      xdpi = dpi, ydpi = dpi, density = Density.create(dpi),
      orientation = ScreenOrientation.LANDSCAPE, softButtons = false),
    showSystemUi = false, useDeviceResolution = true, theme = "AppTheme")

  @Test fun selectedApp() {
    // Layoutlib preloads system typefaces independently of XML font streams.
    // Close the complete resolved runtime font input, including fonts.xml.
    val runtime = File(requireNotNull(System.getProperty("paparazzi.layoutlib.runtime.root"))).canonicalFile
    assertTrue("Bounded Layoutlib runtime version", runtime.name.matches(Regex("layoutlib-runtime-14\\.0\\.11-(win|linux|mac|mac-arm)")))
    val fontEvidence = File(requireNotNull(System.getProperty("lite.preview.evidence")), "fonts").apply { mkdirs() }
    for (font in File(runtime, "data/fonts").listFiles().orEmpty().filter { it.isFile }) {
      File(fontEvidence, "${font.name}.sha256").writeText(MessageDigest.getInstance("SHA-256").digest(font.readBytes()).joinToString("") { "%02x".format(it) })
    }
    val root = paparazzi.inflate<View>(R.layout.activity_rusty_launcher_lite)
    val stress = scenario.startsWith("compact")
    val app = LiteApp(if (stress) "Movement Studio — Extended Training and Demonstration Library for Quest" else "Movement Studio",
      "com.example.movement", "com.example.movement.MainActivity", "quest-vr")
    val store = LitePreferenceStore(paparazzi.context)
    val adapter = LiteAppAdapter(paparazzi.context, store)
    adapter.replace(listOf(app, LiteApp("Orbit Browser", "com.example.browser", "com.example.browser.MainActivity", "android-launcher"),
      LiteApp("Gallery", "com.example.gallery", "com.example.gallery.MainActivity", "android-launcher")))
    root.findViewById<ListView>(R.id.app_list).adapter = adapter
    root.findViewById<View>(R.id.empty_view).visibility = View.GONE
    text(root, R.id.detail_title, app.label)
    text(root, R.id.detail_package, paparazzi.context.getString(R.string.package_detail, app.packageName))
    text(root, R.id.detail_activity, paparazzi.context.getString(R.string.activity_detail, app.activityName))
    text(root, R.id.status, "Showing 3 of 3 apps • Wi-Fi on")
    text(root, R.id.presentation_button, paparazzi.context.getString(if (scenario == "immersive") R.string.window_mode else R.string.immersive_mode))
    text(root, R.id.full_kiosk_status, paparazzi.context.getString(R.string.full_kiosk_unavailable))
    val tags = root.findViewById<LinearLayout>(R.id.tag_chips)
    for (tag in listOf("demo", "movement")) tags.addView(Button(paparazzi.context).apply {
      isAllCaps = false; this.text = "$tag  ×"
    })
    text(root, R.id.launch_options_status, "Verified app launch options")
    val options = root.findViewById<LinearLayout>(R.id.launch_options_container)
    for (label in listOf("Guided movement", "Free exploration", "Training library", "Calibration")) {
      options.addView(Button(paparazzi.context).apply { isAllCaps = false; this.text = label })
    }
    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
      View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
    root.layout(0, 0, width, height)
    for (id in listOf(R.id.show_system_apps, R.id.show_internal_activities)) {
      val toggle = root.findViewById<TextView>(id)
      val rect = bounds(root, toggle)
      assertTrue("Toggle fully within viewport", rect.left >= 0 && rect.top >= 0 && rect.right <= width && rect.bottom <= height)
      val textWidth = toggle.paint.measureText(toggle.text.toString())
      assertTrue("Toggle label must fit", toggle.width - toggle.compoundPaddingLeft - toggle.compoundPaddingRight >= textWidth)
    }
    val launch = root.findViewById<Button>(R.id.launch_button)
    val scroll = root.findViewById<ScrollView>(R.id.lite_detail_scroll)
    assertTrue("Detail viewport must remain usable", scroll.height >= ((if (stress) 72 else 160) * dpi / 160))
    var ancestor = launch.parent
    while (ancestor is View) { assertFalse("Launch must remain outside detail scrolling", ancestor === scroll); ancestor = ancestor.parent }
    val before = bounds(root, launch)
    assertTrue("Launch has usable height", before.height() >= (48 * dpi / 160))
    assertTrue("Launch fully visible", before.left >= 0 && before.top >= 0 && before.right <= width && before.bottom <= height)
    assertTrue("Details need scrolling in stress fixture", scroll.getChildAt(0).height > scroll.height)
    if (scrollEnd) scroll.scrollTo(0, scroll.getChildAt(0).height)
    val after = bounds(root, launch)
    assertEquals("Launch must not move when details scroll", before, after)
    if (scrollEnd) assertTrue("Scroll-end evidence must move detail content", scroll.scrollY > 0)
    val evidence = File(requireNotNull(System.getProperty("lite.preview.evidence"))).apply { mkdirs() }
    File(evidence, "$scenario.json").writeText("""{"scenario":"$scenario","viewport_px":[$width,$height],"dpi":$dpi,"launch_rect_px":[${after.left},${after.top},${after.right},${after.bottom}],"launch_outside_detail_scroll":true,"launch_fully_visible":true,"detail_scroll_y_px":${scroll.scrollY},"detail_content_height_px":${scroll.getChildAt(0).height},"detail_viewport_height_px":${scroll.height}}""")
    paparazzi.snapshot(root, name = scenario)
  }
  private fun text(root: View, id: Int, value: String) { root.findViewById<TextView>(id).text = value }
  private fun bounds(root: View, child: View): Rect = Rect(0, 0, child.width, child.height).also {
    (root as ViewGroup).offsetDescendantRectToMyCoords(child, it)
  }
  companion object {
    @JvmStatic @Parameterized.Parameters(name = "{0}") fun scenarios() = listOf(
      arrayOf<Any>("window", 1024, 640, 160, false),
      arrayOf<Any>("immersive", 1872, 1116, 288, false),
      arrayOf<Any>("compact", 800, 480, 160, false),
      arrayOf<Any>("compact-scroll-end", 800, 480, 160, true))
  }
}
