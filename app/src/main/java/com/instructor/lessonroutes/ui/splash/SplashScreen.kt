package com.instructor.lessonroutes.ui.splash

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp

// The launch screen's fixed design space -- matches Corey-supplied
// lesson-route-planner-launch-1b.svg's own viewBox exactly (402x874). Scaled
// uniformly (never stretched/distorted, never cropped) to whatever the real
// device's canvas size actually is -- see SplashScreen() below, same idea as
// how the SVG itself would scale in a browser. Every shape/path/color/text
// value below is traced directly from that SVG, 1:1, not approximated.
private const val DESIGN_WIDTH = 402f
private const val DESIGN_HEIGHT = 874f

private val BackgroundGreen = Color(0xFFD4F4DB)
private val SquiggleOrange = Color(0xFFE3A72F)
private val SquiggleYellow = Color(0xFFFBD34D)
private val FlagRed = Color(0xFFDB0032)
private val LPlateYellow = Color(0xFFF5C518)
private val LPlateBlack = Color(0xFF111111)
private val TitleColor = Color(0xFF111111)
private val SubtitleColor = Color(0xFF111111).copy(alpha = 0.45f)

/** The hand-drawn squiggle behind the L-plate/flag -- traced point-for-point
 * from the source SVG's `<path id="squiggle">` `d` attribute. */
private fun squigglePath(): Path = Path().apply {
    moveTo(243f, 6f)
    cubicTo(241.3f, 10.0f, 238.3f, 13.5f, 236f, 22f)
    cubicTo(233.8f, 30.5f, 237.0f, 32.5f, 234f, 40f)
    cubicTo(231.0f, 47.5f, 231.3f, 45.0f, 224f, 52f)
    cubicTo(216.8f, 59.0f, 213.5f, 60.0f, 205f, 68f)
    cubicTo(196.5f, 76.0f, 196.8f, 76.0f, 190f, 84f)
    cubicTo(183.3f, 92.0f, 185.0f, 93.8f, 178f, 100f)
    cubicTo(171.0f, 106.3f, 178.5f, 104.5f, 162f, 109f)
    cubicTo(145.5f, 113.5f, 128.5f, 113.8f, 112f, 118f)
    cubicTo(95.5f, 122.3f, 96.0f, 121.0f, 96f, 126f)
    cubicTo(96.0f, 131.0f, 102.5f, 131.5f, 112f, 138f)
    cubicTo(121.5f, 144.5f, 127.0f, 146.0f, 134f, 152f)
    cubicTo(141.0f, 158.0f, 141.0f, 152.5f, 140f, 162f)
    cubicTo(139.0f, 171.5f, 134.0f, 178.0f, 130f, 190f)
    cubicTo(126.0f, 202.0f, 125.8f, 197.5f, 124f, 210f)
    cubicTo(122.3f, 222.5f, 122.0f, 223.8f, 123f, 240f)
    cubicTo(124.0f, 256.3f, 120.8f, 263.3f, 128f, 275f)
    cubicTo(135.3f, 286.8f, 141.0f, 280.0f, 152f, 287f)
    cubicTo(163.0f, 294.0f, 168.3f, 294.8f, 172f, 303f)
    cubicTo(175.8f, 311.3f, 170.8f, 309.5f, 167f, 320f)
    cubicTo(163.3f, 330.5f, 162.3f, 333.0f, 157f, 345f)
    cubicTo(151.8f, 357.0f, 150.3f, 358.8f, 146f, 368f)
    cubicTo(141.8f, 377.3f, 141.5f, 378.5f, 140f, 382f)
}

/** White half of the flag/pennant shape, right of the L-plate. */
private fun flagWhitePath(): Path = Path().apply {
    moveTo(86f, 0f)
    lineTo(86f, 154f)
    lineTo(0f, 215f)
    close()
}

/** Red half of the flag/pennant shape. */
private fun flagRedPath(): Path = Path().apply {
    moveTo(86f, 0f)
    lineTo(173f, 215f)
    lineTo(86f, 154f)
    close()
}

/** The black "L" glyph on the L-plate -- traced from the SVG path's H/V
 * (horizontal/vertical line) commands. */
private fun lGlyphPath(): Path = Path().apply {
    moveTo(94f, 50f)
    lineTo(140f, 50f)
    lineTo(140f, 216f)
    lineTo(246f, 216f)
    lineTo(246f, 262f)
    lineTo(94f, 262f)
    close()
}

/**
 * The app's launch/loading screen -- shown by [com.instructor.lessonroutes.
 * navigation.AppNavHost] while the one-time static-data seed runs (brief on
 * every launch; only does real work on a fresh install). Corey-supplied
 * design (lesson-route-planner-launch-1b.svg), reconstructed with Canvas draw
 * calls rather than shipped as a raster/vector asset, so it scales cleanly to
 * any real device's screen with no image-generation step to keep in sync if
 * the design changes.
 */
