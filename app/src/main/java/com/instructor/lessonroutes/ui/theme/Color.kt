package com.instructor.lessonroutes.ui.theme

import androidx.compose.ui.graphics.Color

// Brand colors (Corey's earlier choice): a fixed blue palette. The MAIN app
// theme below no longer uses these directly (Corey's later request: switch
// the main app back to black-and-white, basic colors, easy to read) -- they
// stay defined here because two things still need them:
//   1. PlanTripTheme in Theme.kt (GenerateRouteScreen.kt's "Plan a trip" and
//      StudentProfilesScreen.kt keep this exact blue look, per Corey's
//      explicit request, even though the rest of the app switched).
//   2. A handful of individual buttons outside those two screens that Corey
//      also wants to stay blue specifically (LiveMapScreen.kt's "Student
//      Profiles"/"Plan a trip" buttons, RouteListScreen.kt's "Profiles"/
//      "Overview" buttons, GenerateRouteScreen.kt's "Generate route" button)
//      -- these already reference these constants directly rather than
//      theme roles, so they're unaffected by the main theme change below.
//   SelectedBlue   #023E8A -- selected buttons; also reused as the border
//                             color for several white-fill buttons (see the
//                             call sites above) rather than filled.
//   UnselectedBlue #0077B6 -- unselected buttons
//   BackgroundWhite plain white -- background/surface shade for the
//                             preserved-blue screens
//   ClockAccentCyan#ADE8F4 -- fallback role used by a couple of "extra" M3
//                             roles in PlanTripTheme (tertiary) that Corey
//                             hasn't given a specific instruction for -- NOT
//                             the TimePicker any more, see AppTimePickerDialog
//                             in GenerateRouteScreen.kt, which deliberately
//                             opts back out to the device's own default
//                             (dynamic/Material You) colours instead.
//   BorderNavy     #03045E -- border colour and button font (on top of
//                             SelectedBlue/UnselectedBlue/ClockAccentCyan)
val SelectedBlue = Color(0xFF023E8A)
val UnselectedBlue = Color(0xFF0077B6)
val BackgroundWhite = Color(0xFFFFFFFF)
val ClockAccentCyan = Color(0xFFADE8F4)
val BorderNavy = Color(0xFF03045E)
val OnBackgroundBlack = Color(0xFF000000)

// The MAIN app theme (Corey's request): back to plain black-and-white, basic
// colors, easy to read -- no blue, no other tint. AppBlack/AppWhite carry the
// two "opposite extremes" (buttons/text vs. background), AppDarkGray/
// AppMidGray/AppLightGray are the only other shades used, for secondary
// elements/borders/subtle fills -- still just grayscale, nothing colored.
// Red stays red regardless of all of this: anywhere in the app that shows an
// explicit warning/error either uses a literal red Color (e.g.
// GenerateRouteScreen.kt's "Set End time" text) or Material3's own default
// error/onError roles, neither of which this file touches.
val AppBlack = Color(0xFF000000)
val AppWhite = Color(0xFFFFFFFF)
val AppDarkGray = Color(0xFF424242)
val AppMidGray = Color(0xFF9E9E9E)
val AppLightGray = Color(0xFFE0E0E0)

// Deliberately identical for light and dark, same convention this file
// already used for the old blue palette -- one literal palette, no separate
// dark-mode alternates.
val PrimaryLight = AppBlack
val OnPrimaryLight = AppWhite
val PrimaryContainerLight = AppBlack
val OnPrimaryContainerLight = AppWhite
val SecondaryLight = AppDarkGray
val OnSecondaryLight = AppWhite
val SecondaryContainerLight = AppLightGray
val OnSecondaryContainerLight = AppBlack

val PrimaryDark = AppBlack
val OnPrimaryDark = AppWhite
val PrimaryContainerDark = AppBlack
val OnPrimaryContainerDark = AppWhite
val SecondaryDark = AppDarkGray
val OnSecondaryDark = AppWhite
val SecondaryContainerDark = AppLightGray
val OnSecondaryContainerDark = AppBlack

val Neutral95 = AppWhite
val Neutral10 = AppWhite

// Every other M3 color role (tertiary, surface[Variant], outline, inverse*)
// falls through to Compose Material3's baseline default scheme (purple/violet-
// seeded) when left unset -- confirmed as a real bug once already (ListItem
// backgrounds, TimePicker's AM/PM selector). Every one of these is filled in
// here too, with grayscale only -- no color anywhere in the main theme.
val TertiaryLight = AppDarkGray
val OnTertiaryLight = AppWhite
val TertiaryContainerLight = AppLightGray
val OnTertiaryContainerLight = AppBlack
val SurfaceLight = AppWhite
val OnSurfaceLight = AppBlack
val SurfaceVariantLight = AppLightGray
val OnSurfaceVariantLight = AppBlack
val OutlineLight = AppMidGray
val OutlineVariantLight = AppLightGray
val InverseSurfaceLight = AppBlack
val InverseOnSurfaceLight = AppWhite
val InversePrimaryLight = AppWhite

val TertiaryDark = AppDarkGray
val OnTertiaryDark = AppWhite
val TertiaryContainerDark = AppLightGray
val OnTertiaryContainerDark = AppBlack
val SurfaceDark = AppWhite
val OnSurfaceDark = AppBlack
val SurfaceVariantDark = AppLightGray
val OnSurfaceVariantDark = AppBlack
val OutlineDark = AppMidGray
val OutlineVariantDark = AppLightGray
val InverseSurfaceDark = AppBlack
val InverseOnSurfaceDark = AppWhite
val InversePrimaryDark = AppWhite

// Same red already used for GenerateRouteScreen.kt's "Set End time" warning
// text -- reused here so a saved route's Avoid/Prefer pill-badge labels read
// the same way: Avoid items in red, Prefer items in a matching green, on
// RouteDetailScreen.
val AvoidRed = Color(0xFFD21F3C)
val PreferGreen = Color(0xFF2E7D32)

// Lighter tints of the above, specifically for the selected-state FILL of the
// Avoid/Prefer FilterChips on "Plan a trip" (GenerateRouteScreen.kt's
// FilterRow) -- Corey wanted these chips' own selected fill to read as
// light green/light red with black text, distinct from AvoidRed/PreferGreen
// above (which are dark, paired with white or theme-driven text on the pill
// badges elsewhere) -- a dark fill would need light text to stay readable,
// not the black text asked for here.
val AvoidLightRed = Color(0xFFEF9A9A)
val PreferLightGreen = Color(0xFFA5D6A7)
