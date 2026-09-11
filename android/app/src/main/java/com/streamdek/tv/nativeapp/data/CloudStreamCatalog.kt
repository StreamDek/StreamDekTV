package com.streamdek.tv.nativeapp.data

import android.util.Log
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.getImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.getTMDbId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MovieSearchResponse
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvSeriesLoadResponse
import com.lagradost.cloudstream3.TvSeriesSearchResponse
import com.lagradost.cloudstream3.TvType
import com.streamdek.tv.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * A CloudStream provider's own catalogue: its main page, its titles, and what they are.
 *
 * CloudStream providers are full catalogues as well as scrapers. Each can declare a main page — a
 * list of named rows ("Trending", a studio, a live-events list) — which is what the CloudStream app
 * puts on its Home screen for that provider. StreamDek offers those rows as Home rows, and a title
 * opened from one is described, and played, through the provider that listed it. The same shape the
 * phone uses, so a row switched on there means the same row here.
 */
object CloudStreamCatalog {
  private const val TAG = "CloudStreamCatalog"
  private const val MAIN_PAGE_TIMEOUT_MS = 20_000L

  /** How many of a CloudStream row's titles Home shows; a main page can return hundreds. */
  const val ROW_MAX_ITEMS = 40

  /** One row a provider's main page offers, as the provider declares it. */
  data class MainPageRow(val provider: MainAPI, val index: Int, val page: MainPageData)

  fun mainPageRows(provider: MainAPI): List<MainPageRow> = runCatching {
    if (!provider.hasMainPage) emptyList() else provider.mainPage.mapIndexed { index, page -> MainPageRow(provider, index, page) }
  }.onFailure { Log.w(TAG, "${provider.name} main page could not be listed", it) }.getOrDefault(emptyList())

  /**
   * The titles one main-page row holds, as StreamDek cards.
   *
   * On the IO dispatcher whatever the caller's: Home collects on the main thread, and some providers
   * make blocking network calls inside `getMainPage` — CNC Verse's cookie bypass does — which Android
   * refuses on the main thread, so those rows came back empty and were dropped.
   */
  suspend fun mainPageItems(provider: MainAPI, page: MainPageData): List<MediaItem> = withContext(Dispatchers.IO) {
    val response = withTimeout(MAIN_PAGE_TIMEOUT_MS) {
      provider.getMainPage(1, MainPageRequest(page.name, page.data, page.horizontalImages))
    } ?: return@withContext emptyList()
    // A request normally answers with one list named after it. A provider that answers with its
    // whole page at once still has its named list picked out when there is one.
    val lists = response.items
    val chosen = lists.firstOrNull { it.name.equals(page.name, ignoreCase = true) }?.let(::listOf) ?: lists
    chosen.flatMap { it.list }.distinctBy { it.url }.map { toMediaItem(provider, it) }
  }

  fun toMediaItem(provider: MainAPI, result: SearchResponse): MediaItem {
    val year = when (result) {
      is MovieSearchResponse -> result.year
      is TvSeriesSearchResponse -> result.year
      is AnimeSearchResponse -> result.year
      else -> null
    }
    return MediaItem(
      id = cloudStreamMediaId(provider.name, result.url),
      title = result.name,
      type = streamDekType(result.type),
      poster = result.posterUrl,
      year = year?.toString(),
    )
  }

  /**
   * Movie or series, in StreamDek's terms. Anything that plays as one item — a film, a live event,
   * a video — is a "movie", so its page offers sources straight away instead of asking for an
   * episode it does not have.
   */
  private fun streamDekType(type: TvType?): String = when (type) {
    TvType.TvSeries, TvType.Cartoon, TvType.Anime, TvType.OVA, TvType.AsianDrama, TvType.Podcast, TvType.AudioBook -> "tv"
    else -> "movie"
  }

  /** A loaded title as StreamDek's detail page shows it, seasons included. */
  fun toMediaDetail(id: String, detail: LoadResponse, seasonLabel: (Int) -> String): MediaDetail {
    val episodes = seriesEpisodes(detail)
    val seasons = episodes.groupBy { it.season ?: 1 }.toSortedMap().map { (season, seasonEpisodes) ->
      SeasonRef(seasonNumber = season, name = seasonLabel(season), episodeCount = seasonEpisodes.size)
    }
    return MediaDetail(
      id = id,
      title = detail.name,
      type = if (episodes.isNotEmpty()) "tv" else "movie",
      poster = detail.posterUrl,
      backdrop = detail.backgroundPosterUrl ?: detail.posterUrl,
      description = detail.plot?.takeIf { it.isNotBlank() },
      year = detail.year?.toString(),
      imdbId = imdbId(detail),
      genreNames = detail.tags.orEmpty(),
      seasons = seasons,
      numberOfSeasons = seasons.size.takeIf { it > 0 },
      numberOfEpisodes = episodes.size.takeIf { it > 0 },
    )
  }

