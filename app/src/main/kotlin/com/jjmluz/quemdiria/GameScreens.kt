package com.jjmluz.quemdiria

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

private val Green = Color(0xFF7BD389)

/** The TV during a game: one screen per stage, faded from one to the next. */
@Composable
fun GameScreen(room: Room, game: Game) {
    AnimatedContent(
        targetState = game,
        // A new stage fades in; votes coming in within a stage just update it. The outgoing screen keeps its
        // own snapshot of the game, so it never draws a stage it wasn't made for.
        contentKey = { it.serial },
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "stage",
    ) { g ->
        Box(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 32.dp)) {
            when (g.stage) {
                Stage.VOTE -> VoteScreen(room, g)
                Stage.VOTE_REVEAL -> VoteReveal(room, g)
                Stage.ANSWER -> AnswerScreen(room, g, reveal = false)
                Stage.ANSWER_REVEAL -> AnswerScreen(room, g, reveal = true)
                Stage.DRAW -> DrawScreen(room, g)
                Stage.PICK -> GalleryScreen(room, g, reveal = false)
                Stage.PICK_REVEAL -> GalleryScreen(room, g, reveal = true)
                Stage.SCORES -> ScoresScreen(room, g)
                Stage.FINAL_INTRO -> FinalIntro()
                Stage.END -> EndScreen(room, g)
            }
        }
    }
}

/** Seconds left in the stage, as a ring that empties; nothing for stages without a deadline. */
@Composable
private fun TimerRing(game: Game, total: Long, size: Dp = 64.dp) {
    // Reveals just pause before moving on; a countdown there would only distract.
    if (game.deadline == 0L || game.stage !in setOf(Stage.VOTE, Stage.ANSWER, Stage.DRAW, Stage.PICK)) return
    val now by produceState(System.currentTimeMillis(), game.serial, game.deadline) {
        while (true) {
            value = System.currentTimeMillis()
            delay(100)
        }
    }
    // Paused: game.remaining ignores the clock, so the ring stands still too.
    val left = game.remaining(now)
    val fraction = (left.toFloat() / total).coerceIn(0f, 1f)
    val colour = if (left < 5_000) Yellow else Accent
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 5.dp.toPx()
            drawCircle(Card, style = Stroke(stroke))
            drawArc(
                colour, startAngle = -90f, sweepAngle = 360f * fraction, useCenter = false,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke),
                style = Stroke(stroke, cap = StrokeCap.Round),
            )
        }
        BasicText(
            "${(left + 999) / 1000}",
            style = TextStyle(color = TextColour, fontSize = 22.sp, fontWeight = FontWeight.Bold),
        )
    }
}

@Composable
private fun Header(title: String, game: Game, total: Long) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BasicText(title, style = TextStyle(color = Yellow, fontSize = 20.sp, fontWeight = FontWeight.Bold))
        Spacer(Modifier.weight(1f))
        TimerRing(game, total)
    }
}

/**
 * TV text at [size], shrinking (down to 40%) rather than cutting off when it doesn't fit its space or [lines]:
 * names and questions vary in length, and the user wants every word visible.
 */
@Composable
private fun Text(text: String, size: TextUnit, colour: Color = TextColour, weight: FontWeight = FontWeight.Normal,
                 align: TextAlign = TextAlign.Start, modifier: Modifier = Modifier, lines: Int = Int.MAX_VALUE) {
    BasicText(
        text,
        modifier = modifier,
        style = TextStyle(color = colour, fontWeight = weight, textAlign = align, lineHeight = 1.2.em),
        maxLines = lines,
        overflow = TextOverflow.Ellipsis,
        autoSize = TextAutoSize.StepBased(minFontSize = size * 0.4f, maxFontSize = size, stepSize = 1.sp),
    )
}

/** Who has answered so far: everyone's avatar, faded until they've answered. */
@Composable
private fun Progress(players: List<Player>, done: Set<Int>) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        players.forEach { p ->
            Box(Modifier.alpha(if (p.id in done) 1f else 0.3f)) {
                Avatar(p, 48.dp)
                if (p.id in done) {
                    Box(
                        Modifier.align(Alignment.BottomEnd).offset(4.dp, 4.dp).size(20.dp).background(Green, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { Text("✓", 12.sp, Background, FontWeight.Black) }
                }
            }
        }
    }
}

