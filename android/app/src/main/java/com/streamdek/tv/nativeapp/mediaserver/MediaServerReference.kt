package com.streamdek.tv.nativeapp.mediaserver

import java.util.Base64

/**
 * Where a title from a personal media server lives: which provider, which server, which item.
 *
 * Travels as a card's `id` exactly as [com.streamdek.tv.nativeapp.data.AddonMediaReference] does
 * for add-on titles, so Home, Search, the title page, the stream list and the player can all carry
 * a Plex title without knowing anything about Plex. The repository recognises the prefix and hands
 * the call to the provider that owns it.
 *
 * [itemKey] is the provider's own identifier - a Plex `ratingKey` - and is opaque here. Never send
 * this envelope to TMDB or an add-on: it names something only one server can answer for.
 */
data class MediaServerReference(
    val provider: String,
    val serverId: String,
    val itemKey: String,
) {
    fun encode(): String = PREFIX + listOf(provider, serverId, itemKey).joinToString(":") {
        Base64.getUrlEncoder().withoutPadding().encodeToString(it.toByteArray(Charsets.UTF_8))
    }

    /** Source attribution for a card: "mediaserver:plex:<server>", unique per server. */
    val sourceId: String get() = sourceIdOf(provider, serverId)

    companion object {
        const val PREFIX = "sd-media:"

        fun isReference(value: String?): Boolean = value?.startsWith(PREFIX) == true

        fun decode(value: String?): MediaServerReference? = runCatching {
            if (value == null || !value.startsWith(PREFIX)) return null
            val parts = value.removePrefix(PREFIX).split(':')
            if (parts.size != 3) return null
            val values = parts.map { String(Base64.getUrlDecoder().decode(it), Charsets.UTF_8) }
            if (values.any { it.isBlank() }) return null
            MediaServerReference(values[0], values[1], values[2])
        }.getOrNull()

        fun sourceIdOf(provider: String, serverId: String): String = "$SOURCE_PREFIX$provider:$serverId"

        /** The provider a card's `sourceAddonId` names, when the card came from a media server. */
        fun providerOfSource(sourceAddonId: String?): String? =
            sourceAddonId?.takeIf { it.startsWith(SOURCE_PREFIX) }?.removePrefix(SOURCE_PREFIX)?.substringBefore(':')
                ?.takeIf { it.isNotBlank() }

        const val SOURCE_PREFIX = "mediaserver:"
    }
}
