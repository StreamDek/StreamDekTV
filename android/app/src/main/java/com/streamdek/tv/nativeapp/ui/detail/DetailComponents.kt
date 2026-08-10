package com.streamdek.tv.nativeapp.ui.detail

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Glow
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.streamdek.tv.nativeapp.data.CastMember
import com.streamdek.tv.nativeapp.data.MediaDetail
import com.streamdek.tv.nativeapp.data.MediaItem
import com.streamdek.tv.nativeapp.data.SeasonDetail
import com.streamdek.tv.nativeapp.data.SeasonEpisode
import com.streamdek.tv.nativeapp.data.SeasonRef
import com.streamdek.tv.nativeapp.data.TraktCommentItem
import com.streamdek.tv.nativeapp.ui.AppCardShape
import com.streamdek.tv.nativeapp.ui.AppPillShape
import com.streamdek.tv.nativeapp.ui.LocalTvExperienceSettings
import com.streamdek.tv.nativeapp.ui.PremiumMediaCard
import com.streamdek.tv.nativeapp.ui.ProgressMeter
import com.streamdek.tv.nativeapp.ui.TvMediaCardVariant
import com.streamdek.tv.nativeapp.ui.TvMotion
import com.streamdek.tv.nativeapp.ui.TvSkeletonBox
import com.streamdek.tv.nativeapp.ui.TvSpacing

/** Shared page inset. Matches the rest of the app rather than the bespoke value this replaced. */
internal val DetailInset = TvSpacing.ScreenHorizontal

/**
 * One focusable surface used by every card on this screen, so focus reads identically everywhere:
 * a ring in the accent colour and a restrained lift. The old screen gave each section its own
 * treatment, which made focus position harder to track at a glance across a room.
 */
@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun DetailFocusCard(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = AppCardShape,
    description: String? = null,
    onFocused: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    val highContrast = LocalTvExperienceSettings.current.highContrast
    Card(
        onClick = onClick,
        modifier = modifier
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier)
            .onFocusChanged { if (it.isFocused) onFocused() },
        shape = CardDefaults.shape(shape),
        colors = CardDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
            focusedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
            pressedContainerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.82f),
        ),
        border = CardDefaults.border(
            border = Border(BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)), shape = shape),
            focusedBorder = Border(
                BorderStroke(if (highContrast) 3.dp else 2.dp, MaterialTheme.colorScheme.primary),
                shape = shape,
            ),
        ),
        glow = CardDefaults.glow(Glow.None, Glow.None, Glow.None),
        scale = CardDefaults.scale(focusedScale = TvMotion.focusScale()),
        content = { content() },
    )
}

/** Section header shared by every band below the hero. */
@Composable
internal fun DetailSectionHeader(title: String, trailing: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = DetailInset),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Black),
            color = MaterialTheme.colorScheme.onBackground,
        )
        trailing?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.62f),
            )
        }
    }
}

/** Small capsule used for year, runtime, rating and genre. */
@Composable
internal fun MetaChip(label: String, emphasised: Boolean = false) {
    Box(
        modifier = Modifier
            .clip(AppPillShape)
            .background(
                if (emphasised) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
                else Color.White.copy(alpha = 0.09f),
            )
            .padding(horizontal = 11.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
            color = if (emphasised) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.86f),
        )
    }
}

/**
 * Poster beside the copy. The screen this replaced showed only a backdrop, which left nothing for
 * the eye to anchor on and made every title look alike; the poster is the artwork viewers actually
 * recognise. Purely decorative, so it is not focusable and never costs a D-pad press.
 */
@Composable
internal fun HeroPoster(detail: MediaDetail, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .width(188.dp)
            .height(282.dp)
            .clip(AppCardShape)
            .background(MaterialTheme.colorScheme.surface),
    ) {
        val art = detail.poster ?: detail.backdrop
        if (!art.isNullOrBlank()) {
            AsyncImage(
                model = art,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        }
    }
}