@Composable
private fun BottomHint(text: String, okLabel: String?) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
        Pill { Label(text) }
        if (okLabel != null) {
            Pill {
                OkIcon()
                Label(okLabel)
            }
        }
    }
}



@Composable
private fun VoteScreen(room: Room, game: Game) {
    val step = game.step as Step.Vote
    val (n, total) = game.voteNumber
    val title = if (game.double) stringResource(R.string.final_round) else stringResource(R.string.question_of, n, total)
    Column(Modifier.fillMaxSize()) {
        Header(title, game, if (game.double) Game.FINAL_VOTE_MS else Game.VOTE_MS)
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(step.question.text, 44.sp, weight = FontWeight.Black, align = TextAlign.Center, modifier = Modifier.widthIn(max = 820.dp))
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Progress(room.players, game.votes.keys) }
        Spacer(Modifier.height(20.dp))
        BottomHint(stringResource(R.string.vote_on_phones), stringResource(R.string.dont_wait))
    }
}

@Composable
private fun VoteReveal(room: Room, game: Game) {
    val step = game.step as Step.Vote
    val byTarget = game.votes.entries.groupBy({ it.value }, { it.key })
    val ranked = room.players.filter { it.id in byTarget }.sortedByDescending { byTarget[it.id]!!.size }
    val top = ranked.firstOrNull()?.let { byTarget[it.id]!!.size } ?: 0
    Column(Modifier.fillMaxSize()) {
        Text(step.question.text, 28.sp, weight = FontWeight.Bold, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(24.dp))
        Row(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.Bottom,
        ) {
            ranked.forEach { target ->
                val voters = byTarget[target.id]!!
                val winner = voters.size == top
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(140.dp)) {
                    // The voters, stacked above the person they voted for.
                    voters.mapNotNull { room.player(it) }.forEach { voter ->
                        Avatar(voter, 36.dp)
                        Spacer(Modifier.height(4.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.then(if (winner) Modifier.border(4.dp, Yellow, CircleShape) else Modifier).padding(4.dp)) {
                        Avatar(target, if (winner) 112.dp else 88.dp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(target.name, 20.sp, weight = FontWeight.Bold, lines = 1)
                    Text(pluralStringResource(R.plurals.votes, voters.size, voters.size), 16.sp, Muted)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Gains(room, game)
    }
}

/** "+2" next to each player who scored at the last reveal. */
@Composable
private fun Gains(room: Room, game: Game) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally)) {
        room.players.filter { (game.gained[it.id] ?: 0) > 0 }.forEach { p ->
            Row(
                Modifier.background(Card, CircleShape).padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Avatar(p, 36.dp)
                Text("+${game.gained[p.id]}", 22.sp, Accent, FontWeight.Black)
            }
        }
    }
}

@Composable
private fun AnswerScreen(room: Room, game: Game, reveal: Boolean) {
    val step = game.step as Step.Spotlight
    val spotlight = room.player(step.playerId) ?: return
    val truth = game.answers[spotlight.id]
    Column(Modifier.fillMaxSize()) {
        Header(stringResource(R.string.in_spotlight), game, Game.ANSWER_MS)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(220.dp)) {
                Box(Modifier.border(5.dp, Yellow, CircleShape).padding(6.dp)) { Avatar(spotlight, 150.dp) }
                Spacer(Modifier.height(12.dp))
                Text(spotlight.name, 26.sp, weight = FontWeight.Black, lines = 1)
            }
            Column(Modifier.weight(1f)) {
                // The TV is only 540 dp tall: question, four answers and the hints all have to fit.
                Text(step.question.text.withName(spotlight.name), 28.sp, weight = FontWeight.Black, lines = 2)
                Spacer(Modifier.height(12.dp))
                step.question.options.forEachIndexed { i, option ->
                    val right = reveal && truth == i
                    val guessers = if (reveal) game.answers.filter { (p, a) -> a == i && p != spotlight.id }.keys else emptySet()
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
                if (reveal) {
                    Text(
                        if (truth == null) stringResource(R.string.no_answer, spotlight.name)
                        else stringResource(R.string.spotlight_answer, spotlight.name) + ": " + "ABCD"[truth],
                        18.sp, Muted,
                    )
                }
            }
        }
        if (reveal) {
            Gains(room, game)
        } else {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Progress(room.players, game.answers.keys) }
            Spacer(Modifier.height(16.dp))
            BottomHint(stringResource(R.string.spotlight_rules, spotlight.name), stringResource(R.string.dont_wait))
        }
    }
}

@Composable
private fun DrawScreen(room: Room, game: Game) {
    val step = game.step as Step.Spotlight
    val spotlight = room.player(step.playerId) ?: return
    val drawers = room.players.filter { it.id != spotlight.id }
    Column(Modifier.fillMaxSize()) {
        Header(stringResource(R.string.in_spotlight), game, Game.DRAW_MS)
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(48.dp)) {
            Box(Modifier.border(5.dp, Yellow, CircleShape).padding(6.dp)) { Avatar(spotlight, 240.dp) }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.draw_label) + ":", 24.sp, Yellow, FontWeight.Bold)
                Text(step.prompt.text.withName(spotlight.name), 44.sp, weight = FontWeight.Black)
            }
        }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Progress(drawers, game.drawings.keys) }
        Spacer(Modifier.height(16.dp))
        BottomHint(stringResource(R.string.draw_on_phones, spotlight.name), stringResource(R.string.dont_wait))
    }
}

