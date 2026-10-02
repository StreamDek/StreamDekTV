package com.streamdek.tv.nativeapp.data

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

internal data class TvSettingsOwner(val account: String, val profile: String?)
internal data class TvSettingsPending(val account: JsonObject, val profile: JsonObject) {
    val empty get() = account.size() == 0 && profile.size() == 0
}

/** Durable sparse edits plus cached settings, isolated by account/profile. No credentials are cached. */
internal class TvSettingsJournal(
    private val read: () -> String?,
    private val write: (String) -> Boolean,
    private val diagnostic: (String) -> Unit = {},
) {
    private fun state(): JsonObject = runCatching { JsonParser.parseString(read() ?: "{}").asJsonObject }
        .getOrElse { diagnostic("journal_invalid"); JsonObject() }
    private fun accountKey(owner: TvSettingsOwner) = "account:${owner.account}"
    private fun profileKey(owner: TvSettingsOwner) = "profile:${owner.account}:${owner.profile.orEmpty()}"
    private fun record(root: JsonObject, key: String): JsonObject = root.asObjectOrNull(key)
        ?: JsonObject().also { root.add(key, it) }
    private fun save(root: JsonObject): Boolean = write(root.toString()).also { if (!it) diagnostic("disk_write_failed") }

    @Synchronized fun enqueue(owner: TvSettingsOwner, patch: JsonObject): Boolean {
        val root = state()
        val account = patch.deepCopy()
        val profile = JsonObject()
        if (!owner.profile.isNullOrBlank()) patch.entrySet().forEach { (section, value) ->
            val scoped = (value as? JsonObject)?.let { PreferenceScopes.profileScopedSection(section, it) }
            if (scoped != null) {
                profile.add(section, scoped)
                scoped.keySet().forEach { account.asObjectOrNull(section)?.remove(it) }
            }
        }
        fun enqueuePart(key: String, part: JsonObject) {
            val entry = record(root, key)
            val pending = entry.asObjectOrNull("pending") ?: JsonObject()
            val revision = (entry.get("revision")?.asLong ?: 0L) + 1
            flattenSettings(part).forEach { (path, value) ->
                pending.add(path, JsonObject().apply { addProperty("revision", revision); add("value", value.deepCopy()) })
            }
            entry.add("pending", pending); entry.addProperty("revision", revision)
        }
        enqueuePart(accountKey(owner), account)
        enqueuePart(profileKey(owner), profile)
        return save(root)
    }

    @Synchronized fun pending(owner: TvSettingsOwner): TvSettingsPending {
        val root = state()
        return TvSettingsPending(record(root, accountKey(owner)).asObjectOrNull("pending")?.deepCopy() ?: JsonObject(),
            record(root, profileKey(owner)).asObjectOrNull("pending")?.deepCopy() ?: JsonObject())
    }

    @Synchronized fun acknowledge(owner: TvSettingsOwner, sent: TvSettingsPending) {
        val root = state()
        fun acknowledgePart(key: String, sentPart: JsonObject) {
            val entry = record(root, key)
            val pending = entry.asObjectOrNull("pending") ?: JsonObject()
            val cache = mergeSettingsObjects(entry.asObjectOrNull("cache") ?: JsonObject(), pendingSettingsPayload(sentPart))
            sentPart.entrySet().forEach { (path, value) -> if (pending.get(path) == value) pending.remove(path) }
            entry.add("cache", cache); entry.add("pending", pending)
        }
        acknowledgePart(accountKey(owner), sent.account); acknowledgePart(profileKey(owner), sent.profile)
        save(root)
    }

    @Synchronized fun acceptRemote(owner: TvSettingsOwner, account: JsonObject, profile: JsonObject, profiles: JsonArray?) {
        val root = state()
        record(root, accountKey(owner)).apply {
            add("cache", sanitizedSettings(account))
            if (profiles != null) add("profiles", profiles.deepCopy())
        }
        record(root, profileKey(owner)).add("cache", sanitizedSettings(profile))
        save(root)
    }

    @Synchronized fun snapshot(owner: TvSettingsOwner): JsonObject? {
        val root = state()
        val accountRecord = root.asObjectOrNull(accountKey(owner)) ?: return null
        val profileRecord = root.asObjectOrNull(profileKey(owner)) ?: JsonObject()
        val account = mergeSettingsObjects(accountRecord.asObjectOrNull("cache") ?: JsonObject(), pendingSettingsPayload(accountRecord.asObjectOrNull("pending") ?: JsonObject()))
        val profile = mergeSettingsObjects(profileRecord.asObjectOrNull("cache") ?: JsonObject(), pendingSettingsPayload(profileRecord.asObjectOrNull("pending") ?: JsonObject()))
        return JsonObject().apply {
            add("preferences", PlatformPreferences.applyToPreferences(PreferenceScopes.mergeIntoAccountPreferences(account, profile)))
            add("profilePreferences", profile)
            add("streamProfiles", accountRecord.getAsJsonArray("profiles") ?: JsonArray())
        }
    }
}

internal fun flattenSettings(root: JsonObject, prefix: String = ""): Map<String, JsonElement> = buildMap {
    root.entrySet().forEach { (key, value) ->
        val path = if (prefix.isEmpty()) key else "$prefix.$key"
        if (value.isJsonObject) putAll(flattenSettings(value.asJsonObject, path)) else put(path, value)
    }
}

internal fun pendingSettingsPayload(pending: JsonObject): JsonObject = JsonObject().apply {
    pending.entrySet().forEach { (path, record) ->
        val keys = path.split('.')
        var parent = this
        keys.dropLast(1).forEach { key ->
            parent = parent.asObjectOrNull(key) ?: JsonObject().also { parent.add(key, it) }
        }
        parent.add(keys.last(), record.asJsonObject.get("value").deepCopy())
    }
}

internal fun mergeSettingsObjects(current: JsonObject, patch: JsonObject): JsonObject = current.deepCopy().apply {
    patch.entrySet().forEach { (key, value) ->
        add(key, if (value.isJsonObject) mergeSettingsObjects(asObjectOrNull(key) ?: JsonObject(), value.asJsonObject) else value.deepCopy())
    }
}

internal fun sanitizedSettings(input: JsonObject): JsonObject = input.deepCopy().apply {
    entrySet().toList().forEach { (key, value) ->
        if (key.contains("apiKey", true) || key.contains("token", true) || key.contains("password", true)) remove(key)
        else if (value.isJsonObject) add(key, sanitizedSettings(value.asJsonObject))
    }
}
