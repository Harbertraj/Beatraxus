package com.beatraxus.app.subtitles.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatraxus.app.subtitles.data.SubtitleRepositoryImpl
import com.beatraxus.app.subtitles.domain.SubtitleLanguage

@Composable
fun LanguagePickerDialog(
    availableLanguages: List<SubtitleLanguage>,
    selectedCodes: List<String>,
    onDismiss: () -> Unit,
    onLanguagesSelected: (List<String>) -> Unit
) {
    val mxOrange = Color(0xFFFF8F00)
    var searchQuery by remember { mutableStateOf("") }
    var selectedList by remember { mutableStateOf(selectedCodes.toMutableList()) }

    val allLangs = remember(availableLanguages) {
        val builtInCodes = SubtitleRepositoryImpl.BUILT_IN_LANGUAGES.map { it.code }.toSet()
        val combined = SubtitleRepositoryImpl.BUILT_IN_LANGUAGES + availableLanguages.filter { it.code !in builtInCodes }
        combined
    }

    val filteredLangs = remember(allLangs, searchQuery) {
        if (searchQuery.isBlank()) allLangs
        else allLangs.filter { it.name.contains(searchQuery, ignoreCase = true) || it.code.contains(searchQuery, ignoreCase = true) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF1E1E1E),
        shape = RoundedCornerShape(16.dp),
        title = {
            Text("Select Subtitle Languages", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(modifier = Modifier.height(350.dp)) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search language", color = Color.White.copy(0.6f)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = mxOrange,
                        unfocusedBorderColor = Color.White.copy(0.3f)
                    )
                )

                Spacer(Modifier.height(10.dp))

                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(filteredLangs) { lang ->
                        val isChecked = selectedList.contains(lang.code)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (isChecked) {
                                        if (selectedList.size > 1) selectedList = selectedList.toMutableList().apply { remove(lang.code) }
                                    } else {
                                        selectedList = selectedList.toMutableList().apply { add(lang.code) }
                                    }
                                }
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = { checked ->
                                    if (checked) {
                                        selectedList = selectedList.toMutableList().apply { add(lang.code) }
                                    } else {
                                        if (selectedList.size > 1) selectedList = selectedList.toMutableList().apply { remove(lang.code) }
                                    }
                                },
                                colors = CheckboxDefaults.colors(checkedColor = mxOrange)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(lang.name, color = Color.White, fontSize = 15.sp, modifier = Modifier.weight(1f))
                            Text(lang.code.uppercase(), color = Color.White.copy(0.5f), fontSize = 12.sp)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onLanguagesSelected(selectedList)
                onDismiss()
            }) {
                Text("Apply", color = mxOrange, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = Color.White.copy(0.7f))
            }
        }
    )
}
