package com.zz.filemanager.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import com.zz.filemanager.core.model.ThemeMode
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF006B5B), onPrimary = Color.White,
    primaryContainer = Color(0xFF8DF8DE), onPrimaryContainer = Color(0xFF00201A),
    secondary = Color(0xFF4B635D), background = Color(0xFFF7FBF8), surface = Color(0xFFF7FBF8),
    surfaceVariant = Color(0xFFDCE5E0), outline = Color(0xFF6F7975), error = Color(0xFFBA1A1A),
)
private val DarkColors = darkColorScheme(
    primary = Color(0xFF70DBC2), onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005143), onPrimaryContainer = Color(0xFF8DF8DE),
    secondary = Color(0xFFB2CCC4), background = Color(0xFF101512), surface = Color(0xFF101512),
    surfaceVariant = Color(0xFF3F4945), outline = Color(0xFF89938F), error = Color(0xFFFFB4AB),
)
private val DenseTypography = Typography(
    titleLarge = Typography().titleLarge.copy(fontSize = 20.sp),
    titleMedium = Typography().titleMedium.copy(fontSize = 16.sp),
    bodyLarge = Typography().bodyLarge.copy(fontSize = 15.sp),
    bodyMedium = Typography().bodyMedium.copy(fontSize = 14.sp),
    bodySmall = Typography().bodySmall.copy(fontSize = 12.sp),
)

@Composable
fun ZZFileManagerTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = DenseTypography, content = content)
}
