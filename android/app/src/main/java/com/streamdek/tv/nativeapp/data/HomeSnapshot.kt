package com.streamdek.tv.nativeapp.data

import com.google.gson.Gson
import java.io.File
import java.security.MessageDigest

/*
 * Two things that let Home open once and stay put.
 *
 * The first is a copy of the last Home this profile finished loading, kept on disk. A cold start
 * has no memory cache, so without it the first thing a returning viewer saw was always a skeleton
 * and then rows arriving. With it the page they left is there at once and the fresh read replaces
 * it in one step: cached, then fresh, with no empty state between.
 *
 * The second keeps a refresh from disturbing rows that did not change. A fresh read builds every
 * row again, so a row that is card-for-card what it was still arrived as a new object and was
 * drawn again. Handing back the row that is already on screen wherever nothing differs means only
 * what actually changed is redrawn.
 */

/**
 * [this], with every row that equals the one in [previous] replaced by that very object - and
 * [previous] itself when nothing at all differs. The result always equals [this].
 */
internal fun HomeContent.sharingRowsWith(previous: HomeContent?): HomeContent {
    if (previous == null) return this
    if (this == previous) return previous
    val previousRails = previous.rails.associateBy { it.id }
    val previousSlots = previous.shelves.associateBy { it.id }
    var reused = 0
    val sharedRails = rails.map { rail ->
        val before = previousRails[rail.id]
        if (before != null && before == rail) { reused++; before } else rail
    }
    if (reused == 0) return this
    val sharedById = sharedRails.associateBy { it.id }
    val sharedShelves = shelves.map { slot ->
        val before = previousSlots[slot.id]
        when {
            before != null && before == slot -> before
            slot is HomeShelfSlot.Loaded -> HomeShelfSlot.Loaded(sharedById[slot.id] ?: slot.rail)
            else -> slot
        }
    }
    return copy(rails = sharedRails, shelves = sharedShelves)
}

/** What is written to disk: the finished page, cut down to what the first screens of it need. */
internal data class HomeSnapshot(
    val format: Int = FORMAT,
    /** Row titles are resource ids, which are only good for the build that wrote them. */
    val appVersionCode: Int = 0,
    val key: String = "",
    val savedAtMs: Long = 0L,
    val featured: MediaItem? = null,
    val rails: List<HomeSnapshotRail>? = null,
) {
    companion object {
        const val FORMAT = 1
    }
}

/**
 * One saved row. A card's subtitle and highlight are worked out when the row is built and are not
 * part of what a card serialises as, so they are kept beside the row, one per card, and put back.
 */
internal data class HomeSnapshotRail(
    val rail: HomeRail? = null,
    val subtitles: List<String?>? = null,
    val highlights: List<Boolean>? = null,
)

/**
 * Whether a card may be written to disk. A live channel or a direct stream carries the address it
 * plays from, and sometimes the headers or keys that unlock it; those stay in memory only.
 */
internal fun MediaItem.mayBeSavedInHomeSnapshot(): Boolean =
    type != "live" && directStreamUrl.isNullOrBlank() && requestHeaders.isEmpty() && drmClearKeys.isNullOrEmpty()

/**
 * The last finished Home per profile, on disk.
 *
 * Deliberately modest: a handful of profiles, a bounded number of cards per row, and anything it
 * cannot vouch for - another build's copy, an old one, a file that does not parse - is treated as
 * not being there. Reads and writes are blocking; callers run them off the main thread.
 */