  /** One season of a loaded title, from the provider's own episode list. */
  fun seasonDetail(
    detail: LoadResponse,
    seasonNumber: Int,
    seasonLabel: (Int) -> String,
    episodeLabel: (Int) -> String,
  ): SeasonDetail {
    val all = seriesEpisodes(detail)
    val inSeason = all.withIndex().filter { (it.value.season ?: 1) == seasonNumber }
    return SeasonDetail(
      seasonNumber = seasonNumber,
      name = seasonLabel(seasonNumber),
      episodes = inSeason.mapIndexed { position, (index, episode) ->
        val number = episode.episode ?: (position + 1)
        SeasonEpisode(
          id = index + 1,
          episodeNumber = number,
          name = episode.name?.takeIf { it.isNotBlank() } ?: episodeLabel(number),
          overview = episode.description?.takeIf { it.isNotBlank() },
          still = episode.posterUrl,
          runtime = episode.runTime,
          airDate = episode.date?.let { java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(it)) },
        )
      },
    )
  }

  /** The TMDB id a provider recorded for a title, when it recorded one. */
  fun tmdbId(detail: LoadResponse): String? = runCatching { detail.getTMDbId() }.getOrNull()?.takeIf { it.isNotBlank() }

  fun imdbId(detail: LoadResponse): String? =
    runCatching { detail.getImdbId() }.getOrNull()?.takeIf { it.startsWith("tt", ignoreCase = true) }

  private fun seriesEpisodes(detail: LoadResponse): List<Episode> = when (detail) {
    is TvSeriesLoadResponse -> detail.episodes
    is AnimeLoadResponse -> detail.episodes[DubStatus.Subbed]?.takeIf { it.isNotEmpty() }
      ?: detail.episodes[DubStatus.Dubbed]?.takeIf { it.isNotEmpty() }
      ?: detail.episodes.values.firstOrNull { it.isNotEmpty() }.orEmpty()
    else -> emptyList()
  }
}

// --- Media ids --------------------------------------------------------------------------------

private const val CLOUDSTREAM_MEDIA_ID_PREFIX = "cs:"
private const val CLOUDSTREAM_MEDIA_ID_FLAGS = android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING

/**
 * The media id of a title from a CloudStream provider's own catalogue.
 *
 * Such a title has no catalogue id of its own — the provider and the link it gave are all that
 * identify it — so those two are the id, encoded so it is safe wherever media ids travel: keys,
 * saved preferences, navigation routes. The same encoding as the phone's.
 */
internal fun cloudStreamMediaId(providerName: String, url: String): String =
  CLOUDSTREAM_MEDIA_ID_PREFIX + android.util.Base64.encodeToString("$providerName\n$url".toByteArray(Charsets.UTF_8), CLOUDSTREAM_MEDIA_ID_FLAGS)

internal fun isCloudStreamMediaId(id: String): Boolean = id.startsWith(CLOUDSTREAM_MEDIA_ID_PREFIX)

/** The provider name and link a [cloudStreamMediaId] was made from, or null for any other id. */
internal fun decodeCloudStreamMediaId(id: String): Pair<String, String>? {
  if (!isCloudStreamMediaId(id)) return null
  val raw = runCatching {
    String(android.util.Base64.decode(id.removePrefix(CLOUDSTREAM_MEDIA_ID_PREFIX), CLOUDSTREAM_MEDIA_ID_FLAGS), Charsets.UTF_8)
  }.getOrNull() ?: return null
  val separator = raw.indexOf('\n')
  if (separator <= 0 || separator == raw.lastIndex) return null
  return raw.substring(0, separator) to raw.substring(separator + 1)
}

// --- Home rows --------------------------------------------------------------------------------

internal const val CLOUDSTREAM_ROW_SOURCE_PREFIX = "cloudstream."

private fun cloudStreamRowSlug(value: String, fallback: String): String =
  value.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { fallback }

/** The row-id source segment for a provider. Colon-free, since row ids are colon-separated. */
internal fun cloudStreamRowSourceId(providerName: String): String =
  CLOUDSTREAM_ROW_SOURCE_PREFIX + cloudStreamRowSlug(providerName, "provider")

/**
 * A CloudStream Home row's id, in the add-on row shape — `addon:cloudstream.<provider>:<type>:<row>:<index>`
 * — so the synced layout, its matching and its ordering treat it like any add-on catalogue. The
 * spelling is the phone's, so a row arranged on either device is the same row on the other.
 */
private fun cloudStreamHomeRowId(provider: MainAPI, index: Int, name: String): String {
  val types = runCatching { provider.supportedTypes }.getOrDefault(emptySet())
  val movieTypes = setOf(TvType.Movie, TvType.AnimeMovie, TvType.Documentary)
  val type = when {
    types.isNotEmpty() && types.all { it == TvType.Live } -> "live"
    types.isNotEmpty() && types.all { it in movieTypes } -> "movie"
    else -> "series"
  }
  return "addon:${cloudStreamRowSourceId(provider.name)}:$type:${cloudStreamRowSlug(name, "row")}:$index"
}

