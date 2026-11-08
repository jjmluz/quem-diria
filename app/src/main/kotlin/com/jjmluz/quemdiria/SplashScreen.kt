package com.jjmluz.quemdiria

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import androidx.compose.ui.graphics.Canvas as GraphicsCanvas

/** How long the splash runs before the room, in milliseconds. OK skips it. */
const val SPLASH_MS = 4_500L

/**
 * The splash screen: a field at dusk at the edge of a village, under a violet sky with a faint rainbow,
 * power pylons and a tree line with lit windows, fireflies and glowing butterflies over the grass, and a
 * black cat walking along the wall in front. The title arrives letter by letter with a worn colour split
 * before the pink "?" drops in, under film grain. All drawn here, on a 1920 × 1080 stage scaled to the
 * screen; Sounds.splash plays along with the same timings.
 */
@Composable
fun SplashScreen(onStart: () -> Unit, onEnd: () -> Unit) {
    // Made before the first frame (drawing the land takes a moment), so the clock starts on a ready scene.
    val scene = remember { Scene() }
    // Seconds since the splash first drew, from the frame clock. Its sound starts with the first frame,
    // and it ends on the same clock, however long the first frame took.
    val time by produceState(0f) {
        val start = withFrameMillis { it }
        onStart()
        while (value < SPLASH_MS / 1000f) value = (withFrameMillis { it } - start) / 1000f
        onEnd()
    }
    val measurer = rememberTextMeasurer(cacheSize = 16)
    Canvas(Modifier.fillMaxSize()) {
        // The stage is drawn at 1920 × 1080 and scaled from the top left to the screen.
        scale(size.width / W, size.height / H, pivot = Offset.Zero) { scene.draw(this, time, measurer) }
    }
}

private const val W = 1920f
private const val H = 1080f
/** Where the trees meet the field. */
private const val HORIZON = 640f

/** The top of the wall in front, which runs down to the right. */
private fun wallTop(x: Float) = 860f + x * 0.095f

private val Ink = Color(0xFF1C1430)
private val Glow = Color(0xFFFFB878)

/** Things made once: the land as a picture, the stars, clouds, fireflies and grain. Fixed randomness. */
private class Scene {
    private val random = Random(11)

