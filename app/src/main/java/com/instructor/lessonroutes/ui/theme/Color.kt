package com.instructor.lessonroutes.ui.theme

import androidx.compose.ui.graphics.Color

// Brand colors (Corey's choice): a fixed blue palette, five exact hex values
// used everywhere -- no other shade is introduced anywhere in this file.
// Was a light grass-green theme before this.
//   SelectedBlue   #023E8A -- selected buttons
//   UnselectedBlue #0077B6 -- unselected buttons
//   BackgroundCyan #CAF0F8 -- background/surface shade
//   ClockAccentCyan#ADE8F4 -- fallback role used by a couple of "extra" M3
//                             roles below (tertiary) that Corey hasn't given
//                             a specific instruction for -- NOT the TimePicker
//                             any more, see AppTimePickerDialog in
//                             GenerateRouteScreen.kt, which now deliberately
//                             opts back out to plain Material3 defaults.
//   BorderNavy     #03045E -- border colour and button font (on top of
//                             SelectedBlue/UnselectedBlue/ClockAccentCyan)
// Font in front of BackgroundCyan is plain black instead, per Corey's spec --
// everywhere else that needs a color but wasn't given one explicitly reuses
// one of the five above rather than introducing a new shade.
val SelectedBlue = Color(0xFF023E8A)
val UnselectedBlue = Color(0xFF0077B6)
val BackgroundCyan = Color(0xFFCAF0F8)
val ClockAccentCyan = Color(0xFFADE8F4)
val BorderNavy = Color(0xFF03045E)
val OnBackgroundBlack = Color(0xFF000000)

// Deliberately identical for light and dark: Corey specified one literal
// five-color palette with no dark-mode alternates, so both schemes below use
// exactly the same values rather than inventing brighter/darker variants that
// aren't in that set.
val PrimaryLight = SelectedBlue
val OnPrimaryLight = BorderNavy
val PrimaryContainerLight = SelectedBlue
val OnPrimaryContainerLight = BorderNavy
val SecondaryLight = UnselectedBlue
val OnSecondaryLight = BorderNavy
val SecondaryContainerLight = UnselectedBlue
val OnSecondaryContainerLight = BorderNavy

val PrimaryDark = SelectedBlue
val OnPrimaryDark = BorderNavy
val PrimaryContainerDark = SelectedBlue
val OnPrimaryContainerDark = BorderNavy
val SecondaryDark = UnselectedBlue
val OnSecondaryDark = BorderNavy
val SecondaryContainerDark = UnselectedBlue
val OnSecondaryContainerDark = BorderNavy

val Neutral95 = BackgroundCyan
val Neutral10 = BackgroundCyan

// Every other M3 color role (tertiary, surface[Variant], outline, inverse*)
// falls through to Compose Material3's baseline default scheme (purple/violet-
// seeded) when left unset -- confirmed as a real bug once already (ListItem
// backgrounds, TimePicker's AM/PM selector). Every one of these is filled in
// here too, reusing the five colors above so nothing outside primary/secondary
// reverts to purple. Tertiary carries the AM/PM + clock-face role Corey asked
// for (ClockAccentCyan, text on it in BorderNavy); surface roles carry the
// background shade with black text, per Corey's "black for all other font in
// front of #90E0EF" instruction.
val TertiaryLight = ClockAccentCyan
val OnTertiaryLight = BorderNavy
val TertiaryContainerLight = ClockAccentCyan
val OnTertiaryContainerLight = BorderNavy
val SurfaceLight = BackgroundCyan
val OnSurfaceLight = OnBackgroundBlack
val SurfaceVariantLight = BackgroundCyan
val OnSurfaceVariantLight = OnBackgroundBlack
val OutlineLight = BorderNavy
val OutlineVariantLight = BorderNavy
val InverseSurfaceLight = BorderNavy
val InverseOnSurfaceLight = ClockAccentCyan
val InversePrimaryLight = ClockAccentCyan

val TertiaryDark = ClockAccentCyan
val OnTertiaryDark = BorderNavy
val TertiaryContainerDark = ClockAccentCyan
val OnTertiaryContainerDark = BorderNavy
val SurfaceDark = BackgroundCyan
val OnSurfaceDark = OnBackgroundBlack
val SurfaceVariantDark = BackgroundCyan
val OnSurfaceVariantDark = OnBackgroundBlack
val OutlineDark = BorderNavy
val OutlineVariantDark = BorderNavy
val InverseSurfaceDark = BorderNavy
val InverseOnSurfaceDark = ClockAccentCyan
val InversePrimaryDark = ClockAccentCyan
