package com.streamdek.tv.nativeapp.ui.home

import androidx.compose.ui.graphics.Color
import com.streamdek.tv.R
import com.streamdek.tv.nativeapp.data.MediaItem

/**
 * Bundled branded artwork for the Streaming Networks row.
 *
 * The row's classic card is the service's TMDB logo fitted onto a white tile — correct, but it
 * reduces every service to the same white rectangle. These tiles are the service's own wordmark on
 * its own colour, so the row reads as a shelf of brands rather than a grid of stamps. They ship
 * with the app rather than coming down with the row because they are branding, not content: they
 * do not change between deploys, and a tile that has to be fetched is a tile that is blank on a
 * cold start.
 *
 * The classic card carries bundled artwork too — see [networkLogoArt]. Its logo used to be the
 * one `/tmdb/networks` supplies, which is a thumbnail sized for a provider list and visibly soft
 * on a card this size.
 *
 * Only the artwork on hand is mapped. A service with neither a tile nor a logo falls back to the
 * one the row supplied (see `NetworkCard`), so these tables growing or shrinking can never
 * empty a card.
 */

/**
 * Watch-provider ids, which are what `/tmdb/networks` puts in a tile's `id`.
 *
 * Every id here is one the backend already asks for by name in `DEFAULT_NETWORKS` — headline ids
 * and the `altIds` a service is carried under elsewhere (Paramount+ is split into tiers in the US,
 * Starz is Lionsgate+ outside it). Ids are matched before names because a provider id is stable
 * across regions and renames, and the name is not.
 */
private val tilesByProviderId: Map<String, Int> = mapOf(
  "8" to R.drawable.network_tile_netflix,
  "9" to R.drawable.network_tile_prime_video,
  "350" to R.drawable.network_tile_apple_tv,
  "1899" to R.drawable.network_tile_hbo_max,
  "531" to R.drawable.network_tile_paramount,
  "2303" to R.drawable.network_tile_paramount,
  "2616" to R.drawable.network_tile_paramount,
  "15" to R.drawable.network_tile_hulu,
  "337" to R.drawable.network_tile_disney,
  "386" to R.drawable.network_tile_peacock,
  "387" to R.drawable.network_tile_peacock,
  "526" to R.drawable.network_tile_amc,
  "80" to R.drawable.network_tile_amc,
  "528" to R.drawable.network_tile_amc,
  "43" to R.drawable.network_tile_starz,
  "2358" to R.drawable.network_tile_starz,
  "151" to R.drawable.network_tile_britbox,
  "197" to R.drawable.network_tile_britbox,
  "283" to R.drawable.network_tile_crunchyroll,
)

/**
 * Service names, flattened to letters and digits, for tiles the row does not currently carry.
 *
 * The curated list on the backend is not the only source of a network card — a region's provider
 * list names services differently, and the list itself is edited without an app release. Matching
 * on the name means a tile that is already bundled lights up the day its service appears, rather
 * than waiting for its id to be added here.
 */