@Composable
private fun GalleryScreen(room: Room, game: Game, reveal: Boolean) {
    val step = game.step as Step.Spotlight
    val spotlight = room.player(step.playerId)
    val counts = game.picks.values.groupingBy { it }.eachCount()
    val best = counts.values.maxOrNull() ?: 0
    Column(Modifier.fillMaxSize()) {
        Header(
            if (reveal) step.prompt.text.withName(spotlight?.name.orEmpty()) else stringResource(R.string.pick_best),
            game, Game.PICK_MS,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val shown = game.galleryOrder.take(7)
            val side = when {
                shown.size <= 3 -> 260.dp
                shown.size <= 5 -> 160.dp
                else -> 116.dp
            }
            shown.forEachIndexed { i, drawer ->
                val jpeg = game.drawings[drawer] ?: return@forEachIndexed
                val image = remember(jpeg) { decode(jpeg) }
                val votes = counts[drawer] ?: 0
                val winner = reveal && best > 0 && votes == best
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(side)) {
                    Box(
                        Modifier.size(side).clip(RoundedCornerShape(16.dp))
                            .then(if (winner) Modifier.border(5.dp, Yellow, RoundedCornerShape(16.dp)) else Modifier)
                            .background(Card)
                    ) {
                        if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        Box(Modifier.padding(8.dp).background(Yellow, CircleShape).padding(horizontal = 10.dp, vertical = 2.dp)) {
                            Text("${i + 1}", 16.sp, Background, FontWeight.Black)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (reveal) {
                        val drawerName = room.player(drawer)?.name.orEmpty()
                        Text(stringResource(R.string.drawing_by, drawerName), 18.sp, weight = FontWeight.Bold, lines = 1)
                        Text(pluralStringResource(R.plurals.votes, votes, votes), 16.sp, if (winner) Yellow else Muted)
                    }
                }
            }
        }
        if (reveal) {
            Gains(room, game)
        } else {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Progress(room.players, game.picks.keys) }
            Spacer(Modifier.height(16.dp))
            BottomHint(stringResource(R.string.vote_on_phones), stringResource(R.string.dont_wait))
        }
    }
}

@Composable
private fun ScoresScreen(room: Room, game: Game) {
    val ranked = room.players.sortedByDescending { game.scores[it.id] ?: 0 }
    val top = (ranked.firstOrNull()?.let { game.scores[it.id] } ?: 0).coerceAtLeast(1)
    Column(Modifier.fillMaxSize()) {
        Header(stringResource(R.string.scores), game, Game.SCORES_MS)
        Spacer(Modifier.height(8.dp))
        Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
            ranked.forEach { p -> ScoreRow(p, game.scores[p.id] ?: 0, top) }
        }
    }
}

@Composable
private fun ScoreRow(player: Player, score: Int, top: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Avatar(player, 44.dp)
        Text(player.name, 22.sp, weight = FontWeight.Bold, modifier = Modifier.width(200.dp), lines = 1)
        Box(Modifier.weight(1f).height(26.dp)) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth((score.toFloat() / top).coerceIn(0.02f, 1f))
                    .background(PlayerColours[player.colour % PlayerColours.size], CircleShape)
            )
        }
        Text("$score", 24.sp, weight = FontWeight.Black, modifier = Modifier.width(60.dp), align = TextAlign.End)
    }
}

@Composable
private fun FinalIntro() {
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text(stringResource(R.string.final_title), 72.sp, weight = FontWeight.Black)
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.final_subtitle), 32.sp, Accent, FontWeight.Bold)
    }
}

