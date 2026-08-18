package com.instructor.lessonroutes.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Non-modal replacement for an AlertDialog on marker taps: an inline banner over
 * the top of the map instead of a popup that blocks interacting with anything
 * else. Renders nothing when [title] is null, so callers can use it unconditionally
 * regardless of what triggered it (a hazard tap, a high-volume-road tap, etc).
 */
@Composable
fun InfoBanner(title: String?, subtitle: String? = null, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (title == null) return

    Surface(
        modifier = modifier.padding(16.dp).widthIn(max = 360.dp),
        tonalElevation = 4.dp,
        shape = MaterialTheme.shapes.small,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
            subtitle?.let { Text(text = it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
