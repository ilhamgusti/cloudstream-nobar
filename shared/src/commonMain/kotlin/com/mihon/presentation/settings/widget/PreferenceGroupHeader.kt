package com.mihon.presentation.settings.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PreferenceGroupHeader(title: String) {
    Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp, top = 20.dp),
    ) {
        // Chica: ink 15sp SemiBold section title, more air above than below,
        // aligned with the text inside the grouped card below it
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(horizontal = PrefsHorizontalPadding * 2),
            style = MaterialTheme.typography.titleSmall,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