    /** The still part of the land (trees, houses, pylons, field, fence, wall), drawn once. */
    private val land: ImageBitmap = ImageBitmap(W.toInt(), H.toInt()).also { image ->
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, GraphicsCanvas(image), Size(W, H)) { land() }
    }

    private class Star(val x: Float, val y: Float, val r: Float, val phase: Float)
    private val stars = List(70) {
        Star(random.nextFloat() * W, random.nextFloat() * HORIZON * 0.75f, 1f + random.nextFloat() * 1.8f, random.nextFloat() * 6.28f)
    }

    /** A soft cloud: overlapping glows, lit pink from below the horizon. */
    private class Cloud(val x: Float, val y: Float, val w: Float, val h: Float, val speed: Float, val lit: Float)
    private val clouds = listOf(
        Cloud(150f, 130f, 560f, 120f, 6f, 0.5f), Cloud(1640f, 210f, 660f, 150f, -5f, 0.55f),
        Cloud(700f, 380f, 520f, 90f, 4f, 0.7f), Cloud(1100f, 450f, 640f, 100f, -3f, 0.8f),
        Cloud(250f, 520f, 420f, 70f, 5f, 0.9f), Cloud(1650f, 520f, 520f, 90f, -4f, 0.9f),
        Cloud(1300f, 60f, 420f, 80f, 3f, 0.4f),
    )

    private class Firefly(var x: Float, var y: Float, val phase: Float, val speed: Float)
    private val fireflies = List(34) {
        Firefly(random.nextFloat() * W, HORIZON + 20f + random.nextFloat() * 330f, random.nextFloat() * 6.28f, 0.5f + random.nextFloat())
    }

    private class Butterfly(val x: Float, val y: Float, val phase: Float, val size: Float)
    private val butterflies = listOf(
        Butterfly(330f, 970f, 0f, 1.0f), Butterfly(590f, 1140f * 0.85f, 2f, 0.9f),
        Butterfly(1610f, 1040f, 4f, 1.1f), Butterfly(1240f, 760f, 1f, 0.7f),
    )

    private class Window(val x: Float, val y: Float, val w: Float, val h: Float, val phase: Float)
    private val windows = listOf(
        Window(90f, 566f, 20f, 16f, 0f), Window(150f, 566f, 16f, 16f, 1f),
        Window(735f, 562f, 16f, 14f, 2f), Window(810f, 562f, 16f, 14f, 3f),
        Window(1530f, 576f, 16f, 13f, 4f),
    )

    // A few frames of fine film grain, cycled: making new noise every frame is too much for the TV.
    private val grain: List<ImageBitmap> = List(4) {
        val pixels = IntArray(GRAIN_W * GRAIN_H) {
            val v = random.nextInt(256)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        Bitmap.createBitmap(pixels, GRAIN_W, GRAIN_H, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    private var lastTime = 0f

    fun draw(scope: DrawScope, t: Float, measurer: TextMeasurer) = with(scope) {
        val dt = (t - lastTime).coerceIn(0f, 0.05f)
        lastTime = t
        sky()
        stars(t)
        rainbow(t)
        clouds(t)
        drawImage(land)
        windows(t)
        fireflies(t, dt)
        cat(t)
        butterflies(t)
        title(t, measurer)
        lightLeaks(t)
        vignette()
        grain(t)
        // From black at the start, and back to black before the room appears.
        val end = SPLASH_MS / 1000f
        val black = min(1f, max(0f, 1 - t / 0.6f) + max(0f, (t - (end - 0.6f)) / 0.6f))
        if (black > 0f) drawRect(Color.Black.copy(alpha = black), size = Size(W, H))
    }

    // --- The sky --------------------------------------------------------------------------------

    private fun DrawScope.sky() {
        drawRect(
            Brush.verticalGradient(
                0f to Color(0xFF2C1A6A), 0.25f to Color(0xFF45288A), 0.42f to Color(0xFF6A349C),
                0.52f to Color(0xFFA2489E), 0.58f to Color(0xFFDA5A8C), 0.62f to Color(0xFFE8708C),
                startY = 0f, endY = H,
            ),
            size = Size(W, HORIZON + 10f),
        )
        // The glow where the sun went down, just behind the trees.
        drawRect(
            Brush.radialGradient(
                0f to Color(0xFFFF8A9A).copy(alpha = 0.55f), 1f to Color(0xFFFF8A9A).copy(alpha = 0f),
                center = Offset(W * 0.5f, HORIZON), radius = W * 0.45f,
            ),
            size = Size(W, HORIZON + 10f),
        )
    }

    private fun DrawScope.stars(t: Float) {
        for (s in stars) {
            val a = 0.3f + 0.5f * (0.5f + 0.5f * sin(t * 1.8f + s.phase))
            drawCircle(Color(0xFFFFE6F0).copy(alpha = a * (1 - s.y / HORIZON)), s.r, Offset(s.x, s.y))
        }
    }

    /** A faint rainbow over the field, fading in, its ends dissolving into the sky. */
    private fun DrawScope.rainbow(t: Float) {
        val appear = ((t - 0.4f) / 1.8f).coerceIn(0f, 1f)
        if (appear <= 0f) return
        val centre = Offset(W * 0.6f, H * 0.86f)
        val colours = listOf(
            Color(0xFFFF6A6A), Color(0xFFFF9A5A), Color(0xFFFFD27A), Color(0xFF9FE08A),
            Color(0xFF7AB8FF), Color(0xFF8C7AFF), Color(0xFFC07AFF),
        )
        // Each band fades towards its ends with a gradient around the centre (angles from 3 o'clock, clockwise).
        colours.forEachIndexed { band, colour ->
            val c = colour.copy(alpha = 0.16f * appear)
            val brush = Brush.sweepGradient(
                0.55f to c.copy(alpha = 0f), 0.63f to c.copy(alpha = c.alpha * 0.5f), 0.74f to c,
                0.86f to c.copy(alpha = c.alpha * 0.5f), 0.95f to c.copy(alpha = 0f), center = centre,
            )
            // Bands exactly a stroke apart, so they neither overlap nor leave gaps.
            val r = 790f - band * 13f
            drawArc(
                brush, 198f, 144f, useCenter = false, topLeft = Offset(centre.x - r, centre.y - r),
                size = Size(2 * r, 2 * r), style = Stroke(13f), blendMode = BlendMode.Screen,
            )
        }
    }

    private fun DrawScope.clouds(t: Float) {
        for (c in clouds) {
            val x = c.x + c.speed * t
            // Darker violet bodies, with a pink rim underneath where the sunset still reaches them.
            for (k in -2..2) {
                val cx = x + k * c.w * 0.18f
                val cy = c.y + abs(k) * c.h * 0.12f
                val rx = c.w * (0.36f - abs(k) * 0.05f)
                drawOval(
                    Brush.radialGradient(
                        0f to Color(0xFF5A2E8A).copy(alpha = 0.55f), 1f to Color(0xFF5A2E8A).copy(alpha = 0f),
                        center = Offset(cx, cy), radius = rx,
                    ),
                    Offset(cx - rx, cy - c.h * 0.6f), Size(2 * rx, c.h * 1.2f),
                )
                drawOval(
                    Brush.radialGradient(
                        0f to Color(0xFFE0609A).copy(alpha = 0.35f * c.lit), 1f to Color(0xFFE0609A).copy(alpha = 0f),
                        center = Offset(cx, cy + c.h * 0.3f), radius = rx * 0.8f,
                    ),
                    Offset(cx - rx * 0.8f, cy), Size(1.6f * rx, c.h * 0.6f), blendMode = BlendMode.Screen,
                )
            }
        }
    }

    // --- The land, drawn once -----------------------------------------------------------------

    private fun DrawScope.land() {
        val r = Random(5)
        // Far trees, then near trees and houses along the horizon.
        for (layer in 0..1) {
            val colour = if (layer == 0) Color(0xFF3A2A5C) else Color(0xFF261B40)
            val base = HORIZON + layer * 6f
            var x = -20f
            while (x < W + 40f) {
                val big = (x < 120f && layer == 1) || (x > 1760f && layer == 1)
                val radius = if (big) 60f + r.nextFloat() * 40f else 18f + r.nextFloat() * (if (layer == 0) 22f else 30f)
                drawCircle(colour, radius, Offset(x, base - radius * (if (big) 1.4f else 0.9f) - layer * 4f))
                x += radius * 0.9f
            }
            drawRect(colour, Offset(0f, base - 22f), Size(W, 30f))
        }
        house(55f, 600f, 150f, 48f)
        house(690f, 598f, 180f, 50f)
        house(1480f, 606f, 170f, 44f)
        // The field: grey-violet, darker towards us, with grass strokes.
        drawRect(
            Brush.verticalGradient(
                0f to Color(0xFF4A4262), 0.25f to Color(0xFF353248), 1f to Color(0xFF1E1C2C), startY = HORIZON, endY = H,
            ),
            Offset(0f, HORIZON), Size(W, H - HORIZON),
        )
        repeat(2600) {
            val y = HORIZON + 20f + (r.nextFloat().pow(0.7f)) * (H - HORIZON)
            val x = r.nextFloat() * W
            val len = 4f + (y - HORIZON) / 22f * (0.5f + r.nextFloat())
            val lean = (r.nextFloat() - 0.5f) * len * 0.6f
            val green = if (r.nextBoolean()) Color(0xFF3C4A4A) else Color(0xFF423B58)
            drawLine(green.copy(alpha = 0.7f), Offset(x, y), Offset(x + lean, y - len), 1.6f)
        }
        pylon(240f, 360f, 34f)
        pylon(560f, 262f, 42f)
        wires()
        fence()
        wall()
        // Tufts of grass and leaves in front of the wall.
        for ((x, y, size) in listOf(Triple(330f, 1000f, 1f), Triple(780f, 1060f, 0.9f), Triple(30f, 820f, 1.3f), Triple(1880f, 1075f, 0.8f))) {
            for (i in 0 until 14) {
                val angle = (-150f + i * 120f / 13f) * PI.toFloat() / 180f
                val len = (60f + r.nextFloat() * 60f) * size
                drawLine(
                    Color(0xFF263A30), Offset(x, y), Offset(x + cos(angle) * len, y + sin(angle) * len),
                    5f * size, cap = StrokeCap.Round,
                )
            }
        }
    }

    /** A house on the horizon: a pale wall lit by the last of the sky, and a dark red roof. */
    private fun DrawScope.house(x: Float, base: Float, w: Float, h: Float) {
        drawRect(Color(0xFF735A86), Offset(x, base - h), Size(w, h))
        val roof = Path().apply {
            moveTo(x - 12f, base - h)
            lineTo(x + w * 0.22f, base - h - 26f)
            lineTo(x + w * 0.85f, base - h - 26f)
            lineTo(x + w + 14f, base - h)
            close()
        }
        drawPath(roof, Color(0xFF8A3A58))
        drawRect(Color(0xFF261B40), Offset(x - 20f, base - 4f), Size(w + 40f, 12f))
    }

    /** A lattice power pylon: two legs narrowing to the top, crossed braces, and two cross arms. */
    private fun DrawScope.pylon(x: Float, top: Float, halfBase: Float) {
        val ink = Color(0xFF2A1D44)
        val base = HORIZON - 4f
        val halfTop = 7f
        fun half(y: Float) = halfTop + (halfBase - halfTop) * (y - top) / (base - top)
        drawLine(ink, Offset(x - halfBase, base), Offset(x - halfTop, top), 3.5f)
        drawLine(ink, Offset(x + halfBase, base), Offset(x + halfTop, top), 3.5f)
        var y = top + 40f
        while (y < base) {
            val next = min(base, y + 36f)
            drawLine(ink, Offset(x - half(y), y), Offset(x + half(next), next), 1.8f)
            drawLine(ink, Offset(x + half(y), y), Offset(x - half(next), next), 1.8f)
            drawLine(ink, Offset(x - half(y), y), Offset(x + half(y), y), 1.8f)
            y = next
        }
        for ((dy, arm) in listOf(42f to 52f, 80f to 44f)) {
            val ay = top + dy
            drawLine(ink, Offset(x - arm, ay), Offset(x + arm, ay), 3f)
            drawLine(ink, Offset(x - arm, ay), Offset(x - half(ay - 26f), ay - 26f), 1.8f)
            drawLine(ink, Offset(x + arm, ay), Offset(x + half(ay - 26f), ay - 26f), 1.8f)
            for (end in listOf(-arm, arm)) drawLine(ink, Offset(x + end, ay), Offset(x + end, ay + 12f), 2f)
        }
        drawLine(ink, Offset(x, top - 20f), Offset(x, top), 2f)
    }

    private fun DrawScope.wires() {
        val ink = Color(0xFF241A3C)
        fun wire(from: Offset, to: Offset, sag: Float) {
            val path = Path().apply {
                moveTo(from.x, from.y)
                quadraticTo((from.x + to.x) / 2, (from.y + to.y) / 2 + sag, to.x, to.y)
            }
            drawPath(path, ink, style = Stroke(1.8f))
        }
        val near = 560f to 262f
        val far = 240f to 360f
        for ((dy, arm) in listOf(54f to 44f, 92f to 36f)) {
            for (side in listOf(-1f, 1f)) {
                val a = Offset(near.first + side * arm, near.second + dy)
                val b = Offset(far.first + side * arm * 0.8f, far.second + dy * 0.8f)
                wire(a, b, 22f)
                // On towards a pylon beyond the top of the picture, and away to the left.
                wire(a, Offset(a.x + 300f + side * 30f, -40f), 30f)
                wire(b, Offset(-60f, b.y + 120f + side * 10f), 26f)
            }
        }
    }

    /** A wire fence along the wall: thin posts and a fine mesh, catching a little light. */
    private fun DrawScope.fence() {
        val top = 700f
        val metal = Color(0xFF6E7190)
        // The mesh first, only above the wall.
        var y = top + 4f
        while (y < wallTop(W)) {
            val from = max(90f, (y - 860f) / 0.095f)
            if (from < W) drawLine(metal.copy(alpha = 0.28f), Offset(from, y), Offset(W, y), 1.4f)
            y += 26f
        }
        var x = 90f
        while (x < W) {
            drawLine(metal.copy(alpha = 0.2f), Offset(x, top), Offset(x, wallTop(x)), 1.2f)
            x += 24f
        }
        drawLine(metal.copy(alpha = 0.6f), Offset(90f, top), Offset(W, top + 6f), 2f)
        for (px in listOf(95f, 225f, 470f, 820f, 1300f, 1600f)) {
            drawLine(Color(0xFF55597A), Offset(px, top - 14f), Offset(px, wallTop(px)), 7f)
            drawLine(Color(0xFF8A8EB0).copy(alpha = 0.5f), Offset(px - 2f, top - 14f), Offset(px - 2f, wallTop(px)), 1.5f)
        }
    }

    /** The low wall in front: a dusky red top catching the sky, and its darker face. */
    private fun DrawScope.wall() {
        val face = Path().apply {
            moveTo(0f, wallTop(0f))
            lineTo(W, wallTop(W))
            lineTo(W, H)
            lineTo(0f, H)
            close()
        }
        drawPath(face, Color(0xFF1A1626))
        val top = Path().apply {
            moveTo(0f, wallTop(0f))
            lineTo(W, wallTop(W))
            lineTo(W, wallTop(W) + 40f)
            lineTo(0f, wallTop(0f) + 40f)
            close()
        }
        drawPath(top, Brush.verticalGradient(0f to Color(0xFF7A3450), 1f to Color(0xFF4A2036), startY = 860f, endY = 1080f))
        drawLine(Color(0xFFB0587A).copy(alpha = 0.6f), Offset(0f, wallTop(0f)), Offset(W, wallTop(W)), 3f)
    }

    // --- Things that move ---------------------------------------------------------------------

    private fun DrawScope.windows(t: Float) {
        for (w in windows) {
            val flicker = 0.85f + 0.15f * sin(t * 3.1f + w.phase * 2)
            drawCircle(
                Brush.radialGradient(
                    0f to Glow.copy(alpha = 0.45f * flicker), 1f to Glow.copy(alpha = 0f),
                    center = Offset(w.x + w.w / 2, w.y + w.h / 2), radius = 40f,
                ),
                40f, Offset(w.x + w.w / 2, w.y + w.h / 2), blendMode = BlendMode.Screen,
            )
            drawRect(Color(0xFFFFC98A).copy(alpha = flicker), Offset(w.x, w.y), Size(w.w, w.h))
        }
    }

    private fun DrawScope.fireflies(t: Float, dt: Float) {
        for (f in fireflies) {
            f.x += sin(t * f.speed + f.phase) * 14f * dt
            f.y += cos(t * f.speed * 0.8f + f.phase) * 8f * dt
            val pulse = 0.5f + 0.5f * sin(t * 2.6f * f.speed + f.phase)
            val near = (f.y - HORIZON) / (H - HORIZON)
            val radius = 2.5f + 5f * near
            drawCircle(
                Brush.radialGradient(
                    0f to Glow.copy(alpha = 0.95f * pulse), 0.3f to Glow.copy(alpha = 0.5f * pulse), 1f to Glow.copy(alpha = 0f),
                    center = Offset(f.x, f.y), radius = radius * 2.5f,
                ),
                radius * 2.5f, Offset(f.x, f.y), blendMode = BlendMode.Screen,
            )
        }
    }

    /** Glowing butterflies, wandering a little and beating their wings. */
    private fun DrawScope.butterflies(t: Float) {
        for (b in butterflies) {
            val x = b.x + sin(t * 0.9f + b.phase) * 30f
            val y = b.y + sin(t * 1.4f + b.phase * 2) * 14f
            val s = 16f * b.size
            drawCircle(
                Brush.radialGradient(
                    0f to Glow.copy(alpha = 0.6f), 1f to Glow.copy(alpha = 0f), center = Offset(x, y), radius = s * 3.5f,
                ),
                s * 3.5f, Offset(x, y), blendMode = BlendMode.Screen,
            )
            val open = 0.25f + 0.75f * abs(cos(t * 9f + b.phase))
            rotate(-20f + sin(t + b.phase) * 10f, Offset(x, y)) {
                for (side in listOf(-1f, 1f)) {
                    val wing = Path().apply {
                        moveTo(x, y)
                        lineTo(x + side * s * open, y - s)
                        lineTo(x + side * s * 0.8f * open, y + s * 0.2f)
                        close()
                        moveTo(x, y)
                        lineTo(x + side * s * 0.6f * open, y + s * 0.7f)
                        lineTo(x + side * s * 0.2f * open, y + s * 0.6f)
                        close()
                    }
                    drawPath(wing, Color(0xFFFFD6A0))
                }
            }
        }
    }

    /** A black cat walking left along the top of the wall, tail up. */
    private fun DrawScope.cat(t: Float) {
        val x = 1190f - t * 34f
        val ground = wallTop(x) + 4f
        val step = t * 6.5f
        // A long low body, a round head held forward, and pointed ears.
        val bob = abs(sin(step)) * 2f
        // Each part filled on its own: overlapping shapes in one path leave seams where they cross.
        val black = Color(0xFF0D0915)
        drawOval(black, Offset(x - 56f, ground - 76f - bob), Size(118f, 40f))
        drawOval(black, Offset(x - 96f, ground - 104f - bob), Size(46f, 42f))
        val body = Path().apply {
            moveTo(x - 94f, ground - 88f - bob)
            lineTo(x - 92f, ground - 120f - bob)
            lineTo(x - 76f, ground - 100f - bob)
            close()
            moveTo(x - 72f, ground - 102f - bob)
            lineTo(x - 62f, ground - 122f - bob)
            lineTo(x - 54f, ground - 92f - bob)
            close()
            // The neck, joining head and body.
            moveTo(x - 66f, ground - 96f - bob)
            lineTo(x - 30f, ground - 74f - bob)
            lineTo(x - 40f, ground - 48f - bob)
            lineTo(x - 70f, ground - 66f - bob)
            close()
        }
        drawPath(body, black)
        // Legs, swinging in diagonal pairs, with the far ones a shade lighter.
        for ((hip, phase, far) in listOf(
            Triple(-38f, 0f, false), Triple(-24f, PI.toFloat(), true), Triple(36f, PI.toFloat(), false), Triple(48f, 0f, true),
        )) {
            val swing = sin(step + phase) * 10f
            val lift = max(0f, cos(step + phase)) * 5f
            drawLine(
                if (far) Color(0xFF1A1426) else Color(0xFF0D0915), Offset(x + hip, ground - 48f - bob),
                Offset(x + hip + swing, ground - 3f - lift), 12f, cap = StrokeCap.Round,
            )
        }
        val sway = sin(t * 2f) * 8f
        val tail = Path().apply {
            moveTo(x + 56f, ground - 62f - bob)
            cubicTo(x + 104f, ground - 58f, x + 118f + sway, ground - 104f, x + 100f + sway, ground - 128f)
        }
        drawPath(tail, Color(0xFF0D0915), style = Stroke(10f, cap = StrokeCap.Round))
    }

    // --- The title and the film -----------------------------------------------------------------

    private var letters: List<TextLayoutResult>? = null
    private var question: TextLayoutResult? = null

    private fun DrawScope.title(t: Float, measurer: TextMeasurer) {
        // Laid out once, at 168 px on the 1920-wide stage: sp is per density, so convert.
        if (letters == null) {
            val style = TextStyle(fontSize = (168f / density / fontScale).sp, fontWeight = FontWeight.Black)
            letters = "QUEM DIRIA".map { measurer.measure(it.toString(), style) }
            question = measurer.measure("?", style)
        }
        val layouts = letters!!
        val q = question!!
        val total = layouts.sumOf { it.size.width } + q.size.width
        var x = (W - total) / 2
        val top = H * 0.4f - layouts[0].firstBaseline
        layouts.forEachIndexed { i, layout ->
            val p = easeOut((t - (0.7f + i * 0.11f)) / 0.45f)
            if (p > 0f) {
                // Each letter rises into place; its colour split (a worn VHS look) closes as it settles, and
                // flickers open now and then afterwards.
                val flicker = if (t > 2.6f) max(0f, sin(t * 23f) - 0.97f) * 120f else 0f
                val split = (1 - p) * 18f + flicker
                val y = top + (1 - p) * 40f
                drawText(layout, Color(255, 60, 120).copy(alpha = 0.9f * p), Offset(x - split, y), blendMode = BlendMode.Screen)
                drawText(layout, Color(60, 220, 255).copy(alpha = 0.75f * p), Offset(x + split, y), blendMode = BlendMode.Screen)
                drawText(
                    layout, TextColour, Offset(x, y), alpha = p,
                    shadow = Shadow(Color(255, 150, 190).copy(alpha = 0.6f), blurRadius = 30f),
                )
            }
            x += layout.size.width
        }
        // The question mark drops in last, in the accent pink, with a bounce.
        val qStart = 2.1f
        if (t > qStart) {
            val p = bounce((t - qStart) / 0.7f)
            drawText(
                q, Accent, Offset(x, top - (1 - p) * 260f), alpha = min(1f, (t - qStart) / 0.12f),
                shadow = Shadow(Accent.copy(alpha = 0.8f), blurRadius = 40f),
            )
        }
    }

    private fun DrawScope.lightLeaks(t: Float) {
        for ((x, y, colour) in listOf(
            Triple(0.08f + sin(t * 0.35f) * 0.05f, 0.15f, Color(255, 120, 170)),
            Triple(0.96f + cos(t * 0.27f) * 0.04f, 0.05f, Color(255, 170, 120)),
        )) {
            drawRect(
                Brush.radialGradient(
                    0f to colour.copy(alpha = 0.2f + 0.05f * sin(t * 1.7f + x * 9)), 1f to colour.copy(alpha = 0f),
                    center = Offset(W * x, H * y), radius = W * 0.45f,
                ),
                size = Size(W, H), blendMode = BlendMode.Screen,
            )
        }
    }

    private fun DrawScope.vignette() {
        drawRect(
            Brush.radialGradient(
                0.4f to Color.Transparent, 1f to Color(10, 6, 22).copy(alpha = 0.7f),
                center = Offset(W / 2, H / 2), radius = H * 1.05f,
            ),
            size = Size(W, H),
        )
        // Faint scanlines.
        val line = Color.Black.copy(alpha = 0.04f)
        var y = 0f
        while (y < H) {
            drawRect(line, Offset(0f, y), Size(W, 1f))
            y += 4f
        }
    }

    private fun DrawScope.grain(t: Float) {
        drawImage(
            grain[((t * 24).toInt()) % grain.size], srcOffset = IntOffset.Zero, srcSize = IntSize(GRAIN_W, GRAIN_H),
            dstOffset = IntOffset.Zero, dstSize = IntSize(W.toInt(), H.toInt()),
            alpha = 0.06f, blendMode = BlendMode.Overlay, filterQuality = FilterQuality.Low,
        )
    }

    private fun easeOut(x: Float): Float = 1 - (1 - x.coerceIn(0f, 1f)).pow(3)

    private fun bounce(value: Float): Float {
        var x = value.coerceIn(0f, 1f)
        val n = 7.5625f
        val d = 2.75f
        return when {
            x < 1 / d -> n * x * x
            x < 2 / d -> { x -= 1.5f / d; n * x * x + 0.75f }
            x < 2.5f / d -> { x -= 2.25f / d; n * x * x + 0.9375f }
            else -> { x -= 2.625f / d; n * x * x + 0.984375f }
        }
    }

    private companion object {
        const val GRAIN_W = 640
        const val GRAIN_H = 360
    }
}
