package com.instructor.lessonroutes.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = PurplePrimaryLight,
    onPrimary = OnPurplePrimaryLight,
    primaryContainer = PurpleContainerLight,
    onPrimaryContainer = OnPurpleContainerLight,
    secondary = YellowSecondaryLight,
    onSecondary = OnYellowSecondaryLight,
    secondaryContainer = YellowContainerLight,
    onSecondaryContainer = OnYellowContainerLight,
    background = Neutral95,
)

private val DarkColors = darkColorScheme(
    primary = PurplePrimaryDark,
    onPrimary = OnPurplePrimaryDark,
    primaryContainer = PurpleContainerDark,
    onPrimaryContainer = OnPurpleContainerDark,
    secondary = YellowSecondaryDark,
    onSecondary = OnYellowSecondaryDark,
    secondaryContainer = YellowContainerDark,
    onSecondaryContainer = OnYellowContainerDark,
    background = Neutral10,
)

@Composable
fun LessonRoutesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Off by default: Android 12+'s dynamic (Material You) color would otherwise
    // override this custom purple/yellow brand palette with tones derived from the
    // device wallpaper, defeating the point of picking specific brand colors.
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}
