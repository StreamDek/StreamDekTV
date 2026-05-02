package com.streamdek.tv.nativeapp.ui.network

import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.streamdek.tv.nativeapp.data.GenreItem
import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.ui.AppCardShape
import com.streamdek.tv.nativeapp.ui.AppPillShape
import java.time.Year

@Composable
fun NetworkBrowseScreen(
    repository: StreamDekRepository,
    networkId: String,
    networkName: String,
    onBack: () -> Unit,
    onOpenDetail: (String, String) -> Unit,
) {
    var mediaType by remember { mutableStateOf("all") }
    var year by remember { mutableStateOf<String?>(null) }
    var genreId by remember { mutableStateOf<Int?>(null) }
    var minRating by remember { mutableStateOf<Int?>(null) }
    var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var genres by remember { mutableStateOf<List<GenreItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(networkId, mediaType, year, genreId) {
        loading = true
        val typeForGenres = if (mediaType == "tv") "tv" else "movie"
        genres = repository.fetchGenres(typeForGenres)
        results = repository.fetchNetworkCatalog(
            networkId = networkId,
            type = mediaType,
            year = year,
            genreId = genreId,
            sort = "year",
            forceRefresh = true,
        ).results
        loading = false
    }

    LaunchedEffect(results) {
        results.take(24).flatMap { listOfNotNull(it.backdrop, it.poster) }.distinct().forEach { url ->
            context.imageLoader.enqueue(
                ImageRequest.Builder(context)
                    .data(url)
                    .memoryCacheKey(url)
                    .diskCacheKey(url)
                    .build(),
            )
        }
    }

    val visibleResults = results.filter { item ->
        minRating == null || (item.rating ?: 0.0) >= minRating!!.toDouble()
    }
    val yearOptions = remember {
        listOf<String?>(null) + (0..10).map { (Year.now().value - it).toString() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(networkHeroBrush(networkName)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color(0x22000000), Color(0xCC040404), Color(0xFF040404)),
                    ),
                ),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 48.dp, end = 48.dp, top = 48.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                onClick = onBack,
                shape = ButtonDefaults.shape(RoundedCornerShape(999.dp)),
            ) {
                Text("Back")
            }
            Text(
                text = networkName,
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Black),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Filter by type, year, genre, and rating.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.78f),
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 188.dp),
            contentPadding = PaddingValues(bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            item {
                FilterRow(
                    title = "Type",
                    options = listOf("All" to "all", "Movies" to "movie", "Series" to "tv"),
                    selected = mediaType,
                    onSelected = {
                        mediaType = it
                        genreId = null
                    },
                )
            }
            item {
                FilterRow(
                    title = "Year",
                    options = yearOptions.map { (it ?: "All") to (it ?: "") },
                    selected = year ?: "",
                    onSelected = { year = it.ifBlank { null } },
                )
            }
            item {
                FilterRow(
                    title = "Genre",
                    options = listOf("All" to "") + genres.map { it.name to it.id.toString() },
                    selected = genreId?.toString().orEmpty(),
                    onSelected = { genreId = it.toIntOrNull() },
                )
            }
            item {
                FilterRow(
                    title = "Rating",
                    options = listOf("All" to "", "5+" to "5", "6+" to "6", "7+" to "7", "8+" to "8"),
                    selected = minRating?.toString().orEmpty(),
                    onSelected = { minRating = it.toIntOrNull() },
                )
            }
            item {
                Text(
                    text = if (loading) "Loading titles..." else "${visibleResults.size} titles",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 48.dp, end = 48.dp),
                )
            }
            item {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(4),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(860.dp)
                        .padding(horizontal = 48.dp),
                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    gridItems(visibleResults, key = { "${it.type}:${it.id}" }) { item ->
                        NetworkCatalogCard(item = item, onPressed = { onOpenDetail(item.type, item.id) })
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterRow(
    title: String,
    options: List<Pair<String, String>>,
    selected: String,
    onSelected: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.66f),
            modifier = Modifier.padding(start = 48.dp, end = 48.dp),
        )
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .focusGroup(),
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(options, key = { it.second.ifBlank { it.first } }) { option ->
                val isSelected = option.second == selected
                OutlinedButton(
                    onClick = { onSelected(option.second) },
                    shape = ButtonDefaults.shape(AppPillShape),
                    colors = ButtonDefaults.colors(
                        containerColor = if (isSelected) Color(0xFFF4EDE2) else Color(0x1215181D),
                        contentColor = if (isSelected) Color(0xFF18120A) else MaterialTheme.colorScheme.onBackground,
                    ),
                ) {
                    Text(option.first)
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NetworkCatalogCard(
    item: MediaItem,
    onPressed: () -> Unit,
) {
    Card(
        onClick = onPressed,
        modifier = Modifier.size(width = 320.dp, height = 190.dp),
        colors = CardDefaults.colors(
            containerColor = Color(0xFF181A1F),
            focusedContainerColor = Color(0xFF181A1F),
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFF0BA66)),
                shape = AppCardShape,
            ),
        ),
        scale = CardDefaults.scale(focusedScale = 1.02f),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(AppCardShape),
        ) {
            AsyncImage(
                model = item.backdrop ?: item.poster,
                contentDescription = item.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color(0xE0000000)),
                        ),
                    ),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = if (item.type == "tv") "Series" else "Movie",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFFF0BA66),
                )
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = listOfNotNull(item.year, item.rating?.let { "IMDb ${"%.1f".format(it)}" }).joinToString("  |  "),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.78f),
                )
            }
        }
    }
}

private fun networkHeroBrush(name: String): Brush {
    val colors = when {
        name.contains("netflix", ignoreCase = true) -> listOf(Color(0xFF2C0004), Color(0xFF8E0E16), Color(0xFF140202))
        name.contains("prime", ignoreCase = true) -> listOf(Color(0xFF03151F), Color(0xFF0F4C81), Color(0xFF122A39))
        name.contains("apple", ignoreCase = true) -> listOf(Color(0xFFF3F6FA), Color(0xFFE3E8EF), Color(0xFFF7F9FC))
        name.contains("disney", ignoreCase = true) -> listOf(Color(0xFF071228), Color(0xFF0E5ACF), Color(0xFF0B1730))
        name.contains("hbo", ignoreCase = true) || name.contains("max", ignoreCase = true) -> listOf(Color(0xFF17102B), Color(0xFF4A3DC7), Color(0xFF160B2B))
        else -> listOf(Color(0xFF111318), Color(0xFF1D2430), Color(0xFF0C0E12))
    }
    return Brush.horizontalGradient(colors)
}