@Composable
private fun EndScreen(room: Room, game: Game) {
    val ids = room.players.map { it.id }
    val winners = game.winners(ids).mapNotNull { room.player(it) }
    val awards = game.awards(ids).associateBy { it.playerId }
    // The best drawing of each spotlight turn, for the collage.
    val bestDrawings = game.gallery.groupBy { it.spotlightId }.values.mapNotNull { list -> list.maxByOrNull { it.picks } }
    // The TV is 960 × 540 dp: the winners, every player's award, the collage and the hint all have to fit, for
    // up to 8 players with long names. Each part gets a fixed share, and its text shrinks to fit that share.
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().height(96.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val side = when {
                winners.size <= 2 -> 72.dp
                winners.size <= 4 -> 56.dp
                else -> 36.dp
            }
            winners.forEach { Box(Modifier.border(4.dp, Yellow, CircleShape).padding(4.dp)) { Avatar(it, side) } }
            Spacer(Modifier.width(4.dp))
            val names = winners.joinToString(" & ") { it.name }
            Text(
                if (winners.size > 1) stringResource(R.string.winners_draw, names) else stringResource(R.string.winner, names),
                40.sp, weight = FontWeight.Black, modifier = Modifier.weight(1f), lines = 3,
            )
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            AwardGrid(room, game, awards, Modifier.weight(1f).fillMaxHeight())
            if (bestDrawings.isNotEmpty()) Collage(bestDrawings)
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Pill {
                OkIcon()
                Label(stringResource(R.string.back_to_lobby))
            }
        }
    }
}

/** Every player, best first, with their score and award: one column up to 4 players, two beyond. */
@Composable
private fun AwardGrid(room: Room, game: Game, awards: Map<Int, Award>, modifier: Modifier) {
    val ranked = room.players.sortedByDescending { game.scores[it.id] ?: 0 }
    val columns = if (ranked.size > 4) 2 else 1
    val rows = (ranked.size + columns - 1) / columns
    // Fill down the first column, then the second, so the ranking reads top to bottom.
    val byColumn = ranked.chunked(rows)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        byColumn.forEach { column ->
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                column.forEach { p -> AwardRow(p, game.scores[p.id] ?: 0, awards[p.id], Modifier.weight(1f)) }
                // Keep rows the same height in a shorter last column.
                repeat(rows - column.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun AwardRow(player: Player, score: Int, award: Award?, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Avatar(player, 40.dp)
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(player.name, 18.sp, weight = FontWeight.Bold, lines = 1, modifier = Modifier.weight(1f, fill = false))
                Text("  $score", 18.sp, Yellow, FontWeight.Bold, lines = 1)
            }
            Text(
                award?.question ?: stringResource(R.string.award_none), 15.sp, Muted, lines = 3,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

@Composable
private fun Collage(drawings: List<Drawing>) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        drawings.take(4).chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { d ->
                    val image = remember(d.jpeg) { decode(d.jpeg) }
                    Box(Modifier.size(140.dp).clip(RoundedCornerShape(14.dp)).background(Card)) {
                        if (image != null) Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
            }
        }
    }
}

/**
 * Back during a game: the clock stops, and Continue or End game (back to the room) are picked with left,
 * right and OK. The QR code is here too, for a player whose phone closed the page: their phone kept
 * them signed in, so scanning it again puts them straight back in the game.
 */
@Composable
fun PauseOverlay(button: PauseButton, invite: Invite?) {
    Box(Modifier.fillMaxSize().background(Background.copy(alpha = 0.97f)), contentAlignment = Alignment.Center) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(64.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.width(300.dp)) {
                JoinCard(invite, caption = stringResource(R.string.scan_to_return), hint = false)
            }
            Column(Modifier.width(380.dp)) {
                Text(stringResource(R.string.paused), 56.sp, weight = FontWeight.Black)
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.paused_clock), 20.sp, Muted)
                Spacer(Modifier.height(32.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    val onContinue = button == PauseButton.CONTINUE
                    Pill(highlighted = true, focused = onContinue) {
                        if (onContinue) OkIcon()
                        Label(stringResource(R.string.keep_playing), TextColour)
                    }
                    Pill(focused = !onContinue) {
                        if (!onContinue) OkIcon()
                        Label(stringResource(R.string.end_game), if (onContinue) Muted else TextColour)
                    }
                }
            }
        }
    }
}
