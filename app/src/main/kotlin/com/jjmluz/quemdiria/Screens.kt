package com.jjmluz.quemdiria

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

// The "Serão" palette (an evening in with the family). The phone page uses the same values (web/style.css).
val Background = Color(0xFF170F2B)
val Card = Color(0xFF2A1D4A)
val Accent = Color(0xFFFF5C8A)
val Yellow = Color(0xFFFFC83D)
val TextColour = Color(0xFFF6F3FF)
val Muted = Color(0xFFB3A8D1)

/** Player colours by [Player.colour]; the phone page has the same list. */
val PlayerColours = listOf(
    Color(0xFFFF5C8A), Color(0xFFFFC83D), Color(0xFF2EC4B6), Color(0xFF4D8DFF),
    Color(0xFFA974FF), Color(0xFFFF8A3D), Color(0xFF7BD389), Color(0xFFFF9BD2),
)

@Composable
fun QuemDiriaApp(viewModel: RoomViewModel) {
    val room by viewModel.room.collectAsStateWithLifecycle()
    val invite by viewModel.invite.collectAsStateWithLifecycle()
    val button by viewModel.button.collectAsStateWithLifecycle()
    val narrator by viewModel.narratorOn.collectAsStateWithLifecycle()
    val paused by viewModel.paused.collectAsStateWithLifecycle()
    Box(Modifier.fillMaxSize().background(Background)) {
        val game = room.game
        if (room.phase == Phase.GAME && game != null) {
            GameScreen(room, game)
            if (paused) PauseOverlay()
        } else {
            LobbyScreen(room, invite, button, narrator)
        }
    }
}

@Composable
fun Logo(size: Int) {
    BasicText(
        buildAnnotatedString {
            append("QUEM DIRIA")
            withStyle(SpanStyle(color = Accent)) { append("?") }
        },
        style = TextStyle(color = TextColour, fontSize = size.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp),
    )
}

@Composable
private fun LobbyScreen(room: Room, invite: Invite?, button: LobbyButton, narrator: Boolean) {
    Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 28.dp)) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(56.dp)) {
            Column(Modifier.width(300.dp)) {
                Logo(40)
                Spacer(Modifier.height(16.dp))
                JoinCard(invite)
            }
            Players(room, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        // Left and right move between the buttons; OK presses the one that's lit.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val mode = stringResource(if (room.mode == Mode.ADULTS) R.string.mode_adults else R.string.mode_family)
            Pill(focused = button == LobbyButton.MODE) {
                if (button == LobbyButton.MODE) OkIcon()
                Label(mode, colour = if (button == LobbyButton.MODE) TextColour else Muted)
            }
            Pill(focused = button == LobbyButton.NARRATOR) {
                if (button == LobbyButton.NARRATOR) OkIcon()
                Label(
                    stringResource(if (narrator) R.string.narrator_on else R.string.narrator_off),
                    colour = if (button == LobbyButton.NARRATOR) TextColour else Muted,
                )
            }
            val missing = Lobby.MIN_PLAYERS - room.players.size
            val focused = button == LobbyButton.START
            if (missing > 0) {
                Pill(focused = focused) { Label(pluralStringResource(R.plurals.players_missing, missing, missing)) }
            } else {
                Pill(highlighted = true, focused = focused) {
                    if (focused) OkIcon()
                    Label(stringResource(R.string.start), colour = TextColour)
                }
            }
        }
    }
}

