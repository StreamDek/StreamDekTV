package com.streamdek.tv.nativeapp.ui.player

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.streamdek.tv.R
import com.streamdek.tv.nativeapp.ui.StreamDekNavIcons
import com.streamdek.tv.nativeapp.ui.StreamDekPlayerIcons
import com.streamdek.tv.nativeapp.data.EpisodeContext
import com.streamdek.tv.nativeapp.data.MediaDetail
import com.streamdek.tv.nativeapp.data.SeasonDetail
import com.streamdek.tv.nativeapp.data.SeasonEpisode
import com.streamdek.tv.nativeapp.data.SeasonRef
import com.streamdek.tv.nativeapp.data.SeriesEpisodeStanding
import com.streamdek.tv.nativeapp.data.StreamDekRepository
import com.streamdek.tv.nativeapp.ui.AppFormats
import com.streamdek.tv.nativeapp.ui.AppPillShape
import com.streamdek.tv.nativeapp.ui.LocalAppLanguage
import com.streamdek.tv.nativeapp.ui.TvMotion
import com.streamdek.tv.nativeapp.ui.detail.formatRuntime
import com.streamdek.tv.nativeapp.ui.detail.isEpisodeReleased
import com.streamdek.tv.nativeapp.ui.detail.watchedEpisodeKey
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.delay

private val EpisodeRowShape = RoundedCornerShape(16.dp)
private val EpisodeThumbShape = RoundedCornerShape(10.dp)