/** Episode still with its number, name and watched tick. */
@Composable
internal fun EpisodeCard(
    compact: Boolean = false,
    episode: SeasonEpisode,
    seasonNumber: Int,
    watched: Boolean,
    released: Boolean,
    modifier: Modifier = Modifier,
    onFocused: () -> Unit,
    onClick: () -> Unit,
) {
    DetailFocusCard(
        onClick = { if (released) onClick() },
        modifier = modifier.width(if (compact) 200.dp else 268.dp),
        onFocused = onFocused,
        description = buildString {
            append("Season $seasonNumber episode ${episode.episodeNumber}, ${episode.name}")
            if (watched) append(", watched")
            if (!released) append(", not released yet")
        },
    ) {
        Column {
            Box(Modifier.fillMaxWidth().height(if (compact) 112.dp else 150.dp).background(Color.Black.copy(alpha = 0.5f))) {
                if (!episode.still.isNullOrBlank()) {
                    AsyncImage(
                        model = episode.still,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop,
                    )
                }
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            colorStops = arrayOf(0f to Color.Transparent, 1f to Color(0xCC000000)),
                        ),
                    ),
                )
                Row(
                    modifier = Modifier.align(Alignment.TopStart).padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    MetaChip("E${episode.episodeNumber}")
                    if (watched) MetaChip("WATCHED", emphasised = true)
                    if (!released) MetaChip("SOON")
                }
                episode.runtime?.takeIf { it > 0 }?.let {
                    Box(Modifier.align(Alignment.BottomEnd).padding(8.dp)) { MetaChip(formatRuntime(it)) }
                }
            }
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = if (compact) 7.dp else 10.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(
                    text = episode.name,
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!compact) Text(
                    text = episode.overview?.takeIf { it.isNotBlank() } ?: "No synopsis available.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.66f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Season selector. Chips rather than a dropdown: one press per season, no menu to open. */
@Composable
internal fun SeasonChipRow(
    seasons: List<SeasonRef>,
    selected: Int,
    firstChipRequester: FocusRequester?,
    onSelect: (Int) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = DetailInset),
    ) {
        itemsIndexed(seasons, key = { _, season -> season.seasonNumber }) { index, season ->
            val active = season.seasonNumber == selected
            DetailFocusCard(
                onClick = { onSelect(season.seasonNumber) },
                shape = AppPillShape,
                modifier = Modifier.then(
                    if (index == 0 && firstChipRequester != null) Modifier.focusRequester(firstChipRequester) else Modifier,
                ),
                description = season.name,
            ) {
                Text(
                    text = season.name,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 9.dp),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
                    color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
internal fun EpisodesBand(
    compact: Boolean,
    onFocusChanged: (Boolean) -> Unit,
    seasons: List<SeasonRef>,
    selectedSeasonNumber: Int,
    seasonDetail: SeasonDetail?,
    watchedEpisodes: Set<Int>,
    firstChipRequester: FocusRequester?,
    onSelectSeason: (Int) -> Unit,
    onEpisodeFocused: (Int) -> Unit,
    onEpisodePressed: (SeasonEpisode) -> Unit,
) {
    Column(
        modifier = Modifier.onFocusChanged { onFocusChanged(it.hasFocus) },
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp),
    ) {
        DetailSectionHeader(
            title = "Episodes",
            trailing = seasonDetail?.episodes?.size?.takeIf { it > 0 }?.let { "$it episodes" },
        )
        if (seasons.size > 1) {
            SeasonChipRow(seasons, selectedSeasonNumber, firstChipRequester, onSelectSeason)
        }
        val episodes = seasonDetail?.episodes.orEmpty()
        if (episodes.isEmpty()) {
            // The season list arrives before its episodes do; a skeleton here stops the band
            // collapsing to nothing and then shoving the sections below it back down.
            Row(
                horizontalArrangement = Arrangement.spacedBy(TvSpacing.Card),
                modifier = Modifier.padding(horizontal = DetailInset),
            ) {
                repeat(4) { TvSkeletonBox(Modifier.width(if (compact) 200.dp else 268.dp).height(if (compact) 128.dp else 232.dp)) }
            }
        } else {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(TvSpacing.Card),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = DetailInset),
            ) {
                itemsIndexed(episodes, key = { _, episode -> episode.id }) { index, episode ->
                    EpisodeCard(
                        compact = compact,
                        episode = episode,
                        seasonNumber = selectedSeasonNumber,
                        watched = watchedEpisodes.contains(episode.episodeNumber),
                        released = isEpisodeReleased(episode.airDate),
                        onFocused = { onEpisodeFocused(index) },
                        onClick = { onEpisodePressed(episode) },
                    )
                }
            }
        }
    }
}

