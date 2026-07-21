package io.github.mesmerprism.rustykiosk

import android.content.Context
import com.meta.spatial.core.Lut
import com.meta.spatial.runtime.Scene
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Public, effect-agnostic styles for the passthrough layer submitted by Spatial SDK.
 *
 * The contour style uses hard luminance bands in a point LUT. It makes scene boundaries easier
 * to see, but it is not neighborhood edge detection and it does not create a second OpenXR
 * passthrough layer outside Spatial SDK's frame submission.
 */
internal object RustyKioskPassthroughLut {
  const val DIMENSION = 16
  const val ENTRY_COUNT = DIMENSION * DIMENSION * DIMENSION

  internal data class Color(val red: Int, val green: Int, val blue: Int)

  fun create(style: KioskPassthroughStyle): Lut {
    val lut = Lut(DIMENSION)
    for (sourceBlue in 0 until DIMENSION) {
      for (sourceGreen in 0 until DIMENSION) {
        for (sourceRed in 0 until DIMENSION) {
          val output = mapping(style, sourceRed, sourceGreen, sourceBlue)
          lut.setMapping(
            sourceRed,
            sourceGreen,
            sourceBlue,
            output.red,
            output.green,
            output.blue,
          )
        }
      }
    }
    return lut
  }

  fun mapping(
    style: KioskPassthroughStyle,
    sourceRed: Int,
    sourceGreen: Int,
    sourceBlue: Int,
  ): Color {
    val red = normalize(sourceRed)
    val green = normalize(sourceGreen)
    val blue = normalize(sourceBlue)
    if (style == KioskPassthroughStyle.NATURAL) {
      return Color(toByte(red), toByte(green), toByte(blue))
    }

    val luma = (0.2126f * red + 0.7152f * green + 0.0722f * blue).coerceIn(0.0f, 1.0f)
    return when {
      luma <= 0.055f -> Color(0, 0, 0)
      luma < 0.23f -> Color(0, 220, 35)
      luma < 0.42f -> Color(250, 230, 0)
      luma < 0.61f -> Color(255, 70, 0)
      luma < 0.80f -> Color(235, 0, 210)
      else -> Color(15, 180, 255)
    }
  }

  private fun normalize(value: Int): Float =
    value.coerceIn(0, DIMENSION - 1) / (DIMENSION - 1).toFloat()

  private fun toByte(value: Float): Int = (value * 255.0f).roundToInt().coerceIn(0, 255)
}

internal class KioskPassthroughSettings(context: Context) {
  private val preferences =
    context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

  fun load(): KioskPassthroughStyle =
    KioskPassthroughStyle.parse(preferences.getString(KEY_STYLE, null))

  fun save(style: KioskPassthroughStyle) {
    preferences.edit().putString(KEY_STYLE, style.wireName).apply()
  }

  private companion object {
    const val PREFERENCES = "rusty_kiosk_passthrough"
    const val KEY_STYLE = "style"
  }
}

internal class RustyKioskPassthroughController(
  private val scene: Scene,
  private val marker: (String) -> Unit,
) {
  // Spatial SDK consumes this LUT asynchronously. Keep the object alive for the active style.
  private var activeLut: Lut? = null
  private var style = KioskPassthroughStyle.NATURAL
  private var lutApplied = false

  fun apply(requestedStyle: KioskPassthroughStyle, source: String): KioskPassthroughState {
    style = requestedStyle
    return runCatching {
        scene.enablePassthrough(true)
        scene.setPassthroughLUT(null)
        activeLut = null
        val lut = RustyKioskPassthroughLut.create(requestedStyle)
        activeLut = lut
        scene.setPassthroughLUT(lut)
        lutApplied = true
        snapshot(source, emitMarker = true)
      }
      .getOrElse { error ->
        activeLut = null
        lutApplied = false
        KioskPassthroughState(
            style = style,
            systemPassthroughEnabled = safeSystemPassthroughEnabled(),
            lutApplied = false,
            message =
              "Passthrough style could not be applied: ${error.message ?: error.javaClass.simpleName}",
          )
          .also { state -> marker(failedMarker(source, state, error)) }
      }
  }

  fun snapshot(source: String, emitMarker: Boolean = false): KioskPassthroughState {
    val enabled = safeSystemPassthroughEnabled()
    val state =
      KioskPassthroughState(
        style = style,
        systemPassthroughEnabled = enabled,
        lutApplied = lutApplied,
        message =
          if (enabled && lutApplied) {
            "System passthrough is active with the ${style.label} style."
          } else {
            "System passthrough is still settling or unavailable on this runtime."
          },
      )
    if (emitMarker) marker(appliedMarker(source, state))
    return state
  }

  fun stop(source: String) {
    runCatching { scene.setPassthroughLUT(null) }
      .onFailure { error ->
        marker(
          failedMarker(
            source,
            KioskPassthroughState(
              style = style,
              systemPassthroughEnabled = safeSystemPassthroughEnabled(),
              lutApplied = lutApplied,
            ),
            error,
          )
        )
      }
    activeLut = null
    lutApplied = false
  }

  private fun safeSystemPassthroughEnabled(): Boolean =
    runCatching { scene.isSystemPassthroughEnabled() }.getOrDefault(false)

  private fun appliedMarker(source: String, state: KioskPassthroughState): String =
    "channel=rusty-kiosk-passthrough status=applied " +
      "source=${markerToken(source)} style=${state.style.wireName} " +
      "passthroughOwner=spatial-sdk-system-passthrough " +
      "passthroughApi=Scene.enablePassthrough+Scene.setPassthroughLUT " +
      "systemPassthroughEnabled=${state.systemPassthroughEnabled} " +
      "lutApplied=${state.lutApplied} lutDimension=${RustyKioskPassthroughLut.DIMENSION} " +
      "lutEntryCount=${RustyKioskPassthroughLut.ENTRY_COUNT} " +
      "edgeRendering=${if (state.style == KioskPassthroughStyle.CONTOUR_LUT) "lut-luminance-bands" else "natural-color"} " +
      "neighborhoodEdgeDetection=false runtimeCrash=false"

  private fun failedMarker(
    source: String,
    state: KioskPassthroughState,
    error: Throwable,
  ): String =
    "channel=rusty-kiosk-passthrough status=apply-failed " +
      "source=${markerToken(source)} style=${state.style.wireName} " +
      "systemPassthroughEnabled=${state.systemPassthroughEnabled} " +
      "error=${markerToken(error.javaClass.simpleName)} " +
      "message=${markerToken(error.message ?: "none")} runtimeCrash=false"

  private fun markerToken(value: String): String =
    value.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9._-]+"), "-").trim('-').take(96)
}
