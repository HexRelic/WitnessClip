// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HexRelic

package io.github.hexrelic.witnessclip.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.hexrelic.witnessclip.Str

data class Palette(
    val bg: Color, val surface: Color, val surface2: Color, val line: Color, val text: Color, val muted: Color,
    val seal: Color, val sealDim: Color, val onSeal: Color, val rec: Color, val warn: Color, val warnDim: Color, val dark: Boolean,
)

val DarkPalette = Palette(
    Color(0xFF081019), Color(0xFF0F1A27), Color(0xFF162536), Color(0xFF22364B), Color(0xFFEAF1F8), Color(0xFF8FA3B8),
    Color(0xFF2DD4BF), Color(0xFF0F3B3A), Color(0xFF04201D), Color(0xFFFF4D5E), Color(0xFFFBBF24), Color(0xFF3B2F0B), true,
)
val LightPalette = Palette(
    Color(0xFFF3F6F9), Color(0xFFFFFFFF), Color(0xFFE8EEF3), Color(0xFFD5DEE7), Color(0xFF0B1826), Color(0xFF5A6B7C),
    Color(0xFF0D9488), Color(0xFFD9F4F0), Color(0xFFFFFFFF), Color(0xFFE11D48), Color(0xFFB45309), Color(0xFFFEF3C7), false,
)

val LocalPalette = staticCompositionLocalOf { DarkPalette }
val LocalStr = staticCompositionLocalOf { Str(false) }
val P: Palette @Composable @ReadOnlyComposable get() = LocalPalette.current
val S: Str @Composable @ReadOnlyComposable get() = LocalStr.current

/** 24×24 stroke icons drawn for this app. */
object Icons {
    const val shield = "M12 3 l8 3 v6 c0 5 -3.5 8 -8 9 c-4.5 -1 -8 -4 -8 -9 V6 z M9 12 l2 2 l4 -4"
    const val mic = "M12 3 a3 3 0 0 0 -3 3 v5 a3 3 0 0 0 6 0 V6 a3 3 0 0 0 -3 -3 z M5 11 a7 7 0 0 0 14 0 M12 18 v3"
    const val check = "M5 12 l5 5 l9 -10"
    const val back = "M15 5 l-7 7 l7 7"
    const val chevron = "M9 6 l6 6 l-6 6"
    const val down = "M6 9 l6 6 l6 -6"
    const val clock = "M12 3 a9 9 0 1 0 0 18 a9 9 0 0 0 0 -18 z M12 7 v5 l3 2"
    const val lock = "M7 11 h10 a2 2 0 0 1 2 2 v5 a2 2 0 0 1 -2 2 H7 a2 2 0 0 1 -2 -2 v-5 a2 2 0 0 1 2 -2 z M8 11 V8 a4 4 0 0 1 8 0 v3"
    const val flag = "M5 21 V4 h11 l-2 4 l2 4 H5"
    const val note = "M4 6 h16 M4 12 h16 M4 18 h10"
    const val share = "M12 15 V3 M7 8 l5 -5 l5 5 M5 21 h14"
    const val download = "M12 3 v12 M7 10 l5 5 l5 -5 M5 21 h14"
    const val upload = "M12 16 V4 M7 9 l5 -5 l5 5 M4 20 h16"
    const val play = "M8 5 l11 7 l-11 7 z"
    const val pause = "M8 5 v14 M16 5 v14"
    const val chart = "M5 20 V11 M12 20 V4 M19 20 v-6"
    const val gear = "M12 9 a3 3 0 1 0 0 6 a3 3 0 0 0 0 -6 z M12 2 v3 M12 19 v3 M2 12 h3 M19 12 h3 M5 5 l2 2 M17 17 l2 2 M19 5 l-2 2 M7 17 l-2 2"
    const val box = "M4 7 l8 -4 l8 4 v10 l-8 4 l-8 -4 z M4 7 l8 4 l8 -4 M12 11 v10"
    const val split = "M5 4 h3 a2 2 0 0 1 2 2 v12 a2 2 0 0 1 -2 2 H5 a2 2 0 0 1 -2 -2 V6 a2 2 0 0 1 2 -2 z M16 4 h3 a2 2 0 0 1 2 2 v12 a2 2 0 0 1 -2 2 h-3 a2 2 0 0 1 -2 -2 V6 a2 2 0 0 1 2 -2 z"
    const val headset = "M4 14 v-2 a8 8 0 0 1 16 0 v2 M4 14 h3 v6 H5 a1 1 0 0 1 -1 -1 z M20 14 h-3 v6 h2 a1 1 0 0 0 1 -1 z"
    const val phone = "M8 2 h8 a2 2 0 0 1 2 2 v16 a2 2 0 0 1 -2 2 H8 a2 2 0 0 1 -2 -2 V4 a2 2 0 0 1 2 -2 z M11 18 h2"
    const val copy = "M9 9 h10 a1 1 0 0 1 1 1 v10 a1 1 0 0 1 -1 1 H9 a1 1 0 0 1 -1 -1 V10 a1 1 0 0 1 1 -1 z M5 15 H4 a1 1 0 0 1 -1 -1 V4 a1 1 0 0 1 1 -1 h10 a1 1 0 0 1 1 1 v1"
    const val key = "M15 4 a5 5 0 1 0 0 10 a5 5 0 0 0 0 -10 z M11 12 l-8 8 M6 17 l2 2 M3 20 l0 0"
    const val warn = "M12 4 l9 16 H3 z M12 10 v4 M12 17 v0.5"
    const val cross = "M6 6 l12 12 M18 6 l-12 12"
    const val trash = "M4 7 h16 M9 7 V4 h6 v3 M6 7 l1 13 h10 l1 -13"
    const val edit = "M4 20 h4 l10 -10 l-4 -4 l-10 10 z M13 7 l4 4"
    const val folder = "M3 6 h6 l2 2 h10 v11 H3 z"
    const val pin = "M12 21 c-4 -5 -6 -8 -6 -11 a6 6 0 0 1 12 0 c0 3 -2 6 -6 11 z M12 8 a2 2 0 1 0 0 4 a2 2 0 0 0 0 -4 z"
    const val info = "M12 3 a9 9 0 1 0 0 18 a9 9 0 0 0 0 -18 z M12 11 v5 M12 7.500 v0.5"
}

