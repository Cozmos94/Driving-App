package com.instructor.lessonroutes.ui.theme

import androidx.compose.ui.graphics.Color

// Brand colors (Corey's choice): a deep purple paired with a bright yellow accent.
val BrandPurple = Color(0xFF71286F)
val BrandYellow = Color(0xFFF3E10E)

// Light theme: purple as primary (white text/icons on it), yellow as secondary (dark
// text/icons on it, since yellow is too light for white-on-yellow to read well).
// Container tones are soft tints of each so filled surfaces (FAB, chips, etc.) read
// as "brand-colored" without being full-strength purple/yellow everywhere.
val PurplePrimaryLight = BrandPurple
val OnPurplePrimaryLight = Color(0xFFFFFFFF)
val PurpleContainerLight = Color(0xFFF3D9F2)
val OnPurpleContainerLight = Color(0xFF2E0E2D)
val YellowSecondaryLight = BrandYellow
val OnYellowSecondaryLight = Color(0xFF1C1B00)
val YellowContainerLight = Color(0xFFFFF9C4)
val OnYellowContainerLight = Color(0xFF3D3800)

// Dark theme: primary/secondary are lightened so they still read against a dark
// background; containers become deep tones with light "on" text.
val PurplePrimaryDark = Color(0xFFE3A6E0)
val OnPurplePrimaryDark = Color(0xFF3D123B)
val PurpleContainerDark = Color(0xFF522153)
val OnPurpleContainerDark = Color(0xFFF3D9F2)
val YellowSecondaryDark = BrandYellow
val OnYellowSecondaryDark = Color(0xFF3D3800)
val YellowContainerDark = Color(0xFF4A4400)
val OnYellowContainerDark = Color(0xFFFFF9C4)

val Neutral95 = Color(0xFFF5F5F5)
val Neutral10 = Color(0xFF1A1A1A)
