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
            "liveBadgeEnabled",
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

/**
 * Settings each client holds for itself rather than sharing with the account.
 *
 * The backend defines the convention (settingsSchema.ts: perPlatformSettingKeys and
 * PER_PLATFORM_PREFERENCES_KEY), and the phone and the web portal already follow it: trailer choices
 * live under `platforms.<client>` so a phone deciding when its trailers start does not change the
 * television's. Until this television read and wrote the same place, the portal's TV tab changed a
 * value nothing read, and every trailer setting saved here quietly became the phone's fallback.
 */
internal object PlatformPreferences {

    const val KEY = "platforms"
    const val PLATFORM = "tv"

    /** The per-platform keys, by the section their shared fallback lives in. */
    val keys: Map<String, Set<String>> = mapOf(
        "detail" to setOf("heroTrailerAutoplay", "heroTrailerDelaySeconds", "heroTrailerResolution"),
    )

    /**
     * Folds this television's own values over the shared sections, so screens keep reading
     * `preferences.detail` and get the answer for this client. A key the television has never
     * saved keeps the shared value, which is how an account configured before the split keeps the
     * settings it already had.
     */
    fun applyToPreferences(preferences: JsonObject): JsonObject {
        val own = preferences.asObjectOrNull(KEY)?.asObjectOrNull(PLATFORM) ?: return preferences
        val merged = preferences.deepCopy()
        for ((section, sectionKeys) in keys) {
            val base = merged.asObjectOrNull(section)?.deepCopy() ?: JsonObject()
            var changed = false
            for (key in sectionKeys) {
                val value = own.get(key) ?: continue
                if (value.isJsonNull) continue
                base.add(key, value)
                changed = true
            }
            if (changed) merged.add(section, base)
        }
        return merged
    }

    /**
     * Settings that used to live only on this television and now travel under `platforms.tv`, so
     * the web portal can set them. Unlike the trailer keys there is no shared value to fall back to:
     * they were never anywhere but the device, so they are not folded into any section.
     */
    object Device {
        const val ANIMATION_SPEED = "animationSpeed"
        const val APP_LANGUAGE = "appLanguage"
        const val SLEEP_WHEN_PAUSED_MINUTES = "sleepWhenPausedMinutes"
        const val APP_IDLE_TIMEOUT_MINUTES = "appIdleTimeoutMinutes"
    }

    /** What to take from the account, and what the account does not hold yet. */
    data class DeviceReconciliation(val apply: Map<String, JsonElement>, val upload: Map<String, Any>)

    /**
     * Compares this television's own section of the account with what the device holds.
     *
     * A key the account has is applied here, which is how a change made in the portal arrives. A key
     * it lacks is uploaded from the device rather than the device being given a default: a television
     * that has slept after thirty minutes for a year keeps doing so, and the portal learns it.
     */
    fun reconcileDevice(platforms: JsonObject?, local: Map<String, Any>): DeviceReconciliation {
        val own = platforms?.asObjectOrNull(PLATFORM)
        val apply = mutableMapOf<String, JsonElement>()
        val upload = mutableMapOf<String, Any>()
        for ((key, value) in local) {
            val remote = own?.get(key)?.takeUnless { it.isJsonNull }
            if (remote == null) upload[key] = value else apply[key] = remote
        }
        return DeviceReconciliation(apply, upload)
    }

    /**
     * Splits a section update into the part that is shared and the part that belongs to this
     * television, returning the payload to PATCH. The backend merges `platforms` one level deep, so
     * sending only this client's sub-object leaves the phone's untouched.
     */
    fun splitSectionUpdate(section: String, values: Map<String, Any?>): Map<String, Any?> {
        val own = keys[section].orEmpty()
        val shared = values.filterKeys { it !in own }
        val platform = values.filterKeys { it in own }
        return buildMap {
            put(section, shared)
            if (platform.isNotEmpty()) put(KEY, mapOf(PLATFORM to platform))
        }
    }
}

internal fun JsonObject.asObjectOrNull(key: String): JsonObject? {
    val element: JsonElement? = get(key)
    return if (element != null && element.isJsonObject) element.asJsonObject else null
}