internal fun isCloudStreamHomeRowId(id: String): Boolean =
  homeCatalogRowAddonId(id)?.startsWith(CLOUDSTREAM_ROW_SOURCE_PREFIX) == true

/**
 * The rows every loaded provider offers, for the Home rows list. Only loaded providers offer any: a
 * source that is switched off, or whose collection is, has nothing to put on Home, so its rows are
 * not listed — though the layout keeps them, and turning it back on brings them back as they were.
 */
internal fun cloudStreamHomeRowOptions(providers: List<MainAPI>): List<HomeRowOption> =
  providers.distinctBy { it.name }.flatMap { provider ->
    CloudStreamCatalog.mainPageRows(provider).map { row ->
      HomeRowOption(
        id = cloudStreamHomeRowId(provider, row.index, row.page.name),
        title = row.page.name.ifBlank { provider.name },
        subtitleRes = R.string.home_row_from_addon,
        subtitleArg = provider.name,
        builtin = false,
        enabled = false,
      )
    }
  }

/** The provider and main-page entry a CloudStream row id names, among the providers loaded now. */
internal fun resolveCloudStreamHomeRow(id: String, providers: List<MainAPI>): CloudStreamCatalog.MainPageRow? {
  val parts = id.split(":")
  if (parts.size < 5) return null
  val provider = providers.firstOrNull { cloudStreamRowSourceId(it.name) == parts[1] } ?: return null
  val rows = CloudStreamCatalog.mainPageRows(provider)
  // By name first, so a provider that reorders its rows still fills the one the viewer chose.
  return rows.firstOrNull { cloudStreamRowSlug(it.page.name, "row") == parts[3] } ?: rows.getOrNull(parts[4].toIntOrNull() ?: -1)
}

/** The CloudStream sources ready to answer right now, or none. */
internal fun loadedCloudStreamProviders(): List<MainAPI> =
  if (!CloudStreamPlugins.isInitialized) emptyList()
  else runCatching { CloudStreamPlugins.manager.activeProviders() }.getOrDefault(emptyList())

/**
 * Which group each loaded provider's rows go in: the plugin that registered it, as a group key and
 * title. One plugin can register several providers — CNC Verse registers Netflix, Prime Video and
 * more — and their rows belong together, the way an add-on's catalogues sit under the add-on.
 */
internal fun cloudStreamRowGroups(): Map<String, Pair<String, String>> =
  if (!CloudStreamPlugins.isInitialized) emptyMap() else runCatching {
    CloudStreamPluginLoader.loadedPlugins().flatMap { plugin ->
      plugin.providers.map { provider -> cloudStreamRowSourceId(provider.name) to ("cloudstream-plugin:${plugin.filePath}" to plugin.name) }
    }.toMap()
  }.getOrDefault(emptyMap())

/** Where each plugin group comes from ("CloudStream · CNC Repo"), keyed as [cloudStreamRowGroups] keys them. */
internal fun cloudStreamGroupLabels(): Map<String, String> =
  if (!CloudStreamPlugins.isInitialized) emptyMap() else runCatching {
    CloudStreamPluginLoader.loadedPlugins().mapNotNull { plugin ->
      plugin.providers.firstOrNull()
        ?.let { provider -> cloudStreamProviderOriginLabel(provider.name) }
        ?.let { label -> "cloudstream-plugin:${plugin.filePath}" to label }
    }.toMap()
  }.getOrDefault(emptyMap())

// --- Where a source came from -----------------------------------------------------------------

private const val CLOUDSTREAM_ORIGIN = "CloudStream"

/**
 * "CloudStream · <collection>" for a loaded CloudStream provider, by name.
 *
 * The provider's name need not match the name its plugin is listed under — a plugin registers its
 * sources under names of their own — so the loaded file is what leads back to the collection, with
 * the name match kept as the fallback. "CloudStream" alone when neither finds it.
 */
internal fun cloudStreamProviderOriginLabel(providerName: String): String {
  if (!CloudStreamPlugins.isInitialized) return CLOUDSTREAM_ORIGIN
  val state = runCatching { CloudStreamPlugins.manager.state }.getOrNull() ?: return CLOUDSTREAM_ORIGIN
  val file = runCatching { CloudStreamPluginLoader.providerFiles()[providerName] }.getOrNull()
  val entry = file?.let { path -> state.providers.firstOrNull { it.installedFilePath == path } }
    ?: state.providers.firstOrNull { it.name == providerName }
  val repoUrl = entry?.repoUrl.orEmpty()
  val collection = state.repos.firstOrNull { it.url == repoUrl }?.name?.takeIf { it.isNotBlank() }
    ?: repoUrl.trim().takeIf { it.isNotEmpty() }?.let { url ->
      runCatching { java.net.URI(url).host }.getOrNull()?.removePrefix("www.")
        ?: url.substringAfter("//").substringBefore('/').takeIf { it.isNotEmpty() }
    }
  return listOfNotNull(CLOUDSTREAM_ORIGIN, collection).joinToString(" · ")
}
