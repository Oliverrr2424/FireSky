package com.firesky.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firesky.app.R
import com.firesky.app.UiState
import com.firesky.app.data.Forecast
import com.firesky.app.data.REACTION_REASONS
import com.firesky.app.data.displayedReactionCounts
import com.firesky.app.ui.theme.DisplayFamily
import com.firesky.app.ui.theme.SurfaceHairline
import com.firesky.app.ui.theme.TextMuted
import com.firesky.app.ui.theme.TextPrimary
import com.firesky.app.ui.theme.TextSecondary

private val LikeAccent = Color(0xFFFFD76A)
private val DislikeAccent = Color(0xFFFF9D8F)

/**
 * Like / dislike for the forecast on screen, the way mainstream apps do it: one tap, instant and
 * animated, tap again to take it back, community totals beside the buttons, and an optional
 * "what was off?" follow-up that only appears after a dislike.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FeedbackCard(
    state: UiState,
    outlook: Forecast,
    onToggleReaction: (String) -> Unit,
    onReason: (String) -> Unit,
    onRefreshCounts: () -> Unit,
) {
    val mode = state.activeMode
    val reaction = state.reaction
    val active = outlook.scores[mode]
    val counts = displayedReactionCounts(state.reactionSummary, reaction)
    LaunchedEffect(state.reactionKey) { onRefreshCounts() }

    GlassCard(shape = RoundedCornerShape(22.dp), contentPadding = PaddingValues(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.feedback_title),
                    color = TextSecondary,
                    fontSize = 12.sp,
                    letterSpacing = 0.7.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    state.reactionKey?.substringBefore(':') ?: "--",
                    color = Color.White,
                    fontFamily = DisplayFamily,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.W700,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(if (mode == "sunrise") R.string.feedback_event_sunrise else R.string.feedback_event_sunset),
                    color = TextMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${Math.round(active.probability)}%",
                    color = Color.White,
                    fontFamily = DisplayFamily,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.W700,
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ReactionButton(
                    selected = reaction.sentiment == "like",
                    label = stringResource(R.string.feedback_like),
                    count = counts?.first ?: 0,
                    outlineIcon = R.drawable.ic_thumbs_up,
                    filledIcon = R.drawable.ic_thumbs_up_filled,
                    accent = LikeAccent,
                    modifier = Modifier.weight(1f),
                    onClick = { onToggleReaction("like") },
                )
                ReactionButton(
                    selected = reaction.sentiment == "dislike",
                    label = stringResource(R.string.feedback_dislike),
                    count = counts?.second ?: 0,
                    outlineIcon = R.drawable.ic_thumbs_down,
                    filledIcon = R.drawable.ic_thumbs_down_filled,
                    accent = DislikeAccent,
                    modifier = Modifier.weight(1f),
                    onClick = { onToggleReaction("dislike") },
                )
            }
            AnimatedVisibility(
                visible = reaction.sentiment == "dislike",
                enter = fadeIn(tween(200)) + expandVertically(tween(260)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(200)),
            ) {
                Column {
                    Spacer(Modifier.height(14.dp))
                    Text(stringResource(R.string.feedback_why), color = TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        REACTION_REASONS.forEach { reason ->
                            ReasonChip(
                                label = stringResource(reasonLabel(reason)),
                                selected = reaction.reason == reason,
                                onClick = { onReason(reason) },
                            )
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = reaction.sentiment != null,
                enter = fadeIn(tween(200)) + expandVertically(tween(220)),
                exit = fadeOut(tween(120)) + shrinkVertically(tween(180)),
            ) {
                Row(
                    modifier = Modifier.padding(top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    LucideIcon(R.drawable.ic_check, null, tint = Color(0xFFD8F7DF), size = 14.dp)
                    Text(
                        stringResource(
                            when {
                                reaction.synced -> R.string.feedback_thanks
                                state.accountToken.isBlank() -> R.string.feedback_saved_local
                                else -> R.string.feedback_syncing
                            },
                        ),
                        color = TextMuted,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(SurfaceHairline))
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LucideIcon(R.drawable.ic_triangle_alert, null, tint = TextSecondary, size = 18.dp)
                Text(
                    text = if (active.blockers.isNotEmpty()) {
                        "Local constraints: ${active.blockers.joinToString("; ")}. Confidence is about ${Math.round(active.confidence)}%."
                    } else {
                        "Local advantages: ${active.boosts.joinToString("; ")}. Confidence is about ${Math.round(active.confidence)}%."
                    },
                    color = TextSecondary,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                )
            }
        }
    }
}

private fun reasonLabel(reason: String): Int = when (reason) {
    "too_high" -> R.string.reason_too_high
    "too_low" -> R.string.reason_too_low
    "wrong_time" -> R.string.reason_wrong_time
    else -> R.string.reason_other
}

@Composable
private fun ReactionButton(
    selected: Boolean,
    label: String,
    count: Int,
    outlineIcon: Int,
    filledIcon: Int,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val interactionSource = remember { MutableInteractionSource() }
    // The icon pops when a reaction lands, the way a heart or thumb does elsewhere.
    val pop = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        if (selected) {
            pop.snapTo(0.7f)
            pop.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 420f))
        }
    }
    val background by animateColorAsState(
        if (selected) accent.copy(alpha = 0.2f) else Color(0x14FFFFFF),
        tween(180),
        label = "reactionBackground",
    )
    val tint by animateColorAsState(if (selected) accent else TextSecondary, tween(180), label = "reactionTint")
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(CircleShape)
            .background(background)
            .selectable(
                selected = selected,
                interactionSource = interactionSource,
                indication = ripple(),
                role = Role.Button,
                onClick = {
                    haptics.performHapticFeedback(if (selected) HapticFeedbackType.ToggleOff else HapticFeedbackType.ToggleOn)
                    onClick()
                },
            )
            .animateContentSize(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(if (selected) filledIcon else outlineIcon),
            contentDescription = null,
            tint = tint,
            modifier = Modifier
                .size(19.dp)
                .graphicsLayer {
                    scaleX = pop.value
                    scaleY = pop.value
                },
        )
        Spacer(Modifier.width(8.dp))
        Text(label, color = if (selected) TextPrimary else TextSecondary, fontSize = 13.sp, fontWeight = FontWeight.W600)
        if (count > 0) {
            Spacer(Modifier.width(6.dp))
            Text("$count", color = if (selected) tint else TextMuted, fontSize = 13.sp, fontWeight = FontWeight.W600)
        }
    }
}

@Composable
private fun ReasonChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val background by animateColorAsState(
        if (selected) DislikeAccent.copy(alpha = 0.26f) else Color(0x14FFFFFF),
        tween(160),
        label = "reasonBackground",
    )
    Box(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(CircleShape)
            .background(background)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (selected) TextPrimary else TextSecondary,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.W700 else FontWeight.W500,
        )
    }
}
