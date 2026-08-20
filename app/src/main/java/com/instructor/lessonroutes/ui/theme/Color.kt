package com.instructor.lessonroutes.ui.theme

import androidx.compose.ui.graphics.Color

// Brand colors (Corey's choice): a light grass-green theme -- the same soft
// green used for parks/grass on most map styles, since this is a driving-route
// map app. Was a stark black/white theme before this; primary here is a
// consistent grass green across both light/dark modes (a stronger shade in
// light mode, a lighter/brighter one in dark mode for contrast against a dark
// background), rather than the old "opposite extreme from background" logic.
val GrassGreenLight = Color(0xFF558B2F)
val GrassGreenLighter = Color(0xFF7CB342)
val GrassGreenPale = Color(0xFFF1F8E9)

// Light theme: primary is a solid, saturated grass green (enough contrast for
// white text/icons on filled buttons); secondary is a brighter green tint for
// a second, distinguishable accent. Container tones are soft green tints so
// filled surfaces (FAB, chips, etc.) read as tonally related without being
// full-strength green everywhere. Text/icon colors (onSurface etc., see below)
// lean dark green rather than pure black/gray for a bit of brand cohesion,
// while staying dark enough not to hurt legibility.
val PrimaryLight = GrassGreenLight
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFC5E1A5)
val OnPrimaryContainerLight = Color(0xFF33691E)
val SecondaryLight = GrassGreenLighter
val OnSecondaryLight = Color(0xFFFFFFFF)
val SecondaryContainerLight = Color(0xFFDCEDC8)
val OnSecondaryContainerLight = Color(0xFF33691E)

// Dark theme: brighter/lighter greens (need more luminance to read against a
// dark background), deep green containers with light "on" text.
val PrimaryDark = Color(0xFF9CCC65)
val OnPrimaryDark = Color(0xFF1B2E0E)
val PrimaryContainerDark = Color(0xFF33691E)
val OnPrimaryContainerDark = Color(0xFFC5E1A5)
val SecondaryDark = Color(0xFFAED581)
val OnSecondaryDark = Color(0xFF1B2E0E)
val SecondaryContainerDark = Color(0xFF2E4A1D)
val OnSecondaryContainerDark = Color(0xFFDCEDC8)

val Neutral95 = GrassGreenPale
val Neutral10 = Color(0xFF10190C)

// Every other M3 color role (tertiary, surface[Variant], outline, inverse*)
// falls through to Compose Material3's baseline default scheme (purple/violet-
// seeded) when left unset -- see Theme.kt's own comment on this, confirmed as
// a real bug once already (ListItem backgrounds, TimePicker's AM/PM selector).
// Every one of these is filled in here too, reusing the green family so
// nothing outside primary/secondary reverts to purple.
val TertiaryLight = SecondaryLight
val OnTertiaryLight = OnSecondaryLight
val TertiaryContainerLight = SecondaryContainerLight
val OnTertiaryContainerLight = OnSecondaryContainerLight
val SurfaceLight = Neutral95
val OnSurfaceLight = Color(0xFF1D2B12)
val SurfaceVariantLight = Color(0xFFDCEDC8)
val OnSurfaceVariantLight = Color(0xFF33691E)
val OutlineLight = Color(0xFF6B8E5A)
val OutlineVariantLight = Color(0xFFC8D6BE)
val InverseSurfaceLight = Color(0xFF1D2B12)
val InverseOnSurfaceLight = GrassGreenPale
val InversePrimaryLight = Color(0xFFAED581)

val TertiaryDark = SecondaryDark
val OnTertiaryDark = OnSecondaryDark
val TertiaryContainerDark = SecondaryContainerDark
val OnTertiaryContainerDark = OnSecondaryContainerDark
val SurfaceDark = Neutral10
val OnSurfaceDark = Color(0xFFE8F5E0)
val SurfaceVariantDark = Color(0xFF2E4A1D)
val OnSurfaceVariantDark = Color(0xFFC5E1A5)
val OutlineDark = Color(0xFF8FA883)
val OutlineVariantDark = Color(0xFF3E5A30)
val InverseSurfaceDark = GrassGreenPale
val InverseOnSurfaceDark = Color(0xFF1D2B12)
val InversePrimaryDark = Color(0xFF558B2F)
