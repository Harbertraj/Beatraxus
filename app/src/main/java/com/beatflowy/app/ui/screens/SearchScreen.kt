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
        val genres = listOf(
            "Alternative" to Color(0xFF673AB7),
            "Americana & Indie" to Color(0xFF009688),
            "Asian Music" to Color(0xFF3F51B5),
            "Asian Music & Indian" to Color(0xFF827717),
            "Bandas originales de..." to Color(0xFFD84315),
            "Blues" to Color(0xFF00ACC1),
            "Classical" to Color(0xFF0097A7),
            "Dance" to Color(0xFF2E7D32),
            "Film Soundtracks" to Color(0xFF455A64),
            "Films, Bandes original..." to Color(0xFFC2185B),
            "Films" to Color(0xFF827717),
            "Films/Games & TV Series" to Color(0xFF43A047),
            "Folk/Rock" to Color(0xFF00796B),
            "Hip-Hop/Rap" to Color(0xFFAD1457),
            "Instrumental, Relax, Premium" to Color(0xFFBF360C),
            "Indian Music" to Color(0xFF9E9D24),
            "Indian Pop" to Color(0xFF0277BD),
            "Indie Pop" to Color(0xFFC62828),
            "International" to Color(0xFF263238),
            "Malayalam" to Color(0xFFEF6C00),
            "Afrobeat, Neo-soul" to Color(0xFF455A64),
            "Malayalam, Tamil Pop" to Color(0xFF37474F),
            "Originale soundtracks" to Color(0xFFB71C1C),
            "OST" to Color(0xFF263238),
            "Pop" to Color(0xFF795548),
            "R&B" to Color(0xFF006064),
            "Rap/Hip-Hop" to Color(0xFFAD1457),
            "Soul" to Color(0xFF311B92),
            "Soundtrack - Telugu" to Color(0xFF01579B),
            "Soundtrack" to Color(0xFF1B5E20),
            "Soundtrack & Music" to Color(0xFF2E7D32),
            "Soundtracks" to Color(0xFF37474F),
            "Tamil" to Color(0xFF0D47A1),
            "Tamil OST" to Color(0xFFB71C1C),
            "Tamil Pop" to Color(0xFF1565C0),
            "Tamil Songs" to Color(0xFF455A64),
            "Tamil - Classic" to Color(0xFF6A1B9A),
            "Tamil, Malayalam, Indian..." to Color(0xFF006064),
            "Tamil, Music, Indian, Regional..." to Color(0xFF4E342E),
            "Tamil, Music, India" to Color(0xFF212121),
            "Western Instrumental" to Color(0xFF004D40),
            "Tamil OST" to Color(0xFF263238),
            "Telugu" to Color(0xFF1B5E20),
            "Unknown Genre" to Color(0xFF212121),
            "Vocal Music - Classic" to Color(0xFF4527A0),
            "World" to Color(0xFFD81B60)
        )

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