internal class HomeSnapshotStore(
    private val directory: File,
    private val appVersionCode: Int,
    private val now: () -> Long = System::currentTimeMillis,
    private val gson: Gson = Gson(),
) {
    @Volatile private var lastWritten: Pair<String, Int>? = null

    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8))
        return File(directory, digest.joinToString("") { "%02x".format(it) } + ".json")
    }

    fun read(key: String): HomeContent? = runCatching {
        val file = fileFor(key).takeIf { it.isFile } ?: return null
        val snapshot = gson.fromJson(file.readText(Charsets.UTF_8), HomeSnapshot::class.java) ?: return null
        if (snapshot.format != HomeSnapshot.FORMAT || snapshot.appVersionCode != appVersionCode || snapshot.key != key) return null
        if (now() - snapshot.savedAtMs !in 0..MAX_AGE_MS) return null
        val rails = snapshot.rails.orEmpty().mapNotNull { saved ->
            val rail = saved.rail?.takeIf { it.isWellFormed() && it.items.isNotEmpty() } ?: return@mapNotNull null
            rail.copy(
                items = rail.items.mapIndexed { index, item ->
                    item.copy(
                        cardSubtitle = saved.subtitles?.getOrNull(index),
                        cardHighlight = saved.highlights?.getOrNull(index) == true,
                    )
                },
            )
        }
        if (rails.isEmpty()) return null
        HomeContent(featured = snapshot.featured?.takeIf { it.isWellFormed() }, rails = rails)
    }.getOrNull()

    fun write(key: String, content: HomeContent) {
        if (!content.isComplete || content.rails.isEmpty()) return
        val trimmed = content.rails.asSequence()
            .filterNot { it.isLive }
            .map { rail ->
                rail.copy(
                    items = rail.items.filter { it.mayBeSavedInHomeSnapshot() }.take(MAX_CARDS_PER_ROW),
                    previewItems = rail.previewItems.filter { it.mayBeSavedInHomeSnapshot() }.take(MAX_PREVIEW_CARDS),
                )
            }
            .filter { it.items.isNotEmpty() }
            .take(MAX_ROWS)
            .toList()
        if (trimmed.isEmpty()) return
        val featured = content.featured?.takeIf { it.mayBeSavedInHomeSnapshot() }
        // The page is re-read every few seconds while Home is open and is almost always the same.
        val fingerprint = key to (31 * trimmed.hashCode() + (featured?.hashCode() ?: 0))
        if (fingerprint == lastWritten) return
        runCatching {
            directory.mkdirs()
            val target = fileFor(key)
            val staging = File(directory, target.name + ".tmp")
            staging.writeText(gson.toJson(HomeSnapshot(appVersionCode = appVersionCode, key = key, savedAtMs = now(), featured = featured, rails = trimmed.map(::savedRail))), Charsets.UTF_8)
            // Written beside and moved into place, so a process killed mid-write leaves the old copy.
            if (!staging.renameTo(target)) {
                target.delete()
                staging.renameTo(target)
            }
            lastWritten = fingerprint
            directory.listFiles { file -> file.name.endsWith(".json") }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_PROFILES)
                ?.forEach { it.delete() }
        }
    }

    private fun savedRail(rail: HomeRail) = HomeSnapshotRail(
        rail = rail,
        subtitles = rail.items.map { it.cardSubtitle },
        highlights = rail.items.map { it.cardHighlight },
    )

    /** Signing out: nothing a previous account watched stays on the device. */
    fun clear() {
        lastWritten = null
        runCatching { directory.listFiles()?.forEach { it.delete() } }
    }

    // Gson fills what the file says and nothing else, so a field the type calls non-null can still
    // come back null from a file this build did not write. Checked here rather than trusted.
    @Suppress("SENSELESS_COMPARISON", "UNNECESSARY_SAFE_CALL")
    private fun HomeRail.isWellFormed(): Boolean =
        id != null && title != null && items != null && previewItems != null && items.all { it != null && it.isWellFormed() } && previewItems.all { it != null && it.isWellFormed() }

    @Suppress("SENSELESS_COMPARISON")
    private fun MediaItem.isWellFormed(): Boolean = id != null && title != null && type != null

    private companion object {
        const val MAX_ROWS = 24
        const val MAX_CARDS_PER_ROW = 20
        const val MAX_PREVIEW_CARDS = 8
        const val MAX_PROFILES = 6
        const val MAX_AGE_MS = 14L * 24 * 60 * 60 * 1000
    }
}
