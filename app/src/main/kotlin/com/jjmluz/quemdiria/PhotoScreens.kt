package com.jjmluz.quemdiria

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** A Google Photos face in a circle; a plain circle until it has loaded. */
@Composable
fun PersonFace(face: ImageBitmap?, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(Background)) {
        if (face != null) Image(face, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/** On a lobby seat: who the player is in Google Photos. */
@Composable
fun PersonBadge(face: ImageBitmap?, modifier: Modifier = Modifier) {
    Box(modifier.offset(6.dp, 6.dp).border(3.dp, Card, CircleShape)) { PersonFace(face, 40.dp) }
}

/** Over the lobby: pick a player, then who they are among the people Google Photos knows. */
@Composable
fun LinkingOverlay(room: Room, linking: Linking, faces: Map<String, ImageBitmap>, names: Map<String, String>) {
    val players = room.players
    val player = players.getOrNull(linking.player.coerceIn(0, (players.size - 1).coerceAtLeast(0)))
    Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 56.dp, vertical = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.link_title), 30.sp, weight = FontWeight.Black, lines = 1, modifier = Modifier.weight(1f))
        }
        Text(stringResource(R.string.link_hint), 16.sp, Muted, lines = 1)
        Spacer(Modifier.height(16.dp))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            val problem = linking.problem
            when {
                problem != null -> Message(stringResource(when (problem) {
                    LinkProblem.NOT_ALLOWED -> R.string.link_not_allowed
                    LinkProblem.NOT_SIGNED_IN -> R.string.link_not_signed_in
                    LinkProblem.FAILED -> R.string.link_failed
                    LinkProblem.NO_PEOPLE -> R.string.link_no_people
                }))
                linking.people == null -> Message(stringResource(R.string.link_loading))
                linking.choosing && player != null -> PeopleGrid(player, linking, faces)
                else -> PlayerGrid(players, linking.player, faces, names)
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
            if (linking.problem == null && linking.people != null) {
                Pill {
                    OkIcon()
                    Label(stringResource(R.string.link_choose))
                }
            }
            Pill {
                BackIcon()
                Label(stringResource(R.string.link_done))
            }
        }
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, 24.sp, Yellow, FontWeight.Bold, TextAlign.Center, Modifier.width(640.dp))
    }
}

@Composable
private fun PlayerGrid(players: List<Player>, focused: Int, faces: Map<String, ImageBitmap>, names: Map<String, String>) {
    Column {
        Text(stringResource(R.string.link_pick_player), 20.sp, Yellow, FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        players.chunked(4).forEachIndexed { row, chunk ->
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (col in 0 until 4) {
                    val i = row * 4 + col
                    val p = chunk.getOrNull(col)
                    Box(Modifier.weight(1f)) {
                        if (p != null) LinkCard(p, i == focused, p.person?.let(faces::get), p.person?.let(names::get))
                    }
                }
            }
        }
    }
}

/** A player, and who they're linked to: their face and name in Google Photos, or "Not linked". */
@Composable
private fun LinkCard(player: Player, focused: Boolean, face: ImageBitmap?, personName: String?) {
    Row(
        Modifier.fillMaxWidth().background(Card, RoundedCornerShape(18.dp))
            .then(if (focused) Modifier.border(3.dp, Accent, RoundedCornerShape(18.dp)) else Modifier)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Avatar(player, 64.dp)
        Column(Modifier.weight(1f)) {
            Text(player.name, 18.sp, weight = FontWeight.Bold, lines = 1)
            Spacer(Modifier.height(6.dp))
            if (player.person != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    PersonFace(face, 28.dp)
                    Text(personName ?: "?", 14.sp, Green, lines = 1)
                }
            } else {
                Text(stringResource(R.string.not_linked), 14.sp, Muted, lines = 1)
            }
        }
    }
}

