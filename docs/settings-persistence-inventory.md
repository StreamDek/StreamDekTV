# TV settings inventory

2026-10-02. All fields in the five TV preference models are listed below, including compatibility fields not exposed as controls. `PreferenceScopes` defines profile ownership and `PlatformPreferences` defines TV-specific overrides. `TvSettingsJournal` persists raw preferences and pending edits for each account/profile before networking; it does not save server credentials. `TvSettingsJournalTest` round-trips every non-credential model field, including false, zero and empty values.

TV Settings writes `defaultAudioLanguage` and `defaultSubtitleLanguage` through their canonical `preferredAudioLanguage`/`preferredSubtitleLanguage` aliases. Audio also updates the legacy profile metadata within the same acknowledged retry operation. Playback and Settings consume these same effective values.

| Field | Owner | Type | Model fallback |
| --- | --- | --- | --- |
| `app.theme` | Account cloud | `String` | `"cinema-blue"` |
| `app.colorMode` | Account cloud | `String` | `"night"` |
| `app.startScreen` | Account cloud | `String` | `"home"` |
| `app.homeRowCardStyle` | Account cloud | `String` | `"landscape"` |
| `app.compactMode` | Account cloud | `Boolean` | `false` |
| `app.syncOverCellular` | Account cloud | `Boolean` | `false` |
| `app.cardDensity` | Account cloud | `String` | `"comfortable"` |
| `app.animationSpeed` | Account cloud | `String` | `"normal"` |
| `app.navigationStyle` | Account cloud | `String` | `"adaptive"` |
| `app.gridSize` | Account cloud | `Int` | `5` |
| `app.backgroundBlur` | Account cloud | `Boolean` | `true` |
| `app.highContrast` | Account cloud | `Boolean` | `false` |
| `app.largeText` | Account cloud | `Boolean` | `false` |
| `app.reducedMotion` | Account cloud | `Boolean` | `false` |
| `app.hideHomeSynopsis` | Account cloud | `Boolean` | `true` |
| `app.hideHomeCardTitles` | Account cloud | `Boolean` | `false` |
| `app.transparentNavigation` | Account cloud | `Boolean` | `true` |
| `playback.autoplayNextEpisode` | Profile cloud (account fallback before a profile is selected) | `Boolean` | `true` |
| `playback.autoPlayNextEpisodeEnabled` | Profile cloud (account fallback before a profile is selected) | `Boolean?` | `null` |
| `playback.preferredQuality` | Profile cloud (account fallback before a profile is selected) | `String` | `"1080p"` |
| `playback.maxFileSizeGB` | Profile cloud (account fallback before a profile is selected) | `String` | `"2"` |
| `playback.streamingServer` | Account cloud | `String` | `"addon"` |
| `playback.defaultSubtitleLanguage` | Account cloud | `String` | `"en"` |
| `playback.defaultAudioLanguage` | Account cloud | `String` | `"en"` |
| `playback.preferredAudioLanguage` | Profile cloud (account fallback before a profile is selected) | `String?` | `null` |
| `playback.preferredSubtitleLanguage` | Profile cloud (account fallback before a profile is selected) | `String?` | `null` |
| `playback.externalPlayerEnabled` | Account cloud | `Boolean` | `false` |
| `playback.preferEmbeddedMpvByDefault` | Account cloud | `Boolean` | `true` |
| `playback.skipSegmentsEnabled` | Profile cloud (account fallback before a profile is selected) | `Boolean?` | `null` |
| `playback.skipIntroEnabled` | Profile cloud (account fallback before a profile is selected) | `Boolean?` | `null` |
| `playback.skipRecapEnabled` | Profile cloud (account fallback before a profile is selected) | `Boolean?` | `null` |
| `playback.skipEndingEnabled` | Profile cloud (account fallback before a profile is selected) | `Boolean?` | `null` |
| `playback.autoSkipIntroEnabled` | Profile cloud (account fallback before a profile is selected) | `Boolean` | `false` |
| `playback.autoSkipRecapEnabled` | Profile cloud (account fallback before a profile is selected) | `Boolean` | `false` |
| `playback.autoSkipEndingEnabled` | Profile cloud (account fallback before a profile is selected) | `Boolean` | `false` |
| `playback.introContributionEnabled` | Account cloud | `Boolean` | `false` |
| `playback.introDbApiKey` | Legacy credential field; separate encrypted credential storage | `String` | `""` |
| `playback.preferBingeGroupNextEpisode` | Profile cloud (account fallback before a profile is selected) | `Boolean` | `true` |
| `playback.autoLoadSubtitles` | Profile cloud (account fallback before a profile is selected) | `Boolean` | `true` |
| `playback.showOnlyPreferredSubtitleLanguages` | Profile cloud (account fallback before a profile is selected) | `Boolean` | `false` |
| `playback.secondarySubtitleLanguage` | Profile cloud (account fallback before a profile is selected) | `String` | `"none"` |
| `playback.addonSubtitleLoading` | Profile cloud (account fallback before a profile is selected) | `String` | `"preferred"` |
| `playback.subtitleDefaultSource` | Profile cloud (account fallback before a profile is selected) | `String` | `"All"` |
| `playback.nextEpisodeThresholdMode` | Profile cloud (account fallback before a profile is selected) | `String` | `"minutes"` |
| `playback.nextEpisodeThresholdPercent` | Profile cloud (account fallback before a profile is selected) | `Int` | `95` |
| `playback.nextEpisodeThresholdMinutes` | Profile cloud (account fallback before a profile is selected) | `Int` | `2` |
| `playback.endOfPlaybackRecommendationsEnabled` | Profile cloud (account fallback before a profile is selected) | `Boolean` | `false` |
| `playback.recommendationTiming` | Profile cloud (account fallback before a profile is selected) | `String` | `"standard"` |
| `playback.recommendationItemCount` | Profile cloud (account fallback before a profile is selected) | `Int` | `1` |
| `playback.timingProvider` | Profile cloud (account fallback before a profile is selected) | `String` | `"introdb"` |
| `playback.timingProviderFallbackEnabled` | Profile cloud (account fallback before a profile is selected) | `Boolean` | `true` |
| `playback.decoderMode` | Account cloud | `String` | `"hardware_plus"` |
| `playback.renderSurface` | Account cloud | `String` | `"auto"` |
| `playback.playerEngine` | Account cloud | `String` | `"Auto"` |
| `playback.rememberLastSource` | Account cloud | `Boolean` | `true` |
| `playback.subtitleSources` | Account cloud | `List<SubtitleSourcePreference>` | `emptyList()` |
| `playback.customSubtitleSources` | Account cloud | `List<SubtitleSourcePreference>` | `emptyList()` |
| `playback.manualStreamSelectionEnabled` | Account cloud | `Boolean` | `true` |
| `playback.liveProgressBarEnabled` | Profile cloud (account fallback before a profile is selected) | `Boolean` | `false` |
| `playback.liveBadgeEnabled` | Profile cloud (account fallback before a profile is selected) | `Boolean` | `true` |
| `home.primarySyncService` | Profile cloud (account fallback before a profile is selected) | `String` | `SyncServiceId.TRAKT` |
| `home.defaultAppCatalogsEnabled` | Profile cloud (account fallback before a profile is selected) | `Boolean` | `true` |
| `home.continueWatchingStyle` | Profile cloud (account fallback before a profile is selected) | `String?` | `null` |
| `home.networkCardStyle` | Profile cloud (account fallback before a profile is selected) | `String?` | `null` |
| `home.liveCategoriesEnabled` | Profile cloud (account fallback before a profile is selected) | `Boolean` | `true` |
| `home.liveLandscapeCards` | Profile cloud (account fallback before a profile is selected) | `Boolean` | `true` |
| `home.liveFavouriteDrawerCards` | Profile cloud (account fallback before a profile is selected) | `Boolean` | `false` |
| `home.showHeroSynopsis` | Profile cloud (account fallback before a profile is selected) | `Boolean` | `true` |
| `home.detailPageStyle` | Profile cloud (account fallback before a profile is selected) | `String?` | `null` |
| `home.vividAmbient` | Profile cloud (account fallback before a profile is selected) | `Boolean` | `true` |
| `home.ambientTintPercent` | Profile cloud (account fallback before a profile is selected) | `Int` | `100` |
| `home.homeCatalogRows` | Profile cloud (account fallback before a profile is selected) | `List<HomeCatalogRowPreference>` | `emptyList()` |
| `detail.seasonTabStyle` | Profile cloud (account fallback before a profile is selected) | `String?` | `null` |
| `detail.heroTrailerAutoplay` | Account cloud, platforms.tv | `Boolean` | `true` |
| `detail.heroTrailerDelaySeconds` | Account cloud, platforms.tv | `Int` | `DefaultTrailerDelaySeconds` |
| `detail.heroTrailerResolution` | Account cloud, platforms.tv | `Int` | `2160` |
| `detail.trailerCacheClearHours` | Profile cloud (account fallback before a profile is selected) | `Int` | `DefaultTrailerCacheClearHours` |
| `detail.ratingsEnabled` | Profile cloud (account fallback before a profile is selected) | `Boolean` | `true` |
| `detail.externalRatingsEnabled` | Profile cloud (account fallback before a profile is selected) | `Boolean` | `true` |
| `detail.enabledRatingProviders` | Profile cloud (account fallback before a profile is selected) | `List<String>` | `emptyList()` |
| `detail.mdblistApiKey` | Legacy credential field; separate encrypted credential storage | `String?` | `null` |
| `streams.fusionBadgesEnabled` | Profile cloud (account fallback before a profile is selected) | `Boolean` | `true` |
| `streams.showSizeBadges` | Profile cloud (account fallback before a profile is selected) | `Boolean` | `true` |
| `streams.badgePosition` | Profile cloud (account fallback before a profile is selected) | `String` | `"bottom"` |
| `streams.fusionBadgeUrls` | Profile cloud (account fallback before a profile is selected) | `List<String>` | `listOf(DEFAULT_FUSION_BADGE_URL)` |
| `streams.activeFusionBadgeUrl` | Profile cloud (account fallback before a profile is selected) | `String?` | `null` |
| `streams.showStreamsList` | Profile cloud (account fallback before a profile is selected) | `Boolean` | `true` |
| `streams.rememberLastSource` | Profile cloud (account fallback before a profile is selected) | `Boolean` | `true` |
| `streams.blurUnwatchedEpisodes` | Profile cloud (account fallback before a profile is selected) | `Boolean` | `true` |
| `streams.streamDekFormattingEnabled` | Profile cloud (account fallback before a profile is selected) | `Boolean` | `false` |
| `streams.showAddonTmdbRatings` | Profile cloud (account fallback before a profile is selected) | `Boolean` | `false` |
| `streams.favoriteSourceKeys` | Profile cloud (account fallback before a profile is selected) | `List<String>` | `emptyList()` |