@Composable
internal fun SimilarBand(
    items: List<MediaItem>,
    compact: Boolean = false,
    onFocusChanged: (Boolean) -> Unit = {},
    onOpen: (MediaItem) -> Unit,
) {
    Column(
        modifier = Modifier.onFocusChanged { onFocusChanged(it.hasFocus) },
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        DetailSectionHeader("More Like This")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.Card),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = DetailInset),
        ) {
            items(items, key = { "${it.type}:${it.id}" }) { item ->
                // The same card Home and Library use, so a title looks identical wherever it turns
                // up. The old screen had a bespoke card here for no reason.
                PremiumMediaCard(
                    item = item,
                    variant = TvMediaCardVariant.Poster,
                    modifier = Modifier.width(if (compact) 84.dp else 106.dp).height(if (compact) 126.dp else 159.dp),
                    onClick = { onOpen(item) },
                )
            }
        }
    }
}

@Composable
internal fun CastBand(cast: List<CastMember>, compact: Boolean = false, onFocusChanged: (Boolean) -> Unit = {}) {
    // Cast shrinks once the viewer has moved past it to the recommendations. Nobody is reading
    // character names at that point, and the space buys the row below enough height to sit fully
    // on screen instead of being clipped by the fold.
    val photoSize = if (compact) 56.dp else 96.dp
    val columnWidth = if (compact) 72.dp else 116.dp
    Column(
        modifier = Modifier.onFocusChanged { onFocusChanged(it.hasFocus) },
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp),
    ) {
        DetailSectionHeader("Cast")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.Card),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = DetailInset),
        ) {
            items(cast, key = { it.id }) { member ->
                Column(
                    modifier = Modifier.width(columnWidth),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(if (compact) 5.dp else 8.dp),
                ) {
                    Box(
                        Modifier.size(photoSize).clip(CircleShape).background(MaterialTheme.colorScheme.surface),
                    ) {
                        if (!member.photo.isNullOrBlank()) {
                            AsyncImage(
                                model = member.photo,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                    Text(
                        text = member.name,
                        style = (if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelLarge)
                            .copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!compact) member.character?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            text = it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.58f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun CommentsBand(comments: List<TraktCommentItem>, compact: Boolean = false, onFocusChanged: (Boolean) -> Unit = {}) {
    Column(
        modifier = Modifier.onFocusChanged { onFocusChanged(it.hasFocus) },
        verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 14.dp),
    ) {
        DetailSectionHeader("Reviews", trailing = "${comments.size}")
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.Card),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = DetailInset),
        ) {
            items(comments, key = { it.id }) { comment ->
                Column(
                    modifier = Modifier
                        .width(if (compact) 260.dp else 340.dp)
                        .clip(AppCardShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = comment.author,
                            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black),
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        comment.userRating?.let { MetaChip("★ $it", emphasised = true) }
                    }
                    Text(
                        // Spoilers stay collapsed: there is no hover on a remote, so a reveal
                        // control would be one more thing to focus past on every review.
                        text = if (comment.spoiler) "Hidden — this review is marked as a spoiler." else comment.comment,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(
                            alpha = if (comment.spoiler) 0.48f else 0.76f,
                        ),
                        maxLines = if (compact) 2 else 5,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Loading state shaped like the finished screen, so nothing jumps when the data lands. */
@Composable
internal fun DetailSkeleton() {
    Column(
        modifier = Modifier.fillMaxSize().padding(top = 96.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = DetailInset),
            horizontalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            TvSkeletonBox(Modifier.width(188.dp).height(282.dp))
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                TvSkeletonBox(Modifier.width(420.dp).height(44.dp))
                TvSkeletonBox(Modifier.width(300.dp).height(20.dp))
                TvSkeletonBox(Modifier.width(560.dp).height(16.dp))
                TvSkeletonBox(Modifier.width(520.dp).height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TvSkeletonBox(Modifier.width(168.dp).height(48.dp), AppPillShape)
                    repeat(3) { TvSkeletonBox(Modifier.size(48.dp), AppPillShape) }
                }
            }
        }
        Row(
            modifier = Modifier.padding(horizontal = DetailInset),
            horizontalArrangement = Arrangement.spacedBy(TvSpacing.Card),
        ) {
            repeat(5) { TvSkeletonBox(Modifier.width(136.dp).height(204.dp)) }
        }
    }
}

/** Progress under the play button, with the position read out beside it. */
@Composable
internal fun ResumeProgress(progressFraction: Float, label: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        ProgressMeter(
            progress = (progressFraction * 100.0),
            modifier = Modifier.width(280.dp).height(4.dp),
        )
        label?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.66f),
            )
        }
    }
}