private val tilesByName: Map<String, Int> = mapOf(
  "netflix" to R.drawable.network_tile_netflix,
  "netflixkids" to R.drawable.network_tile_netflix_kids,
  "primevideo" to R.drawable.network_tile_prime_video,
  "amazonprimevideo" to R.drawable.network_tile_prime_video,
  "amazonvideo" to R.drawable.network_tile_prime_video,
  "appletv" to R.drawable.network_tile_apple_tv,
  "appletvplus" to R.drawable.network_tile_apple_tv,
  "hbomax" to R.drawable.network_tile_hbo_max,
  "max" to R.drawable.network_tile_hbo_max,
  "paramount" to R.drawable.network_tile_paramount,
  "paramountplus" to R.drawable.network_tile_paramount,
  "hulu" to R.drawable.network_tile_hulu,
  "disney" to R.drawable.network_tile_disney,
  "disneyplus" to R.drawable.network_tile_disney,
  "peacock" to R.drawable.network_tile_peacock,
  "amc" to R.drawable.network_tile_amc,
  "amcplus" to R.drawable.network_tile_amc,
  "starz" to R.drawable.network_tile_starz,
  "lionsgateplus" to R.drawable.network_tile_starz,
  "britbox" to R.drawable.network_tile_britbox,
  "crunchyroll" to R.drawable.network_tile_crunchyroll,
  "acorntv" to R.drawable.network_tile_acorn_tv,
  "bbciplayer" to R.drawable.network_tile_bbc_iplayer,
  "crave" to R.drawable.network_tile_crave,
  "curiosity" to R.drawable.network_tile_curiosity,
  "curiositystream" to R.drawable.network_tile_curiosity,
  "discovery" to R.drawable.network_tile_discovery,
  "discoveryplus" to R.drawable.network_tile_discovery,
  "hayu" to R.drawable.network_tile_hayu,
  "mgm" to R.drawable.network_tile_mgm,
  "mgmplus" to R.drawable.network_tile_mgm,
  "plex" to R.drawable.network_tile_plex,
  "plutotv" to R.drawable.network_tile_pluto_tv,
  "shudder" to R.drawable.network_tile_shudder,
  "skyshowtime" to R.drawable.network_tile_skyshowtime,
  "skygo" to R.drawable.network_tile_sky_go,
  "channel4" to R.drawable.network_tile_channel_4,
  "all4" to R.drawable.network_tile_channel_4,
  "channel5" to R.drawable.network_tile_channel_5,
  "my5" to R.drawable.network_tile_channel_5,
  "itvx" to R.drawable.network_tile_itvx,
  "itvplayer" to R.drawable.network_tile_itvx,
  "youtube" to R.drawable.network_tile_youtube,
  "youtubepremium" to R.drawable.network_tile_youtube,
)


/**
 * Transparent service logos for the classic card, by watch-provider id.
 *
 * Covers every service in the backend's `DEFAULT_NETWORKS` — including HBO and NOW, which have no
 * branded tile, so the branded row draws those two as classic cards with a bundled logo rather
 * than a fetched one.
 */
private val logosByProviderId: Map<String, Int> = mapOf(
  "8" to R.drawable.network_logo_netflix,
  "9" to R.drawable.network_logo_prime_video,
  "350" to R.drawable.network_logo_apple_tv,
  "1899" to R.drawable.network_logo_hbo_max,
  "49" to R.drawable.network_logo_hbo,
  "531" to R.drawable.network_logo_paramount,
  "2303" to R.drawable.network_logo_paramount,
  "2616" to R.drawable.network_logo_paramount,
  "15" to R.drawable.network_logo_hulu,
  "337" to R.drawable.network_logo_disney,
  "386" to R.drawable.network_logo_peacock,
  "387" to R.drawable.network_logo_peacock,
  "526" to R.drawable.network_logo_amc,
  "80" to R.drawable.network_logo_amc,
  "528" to R.drawable.network_logo_amc,
  "39" to R.drawable.network_logo_now,
  "591" to R.drawable.network_logo_now,
  "43" to R.drawable.network_logo_starz,
  "2358" to R.drawable.network_logo_starz,
  "151" to R.drawable.network_logo_britbox,
  "197" to R.drawable.network_logo_britbox,
  "283" to R.drawable.network_logo_crunchyroll,
)

