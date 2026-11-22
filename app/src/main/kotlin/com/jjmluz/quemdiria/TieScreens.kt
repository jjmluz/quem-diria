package com.jjmluz.quemdiria

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The tied players' names, as on the end screen: "Maya & Daniel". */
internal fun tiedNames(room: Room, ids: List<Int>) = ids.mapNotNull { room.player(it)?.name }.joinToString(" & ")

/** Before the tie-breaker: who's tied, and what it's about. */
@Composable
fun TieIntro(room: Room, game: Game) {
    val tie = game.tie ?: return
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.tie_title), 72.sp, weight = FontWeight.Black, lines = 1)
        Spacer(Modifier.height(24.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            tie.contenders.mapNotNull { room.player(it) }.forEach {
                Box(Modifier.border(4.dp, Yellow, CircleShape).padding(4.dp)) { Avatar(it, if (tie.contenders.size <= 4) 96.dp else 64.dp) }
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(
            stringResource(R.string.tie_subtitle, tiedNames(room, tie.contenders)), 30.sp, Accent, FontWeight.Bold,
            align = TextAlign.Center, lines = 2, modifier = Modifier.widthIn(max = 820.dp),
        )
    }
}

/**
 * A tie-breaker question, and its reveal: the answer, each tied player's guess (right or not), and what it
 * means: a winner, or who's still in.
 */
@Composable
fun TieScreen(room: Room, game: Game, reveal: Boolean) {
    val tie = game.tie ?: return
    Column(Modifier.fillMaxSize()) {
        Header(stringResource(R.string.tie_header, tie.round), game, Game.TIE_MS)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            when (val q = tie.question) {
                is Memory.Drew -> DrewQuestion(room, q, reveal)
                is Memory.Picked -> PickedQuestion(room, q, reveal)
                is Memory.Answered -> AnsweredQuestion(room, game, q, reveal)
            }
        }
        Spacer(Modifier.height(12.dp))
        if (reveal) {
            Guesses(room, game, tie)
            Spacer(Modifier.height(10.dp))
            val right = tie.right.orEmpty()
            Text(
                when {
                    right.size == 1 -> stringResource(R.string.tie_won, tiedNames(room, right))
                    right.isEmpty() -> stringResource(R.string.tie_nobody)
                    right.size == tie.contenders.size -> stringResource(R.string.tie_still)
                    else -> stringResource(R.string.tie_still_in, tiedNames(room, right))
                },
                30.sp, Yellow, FontWeight.Black, TextAlign.Center, Modifier.fillMaxWidth(), lines = 1,
            )
        } else {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Progress(tie.contenders.mapNotNull { room.player(it) }, game.answers.keys)
            }
            Spacer(Modifier.height(16.dp))
            BottomHint(stringResource(R.string.tie_only, tiedNames(room, tie.contenders)), stringResource(R.string.dont_wait))
        }
    }
}

@Composable
private fun DrewQuestion(room: Room, q: Memory.Drew, reveal: Boolean) {
    val image = remember(q.drawing.jpeg) { decode(q.drawing.jpeg) }
    val drawer = room.player(q.drawing.drawerId)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Box(Modifier.size(260.dp).clip(RoundedCornerShape(20.dp)).background(Card)) {
            if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.tie_who_drew), 44.sp, weight = FontWeight.Black, lines = 2)
            Spacer(Modifier.height(8.dp))
            Text(q.drawing.prompt.withName(room.player(q.drawing.spotlightId)?.name.orEmpty()), 22.sp, Muted, lines = 2)
            if (reveal && drawer != null) {
                Spacer(Modifier.height(16.dp))
                Answer(drawer, stringResource(R.string.tie_drawn_by, drawer.name))
            }
        }
    }
}

@Composable
private fun PickedQuestion(room: Room, q: Memory.Picked, reveal: Boolean) {
    val picked = room.player(q.player)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.widthIn(max = 820.dp)) {
        Text(stringResource(R.string.tie_picked), 24.sp, Yellow, FontWeight.Bold, TextAlign.Center, lines = 2)
        Spacer(Modifier.height(12.dp))
        Text(q.question, 40.sp, weight = FontWeight.Black, align = TextAlign.Center, lines = 3)
        if (reveal && picked != null) {
            Spacer(Modifier.height(16.dp))
            Answer(picked, stringResource(R.string.tie_room_picked, picked.name))
        }
    }
}

@Composable
private fun AnsweredQuestion(room: Room, game: Game, q: Memory.Answered, reveal: Boolean) {
    val player = room.player(q.player) ?: return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(180.dp)) {
            Box(Modifier.border(5.dp, Yellow, CircleShape).padding(6.dp)) { Avatar(player, 120.dp) }
            Spacer(Modifier.height(10.dp))
            Text(player.name, 24.sp, weight = FontWeight.Black, lines = 1)
        }
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.tie_answered, player.name), 20.sp, Yellow, FontWeight.Bold, lines = 1)
            Text(q.question.text.withName(player.name), 28.sp, weight = FontWeight.Black, lines = 2)
            Spacer(Modifier.height(10.dp))
            q.question.options.forEachIndexed { i, option ->
                val right = reveal && q.answer == i
                val guessers = if (reveal) game.answers.filterValues { it == i }.keys else emptySet()
                Row(
                    Modifier.fillMaxWidth().padding(bottom = 6.dp)
                        .background(if (right) Green.copy(alpha = 0.25f) else Card, RoundedCornerShape(14.dp))
                        .then(if (right) Modifier.border(3.dp, Green, RoundedCornerShape(14.dp)) else Modifier)
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(Modifier.size(30.dp).background(Background, CircleShape), contentAlignment = Alignment.Center) {
                        Text("ABCD"[i].toString(), 16.sp, Yellow, FontWeight.Black)
                    }
                    Text(option, 20.sp, modifier = Modifier.weight(1f), lines = 1)
                    guessers.mapNotNull { room.player(it) }.forEach { Avatar(it, 30.dp) }
                }
            }
        }
    }
}

/** The right answer, when it's a player: their face and who it was. */
@Composable
private fun Answer(player: Player, text: String) {
    Row(
        Modifier.background(Green.copy(alpha = 0.2f), CircleShape).border(3.dp, Green, CircleShape)
            .padding(start = 6.dp, end = 20.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Avatar(player, 52.dp)
        Text(text, 24.sp, weight = FontWeight.Bold, lines = 1)
    }
}

/** Each tied player at the reveal: right (a green tick) or not (a cross), with who they said when it's a player. */
@Composable
private fun Guesses(room: Room, game: Game, tie: TieBreak) {
    val right = tie.right.orEmpty()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp, Alignment.CenterHorizontally)) {
        tie.contenders.mapNotNull { room.player(it) }.forEach { p ->
            val guess = game.answers[p.id]
            val said = when {
                guess == null -> "—"
                tie.question is Memory.Answered -> "ABCD"[guess].toString()
                else -> room.player(guess)?.name.orEmpty()
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box {
                    Avatar(p, 48.dp)
                    Box(
                        Modifier.align(Alignment.BottomEnd).offset(4.dp, 4.dp).size(22.dp)
                            .background(if (p.id in right) Green else Accent, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text(if (p.id in right) "✓" else "✗", 13.sp, Background, FontWeight.Black) }
                }
                Column {
                    Text(p.name, 18.sp, weight = FontWeight.Bold, lines = 1)
                    Text(said, 16.sp, Muted, lines = 1)
                }
            }
        }
    }
}
