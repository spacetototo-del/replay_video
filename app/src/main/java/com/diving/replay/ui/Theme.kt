package com.diving.replay.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One place for the app's look. The goal is "clean paid app", not the stock Compose defaults:
 * a deep near-black ground, a single confident cyan accent (pulled from the launcher icon), soft
 * translucent panels for overlays, and generous rounding.
 */

private val Accent = Color(0xFF4FC3F7)      // icon cyan
private val AccentDim = Color(0xFF2E7DB0)
private val Ground = Color(0xFF0A0E12)
private val Panel = Color(0xFF161C24)
private val PanelHi = Color(0xFF1E2630)
private val Danger = Color(0xFFFF5252)
private val OnGround = Color(0xFFECF1F5)
private val Muted = Color(0xFF9AA7B2)

private val DivingColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF042430),
    primaryContainer = AccentDim,
    onPrimaryContainer = Color(0xFFEAF6FF),
    secondary = Color(0xFF8FD3F0),
    background = Ground,
    onBackground = OnGround,
    surface = Panel,
    onSurface = OnGround,
    surfaceVariant = PanelHi,
    onSurfaceVariant = Muted,
    error = Danger,
    onError = Color.White,
    outline = Color(0xFF33404C),
)

/** Chip/overlay backgrounds — deliberately semi-transparent so they read as "floating" on video. */
object DivingTokens {
    val scrim = Color(0xCC0B1116)
    val scrimSoft = Color(0x990B1116)
    val recRed = Danger
    val warn = Color(0xFFFFB74D)
    val ok = Color(0xFF66BB6A)
    val panelRadius = 18.dp
    val chipRadius = 12.dp
    /** Content never gets wider than this — keeps controls centred on tablets / unfolded Fold. */
    val contentMaxWidth = 560.dp
}

@Composable
fun DivingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DivingColors,
        typography = MaterialTheme.typography,
        content = content,
    )
}

/** Full-bleed dark ground with the content column centred and width-capped for big screens. */
@Composable
fun ScreenScaffold(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            Column(
                modifier = modifier
                    .align(Alignment.TopCenter)
                    .fillMaxSize()
                    .widthIn(max = DivingTokens.contentMaxWidth),
                content = content,
            )
        }
    }
}

/** A translucent rounded panel for overlays that float on top of the camera / video. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    soft: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(DivingTokens.chipRadius))
            .background(if (soft) DivingTokens.scrimSoft else DivingTokens.scrim)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        content = { content() },
    )
}

/** Small status pill: "버퍼링 12초", "● 녹화 대기" … */
@Composable
fun StatusPill(text: String, color: Color = MaterialTheme.colorScheme.onSurface, soft: Boolean = false) {
    GlassPanel(soft = soft) {
        Text(text, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

/** A status pill whose background carries the meaning: red = problem, green = done, amber = warn. */
@Composable
fun StatusPillTinted(text: String, tint: Color, dark: Boolean = false) {
    Box(
        Modifier
            .clip(RoundedCornerShape(DivingTokens.chipRadius))
            .background(tint.copy(alpha = 0.92f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text,
            color = if (dark) Color(0xFF0B1116) else Color.White,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

/** Section header used on the Settings / list screens. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
    )
}

/** A grouped settings card. */
@Composable
fun SettingCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(DivingTokens.panelRadius),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/**
 * A pill toggle used for the "pick one of a few values" rows (speed, fps, delay, dim time…).
 * Selected = filled accent, others = outlined. Wrap a set of these in a [FlowingRow].
 */
@Composable
fun ChoiceChip(label: String, selected: Boolean, compact: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(DivingTokens.chipRadius),
        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
    ) {
        Text(
            label,
            modifier = Modifier.padding(
                horizontal = if (compact) 9.dp else 14.dp,
                vertical = if (compact) 4.dp else 8.dp,
            ),
            fontSize = if (compact) 12.sp else 14.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

/**
 * An icon + label control for the live-screen bottom bar. Grows to share width evenly.
 *
 * [compact] drops the label and shrinks to a small icon-only tile — for landscape on a
 * regular (non-foldable) phone, where the dock's cross-axis budget is the phone's own portrait
 * *width* (~360dp on a normal phone vs ~670dp on an unfolded Fold), so the full icon+label tile
 * that's comfortably small on a Fold eats a much bigger share of that budget here.
 */
@Composable
fun BarAction(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
    compact: Boolean = false,
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(DivingTokens.chipRadius),
        color = DivingTokens.scrim,
        contentColor = tint,
    ) {
        if (compact) {
            Box(Modifier.padding(10.dp).heightIn(min = 40.dp), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = label)
            }
        } else {
            Column(
                Modifier.padding(vertical = 10.dp, horizontal = 4.dp).heightIn(min = 56.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(icon, contentDescription = label, modifier = Modifier.padding(bottom = 1.dp))
                Text(
                    label,
                    fontSize = 11.sp,
                    lineHeight = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Content padding shared by the compact chip buttons. */
val CompactButtonPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
