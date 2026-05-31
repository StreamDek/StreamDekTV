package com.streamdek.tv.nativeapp.ui.search

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.ui.AppCardShape
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(
    repository: StreamDekRepository,
    onOpenDetail: (String, String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<MediaItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(false) }
    val searchFieldRequester = remember { FocusRequester() }
    val searchBoxRequester = remember { FocusRequester() }
    val firstResultRequester = remember { FocusRequester() }
    var anchoredIndex by remember { mutableIntStateOf(0) }
    val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current

    LaunchedEffect(Unit) {
        delay(180)
        searchBoxRequester.requestFocus()
    }

    LaunchedEffect(query) {
        val normalized = query.trim()
        if (normalized.length < 2) {
            results = emptyList()
            loading = false
            return@LaunchedEffect
        }
        loading = true
        delay(220)
        results = repository.searchMedia(normalized)
        loading = false
    }

    LaunchedEffect(results) {
        results.take(16).flatMap { listOfNotNull(it.backdrop, it.poster) }.distinct().forEach { url ->
            context.imageLoader.enqueue(
                ImageRequest.Builder(context)
                    .data(url)
                    .memoryCacheKey(url)
                    .diskCacheKey(url)
                    .build(),
            )
        }
    }

    LaunchedEffect(searchActive) {
        if (searchActive) {
            delay(40)
            searchFieldRequester.requestFocus()
        }
    }

    LaunchedEffect(anchoredIndex, results.size) {
        val targetIndex = anchoredIndex.coerceIn(0, (results.size - 1).coerceAtLeast(0))
        if (results.isNotEmpty() && rowState.firstVisibleItemIndex != targetIndex) {
            rowState.scrollToItem(targetIndex)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 48.dp, end = 48.dp, top = 48.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Search",
                style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Black),
                color = MaterialTheme.colorScheme.onBackground,
            )
            Text(
                text = "Search movies and series.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
            )
            if (searchActive) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { androidx.compose.material3.Text("Title, actor, or genre") },
                    singleLine = true,
                    shape = RoundedCornerShape(999.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFF11141B),
                        unfocusedContainerColor = Color(0xFF11141B),
                        focusedIndicatorColor = Color(0xFFF0BA66),
                        unfocusedIndicatorColor = Color(0x3311161D),
                        focusedTextColor = Color(0xFFF5F1E8),
                        unfocusedTextColor = Color(0xFFF5F1E8),
                        focusedLabelColor = Color(0xFFF0BA66),
                        unfocusedLabelColor = Color(0xB3F5F1E8),
                        cursorColor = Color(0xFFF0BA66),
                    ),
                    modifier = Modifier
                        .width(640.dp)
                        .focusRequester(searchFieldRequester)
                        .focusProperties {
                            if (results.isNotEmpty()) down = firstResultRequester
                        }
                        .onFocusChanged {
                            if (!it.isFocused) searchActive = false
                        },
                )
            } else {
                Button(
                    onClick = { searchActive = true },
                    shape = ButtonDefaults.shape(RoundedCornerShape(999.dp)),
                    colors = ButtonDefaults.colors(
                        containerColor = Color(0xFF11141B),
                        focusedContainerColor = Color(0xFF11141B),
                        contentColor = Color(0xFFF5F1E8),
                        focusedContentColor = Color(0xFFF5F1E8),
                    ),
                    border = ButtonDefaults.border(
                        border = Border(
                            border = androidx.compose.foundation.BorderStroke(2.dp, Color(0x3311161D)),
                            shape = RoundedCornerShape(999.dp),
                        ),
                        focusedBorder = Border(
                            border = androidx.compose.foundation.BorderStroke(2.dp, Color(0xFFF0BA66)),
                            shape = RoundedCornerShape(999.dp),
                        ),
                    ),
                    modifier = Modifier
                        .width(640.dp)
                        .height(56.dp)
                        .focusRequester(searchBoxRequester)
                        .focusProperties {
                            if (results.isNotEmpty()) down = firstResultRequester
                        },
                    contentPadding = PaddingValues(horizontal = 22.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = query.ifBlank { "Title, actor, or genre" },
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (query.isBlank()) Color(0xB3F5F1E8) else Color(0xFFF5F1E8),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 198.dp),
            contentPadding = PaddingValues(bottom = 180.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            if (query.trim().length < 2) {
                item {
                    Text(
                        text = "Start typing to search.",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f),
                        modifier = Modifier.padding(start = 48.dp, end = 48.dp),
                    )
                }
            } else {
                item {
                    Text(
                        text = if (loading) "Searching..." else "${results.size} results",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(start = 48.dp, end = 48.dp),
                    )
                }
            }

            if (results.isNotEmpty()) {
                item {
                    LazyRow(
                        state = rowState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusGroup(),
                        contentPadding = PaddingValues(start = 48.dp, end = 48.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        items(results, key = { "${it.type}:${it.id}" }) { item ->
                            val index = results.indexOf(item)
                            SearchResultCard(
                                item = item,
                                modifier = if (index == 0) {
                                    Modifier
                                        .focusRequester(firstResultRequester)
                                        .focusProperties { up = searchBoxRequester }
                                } else {
                                    Modifier
                                },
                                onFocused = { anchoredIndex = index },
                                onPressed = { onOpenDetail(item.type, item.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun SearchResultCard(
    item: MediaItem,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit = {},
    onPressed: () -> Unit,
) {
    Card(
        onClick = onPressed,
        modifier = modifier
            .size(width = 260.dp, height = 150.dp)
            .onFocusChanged { if (it.isFocused) onFocused() },
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
                            colors = listOf(Color.Transparent, Color(0xD9000000)),
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
            }
        }
    }
}
