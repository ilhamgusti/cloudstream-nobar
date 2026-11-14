package com.mihon.presentation.settings.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Where a row sits inside a grouped settings card (Chica "Mixtape Bareng":
 * rows grouped on rounded surfaceVariant cards, like Android settings groups).
 */
enum class PreferenceGroupPosition {
    Single, First, Middle, Last;

    companion object {
        fun of(index: Int, count: Int): PreferenceGroupPosition = when {
            count <= 1 -> Single
            index <= 0 -> First
            index >= count - 1 -> Last
            else -> Middle
        }
    }
}

private val CardOuterRadius = 16.dp
private val CardInnerRadius = 4.dp
private val CardRowGap = 2.dp

fun PreferenceGroupPosition.cardShape(): Shape = when (this) {
    PreferenceGroupPosition.Single -> RoundedCornerShape(CardOuterRadius)
    PreferenceGroupPosition.First -> RoundedCornerShape(
        topStart = CardOuterRadius,
        topEnd = CardOuterRadius,
        bottomStart = CardInnerRadius,
        bottomEnd = CardInnerRadius,
    )

    PreferenceGroupPosition.Middle -> RoundedCornerShape(CardInnerRadius)
    PreferenceGroupPosition.Last -> RoundedCornerShape(
        topStart = CardInnerRadius,
        topEnd = CardInnerRadius,
        bottomStart = CardOuterRadius,
        bottomEnd = CardOuterRadius,
    )
}

/**
 * The shape of the card segment the current row lives in, so the row's D-pad focus ring
 * follows the card's corners. Null when the row is not on a card.
 */
val LocalPreferenceCardShape = staticCompositionLocalOf<Shape?> { null }

/**
 * Draws one segment of a grouped settings card: 16dp gutters, surfaceVariant fill,
 * 16dp outer corners, small inner corners and a hairline gap between rows.
 */
@Composable
fun PreferenceCard(
    position: PreferenceGroupPosition,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val shape = position.cardShape()
    val gap = when (position) {
        PreferenceGroupPosition.First, PreferenceGroupPosition.Middle -> CardRowGap
        PreferenceGroupPosition.Single, PreferenceGroupPosition.Last -> 0.dp
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = PrefsHorizontalPadding, end = PrefsHorizontalPadding, bottom = gap)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        CompositionLocalProvider(LocalPreferenceCardShape provides shape, content = content)
    }
}
