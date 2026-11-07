package com.darknes.tor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Shared visual language for Darknes Tor 2.0 Remaster.
 *
 * Keep this file presentation-only: no browser state, network calls, or account logic.
 */
internal object DarknesDesignTokens {
    val ink = Color(0xFF070A14)
    val paper = Color(0xFFF1F5FF)
    val cyan = Color(0xFF38D9FF)
    val lavender = Color(0xFFB69CFF)
    val violet = Color(0xFF8B6CFF)
    val muted = Color(0xFF929DB8)
    val glass = Color(0xD9161D32)
    val border = Color(0x5546CFFF)

    val surface = Color(0xFF11182A)
    val elevatedSurface = Color(0xFF171F35)
    val danger = Color(0xFFFF647C)
    val success = Color(0xFF65D69A)

    val radiusSmall = 12.dp
    val radiusMedium = 18.dp
    val radiusLarge = 26.dp
    val radiusPill = 999.dp

    val pageHorizontalPadding = 18.dp
    val sectionSpacing = 16.dp
    val controlHeight = 50.dp
}
