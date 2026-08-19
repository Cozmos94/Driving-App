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
