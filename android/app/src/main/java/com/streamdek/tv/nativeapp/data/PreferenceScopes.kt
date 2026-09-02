package com.streamdek.tv.nativeapp.data

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Settings live in two places since the profile-identity work landed on the backend.
 *
 * `/account/bootstrap.preferences` holds the account-wide answer, and every viewing profile can
 * override the content-facing parts of it. The override arrives on the same bootstrap call as
 * `profilePreferences` (scoped by the `x-profile-id` header) and is written back with
 * `PUT /profiles/:id/preferences`.
 *
 * The split below mirrors the mobile client exactly, which is what makes a setting changed on the
 * phone show up on the TV under the same profile. Hardware and app-shell concerns — decoder,
 * render surface, player engine, theme, update checks — stay account/device scoped, because they
 * describe the box rather than the person using it.
 */
internal object PreferenceScopes {

    /** Sections a profile may override wholesale. */
    private val WHOLE_SECTIONS = setOf("home", "streams")

    /** Sections a profile overrides only in part; the remaining keys stay account-wide. */
    private val PARTIAL_SECTIONS = mapOf(
        // The MDBList key authenticates the account against the tracking service, so it is stored
        // once rather than copied into every profile blob.
        "detail" to null,
        "playback" to setOf(
            "preferredQuality",
            "maxFileSizeGB",
            "skipSegmentsEnabled",
            "skipIntroEnabled",
            "skipRecapEnabled",
            "skipEndingEnabled",
            "autoSkipIntroEnabled",
            "autoSkipRecapEnabled",
            "autoSkipEndingEnabled",
            "autoPlayNextEpisodeEnabled",
            "autoplayNextEpisode",
            "preferBingeGroupNextEpisode",
            "autoLoadSubtitles",
            "showOnlyPreferredSubtitleLanguages",
            "secondarySubtitleLanguage",
            "addonSubtitleLoading",
            "nextEpisodeThresholdMode",
            "nextEpisodeThresholdPercent",
            "nextEpisodeThresholdMinutes",
            "endOfPlaybackRecommendationsEnabled",
            "recommendationTiming",
            "recommendationItemCount",
            "timingProvider",
            "timingProviderFallbackEnabled",
            "liveProgressBarEnabled",
        ),
    )

    private val DETAIL_ACCOUNT_ONLY_KEYS = setOf("mdblistApiKey")

    val sections: Set<String> = WHOLE_SECTIONS + PARTIAL_SECTIONS.keys

    /** Narrows one section of a preferences payload down to the keys a profile owns. */
    fun profileScopedSection(section: String, source: JsonObject): JsonObject? {
        if (section !in sections) return null
        val allowed = PARTIAL_SECTIONS[section]
        val result = JsonObject()
        for ((key, value) in source.entrySet()) {
            val included = when {
                section == "detail" -> key !in DETAIL_ACCOUNT_ONLY_KEYS
                allowed != null -> key in allowed
                else -> true
            }
            if (included) result.add(key, value)
        }
        return result.takeIf { it.size() > 0 }
    }

    /**
     * Folds a profile's overrides onto the account preferences, key by key. Only the sections the
     * profile owns are touched, and a key the profile has not set keeps the account value, so a
     * partially populated profile blob never blanks out a setting.
     */
    fun mergeIntoAccountPreferences(accountPreferences: JsonObject, profilePreferences: JsonObject): JsonObject {
        val merged = accountPreferences.deepCopy()
        for (section in sections) {
            val overrides = profilePreferences.asObjectOrNull(section) ?: continue
            val scoped = profileScopedSection(section, overrides) ?: continue
            val base = merged.asObjectOrNull(section)?.deepCopy() ?: JsonObject()
            for ((key, value) in scoped.entrySet()) {
                base.add(key, value)
            }
            merged.add(section, base)
        }
        return merged
    }

    /**
     * Builds the blob to PUT back for a profile: everything already stored for it, with the
     * profile-scoped parts of [changedPreferences] applied on top.
     *
     * The whole blob has to be resent because the backend replaces it outright, and it holds more
     * than settings — live favourites among them — so anything not being changed is carried over
     * verbatim rather than rebuilt from the sections the TV happens to know about.
     */
    fun applyToProfileBlob(existing: JsonObject, changedPreferences: JsonObject): JsonObject? {
        val next = existing.deepCopy()
        var changed = false
        for ((section, value) in changedPreferences.entrySet()) {
            val incoming = value as? JsonObject ?: continue
            val scoped = profileScopedSection(section, incoming) ?: continue
            val base = next.asObjectOrNull(section)?.deepCopy() ?: JsonObject()
            for ((key, entry) in scoped.entrySet()) {
                base.add(key, entry)
            }
            next.add(section, base)
            changed = true
        }
        return next.takeIf { changed }
    }
}

internal fun JsonObject.asObjectOrNull(key: String): JsonObject? {
    val element: JsonElement? = get(key)
    return if (element != null && element.isJsonObject) element.asJsonObject else null
}
