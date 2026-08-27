package com.streamdek.tv.nativeapp.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.ui.player.PlayerGlassSurface
import kotlinx.coroutines.launch

@Composable
fun BrowseItemActionMenu(
    repository: StreamDekRepository,
    item: MediaItem,
    showRemoveFromContinueWatching: Boolean = false,
    onDismiss: () -> Unit,
    onDismissAfterRemoval: () -> Unit = onDismiss,
    onOpenDetail: () -> Unit,
    onChanged: suspend () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val primaryRequester = remember { FocusRequester() }
    val watchlistRequester = remember { FocusRequester() }
    val continueRequester = remember { FocusRequester() }
    val secondaryRequester = remember { FocusRequester() }
    val closeRequester = remember { FocusRequester() }
    var loading by remember(item.type, item.id) { mutableStateOf(true) }
    var inWatchlist by remember(item.type, item.id) { mutableStateOf(false) }
    var watched by remember(item.type, item.id, item.episode?.seasonNumber, item.episode?.episodeNumber) { mutableStateOf(false) }
    var actionError by remember(item.type, item.id) { mutableStateOf<String?>(null) }

    LaunchedEffect(item.type, item.id, item.episode?.seasonNumber, item.episode?.episodeNumber) {
        loading = true
        actionError = null
        inWatchlist = runCatching {
            repository.isInWatchlist(item)
        }.getOrDefault(false)
        watched = runCatching {
            repository.isWatched(item.type, item.id, item.episode, forceRefresh = true)
        }.getOrDefault(false)
        loading = false
    }

    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        runCatching { primaryRequester.requestFocus() }
    }

    BackHandler(onBack = onDismiss)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xB8000000)),
        contentAlignment = Alignment.Center,
    ) {
        PlayerGlassSurface(
            modifier = Modifier.width(560.dp),
            contentPadding = PaddingValues(24.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = when {
                        loading -> "Loading actions…"
                        watched && item.type == "movie" -> "This movie is already marked watched."
                        watched && item.type == "tv" && item.episode != null -> "This episode is already marked watched."
                        watched && item.type == "tv" -> "This series is already marked watched."
                        item.type == "tv" && item.episode != null ->
                            "Choose an action for S${item.episode.seasonNumber}E${item.episode.episodeNumber}."
                        item.type == "tv" -> "Choose an action for this series."
                        else -> "Choose an action for this title."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                actionError?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFFFB4AB),
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            onDismiss()
                            onOpenDetail()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(primaryRequester),
                        scale = androidx.tv.material3.ButtonDefaults.scale(focusedScale = 1f),
                    ) {
                        Text("Open Details")
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val result = runCatching {
                                    if (inWatchlist) {
                                        repository.removeFromWatchlist(item)
                                    } else {
                                        repository.addToWatchlist(item)
                                    }
                                    // Release the dialog's focus trap before the backing grid is
                                    // changed. A removed card cannot safely receive focus again.
                                    onDismiss()
                                    onChanged()
                                }
                                result.onSuccess {
                                    inWatchlist = !inWatchlist
                                }.onFailure {
                                    actionError = it.message ?: "Watchlist update failed."
                                }
                            }
                        },
                        enabled = !loading,
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(watchlistRequester)
                            .focusProperties {
                                up = primaryRequester
                                down = if (showRemoveFromContinueWatching) continueRequester else secondaryRequester
                            },
                        scale = androidx.tv.material3.ButtonDefaults.scale(focusedScale = 1f),
                    ) {
                        Text(if (inWatchlist) "Remove from Watchlist" else "Add to Watchlist")
                    }

                    if (showRemoveFromContinueWatching) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val result = runCatching {
                                        // This is intentionally a dismissed progress tombstone. It
                                        // neither writes a completion marker nor adds watched history,
                                        // but it does stop stale provider progress returning after sync.
                                        check(repository.dismissContinueWatching(item)) {
                                            "Could not remove this title from Continue Watching."
                                        }
                                        // The focused card no longer exists after this mutation.
                                        // Let the owning grid dismiss without restoring that stale
                                        // requester, then choose a surviving neighbour itself.
                                        onDismissAfterRemoval()
                                        onChanged()
                                    }
                                    result.onFailure {
                                        actionError = it.message ?: "Could not remove this title from Continue Watching."
                                    }
                                }
                            },
                            enabled = !loading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .focusRequester(continueRequester)
                                .focusProperties {
                                    up = watchlistRequester
                                    down = secondaryRequester
                                },
                            scale = androidx.tv.material3.ButtonDefaults.scale(focusedScale = 1f),
                        ) {
                            Text("Remove from Continue Watching")
                        }
                    }

                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val result = runCatching {
                                    val success = repository.markBrowseItemWatched(item)
                                    onChanged()
                                    success
                                }
                                result.onSuccess { success ->
                                    if (success) {
                                        watched = true
                                        onDismiss()
                                    } else {
                                        actionError = "Could not mark this title watched."
                                    }
                                }.onFailure {
                                    actionError = it.message ?: "Could not mark this title watched."
                                }
                            }
                        },
                        enabled = !loading && (!watched || item.progress?.let { it > 0.0 } == true),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(secondaryRequester)
                            .focusProperties {
                                up = if (showRemoveFromContinueWatching) continueRequester else watchlistRequester
                                down = closeRequester
                            },
                        scale = androidx.tv.material3.ButtonDefaults.scale(focusedScale = 1f),
                    ) {
                        Text(
                            when {
                                watched && item.progress?.let { it > 0.0 } == true -> "Mark as Watched Again"
                                watched -> "Already Watched"
                                item.type == "tv" && item.episode == null -> "Mark Series Watched"
                                else -> "Mark as Watched"
                            },
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .focusRequester(closeRequester)
                            .focusProperties {
                                up = secondaryRequester
                            },
                        scale = androidx.tv.material3.ButtonDefaults.scale(focusedScale = 1f),
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}