/** The same logos by flattened service name, for the reasons given above [tilesByName]. */
private val logosByName: Map<String, Int> = mapOf(
  "netflix" to R.drawable.network_logo_netflix,
  "primevideo" to R.drawable.network_logo_prime_video,
  "amazonprimevideo" to R.drawable.network_logo_prime_video,
  "amazonvideo" to R.drawable.network_logo_prime_video,
  "appletv" to R.drawable.network_logo_apple_tv,
  "appletvplus" to R.drawable.network_logo_apple_tv,
  "hbomax" to R.drawable.network_logo_hbo_max,
  "max" to R.drawable.network_logo_hbo_max,
  "hbo" to R.drawable.network_logo_hbo,
  "paramount" to R.drawable.network_logo_paramount,
  "paramountplus" to R.drawable.network_logo_paramount,
  "hulu" to R.drawable.network_logo_hulu,
  "disney" to R.drawable.network_logo_disney,
  "disneyplus" to R.drawable.network_logo_disney,
  "peacock" to R.drawable.network_logo_peacock,
  "amc" to R.drawable.network_logo_amc,
  "amcplus" to R.drawable.network_logo_amc,
  "now" to R.drawable.network_logo_now,
  "nowtv" to R.drawable.network_logo_now,
  "starz" to R.drawable.network_logo_starz,
  "lionsgateplus" to R.drawable.network_logo_starz,
  "britbox" to R.drawable.network_logo_britbox,
  "crunchyroll" to R.drawable.network_logo_crunchyroll,
)

/**
 * Brand colours, one per service, for the hero to wear while that service's card is highlighted.
 *
 * Each one is read off the bundled tile rather than picked by eye: the tile's own gradient, with
 * the white wordmark and the vignetted edges discarded, averaged over its more saturated third —
 * which is where a brand's hue actually lives — then held to a lightness of 0.12..0.22 and a
 * saturation of at most 0.85. That clamp is the whole reason these are constants and not a
 * runtime sample of the drawable: it guarantees white copy clears 6:1 against every one of them,
 * so the hero is legible before it is on-brand, and it costs a stick nothing to look one up.
 *
 * HBO and NOW ship a logo but no tile, so their two are chosen by hand to the same rule.
 */
private val AcornGreen = Color(0xFF064932)
private val AmcTeal = Color(0xFF086862)
private val AppleCrimson = Color(0xFF46061B)
private val IPlayerMagenta = Color(0xFF680855)
private val BritboxNavy = Color(0xFF10172D)
private val Channel4Green = Color(0xFF416808)
private val Channel5Slate = Color(0xFF1C243C)
private val CraveBlue = Color(0xFF08425D)
private val CrunchyrollRust = Color(0xFF681408)
private val CuriosityInk = Color(0xFF060538)
private val DiscoveryViolet = Color(0xFF150544)
private val DisneyTeal = Color(0xFF053844)
private val HayuMagenta = Color(0xFF68085D)
private val MaxPurple = Color(0xFF3A064B)
private val HuluGreen = Color(0xFF064D0F)
private val ItvSlate = Color(0xFF1D3141)
private val MgmGold = Color(0xFF685A08)
private val NetflixRed = Color(0xFF680808)
private val NetflixKidsTeal = Color(0xFF085768)
private val ParamountBlue = Color(0xFF071453)
private val PeacockPlum = Color(0xFF231B25)
private val PlexCharcoal = Color(0xFF2C2B25)
private val PlutoOlive = Color(0xFF686608)
private val PrimeBlue = Color(0xFF052744)
private val ShudderInk = Color(0xFF15192B)
private val SkyTeal = Color(0xFF085E68)
private val SkyshowtimeMagenta = Color(0xFF5C085A)
private val StarzTeal = Color(0xFF074352)
private val YouTubeRed = Color(0xFF680808)
private val HboGraphite = Color(0xFF202127)
private val NowTeal = Color(0xFF004449)

/** Brand colours by watch-provider id, matched first for the reasons given above [tilesByProviderId]. */
private val brandByProviderId: Map<String, Color> = mapOf(
  "8" to NetflixRed,
  "9" to PrimeBlue,
  "350" to AppleCrimson,
  "1899" to MaxPurple,
  "49" to HboGraphite,
  "531" to ParamountBlue,
  "2303" to ParamountBlue,
  "2616" to ParamountBlue,
  "15" to HuluGreen,
  "337" to DisneyTeal,
  "386" to PeacockPlum,
  "387" to PeacockPlum,
  "526" to AmcTeal,
  "80" to AmcTeal,
  "528" to AmcTeal,
  "39" to NowTeal,
  "591" to NowTeal,
  "43" to StarzTeal,
  "2358" to StarzTeal,
  "151" to BritboxNavy,
  "197" to BritboxNavy,
  "283" to CrunchyrollRust,
)

