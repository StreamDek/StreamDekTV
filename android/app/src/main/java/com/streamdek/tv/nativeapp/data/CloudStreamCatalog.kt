package com.streamdek.tv.nativeapp.data

import android.util.Log
import com.lagradost.cloudstream3.AnimeLoadResponse
import com.lagradost.cloudstream3.AnimeSearchResponse
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.LiveStreamLoadResponse
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
    chosen.flatMap { it.list }.distinctBy { it.url }.map { toMediaItem(provider, it, rowName = page.name) }
  }

  /**
   * A provider's title as a StreamDek card.
   *
   * A channel is typed "live", so Home opens it in the live player rather than on a detail page,
   * favourites accept it, and the player's channel row is the rest of [rowName].
   */
  fun toMediaItem(provider: MainAPI, result: SearchResponse, rowName: String? = null): MediaItem {
    val year = when (result) {
      is MovieSearchResponse -> result.year
      is TvSeriesSearchResponse -> result.year
      is AnimeSearchResponse -> result.year
      else -> null
    }
    val live = result.type == TvType.Live || isLiveSource(provider.name)
    return MediaItem(
      id = cloudStreamMediaId(provider.name, result.url),
      title = result.name,
      type = if (live) "live" else streamDekType(result.type),
      poster = result.posterUrl,
      year = year?.toString(),
      sourceAddonId = if (live) cloudStreamAddonId(provider) else null,
      sourceAddonName = if (live) provider.name else null,
      sourceCatalogId = if (live) rowName?.takeIf { it.isNotBlank() } else null,
      sourceCatalogName = if (live) rowName?.takeIf { it.isNotBlank() } else null,
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

  /**
   * Whether a title plays as a live channel: the provider described it as live, or - when there is
   * no description to go on - the provider serves nothing but live channels.
   */
  fun isLive(provider: MainAPI, detail: LoadResponse?): Boolean {
    if (detail != null && (detail is LiveStreamLoadResponse || detail.type == TvType.Live)) return true
    return isLiveSource(provider.name) && (detail == null || seriesEpisodes(detail).isEmpty())
  }

  /**
   * Whether a provider serves live channels, by its own declaration or its plugin's.
   *
   * A provider's own types are not always enough. CNCVerse's Sportzx lists its channel groups -
   * Music among them - through a provider that calls every entry a movie while declaring films,
   * series and live together; the plugin as published in its repository says Live and nothing else.
   * Either declaration counts.
   */
  fun isLiveSource(providerName: String): Boolean = liveSources().first.contains(providerName)

  /** Row-id sources ([cloudStreamRowSourceId]) of the providers [isLiveSource] answers true for. */
  fun liveRowSources(): Set<String> = liveSources().second

  private class LiveSourceCache(val state: CsPluginState, val generation: Int, val names: Set<String>, val rowSources: Set<String>)
  @Volatile private var liveSourceCache: LiveSourceCache? = null

  private fun liveSources(): Pair<Set<String>, Set<String>> {
    val cloudStream = cloudStreamLiveSources()
    // SkyStream sources declare their types in the plugin manifest, which is all there is to go on.
    val skyStream = if (!SkyStreamPlugins.isInitialized) emptySet() else SkyStreamPlugins.manager.lastProviders
      .filter { provider -> provider.supportedTypes.let { types -> types.isNotEmpty() && types.all { it == TvType.Live } } }
      .mapTo(hashSetOf()) { it.name }
    if (skyStream.isEmpty()) return cloudStream
    return (cloudStream.first + skyStream) to (cloudStream.second + skyStream.map(::cloudStreamRowSourceId))
  }

  private fun cloudStreamLiveSources(): Pair<Set<String>, Set<String>> {
    if (!CloudStreamPlugins.isInitialized) return emptySet<String>() to emptySet()
    val state = CloudStreamPlugins.manager.state
    val generation = CloudStreamPluginLoader.generation
    // By identity: the manager replaces its state on every change, and this is asked per card.
    liveSourceCache?.takeIf { it.state === state && it.generation == generation }?.let { return it.names to it.rowSources }
    val liveOnlyPlugins = state.providers
      .filter { entry -> entry.tvTypes.isNotEmpty() && entry.tvTypes.all { it.equals(TvType.Live.name, ignoreCase = true) } }
      .mapNotNullTo(hashSetOf()) { it.installedFilePath }
    val names = CloudStreamPluginLoader.loadedPlugins().flatMap { plugin ->
      plugin.providers.filter { provider ->
        plugin.filePath in liveOnlyPlugins ||
          runCatching { provider.supportedTypes }.getOrDefault(emptySet()).let { types -> types.isNotEmpty() && types.all { it == TvType.Live } }
      }.map { it.name }
    }.toSet()
    val rowSources = names.mapTo(hashSetOf(), ::cloudStreamRowSourceId)
    liveSourceCache = LiveSourceCache(state, generation, names, rowSources)
    return names to rowSources
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

/**
 * The provider named at the start of a CloudStream id the account cut short (see
 * [ACCOUNT_FAVOURITE_ID_LIMIT]). The whole id no longer decodes, but the provider comes first in it,
 * so the part that still does is enough to give the favourite back its source.
 */
internal fun cloudStreamProviderNameFromCutId(id: String): String? {
  if (!isCloudStreamMediaId(id) || id.length != ACCOUNT_FAVOURITE_ID_LIMIT) return null
  val encoded = id.removePrefix(CLOUDSTREAM_MEDIA_ID_PREFIX).let { it.take(it.length / 4 * 4) }
  val raw = runCatching { String(android.util.Base64.decode(encoded, CLOUDSTREAM_MEDIA_ID_FLAGS), Charsets.UTF_8) }.getOrNull() ?: return null
  return raw.substringBefore('\n', missingDelimiterValue = "").takeIf { it.isNotBlank() }
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
 * A CloudStream row of channels: its id says so, or its provider or plugin declares itself live even
 * though the id - fixed when the row was first offered - does not.
 */
internal fun isLiveCloudStreamHomeRowId(id: String): Boolean =
  isCloudStreamHomeRowId(id) &&
    (id.split(":").getOrNull(2) == "live" || homeCatalogRowAddonId(id) in CloudStreamCatalog.liveRowSources())

/** The provider name in a live CloudStream card's source id ([cloudStreamAddonId]), or null. */
internal fun cloudStreamProviderNameFromAddonId(addonId: String?): String? =
  addonId?.takeIf { it.startsWith("cloudstream:") }?.removePrefix("cloudstream:")?.takeIf { it.isNotBlank() }

/**
 * A favourite channel with the source a CloudStream card here carries.
 *
 * The phone saves a CloudStream channel without one - it routes these by media id alone - while
 * favourites here are matched on source and id together. Filled in, a channel starred on the phone
 * shows as starred here and starring it again cannot add a second copy.
 */
internal fun withCloudStreamChannelSource(item: MediaItem): MediaItem {
  if (!item.sourceAddonId.isNullOrBlank()) return item
  val providerName = (decodeCloudStreamMediaId(item.id)?.first ?: cloudStreamProviderNameFromCutId(item.id)) ?: return item
  return item.copy(sourceAddonId = "cloudstream:$providerName", sourceAddonName = item.sourceAddonName ?: providerName)
}

/**
 * Whether a saved card is really a CloudStream channel, which has nowhere to carry on from. Entries
 * written before a plugin's channels were recognised as live were saved as films.
 */
internal fun isCloudStreamLiveChannelId(id: String): Boolean =
  decodeCloudStreamMediaId(id)?.let { (provider, _) -> CloudStreamCatalog.isLiveSource(provider) } == true

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

/**
 * The sources ready to answer right now, or none: loaded `.cs3` providers, then switched-on
 * SkyStream sources, which run as CloudStream providers (see [SkyStreamMainApi]) so Home rows, Fuse,
 * title pages and stream lookups take them up with nothing written twice.
 */
internal fun loadedCloudStreamProviders(): List<MainAPI> {
  val cloudStream = if (!CloudStreamPlugins.isInitialized) emptyList()
    else runCatching { CloudStreamPlugins.manager.activeProviders() }.getOrDefault(emptyList())
  return cloudStream + skyStreamProviders(cloudStream)
}

/** Switched-on SkyStream sources as CloudStream providers, named clear of [cloudStream]'s. */
internal fun skyStreamProviders(
  cloudStream: List<MainAPI> = if (!CloudStreamPlugins.isInitialized) emptyList()
    else runCatching { CloudStreamPlugins.manager.activeProviders() }.getOrDefault(emptyList()),
): List<MainAPI> =
  if (!SkyStreamPlugins.isInitialized) emptyList()
  else runCatching { SkyStreamPlugins.manager.mainApis(cloudStream.mapTo(hashSetOf()) { it.name }) }
    .onFailure { TvDebugLogger.w("SkyStream", "sources could not be listed", it) }
    .getOrDefault(emptyList())

private fun skyStreamRowGroupKey(source: SkySource): String = "skystream-plugin:${source.repoUrl}|${source.packageName}"

/**
 * Which group each loaded provider's rows go in: the plugin that registered it, as a group key and
 * title. One plugin can register several providers — CNC Verse registers Netflix, Prime Video and
 * more — and their rows belong together, the way an add-on's catalogues sit under the add-on.
 */
internal fun cloudStreamRowGroups(): Map<String, Pair<String, String>> {
  val cloudStream = if (!CloudStreamPlugins.isInitialized) emptyMap() else runCatching {
    CloudStreamPluginLoader.loadedPlugins().flatMap { plugin ->
      plugin.providers.map { provider -> cloudStreamRowSourceId(provider.name) to ("cloudstream-plugin:${plugin.filePath}" to plugin.name) }
    }.toMap()
  }.getOrDefault(emptyMap())
  // A SkyStream plugin that splits into sub-providers groups them the same way.
  val skyStream = skyStreamProviders().filterIsInstance<SkyStreamMainApi>().associate { provider ->
    cloudStreamRowSourceId(provider.name) to (skyStreamRowGroupKey(provider.source) to provider.source.pluginName)
  }
  return cloudStream + skyStream
}

/** Where each plugin group comes from ("CloudStream · CNC Repo"), keyed as [cloudStreamRowGroups] keys them. */
internal fun cloudStreamGroupLabels(): Map<String, String> {
  val cloudStream = if (!CloudStreamPlugins.isInitialized) emptyMap() else runCatching {
    CloudStreamPluginLoader.loadedPlugins().mapNotNull { plugin ->
      plugin.providers.firstOrNull()
        ?.let { provider -> cloudStreamProviderOriginLabel(provider.name) }
        ?.let { label -> "cloudstream-plugin:${plugin.filePath}" to label }
    }.toMap()
  }.getOrDefault(emptyMap())
  val skyStream = skyStreamProviders().filterIsInstance<SkyStreamMainApi>()
    .associate { provider -> skyStreamRowGroupKey(provider.source) to cloudStreamProviderOriginLabel(provider.name) }
  return cloudStream + skyStream
}

// --- Where a source came from -----------------------------------------------------------------

private const val CLOUDSTREAM_ORIGIN = "CloudStream"
private const val SKYSTREAM_ORIGIN = "SkyStream"

/**
 * "CloudStream · <collection>" for a loaded CloudStream provider, by name.
 *
 * The provider's name need not match the name its plugin is listed under — a plugin registers its
 * sources under names of their own — so the loaded file is what leads back to the collection, with
 * the name match kept as the fallback. "CloudStream" alone when neither finds it.
 */
internal fun cloudStreamProviderOriginLabel(providerName: String): String {
  // A SkyStream source runs as a CloudStream provider, so its name arrives here too.
  if (SkyStreamPlugins.isInitialized) {
    SkyStreamPlugins.manager.collectionNamesByProvider()[providerName]?.let { collection ->
      val shown = collection.takeUnless { it.startsWith("http", true) }
        ?: runCatching { java.net.URI(collection).host }.getOrNull()?.removePrefix("www.")
      return listOfNotNull(SKYSTREAM_ORIGIN, shown).joinToString(" · ")
    }
  }
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