@Composable
private fun PeopleGrid(player: Player, linking: Linking, faces: Map<String, ImageBitmap>) {
    val people = linking.people.orEmpty()
    val state = rememberLazyGridState()
    // Keep the lit face on screen as the d-pad moves through a long list.
    LaunchedEffect(linking.person) { state.animateScrollToItem((linking.person - 7).coerceAtLeast(0)) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Avatar(player, 40.dp)
            Text(stringResource(R.string.link_pick_person, player.name), 20.sp, Yellow, FontWeight.Bold, lines = 1)
        }
        Spacer(Modifier.height(12.dp))
        LazyVerticalGrid(
            GridCells.Fixed(7), state = state,
            horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            userScrollEnabled = false,
        ) {
            items(people.size + 1) { i ->
                val focused = i == linking.person
                val person = people.getOrNull(i - 1)
                Column(
                    Modifier.fillMaxWidth()
                        .background(if (focused) Card else Background, RoundedCornerShape(14.dp))
                        .then(if (focused) Modifier.border(3.dp, Accent, RoundedCornerShape(14.dp)) else Modifier)
                        .padding(8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (person == null) {
                        Box(Modifier.size(72.dp).background(Card, CircleShape), contentAlignment = Alignment.Center) {
                            Text("—", 28.sp, Muted, FontWeight.Black)
                        }
                    } else {
                        PersonFace(faces[person.key], 72.dp)
                    }
                    Spacer(Modifier.height(6.dp))
                    val linked = person != null && person.key == player.person
                    Text(
                        person?.name ?: stringResource(R.string.not_linked), 14.sp,
                        if (linked) Green else TextColour, if (linked) FontWeight.Bold else FontWeight.Normal,
                        TextAlign.Center, Modifier.fillMaxWidth(), lines = 1,
                    )
                }
            }
        }
    }
}

/** A question about a player's photo: the photo, the four choices, and at the reveal who guessed what. */
@Composable
fun PhotoScreen(room: Room, game: Game, photos: Map<String, ImageBitmap>, reveal: Boolean) {
    val step = game.step as Step.Photo
    val player = room.player(step.playerId) ?: return
    val question = step.question
    Column(Modifier.fillMaxSize()) {
        Header(stringResource(R.string.photo_title, player.name), game, Game.PHOTO_MS)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
            Box(
                Modifier.width(470.dp).fillMaxHeight().clip(RoundedCornerShape(18.dp)).background(Card),
                contentAlignment = Alignment.Center,
            ) {
                val picture = photos[question.photo]
                if (picture != null) {
                    Image(picture, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                } else {
                    Text(stringResource(R.string.photo_loading), 18.sp, Muted, align = TextAlign.Center)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(question.text.withName(player.name), 26.sp, weight = FontWeight.Black, lines = 3)
                Spacer(Modifier.height(12.dp))
                question.options.forEachIndexed { i, option ->
                    val right = reveal && question.truth == i
                    val guessers = if (reveal) game.answers.filterValues { it == i }.keys else emptySet()
                    Row(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp)
                            .background(if (right) Green.copy(alpha = 0.25f) else Card, RoundedCornerShape(14.dp))
                            .then(if (right) Modifier.border(3.dp, Green, RoundedCornerShape(14.dp)) else Modifier)
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(30.dp).background(Background, CircleShape), contentAlignment = Alignment.Center) {
                            Text("ABCD"[i].toString(), 16.sp, Yellow, FontWeight.Black)
                        }
                        Text(option, 20.sp, modifier = Modifier.weight(1f), lines = 2)
                        guessers.mapNotNull { room.player(it) }.forEach { Avatar(it, 28.dp) }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (reveal) {
            Gains(room, game)
        } else {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Progress(room.players, game.answers.keys) }
            Spacer(Modifier.height(12.dp))
            BottomHint(stringResource(R.string.photo_rules, player.name), stringResource(R.string.dont_wait))
        }
    }
}
