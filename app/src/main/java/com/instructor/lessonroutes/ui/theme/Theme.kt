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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Every role below is set explicitly -- leaving any of these unset falls back to
// Compose Material3's baseline default scheme, which is purple/violet-seeded, not
// neutral gray. That's what was showing through as pink/purple on ListItem
// backgrounds (reads surface/surfaceVariant) and the TimePicker's AM/PM selector
// (reads tertiaryContainer), neither of which this theme used to define.
private val LightColors = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    inversePrimary = InversePrimaryLight,
    secondary = SecondaryLight,
    onSecondary = OnSecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiary = TertiaryLight,
    onTertiary = OnTertiaryLight,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnTertiaryContainerLight,
    background = Neutral95,
    surface = SurfaceLight,
    onSurface = OnSurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    outlineVariant = OutlineVariantLight,
    inverseSurface = InverseSurfaceLight,
    inverseOnSurface = InverseOnSurfaceLight,
    // Left unset, this defaults to `primary` -- M3 blends this tint over any
    // elevated Surface (AlertDialog, Card, DropdownMenu, the app's own
    // GeneratingDialog, etc.) proportional to its elevation, via
    // surfaceColorAtElevation(). That's why colors that were exact flat hex
    // values in Color.kt were rendering visibly different (blended toward
    // primary blue) once shown inside anything elevated -- confirmed as the
    // real cause of Corey's "these are the colours, they don't look the same
    // in the app" report. Transparent disables that blending entirely, so
    // every surface stays exactly the literal hex it was given.
    surfaceTint = Color.Transparent,
)

private val DarkColors = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    inversePrimary = InversePrimaryDark,
    secondary = SecondaryDark,
    onSecondary = OnSecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiary = TertiaryDark,
    onTertiary = OnTertiaryDark,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnTertiaryContainerDark,
    background = Neutral10,
    surface = SurfaceDark,
    onSurface = OnSurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    inverseSurface = InverseSurfaceDark,
    inverseOnSurface = InverseOnSurfaceDark,
    // See LightColors' surfaceTint comment above -- same fix, same reason.
    surfaceTint = Color.Transparent,
)

// Preserved exactly as the app's whole theme looked before Corey asked to
// switch the *main* app back to black-and-white -- built directly from the
// blue literal constants (SelectedBlue/UnselectedBlue/BackgroundWhite/
// ClockAccentCyan/BorderNavy in Color.kt), not from PrimaryLight/
// SecondaryLight/etc., since those role variables now point at the new
// black-and-white palette instead. GenerateRouteScreen.kt ("Plan a trip")
// and StudentProfilesScreen.kt wrap their whole screen content in
// PlanTripTheme below to keep looking exactly as they did, per Corey's
// explicit request -- same technique AppTimePickerDialog (in
// GenerateRouteScreen.kt) already uses to opt the TimePicker back out of
// this app's own custom theme, just applied to two whole screens instead of
// one dialog.
private val PlanTripColors = lightColorScheme(
    primary = SelectedBlue,
    onPrimary = BorderNavy,
    primaryContainer = SelectedBlue,
    onPrimaryContainer = BorderNavy,
    inversePrimary = ClockAccentCyan,
    secondary = UnselectedBlue,
    onSecondary = BorderNavy,
    secondaryContainer = UnselectedBlue,
    onSecondaryContainer = BorderNavy,
    tertiary = ClockAccentCyan,
    onTertiary = BorderNavy,
    tertiaryContainer = ClockAccentCyan,
    onTertiaryContainer = BorderNavy,
    background = BackgroundWhite,
    surface = BackgroundWhite,
    onSurface = OnBackgroundBlack,
    surfaceVariant = BackgroundWhite,
    onSurfaceVariant = OnBackgroundBlack,
    outline = BorderNavy,
    outlineVariant = BorderNavy,
    inverseSurface = BorderNavy,
    inverseOnSurface = ClockAccentCyan,
    // See LightColors' own surfaceTint comment above -- same fix, same reason.
    surfaceTint = Color.Transparent,
)

@Composable
fun PlanTripTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = PlanTripColors, typography = Typography, content = content)
}

// GenerateRouteScreen.kt's clock. History: reusing LightColors/DarkColors
// as-is got the dial/digits right (surface=white, onSurface=black,
// surfaceVariant=a real grey -- AppLightGray) but not the selected hour/
// minute indicator, whose filled circle reads primary/onPrimary --
// LightColors' primary is AppBlack, so that indicator rendered as a black
// circle with white digits ("invert the colours... it should be black font,
// with white background"). The first fix overcorrected: it flipped EVERY
// role to white, including surfaceVariant -- the dial's own background --
// which had been a real grey under LightColors and turned stark white,
// prompting "remove the white colours, just leave it as the default grey
// colour with the rest of the clock." Fixed properly this time: start from
// LightColors itself (ColorScheme has a real copy() function -- confirmed
// via its own source, not assumed -- that keeps every already-correct role,
// grey surfaceVariant/outline included) and flip only the primary family,
// the one role that actually needed inverting.
private val ClockColors = LightColors.copy(
    primary = AppWhite,
    onPrimary = AppBlack,
    primaryContainer = AppWhite,
    onPrimaryContainer = AppBlack,
    inversePrimary = AppBlack,
)

/** No status-bar SideEffect (see the app's own root LessonRoutesTheme below
 * for why that's fine there but wrong here) -- something embedded inside a
 * dialog that's already showing on top of PlanTripTheme's blue shouldn't
 * repaint the status bar out from under the screen underneath it, since
 * nothing would revert that once the dialog closes. */
@Composable
fun ClockTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = ClockColors, typography = Typography, content = content)
}

@Composable
fun LessonRoutesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Off by default: Android 12+'s dynamic (Material You) color would otherwise
    // override this custom grass-green brand palette with tones derived from the
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
