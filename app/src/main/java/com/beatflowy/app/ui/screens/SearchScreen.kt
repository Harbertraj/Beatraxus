package com.beatflowy.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.beatflowy.app.repository.GenreApiService
import com.beatflowy.app.ui.theme.BgBase
import com.beatflowy.app.ui.theme.BgDeep
import com.beatflowy.app.ui.theme.TextPrimary
import com.beatflowy.app.ui.theme.TextSecondary
import com.beatflowy.app.viewmodel.PlayerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    viewModel: PlayerViewModel,
    onBack: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    Scaffold(
        containerColor = BgBase,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(BgDeep)
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    placeholder = { Text("Search...", color = TextSecondary) },
                    leadingIcon = { Icon(Icons.Rounded.Search, null, tint = TextSecondary) },
                    shape = RoundedCornerShape(28.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color.White.copy(0.05f),
                        unfocusedContainerColor = Color.White.copy(0.05f),
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        cursorColor = TextPrimary
                    ),
                    singleLine = true
                )
                
                Spacer(Modifier.height(16.dp))
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Browse by genre",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                    IconButton(onClick = { /* Toggle view */ }) {
                        Icon(Icons.Rounded.GridView, null, tint = TextPrimary)
                    }
                }
            }
        }
    ) { paddingValues ->
        val genres = remember {
            GenreApiService.STANDARD_GENRES.map { name ->
                name to getGenreColor(name)
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(paddingValues)
        ) {
            items(genres) { (name, color) ->
                GenreCard(name, color) {
                    // Navigate to genre detail
                }
            }
        }
    }
}

private fun getGenreColor(name: String): Color {
    val hash = name.hashCode()
    return when (name) {
        "Tamil", "Tamil Film Music", "Tamil Melody" -> Color(0xFF0D47A1)
        "Hindi", "Hindi Film Music" -> Color(0xFFE65100)
        "English Pop", "Pop" -> Color(0xFFC2185B)
        "Rock" -> Color(0xFFD32F2F)
        "Hip-Hop", "Rap" -> Color(0xFF4527A0)
        "Electronic", "EDM", "Dance" -> Color(0xFF00796B)
        "Classical" -> Color(0xFF5D4037)
        "Lo-Fi", "Ambient" -> Color(0xFF263238)
        else -> {
            // Generate a stable color based on name
            val hue = (hash.coerceAtLeast(0) % 360).toFloat()
            Color.hsl(hue, 0.6f, 0.4f)
        }
    }
}

@Composable
fun GenreCard(name: String, color: Color, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
    ) {
        Box(Modifier.padding(12.dp)) {
            Text(
                name,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    fontSize = 14.sp
                ),
                modifier = Modifier.align(Alignment.TopStart)
            )
        }
    }
}
