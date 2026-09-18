package com.streamdek.tv.nativeapp.data

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/** One SkyStream collection as the synced document describes it. */
data class SkyProfileRepo(
    val url: String,
    val name: String,
    val description: String?,
    val enabled: Boolean,
    val favourite: Boolean,
)

/** One SkyStream source as the synced document describes it, with its settings. */
data class SkyProfileSource(
    val repoUrl: String,
    val packageName: String,
    val name: String,
    val version: Int,
    val downloadUrl: String,
    val description: String?,
    val categories: List<String>,
    val languages: List<String>,
    val enabled: Boolean,
    /** Every value as text, SkyStream's convention; host keys start with `_`. */
    val settings: Map<String, String>,
)

/**
 * Reads and edits the `skystream` section of a profile's plugin document.
 *
 * The section is kept as raw JSON on [ProfilePluginState] so that nothing the phone or the portal
 * writes into it is lost when this television sends the document back. Edits here therefore work
 * on a copy of that JSON, touching only the fields they change, and stamp the section so the change
 * wins over older copies on the other devices.
 */
object SkyStreamProfile {
    fun repos(section: JsonObject?): List<SkyProfileRepo> = section.array("repos").mapNotNull { element ->
        val item = element.asObjectOrNull() ?: return@mapNotNull null
        val url = item.string("url") ?: return@mapNotNull null
        SkyProfileRepo(
            url = url,
            name = item.string("name").orEmpty().ifBlank { url },
            description = item.string("description"),
            enabled = item.boolean("enabled", true),
            favourite = item.boolean("favourite", false),
        )
    }

    fun sources(section: JsonObject?): List<SkyProfileSource> = section.array("providers").mapNotNull { element ->
        val item = element.asObjectOrNull() ?: return@mapNotNull null
        val packageName = item.string("packageName") ?: return@mapNotNull null
        SkyProfileSource(
            repoUrl = item.string("repoUrl").orEmpty(),
            packageName = packageName,
            name = item.string("name").orEmpty().ifBlank { packageName },
            version = item.get("version")?.takeIf { it.isJsonPrimitive }?.runCatching { asInt }?.getOrNull() ?: 0,
            downloadUrl = item.string("downloadUrl").orEmpty(),
            description = item.string("description"),
            categories = item.stringList("categories"),
            languages = item.stringList("languages"),
            enabled = item.boolean("enabled", false),
            settings = item.get("settings")?.asObjectOrNull()?.entrySet()
                ?.mapNotNull { (key, value) -> value.takeIf { it.isJsonPrimitive }?.asString?.let { key to it } }
                ?.toMap()
                .orEmpty(),
        )
    }

    /** A copy of [section] with one collection changed, stamped. */
    fun withRepo(section: JsonObject, url: String, change: (JsonObject) -> Unit): JsonObject =
        stamped(section) { copy -> copy.array("repos").forEach { element -> element.asObjectOrNull()?.takeIf { it.string("url") == url }?.let(change) } }

    /** A copy of [section] with one source changed, stamped. */
    fun withSource(section: JsonObject, repoUrl: String, packageName: String, change: (JsonObject) -> Unit): JsonObject =
        stamped(section) { copy ->
            copy.array("providers").forEach { element ->
                element.asObjectOrNull()?.takeIf { it.string("repoUrl") == repoUrl && it.string("packageName") == packageName }?.let(change)
            }
        }

    /** A copy of [section] with a source's settings replaced by [values]. */
    fun withSettings(section: JsonObject, repoUrl: String, packageName: String, values: Map<String, String>): JsonObject =
        withSource(section, repoUrl, packageName) { source ->
            source.add("settings", JsonObject().apply { values.forEach { (key, value) -> addProperty(key, value) } })
        }

    private fun stamped(section: JsonObject, edit: (JsonObject) -> Unit): JsonObject =
        section.deepCopy().also { copy ->
            edit(copy)
            copy.addProperty("updatedAt", System.currentTimeMillis())
        }

    private fun JsonObject?.array(name: String): JsonArray = this?.get(name)?.takeIf { it.isJsonArray }?.asJsonArray ?: JsonArray()
    private fun JsonElement.asObjectOrNull(): JsonObject? = takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.string(name: String): String? =
        get(name)?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.takeIf { it.isNotEmpty() }
    private fun JsonObject.boolean(name: String, fallback: Boolean): Boolean =
        get(name)?.takeIf { it.isJsonPrimitive }?.runCatching { asBoolean }?.getOrNull() ?: fallback
    private fun JsonObject.stringList(name: String): List<String> =
        array(name).mapNotNull { it.takeIf { element -> element.isJsonPrimitive }?.asString?.takeIf(String::isNotBlank) }
}
