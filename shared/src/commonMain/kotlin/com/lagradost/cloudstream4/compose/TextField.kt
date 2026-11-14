package com.lagradost.cloudstream4.compose

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun BlackTextField(
    value: String,
    placeHolder : String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = false,
    onValueChange: (String) -> Unit,
) {
    TextField(
        modifier = modifier.focusOutline(shape = if (singleLine) PillShape() else RoundedCornerShape(16.dp)),
        value = value,
        onValueChange = onValueChange,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        placeholder = {
            Text(text = placeHolder)
        },
        singleLine = singleLine,
        shape = if (singleLine) PillShape() else RoundedCornerShape(16.dp),
        colors = Colors.blackTextField,
    )
}