## Local and separate stores

| Controls | Store / source of truth | Lifecycle |
| --- | --- | --- |
| Fuse; remember last profile; live captions and chosen-caption flag; subtitle size and position | `AuthSessionStore`, `streamdek_tv_native` | Local device; checked synchronous writes. Active profile id is namespaced per account. |
| App language; animation speed; sleep while paused; app idle timeout | `TvLanguagePreferences`, `TvAnimationPreferences`, `TvIdlePreferences` | Local startup projection plus durable account journal under `platforms.tv`. One-time migration copies only keys actually stored. |
| Dolby Vision fallback; tunneled playback | `PlaybackCodecOptions` | Local device, checked writes. |
| Default audio delay | `AudioSyncOptions` | Local device; checked writes. Per-title delay adjustments remain playback-session state unless explicitly saved as default. |
| Subtitle timing and selected track | Player session | Intentionally session-only; Settings explains that subtitle timing is specific to a release. |
| DNS over HTTPS enabled/provider/custom endpoint | `DoH` | Local device, checked writes. |
| Automatic update checks | `AppUpdateManager` | Local device, checked writes. |
| Media-server home-row display and ambient choices | `MediaServerManager` and repository media-server preferences | Local display settings. Server connections use the media-server vault/sync contract. |
| Plugin repositories, enabled providers, ordering, options | Plugin managers and profile-plugin document | Durable local document, existing profile-plugin synchronization. Third-party plugin-owned screens can also write their own stores. StreamDek-controlled restores now check disk success. |
| Add-ons, playlists, tracking connections, service credentials | Their resource APIs and encrypted local vaults | Persistent resource records, separate from the preference document. Backend add-on/favourite writers now preserve concurrent settings edits. |
| Library/search filters and display modes | `streamdek_tv_library`, `streamdek_tv_search` | Local device. Not cloud/profile preferences. |

No TV PiP control is exposed in Settings. The reported seekbar equivalent is `playback.liveProgressBarEnabled`; Fuse intentionally stays local to each TV.
