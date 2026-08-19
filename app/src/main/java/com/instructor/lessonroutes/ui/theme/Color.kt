package com.instructor.lessonroutes.ui.theme

import androidx.compose.ui.graphics.Color

// Brand colors (Corey's choice): a stark black-and-white theme. Primary is
// "the opposite extreme from the background" -- black on the light background,
// white on the dark one -- with mid-gray tones for secondary accents, since a
// literal white-on-white secondary (e.g. a filled FAB) would have no visible
// edge against a white page with no border.
val BrandBlack = Color(0xFF1A1A1A)
val BrandWhite = Color(0xFFF5F5F5)

// Light theme: near-black primary (white text/icons on it), mid-gray secondary
// (dark text/icons on it). Container tones are soft tints so filled surfaces
// (FAB, chips, etc.) read as tonally related without being full-strength
// black/gray everywhere.
val PrimaryLight = BrandBlack
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFE0E0E0)
val OnPrimaryContainerLight = BrandBlack
val SecondaryLight = Color(0xFF616161)
val OnSecondaryLight = Color(0xFFFFFFFF)
val SecondaryContainerLight = Color(0xFFEEEEEE)
val OnSecondaryContainerLight = BrandBlack

// Dark theme: near-white primary (dark text/icons on it), lighter-gray
// secondary; containers become deep tones with light "on" text.
val PrimaryDark = BrandWhite
val OnPrimaryDark = BrandBlack
val PrimaryContainerDark = Color(0xFF333333)
val OnPrimaryContainerDark = BrandWhite
val SecondaryDark = Color(0xFFBDBDBD)
val OnSecondaryDark = BrandBlack
val SecondaryContainerDark = Color(0xFF424242)
val OnSecondaryContainerDark = BrandWhite

val Neutral95 = Color(0xFFF5F5F5)
val Neutral10 = Color(0xFF1A1A1A)

// Every other M3 color role (tertiary, surface[Variant], outline, inverse*)
// used to fall through to Compose Material3's baseline default scheme when left
// unset -- which is seeded from a purple/violet swatch, not neutral gray, and
// visibly leaked through on anything that reads one of these roles directly
// (e.g. ListItem's container/text colors, TimePicker's AM/PM selector, which
// defaults to tertiaryContainer). Reusing the secondary/gray tones already
// defined above for tertiary, and adding real gray surface/outline/inverse
// values, keeps every role -- not just primary/secondary -- inside the
// black/white/gray brand palette.
val TertiaryLight = SecondaryLight
val OnTertiaryLight = OnSecondaryLight
val TertiaryContainerLight = SecondaryContainerLight
val OnTertiaryContainerLight = OnSecondaryContainerLight
val SurfaceLight = Neutral95
val OnSurfaceLight = BrandBlack
val SurfaceVariantLight = Color(0xFFE0E0E0)
val OnSurfaceVariantLight = Color(0xFF424242)
val OutlineLight = Color(0xFF757575)
val OutlineVariantLight = Color(0xFFBDBDBD)
val InverseSurfaceLight = BrandBlack
val InverseOnSurfaceLight = BrandWhite
val InversePrimaryLight = Color(0xFFBDBDBD)

val TertiaryDark = SecondaryDark
val OnTertiaryDark = OnSecondaryDark
val TertiaryContainerDark = SecondaryContainerDark
val OnTertiaryContainerDark = OnSecondaryContainerDark
val SurfaceDark = Neutral10
val OnSurfaceDark = BrandWhite
val SurfaceVariantDark = Color(0xFF424242)
val OnSurfaceVariantDark = Color(0xFFBDBDBD)
val OutlineDark = Color(0xFF9E9E9E)
val OutlineVariantDark = Color(0xFF616161)
val InverseSurfaceDark = BrandWhite
val InverseOnSurfaceDark = BrandBlack
val InversePrimaryDark = Color(0xFF424242)
