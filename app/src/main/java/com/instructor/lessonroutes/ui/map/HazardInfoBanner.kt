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
import com.instructor.lessonroutes.data.remote.Hazard

/**
 * Non-modal replacement for a hazard-tap AlertDialog: an inline banner over the top
 * of the map instead of a popup that blocks interacting with anything else. Renders
 * nothing when [hazard] is null, so callers can use it unconditionally.
 */
@Composable
fun HazardInfoBanner(hazard: Hazard?, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    if (hazard == null) return

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
                Text(text = hazard.title, style = MaterialTheme.typography.titleSmall)
                TextButton(onClick = onDismiss) { Text("Dismiss") }
            }
            hazard.advice?.let { advice ->
                Text(text = advice, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