/** The QR code that opens the phone page, with the address to type for a phone that can't scan. */
@Composable
private fun JoinCard(invite: Invite?) {
    Column(
        Modifier.fillMaxWidth().background(Card, RoundedCornerShape(20.dp)).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (invite == null) {
            BasicText(
                stringResource(R.string.no_network),
                style = TextStyle(color = Yellow, fontSize = 18.sp, textAlign = TextAlign.Center),
            )
            return
        }
        val qr = remember(invite.url) { qrCode(invite.url) }
        Box(Modifier.size(176.dp).background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp)) {
            Image(qr, null, Modifier.fillMaxSize(), filterQuality = FilterQuality.None)
        }
        Spacer(Modifier.height(14.dp))
        BasicText(
            stringResource(R.string.scan_to_join),
            style = TextStyle(color = TextColour, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
        )
        Spacer(Modifier.height(8.dp))
        BasicText(
            stringResource(R.string.or_open, invite.host, invite.code),
            style = TextStyle(color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center),
        )
    }
    // Android 17 asks before an app reaches devices at home. If Chrome was refused, the page fails with
    // ERR_TOO_MANY_RETRIES and the TV never hears from the phone, so the fix can only be shown here.
    Spacer(Modifier.height(10.dp))
    BasicText(
        stringResource(R.string.local_network_hint),
        style = TextStyle(color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun Players(room: Room, modifier: Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            BasicText(
                stringResource(R.string.players),
                style = TextStyle(color = TextColour, fontSize = 26.sp, fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.width(12.dp))
            BasicText(
                "${room.players.size}/${Lobby.MAX_PLAYERS}",
                style = TextStyle(color = Muted, fontSize = 20.sp),
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        // Two rows of four seats: everyone who has joined, then empty places.
        for (row in 0 until 2) {
            Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (col in 0 until 4) {
                    val player = room.players.getOrNull(row * 4 + col)
                    Box(Modifier.weight(1f)) {
                        if (player != null) PlayerSeat(player) else EmptySeat()
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerSeat(player: Player) {
    val alpha by animateFloatAsState(if (player.connected) 1f else 0.4f, label = "connected")
    Column(
        Modifier.fillMaxWidth().alpha(alpha).background(Card, RoundedCornerShape(20.dp)).padding(vertical = 18.dp, horizontal = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Avatar(player, 104.dp)
        Spacer(Modifier.height(12.dp))
        BasicText(
            player.name,
            style = TextStyle(color = TextColour, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmptySeat() {
    Column(Modifier.fillMaxWidth().padding(vertical = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(104.dp)) {
            drawCircle(
                Card,
                radius = size.minDimension / 2 - 2.dp.toPx(),
                style = Stroke(3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 12f))),
            )
        }
        Spacer(Modifier.height(12.dp))
        BasicText(" ", style = TextStyle(fontSize = 18.sp))
    }
}

/** The player's selfie in a circle, or their initial on their colour until they take one. */
@Composable
fun Avatar(player: Player, size: Dp) {
    val colour = PlayerColours[player.colour % PlayerColours.size]
    val selfie = remember(player.id, player.selfieVersion) { player.selfie?.let(::decode) }
    Box(Modifier.size(size).clip(CircleShape).background(colour), contentAlignment = Alignment.Center) {
        if (selfie != null) {
            Image(selfie, player.name, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        } else {
            BasicText(
                player.name.take(1).uppercase(),
                style = TextStyle(color = Background, fontSize = (size.value * 0.45f).sp, fontWeight = FontWeight.Black),
            )
        }
    }
}

/**
 * A hint or button pill. [highlighted] when it's the thing to do; [focused] when OK would press it, shown
 * with an outline.
 */
@Composable
fun Pill(highlighted: Boolean = false, focused: Boolean = false, content: @Composable () -> Unit) {
    Row(
        Modifier
            .background(if (highlighted) Accent.copy(alpha = 0.25f) else Card, CircleShape)
            .then(if (focused) Modifier.border(2.dp, Accent, CircleShape) else Modifier)
            .padding(horizontal = 22.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
fun Label(text: String, colour: Color = Muted) {
    BasicText(text, style = TextStyle(color = colour, fontSize = 18.sp, fontWeight = FontWeight.SemiBold))
}

/** The remote's OK button: the D-pad's ring with its centre lit. */
@Composable
fun OkIcon() {
    Canvas(Modifier.size(26.dp)) {
        drawCircle(TextColour, radius = size.minDimension / 2 - 1.5.dp.toPx(), style = Stroke(2.dp.toPx()))
        drawCircle(Accent, radius = size.minDimension * 0.22f)
    }
}

/** The remote's Back button: a curved arrow. */
@Composable
fun BackIcon() {
    Canvas(Modifier.size(26.dp)) {
        val w = size.width
        val stroke = 2.5.dp.toPx()
        drawArc(
            Muted, startAngle = -90f, sweepAngle = 180f, useCenter = false,
            topLeft = Offset(w * 0.25f, w * 0.2f), size = androidx.compose.ui.geometry.Size(w * 0.55f, w * 0.6f),
            style = Stroke(stroke),
        )
        drawLine(Muted, Offset(w * 0.52f, w * 0.8f), Offset(w * 0.2f, w * 0.8f), stroke)
        drawLine(Muted, Offset(w * 0.52f, w * 0.2f), Offset(w * 0.2f, w * 0.2f), stroke)
        drawLine(Muted, Offset(w * 0.2f, w * 0.2f), Offset(w * 0.34f, w * 0.06f), stroke)
        drawLine(Muted, Offset(w * 0.2f, w * 0.2f), Offset(w * 0.34f, w * 0.34f), stroke)
    }
}

fun decode(jpeg: ByteArray): ImageBitmap? =
    BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap()

/** [text] as a QR code, one pixel per module, to be scaled up without smoothing. */
private fun qrCode(text: String): ImageBitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    val pixels = IntArray(matrix.width * matrix.height) { i ->
        if (matrix[i % matrix.width, i / matrix.width]) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    return Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888).asImageBitmap()
}