@Composable
fun Ic(path: String, size: Dp = 22.dp, tint: Color = P.text, filled: Boolean = false, stroke: Float = 2f) {
    val parsed = remember(path) { PathParser().parsePathString(path).toPath() }
    Canvas(Modifier.size(size)) {
        scale(this.size.width / 24f, pivot = Offset.Zero) {
            drawPath(parsed, tint, style = if (filled) Fill else Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
fun T(
    text: String, modifier: Modifier = Modifier, size: TextUnit = 14.sp, color: Color = P.text, weight: FontWeight = FontWeight.Normal,
    align: TextAlign? = null, maxLines: Int = Int.MAX_VALUE, mono: Boolean = false, lineHeight: TextUnit = TextUnit.Unspecified,
) = Text(
    text, modifier, color = color, fontSize = size, fontWeight = weight, textAlign = align, maxLines = maxLines,
    overflow = TextOverflow.Ellipsis, fontFamily = if (mono) FontFamily.Monospace else null, lineHeight = lineHeight,
)

@Composable
fun Card(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) = Column(
    modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(P.surface).border(1.dp, P.line, RoundedCornerShape(22.dp)).padding(padding),
    content = content,
)

enum class PillKind { OK, WAIT, PLAIN, FAIL }

@Composable
fun Pill(text: String, kind: PillKind = PillKind.PLAIN, icon: String? = null, onClick: (() -> Unit)? = null, trailing: String? = null) {
    val (bg, fg) = when (kind) {
        PillKind.OK -> P.sealDim to P.seal
        PillKind.WAIT -> P.warnDim to P.warn
        PillKind.FAIL -> P.rec.copy(alpha = 0.18f) to P.rec
        PillKind.PLAIN -> P.surface2 to P.muted
    }
    Row(
        Modifier.clip(CircleShape).background(bg).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 11.dp, vertical = if (onClick != null) 8.dp else 5.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Ic(icon, 14.dp, fg)
        T(text, size = 12.sp, color = if (onClick != null) P.text else fg, weight = FontWeight.SemiBold, maxLines = 1)
        if (trailing != null) Ic(trailing, 14.dp, P.muted)
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) =
    T(text.uppercase(), modifier.padding(top = 22.dp, bottom = 10.dp), size = 12.5.sp, color = P.muted, weight = FontWeight.SemiBold)

@Composable
fun IconButton(icon: String, tint: Color = P.text, size: Dp = 42.dp, onClick: () -> Unit) =
    Box(Modifier.size(size).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) { Ic(icon, tint = tint) }

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, icon: String? = null, ghost: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier.clip(shape).then(if (ghost) Modifier.border(1.dp, P.line, shape) else Modifier.background(if (enabled) P.seal else P.surface2))
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        val fg = if (ghost) P.text else if (enabled) P.onSeal else P.muted
        if (icon != null) { Ic(icon, 20.dp, fg); Spacer(Modifier.width(9.dp)) }
        T(text, size = 15.sp, color = fg, weight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
fun Divider() = Box(Modifier.fillMaxWidth().height(1.dp).background(P.line))
