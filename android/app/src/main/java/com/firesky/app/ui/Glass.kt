package com.firesky.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firesky.app.ui.theme.DisplayFamily
import com.firesky.app.ui.theme.SurfaceControl
import com.firesky.app.ui.theme.SurfaceHairline
import com.firesky.app.ui.theme.SurfaceInset
import com.firesky.app.ui.theme.SurfacePanel
import com.firesky.app.ui.theme.TextMuted
import com.firesky.app.ui.theme.TextPrimary
import com.firesky.app.ui.theme.TextSecondary
import com.firesky.app.ui.theme.UiFamily

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(22.dp),
    contentPadding: PaddingValues = PaddingValues(18.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .shadow(14.dp, shape, ambientColor = Color(0x240B2645), spotColor = Color(0x240B2645))
            .clip(shape)
            .background(SurfacePanel)
            .drawWithContent {
                drawContent()
                drawLine(
                    color = SurfaceHairline,
                    start = Offset(0f, 0.5f),
                    end = Offset(size.width, 0.5f),
                    strokeWidth = 1f,
                )
            }
            .padding(contentPadding),
        content = content,
    )
}

@Composable
fun InsetCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(16.dp),
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 11.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(shape)
            .background(SurfaceInset)
            .padding(contentPadding),
        content = content,
    )
}

@Composable
fun SurfaceControlBox(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(999.dp),
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(SurfaceControl)
            .drawWithContent {
                drawContent()
                drawLine(
                    color = Color(0x1FFFFFFF),
                    start = Offset(0f, 0.5f),
                    end = Offset(size.width, 0.5f),
                    strokeWidth = 1f,
                )
            },
        content = content,
    )
}

/**
 * A round icon button. The visible circle is [size], but the button always reserves a 48dp touch target.
 * Pass [checked] for on/off controls so accessibility services announce them as switches.
 */
@Composable
fun ControlIconButton(
    painter: Painter,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    checked: Boolean? = null,
    size: Dp = 34.dp,
    iconSize: Dp = 18.dp,
) {
    ControlIconButtonFrame(onClick, modifier, selected, checked, size) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            tint = if (selected) TextPrimary else TextSecondary,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
fun ControlIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    checked: Boolean? = null,
    size: Dp = 34.dp,
    iconSize: Dp = 18.dp,
) {
    ControlIconButtonFrame(onClick, modifier, selected, checked, size) {
        Icon(
            imageVector = icon,
            contentDescription = contentDescription,
            tint = if (selected) TextPrimary else TextSecondary,
            modifier = Modifier.size(iconSize),
        )
    }
}

@Composable
private fun ControlIconButtonFrame(
    onClick: () -> Unit,
    modifier: Modifier,
    selected: Boolean,
    checked: Boolean?,
    size: Dp,
    content: @Composable () -> Unit,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val interaction = if (checked != null) {
        Modifier
            .toggleable(
                value = checked,
                interactionSource = interactionSource,
                indication = null,
                role = Role.Switch,
                onValueChange = { onClick() },
            )
            .semantics { stateDescription = if (checked) "On" else "Off" }
    } else {
        Modifier.clickable(
            interactionSource = interactionSource,
            indication = null,
            role = Role.Button,
            onClick = onClick,
        )
    }
    Box(
        modifier = modifier
            .sizeIn(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
            .then(interaction),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (selected) Color(0x4DFFFFFF) else SurfaceControl)
                .indication(interactionSource, ripple()),
            contentAlignment = Alignment.Center,
        ) {
            content()
        }
    }
}

val MinTouchTarget = 48.dp

@Composable
fun LucideIcon(
    resId: Int,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    tint: Color = TextSecondary,
    size: Dp = 18.dp,
) {
    Icon(
        painter = painterResource(resId),
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier.size(size),
    )
}

@Composable
fun LabelText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = TextSecondary,
    size: Int = 13,
    weight: FontWeight = FontWeight.W500,
    maxLines: Int = 1,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontFamily = UiFamily,
        fontSize = size.sp,
        fontWeight = weight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun DisplayText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = TextPrimary,
    size: Int = 18,
    weight: FontWeight = FontWeight.W700,
    maxLines: Int = 1,
    letterSpacing: Float = 0f,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontFamily = DisplayFamily,
        fontSize = size.sp,
        fontWeight = weight,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        letterSpacing = letterSpacing.sp,
    )
}

@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(if (selected) SurfaceControl else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (selected) TextPrimary else TextSecondary,
            fontFamily = UiFamily,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun NoticeCard(
    iconRes: Int,
    text: String,
    modifier: Modifier = Modifier,
    warning: Boolean = false,
) {
    GlassCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LucideIcon(iconRes, null, tint = if (warning) Color(0xFFFACC15) else TextPrimary, size = 18.dp)
            LabelText(text, color = TextPrimary, size = 13, maxLines = 3)
        }
    }
}

@Composable
fun RowScope.WeightSpacer() {
    Box(Modifier.weight(1f))
}

fun shape22() = RoundedCornerShape(22.dp)
fun shape16() = RoundedCornerShape(16.dp)

@Composable
fun TopHighlight(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0x2EFFFFFF)),
    )
}

val smallTextStyle = TextStyle(fontFamily = UiFamily, fontSize = 11.sp, color = TextMuted)
