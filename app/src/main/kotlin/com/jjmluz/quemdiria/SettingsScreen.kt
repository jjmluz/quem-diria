package com.jjmluz.quemdiria

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Over the lobby: the game's settings, one row each, with every choice in view and the current one lit. */
@Composable
fun SettingsOverlay(focused: Setting, mode: Mode, narrator: Boolean, spotlights: Spotlights) {
    Column(Modifier.fillMaxSize().background(Background).padding(horizontal = 56.dp, vertical = 28.dp)) {
        Text(stringResource(R.string.settings), 30.sp, weight = FontWeight.Black, lines = 1)
        Text(stringResource(R.string.settings_hint), 16.sp, Muted, lines = 1)
        Spacer(Modifier.height(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val rowModifier = Modifier.weight(1f)
            SettingRow(
                stringResource(R.string.setting_mode), focused == Setting.MODE,
                listOf(stringResource(R.string.mode_family), stringResource(R.string.mode_adults)), mode.ordinal,
                stringResource(if (mode == Mode.ADULTS) R.string.mode_adults_about else R.string.mode_family_about),
                rowModifier,
            )
            SettingRow(
                stringResource(R.string.setting_narrator), focused == Setting.NARRATOR,
                listOf(stringResource(R.string.narrator_off), stringResource(R.string.narrator_on)), if (narrator) 1 else 0,
                stringResource(if (narrator) R.string.narrator_on_about else R.string.narrator_off_about),
                rowModifier,
            )
            SettingRow(
                stringResource(R.string.setting_spotlight), focused == Setting.SPOTLIGHT,
                listOf(
                    stringResource(R.string.spotlight_none), stringResource(R.string.spotlight_moderate),
                    stringResource(R.string.spotlight_all),
                ),
                spotlights.ordinal,
                stringResource(when (spotlights) {
                    Spotlights.NONE -> R.string.spotlight_none_about
                    Spotlights.MODERATE -> R.string.spotlight_moderate_about
                    Spotlights.ALL -> R.string.spotlight_all_about
                }),
                rowModifier,
            )
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally)) {
            Pill {
                LeftRightIcon()
                Label(stringResource(R.string.setting_change))
            }
            Pill {
                BackIcon()
                Label(stringResource(R.string.link_done))
            }
        }
    }
}

/**
 * One setting: its name and what the current choice means on the left, the choices on the right. The lit row
 * has an outline, as does its current choice, which is what left and right move.
 */
@Composable
private fun SettingRow(
    name: String, focused: Boolean, choices: List<String>, chosen: Int, about: String, modifier: Modifier,
) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier.fillMaxWidth()
            .background(Card, shape)
            .then(if (focused) Modifier.border(2.dp, Accent, shape) else Modifier)
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, 24.sp, if (focused) TextColour else Muted, FontWeight.Bold, lines = 1)
            Spacer(Modifier.height(4.dp))
            Text(about, 16.sp, Muted, lines = 2)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            choices.forEachIndexed { i, choice ->
                Pill(highlighted = i == chosen, focused = focused && i == chosen) {
                    Label(choice, colour = if (i == chosen) TextColour else Muted)
                }
            }
        }
    }
}

/** The remote's left and right: two arrowheads pointing apart. */
@Composable
private fun LeftRightIcon() {
    Canvas(Modifier.size(26.dp)) {
        val w = size.width
        val h = size.height
        fun arrow(tip: Float, base: Float) = Path().apply {
            moveTo(tip, h / 2)
            lineTo(base, h * 0.22f)
            lineTo(base, h * 0.78f)
            close()
        }
        drawPath(arrow(w * 0.04f, w * 0.4f), Muted)
        drawPath(arrow(w * 0.96f, w * 0.6f), Muted)
        drawCircle(Muted, radius = w * 0.05f, center = Offset(w / 2, h / 2))
    }
}
