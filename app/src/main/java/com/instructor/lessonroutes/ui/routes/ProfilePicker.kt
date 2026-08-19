package com.instructor.lessonroutes.ui.routes

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.instructor.lessonroutes.data.StudentProfile

/**
 * Multi-select checklist of student profiles plus an inline "create a new profile"
 * row -- shared by the save-route dialog (record time) and the edit-route dialog
 * (reassign later). Newly created profiles are handed back via [onCreateProfile] so
 * the caller can insert them and auto-select the resulting id.
 */
@Composable
fun ProfilePickerSection(
    allProfiles: List<StudentProfile>,
    selectedIds: Set<Long>,
    onToggle: (Long) -> Unit,
    onCreateProfile: (name: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var newProfileName by remember { mutableStateOf("") }

    Column(modifier = modifier) {
        Text("Student profile(s)")
        Spacer(modifier = Modifier.height(4.dp))
        if (allProfiles.isNotEmpty()) {
            Column(modifier = Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                allProfiles.forEach { profile ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = selectedIds.contains(profile.id),
                            onCheckedChange = { onToggle(profile.id) },
                        )
                        Text(profile.name)
                    }
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = newProfileName,
                onValueChange = { newProfileName = it },
                label = { Text("New student profile") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(4.dp))
            OutlinedButton(
                onClick = {
                    if (newProfileName.isNotBlank()) {
                        onCreateProfile(newProfileName.trim())
                        newProfileName = ""
                    }
                },
            ) {
                Text("Add")
            }
        }
    }
}
