package io.github.mesmerprism.rustykiosk

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.meta.spatial.uiset.theme.SpatialTheme

@Composable
internal fun RustyKioskTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = RustyKioskColorScheme) {
    SpatialTheme(content = content)
  }
}

private val RustyKioskColorScheme =
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