/**
 * The episode browser: the series being played, season by season, inside the player.
 *
 * It keeps nothing of its own. Seasons come from the title's detail, episodes from
 * [StreamDekRepository.fetchSeason] and its cache - the same call the series page makes - and
 * watched and part-watched from [StreamDekRepository.fetchSeriesEpisodeStanding], which reads the
 * history the series page's resume point is worked out from. Choosing an episode hands an
 * [EpisodeContext] back to the player, which loads it exactly as it loads the next episode.
 *
 * Built for a remote, as two focus islands and nothing else:
 *
 *  - the season chips, a row. Left and Right walk it and the list below follows the highlight, so
 *    looking through a series is one press per season with nothing to confirm. Down goes into the
 *    list; the other three directions are closed.
 *  - the episodes, a column. Up and Down walk it, Up from the first episode returns to the chip of
 *    the season on show (not whichever chip happens to sit above), and Left and Right are closed so
 *    the highlight can never slide off the panel onto the picture.
 *
 * It opens with the highlight already on the episode that is playing, in its own season, so OK
 * straight away is "carry on" and one press either way is the neighbouring episode. Back closes it
 * and returns the highlight to the Episodes button, which is the player's doing, not this panel's.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun PlayerEpisodePanel(
    repository: StreamDekRepository,
    mediaId: String,
    detail: MediaDetail,
    currentEpisode: EpisodeContext,
    /** How far through the playing episode the player is right now, which is newer than any record. */
    currentFraction: Float?,
    onInteract: () -> Unit,
    /** The chosen episode, and whether it should start from the beginning rather than resume. */
    onSelectEpisode: (EpisodeContext, Boolean) -> Unit,
    /** Choosing the episode already on screen: there is nowhere to go, so the panel just closes. */
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val seasons = remember(detail.seasons, currentEpisode.seasonNumber) {
        val listed = detail.seasons.filter { it.episodeCount > 0 || it.seasonNumber == currentEpisode.seasonNumber }
        val all = if (listed.any { it.seasonNumber == currentEpisode.seasonNumber }) listed
        else listed + SeasonRef(seasonNumber = currentEpisode.seasonNumber, name = "")
        all.sortedBy { it.seasonNumber }
    }
    var selectedSeason by remember(mediaId) { mutableIntStateOf(currentEpisode.seasonNumber) }
    val loadedSeasons = remember(mediaId) { mutableStateMapOf<Int, SeasonDetail>() }
    var failedSeason by remember(mediaId) { mutableStateOf<Int?>(null) }
    var retryToken by remember(mediaId) { mutableIntStateOf(0) }
    var standing by remember(mediaId) { mutableStateOf(SeriesEpisodeStanding()) }
    /** Once the highlight has been put on the playing episode, or the viewer has moved it themselves. */
    var initialFocusPlaced by remember(mediaId) { mutableStateOf(false) }

    LaunchedEffect(mediaId) {
        standing = runCatching { repository.fetchSeriesEpisodeStanding(detail) }.getOrDefault(SeriesEpisodeStanding())
    }
    LaunchedEffect(mediaId, selectedSeason, retryToken) {
        if (loadedSeasons.containsKey(selectedSeason)) return@LaunchedEffect
        failedSeason = null
        // The highlight travelling along the chips selects every season it passes. Only the one it
        // stops on is worth a request; the season being played is asked for at once.
        if (selectedSeason != currentEpisode.seasonNumber) delay(160)
        val season = runCatching { repository.fetchSeason(mediaId, selectedSeason) }.getOrNull()
        if (season != null) loadedSeasons[selectedSeason] = season else failedSeason = selectedSeason
    }

    val episodes = loadedSeasons[selectedSeason]?.episodes
    val playingIndex = if (selectedSeason == currentEpisode.seasonNumber) {
        episodes?.indexOfFirst { it.episodeNumber == currentEpisode.episodeNumber } ?: -1
    } else -1
    /** The row the highlight lands on when it comes into the list: the last one it was on. */
    var entryIndex by remember(selectedSeason, episodes?.size) { mutableIntStateOf(playingIndex.coerceAtLeast(0)) }

    val seasonRequesters = remember(seasons) { seasons.associate { it.seasonNumber to FocusRequester() } }
    val entryRequester = remember { FocusRequester() }
    val panelRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val showSeasons = seasons.size > 1

    // Each season opens on what is playing, when that is in it, and at its first episode otherwise.
    LaunchedEffect(selectedSeason, episodes?.size) {
        if (!episodes.isNullOrEmpty()) listState.scrollToItem(playingIndex.coerceAtLeast(0))
    }
    LaunchedEffect(episodes != null, initialFocusPlaced) {
        if (initialFocusPlaced) return@LaunchedEffect
        if (episodes == null) {
            // Something in the panel holds the highlight while the first season is read, so the
            // remote is never left pointing at controls that are no longer on screen.
            delay(80)
            runCatching { ((seasonRequesters[selectedSeason]?.takeIf { showSeasons }) ?: panelRequester).requestFocus() }
            return@LaunchedEffect
        }
        if (episodes.isEmpty()) {
            initialFocusPlaced = true
            return@LaunchedEffect
        }
        listState.scrollToItem(playingIndex.coerceAtLeast(0))
        // The row is attached a frame or two after the scroll, and later still on a slow stick.
        repeat(8) { attempt ->
            delay(if (attempt == 0) 16L else 40L)
            if (runCatching { entryRequester.requestFocus() }.isSuccess) {
                initialFocusPlaced = true
                return@LaunchedEffect
            }
        }
        initialFocusPlaced = true
    }

    PlayerGlassSurface(
        drawer = true,
        contentPadding = PaddingValues(0.dp),
        modifier = modifier
            .width(600.dp)
            .fillMaxHeight()
            .focusRequester(panelRequester)
            .focusable(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(PlayerTokens.AccentSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(StreamDekNavIcons.LibraryOutline, contentDescription = null, tint = PlayerTokens.Accent, modifier = Modifier.size(24.dp))
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.detail_episodes),
                        style = androidx.tv.material3.MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
                        color = PlayerTokens.TextPrimary,
                        maxLines = 1,
                    )
                    Text(
                        text = detail.title.ifBlank { stringResource(R.string.player_panel_episodes_description) },
                        style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
                        color = PlayerTokens.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (showSeasons) {
                val seasonScroll = rememberScrollState()
                val chipOffsets = remember(seasons) { mutableStateMapOf<Int, Int>() }
                var seasonRowAligned by remember(seasons) { mutableStateOf(false) }
                // Once, on opening: bring the playing season's chip into view. After that the row
                // follows the highlight on its own, as any scrolling row of focusable things does.
                val selectedOffset = chipOffsets[selectedSeason]
                LaunchedEffect(selectedOffset, seasonRowAligned) {
                    if (!seasonRowAligned && selectedOffset != null) {
                        seasonScroll.scrollTo(selectedOffset)
                        seasonRowAligned = true
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(seasonScroll)
                        .focusGroup()
                        .focusProperties {
                            // Coming up from the list lands on the season on show. Landing on the
                            // nearest chip instead would change season just by arriving.
                            enter = { seasonRequesters[selectedSeason] ?: FocusRequester.Default }
                            exit = { direction ->
                                when (direction) {
                                    // Into the list, or onto Try Again when the season could not be read.
                                    FocusDirection.Down ->
                                        if (episodes.isNullOrEmpty() && failedSeason != selectedSeason) FocusRequester.Cancel else entryRequester
                                    FocusDirection.Up, FocusDirection.Left, FocusDirection.Right -> FocusRequester.Cancel
                                    else -> FocusRequester.Default
                                }
                            }
                        }
                        .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    seasons.forEach { season ->
                        val number = season.seasonNumber
                        PlayerSeasonChip(
                            label = season.name.ifBlank { stringResource(R.string.detail_season_number, number) },
                            selected = number == selectedSeason,
                            modifier = Modifier
                                .then(seasonRequesters[number]?.let { Modifier.focusRequester(it) } ?: Modifier)
                                .onGloballyPositioned { chipOffsets[number] = it.positionInParent().x.toInt() },
                            onFocused = {
                                if (selectedSeason != number) {
                                    selectedSeason = number
                                    // The viewer is steering now; the opening highlight must not
                                    // arrive late and pull them back down into the list.
                                    initialFocusPlaced = true
                                }
                                onInteract()
                            },
                            onClick = { runCatching { entryRequester.requestFocus() } },
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    episodes == null && failedSeason == selectedSeason -> Column(
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 36.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        // With no chips above it, Try Again is the only thing here to land on.
                        LaunchedEffect(Unit) {
                            if (!showSeasons) {
                                delay(60)
                                runCatching { entryRequester.requestFocus() }
                            }
                        }
                        Text(
                            text = stringResource(R.string.error_episode_list_failed),
                            style = androidx.tv.material3.MaterialTheme.typography.bodyMedium,
                            color = PlayerTokens.TextSecondary,
                        )
                        OutlinedButton(
                            onClick = { retryToken += 1 },
                            shape = ButtonDefaults.shape(AppPillShape),
                            modifier = Modifier.focusRequester(entryRequester).onFocusChanged { if (it.isFocused) onInteract() },
                            colors = ButtonDefaults.colors(
                                containerColor = Color(0x10FFFFFF),
                                focusedContainerColor = Color(0x22FFFFFF),
                                contentColor = Color.White,
                                focusedContentColor = Color.White,
                            ),
                        ) {
                            Text(stringResource(R.string.action_retry))
                        }
                    }
                    episodes == null -> CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center).size(30.dp),
                        strokeWidth = 3.dp,
                        color = PlayerTokens.Accent,
                    )
                    episodes.isEmpty() -> Text(
                        text = stringResource(R.string.player_episodes_empty),
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 36.dp),
                        style = androidx.tv.material3.MaterialTheme.typography.bodyMedium,
                        color = PlayerTokens.TextSecondary,
                    )
                    else -> LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize().focusGroup(),
                        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 18.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(episodes) { index, episode ->
                            val key = watchedEpisodeKey(selectedSeason, episode.episodeNumber)
                            val playing = index == playingIndex
                            val watched = key in standing.watchedEpisodeKeys
                            val released = isEpisodeReleased(episode.airDate)
                            val fraction = when {
                                playing -> currentFraction?.takeIf { it > 0.01f }
                                watched -> null
                                else -> standing.progressFractions[key]
                            }
                            PlayerEpisodeRow(
                                episode = episode,
                                playing = playing,
                                watched = watched,
                                released = released,
                                fraction = fraction,
                                modifier = Modifier
                                    .then(if (index == entryIndex) Modifier.focusRequester(entryRequester) else Modifier)
                                    .focusProperties {
                                        left = FocusRequester.Cancel
                                        right = FocusRequester.Cancel
                                        if (index == 0) up = seasonRequesters[selectedSeason]?.takeIf { showSeasons } ?: FocusRequester.Cancel
                                        if (index == episodes.lastIndex) down = FocusRequester.Cancel
                                    },
                                onFocused = {
                                    entryIndex = index
                                    initialFocusPlaced = true
                                    onInteract()
                                },
                                onClick = {
                                    if (playing) {
                                        onClose()
                                    } else {
                                        onSelectEpisode(
                                            EpisodeContext(
                                                seasonNumber = selectedSeason,
                                                episodeNumber = episode.episodeNumber,
                                                title = episode.name,
                                                overview = episode.overview,
                                                still = episode.still,
                                                runtime = episode.runtime,
                                                airDate = episode.airDate,
                                                tmdbEpisodeId = episode.id,
                                            ),
                                            // A finished episode chosen again is being rewatched, not
                                            // resumed from its last few seconds.
                                            watched && fraction == null,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** One season in the browser's chip row. Focus selects it; OK steps down into its episodes. */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlayerSeasonChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        scale = ButtonDefaults.scale(focusedScale = TvMotion.focusScale()),
        shape = ButtonDefaults.shape(AppPillShape),
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocused() },
        colors = ButtonDefaults.colors(
            containerColor = if (selected) PlayerTokens.AccentSoft else Color(0x10FFFFFF),
            focusedContainerColor = if (selected) Color(0x4DF0BA66) else Color(0x22FFFFFF),
            contentColor = Color.White,
            focusedContentColor = Color.White,
        ),
        border = ButtonDefaults.border(
            border = if (selected) Border(BorderStroke(1.dp, PlayerTokens.Accent.copy(alpha = 0.55f)), shape = AppPillShape) else Border.None,
            focusedBorder = Border(BorderStroke(2.dp, PlayerTokens.Accent), shape = AppPillShape),
        ),
    ) {
        Text(
            text = label,
            maxLines = 1,
            style = androidx.tv.material3.MaterialTheme.typography.labelLarge.copy(
                fontWeight = if (selected) FontWeight.Black else FontWeight.SemiBold,
            ),
        )
    }
}

/**
 * One episode: its still on the left with the states that are about the picture - playing now, how
 * far in, not out yet - and number, name, date, length and synopsis beside it.
 *
 * An unaired episode keeps its place and can be landed on, so the season reads whole and the remote
 * never skips a row, but OK does nothing there, as on the series page.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PlayerEpisodeRow(
    episode: SeasonEpisode,
    playing: Boolean,
    watched: Boolean,
    released: Boolean,
    fraction: Float?,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    val language = LocalAppLanguage.current
    val airDate = remember(episode.airDate, language) {
        episode.airDate?.takeIf { it.isNotBlank() }
            ?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
            ?.let { AppFormats.date(language, it.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()) }
    }
    val meta = listOfNotNull(
        airDate?.let { if (released) it else stringResource(R.string.player_episodes_airs_on, it) },
        episode.runtime?.takeIf { it > 0 }?.let { formatRuntime(it) },
    )
    val name = episode.name.ifBlank { stringResource(R.string.new_episode_number, episode.episodeNumber) }
    val heading = episode.episodeNumber.toString() + ". " + name
    OutlinedButton(
        onClick = { if (released) onClick() },
        scale = ButtonDefaults.scale(focusedScale = TvMotion.focusScale()),
        shape = ButtonDefaults.shape(EpisodeRowShape),
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { if (it.isFocused) onFocused() },
        colors = ButtonDefaults.colors(
            containerColor = if (playing) PlayerTokens.AccentSoft else Color(0x10FFFFFF),
            focusedContainerColor = if (playing) Color(0x4DF0BA66) else Color(0x24FFFFFF),
            contentColor = Color.White,
            focusedContentColor = Color.White,
        ),
        border = ButtonDefaults.border(
            border = if (playing) Border(BorderStroke(1.dp, PlayerTokens.Accent.copy(alpha = 0.55f)), shape = EpisodeRowShape) else Border.None,
            focusedBorder = Border(BorderStroke(2.dp, PlayerTokens.Accent), shape = EpisodeRowShape),
        ),
        contentPadding = PaddingValues(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = if (released) 1f else 0.55f },
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(152.dp)
                    .height(86.dp)
                    .clip(EpisodeThumbShape)
                    .background(Color.Black.copy(alpha = 0.5f)),
            ) {
                if (!episode.still.isNullOrBlank()) {
                    AsyncImage(
                        model = episode.still,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                if (playing || !released) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 7.dp, bottom = if (fraction != null) 11.dp else 7.dp)
                            .clip(AppPillShape)
                            .background(if (playing) PlayerTokens.Accent else Color(0xB3000000))
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = stringResource(if (playing) R.string.player_now_playing else R.string.player_episodes_upcoming),
                            style = androidx.tv.material3.MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Black),
                            color = if (playing) PlayerTokens.Ink else Color.White,
                            maxLines = 1,
                        )
                    }
                }
                if (fraction != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .height(4.dp)
                            .background(Color.Black.copy(alpha = 0.55f)),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                                .background(PlayerTokens.Accent),
                        )
                    }
                }
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = heading,
                        modifier = Modifier.weight(1f),
                        style = androidx.tv.material3.MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                        color = PlayerTokens.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (watched) {
                        Icon(
                            StreamDekPlayerIcons.Watched,
                            contentDescription = stringResource(R.string.player_watched),
                            tint = PlayerTokens.Accent,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                if (meta.isNotEmpty()) {
                    Text(
                        text = meta.joinToString("  ·  "),
                        style = androidx.tv.material3.MaterialTheme.typography.labelMedium,
                        color = PlayerTokens.TextTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                episode.overview?.takeIf { it.isNotBlank() }?.let { overview ->
                    Text(
                        text = overview,
                        style = androidx.tv.material3.MaterialTheme.typography.bodySmall,
                        color = PlayerTokens.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