/** The same colours by flattened service name, for the reasons given above [tilesByName]. */
private val brandByName: Map<String, Color> = mapOf(
  "netflix" to NetflixRed,
  "netflixkids" to NetflixKidsTeal,
  "primevideo" to PrimeBlue,
  "amazonprimevideo" to PrimeBlue,
  "amazonvideo" to PrimeBlue,
  "appletv" to AppleCrimson,
  "appletvplus" to AppleCrimson,
  "hbomax" to MaxPurple,
  "max" to MaxPurple,
  "hbo" to HboGraphite,
  "paramount" to ParamountBlue,
  "paramountplus" to ParamountBlue,
  "hulu" to HuluGreen,
  "disney" to DisneyTeal,
  "disneyplus" to DisneyTeal,
  "peacock" to PeacockPlum,
  "amc" to AmcTeal,
  "amcplus" to AmcTeal,
  "now" to NowTeal,
  "nowtv" to NowTeal,
  "starz" to StarzTeal,
  "lionsgateplus" to StarzTeal,
  "britbox" to BritboxNavy,
  "crunchyroll" to CrunchyrollRust,
  "acorntv" to AcornGreen,
  "bbciplayer" to IPlayerMagenta,
  "crave" to CraveBlue,
  "curiosity" to CuriosityInk,
  "curiositystream" to CuriosityInk,
  "discovery" to DiscoveryViolet,
  "discoveryplus" to DiscoveryViolet,
  "hayu" to HayuMagenta,
  "mgm" to MgmGold,
  "mgmplus" to MgmGold,
  "plex" to PlexCharcoal,
  "plutotv" to PlutoOlive,
  "shudder" to ShudderInk,
  "skyshowtime" to SkyshowtimeMagenta,
  "skygo" to SkyTeal,
  "channel4" to Channel4Green,
  "all4" to Channel4Green,
  "channel5" to Channel5Slate,
  "my5" to Channel5Slate,
  "itvx" to ItvSlate,
  "itvplayer" to ItvSlate,
  "youtube" to YouTubeRed,
  "youtubepremium" to YouTubeRed,
)

/** Lowercase letters and digits only, so "Apple TV+" and "apple tv plus" are not two services. */
internal fun networkTileKey(name: String): String =
  name.lowercase().filter { it.isLetterOrDigit() }

/**
 * The bundled tile for a network card, or null when none ships for it.
 *
 * Null is a normal answer, not a failure: the card falls back to the logo the row supplied, which
 * is what every card used before these tiles existed.
 */
internal fun networkTileArt(id: String, title: String): Int? =
  tilesByProviderId[id.trim()] ?: tilesByName[networkTileKey(title)]

internal fun networkTileArt(item: MediaItem): Int? = networkTileArt(item.id, item.title)

/**
 * The bundled transparent logo for a network card, or null when none ships for it.
 *
 * Drawn on the classic card's white tile, in place of the logo `/tmdb/networks` hands over — that
 * one is a provider-list thumbnail, and it shows at card size.
 */
internal fun networkLogoArt(id: String, title: String): Int? =
  logosByProviderId[id.trim()] ?: logosByName[networkTileKey(title)]

internal fun networkLogoArt(item: MediaItem): Int? = networkLogoArt(item.id, item.title)

/**
 * The colour the hero wears while this network card is highlighted, or null when none ships.
 *
 * Null leaves the hero on the app background, which is what every network card had before.
 */
internal fun networkBrandColor(id: String, title: String): Color? =
  brandByProviderId[id.trim()] ?: brandByName[networkTileKey(title)]

internal fun networkBrandColor(item: MediaItem): Color? = networkBrandColor(item.id, item.title)