@Composable
fun SplashScreen(modifier: Modifier = Modifier) {
    // Real bug, confirmed by working through the math (Corey: "text overlaying
    // other text" / "the image looks too zoomed in"): every path/shape below is
    // plain design-unit floats, scaled exactly once by this function's own
    // `scale()` transform to fit the real device. TextStyle's fontSize/
    // letterSpacing are `.sp` though, a density-aware unit -- rememberTextMeasurer()
    // would otherwise measure them using the REAL device density first (its own,
    // correct, sp-to-px conversion), and then the transform below scales that
    // already-real-sized text a second time, compounding into text several times
    // too large -- overlapping itself/the line below it, and reading as "too
    // zoomed in" since nothing else on screen is affected the same way. Forcing
    // density/fontScale to 1 here makes "34.sp" mean exactly "34 design units",
    // the same units every path already uses, so text ends up scaled exactly
    // once too, same as everything else.
    CompositionLocalProvider(LocalDensity provides Density(density = 1f, fontScale = 1f)) {
    val textMeasurer = rememberTextMeasurer()
    val squiggle = remember { squigglePath() }
    val flagWhite = remember { flagWhitePath() }
    val flagRed = remember { flagRedPath() }
    val lGlyph = remember { lGlyphPath() }

    Canvas(modifier = modifier.fillMaxSize()) {
        // Whole canvas filled first -- scale-to-fit below can leave a margin
        // on a differently-proportioned real screen than the 402x874 design
        // was drawn for, and that margin needs to be the same background
        // color, not a visible seam of the default window background.
        drawRect(BackgroundGreen)

        val scale = minOf(size.width / DESIGN_WIDTH, size.height / DESIGN_HEIGHT)
        val offsetX = (size.width - DESIGN_WIDTH * scale) / 2f
        val offsetY = (size.height - DESIGN_HEIGHT * scale) / 2f

        withTransform({
            translate(offsetX, offsetY)
            scale(scale, scale, pivot = Offset.Zero)
        }) {
            // Squiggle group -- source SVG: translate(-192,-58) scale(2.5).
            withTransform({
                translate(-192f, -58f)
                scale(2.5f, 2.5f, pivot = Offset.Zero)
            }) {
                drawPath(
                    squiggle,
                    SquiggleOrange,
                    style = Stroke(width = 17f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
                drawPath(
                    squiggle,
                    SquiggleYellow,
                    style = Stroke(width = 11f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }

            // Flag/pennant group -- source SVG: translate(232,120) scale(0.62).
            withTransform({
                translate(232f, 120f)
                scale(0.62f, 0.62f, pivot = Offset.Zero)
            }) {
                drawPath(flagWhite, Color.White)
                drawPath(flagRed, FlagRed)
            }

            // L-plate group -- source SVG: translate(46,556) rotate(-20) scale(0.52).
            // Order matters (matches the SVG's own transform list order): each
            // operation applies within the coordinate space the previous one
            // already established.
            withTransform({
                translate(46f, 556f)
                rotate(-20f, pivot = Offset.Zero)
                scale(0.52f, 0.52f, pivot = Offset.Zero)
            }) {
                drawRoundRect(LPlateYellow, size = Size(312f, 312f), cornerRadius = CornerRadius(16f, 16f))
                drawPath(lGlyph, LPlateBlack)
            }

            // Bottom fade -- solid background color fading in from fully
            // transparent at the top of this band to fully opaque by 62% down
            // (then staying opaque to the bottom), same stops as the source
            // SVG's <linearGradient id="fade">, so the squiggle/L-plate soften
            // into flat background before the text sits on top of it.
            drawRect(
                brush = Brush.verticalGradient(
                    0f to BackgroundGreen.copy(alpha = 0f),
                    0.62f to BackgroundGreen,
                    1f to BackgroundGreen,
                    startY = 644f,
                    endY = 874f,
                ),
                topLeft = Offset(0f, 644f),
                size = Size(DESIGN_WIDTH, 230f),
            )

            // Text: drawText's topLeft is the top of the text's bounding box,
            // not a baseline the way SVG's text x/y is -- these are nudged up
            // from the SVG's own baseline y (782/802) by roughly each line's
            // ascent so they land in the same visual spot; not pixel-exact,
            // fine for a launch screen. Inter (the SVG's requested font) isn't
            // bundled in this app, so this falls back to the platform's
            // default sans-serif rather than pulling in a new font asset.
            drawText(
                textMeasurer = textMeasurer,
                text = "Lesson Route Planner",
                topLeft = Offset(34f, 754f),
                style = TextStyle(
                    color = TitleColor,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-0.85).sp,
                ),
            )
            drawText(
                textMeasurer = textMeasurer,
                text = "FOR NSW DRIVING INSTRUCTORS",
                // A few design units lower than the SVG's own baseline math
                // would put it (794) -- Compose's line-box metrics for a
                // 34sp title run slightly taller than the source SVG's exact
                // font-rendered glyph bounds, so the original gap left near-
                // zero clearance before the title/subtitle overlap bug above
                // was fixed. This is just a small safety margin on top of
                // that real fix, not a second attempt at the same bug.
                topLeft = Offset(34f, 800f),
                style = TextStyle(
                    color = SubtitleColor,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.62.sp,
                ),
            )
        }
    }
    }
}
