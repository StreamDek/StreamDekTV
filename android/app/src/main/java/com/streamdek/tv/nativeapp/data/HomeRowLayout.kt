package com.streamdek.tv.nativeapp.data

import androidx.annotation.StringRes
import com.streamdek.tv.R
import java.util.Locale

/**
 * Which Home rows this profile wants, and in what order — the television's half of a layout the
 * phone and the portal write to as well.
 *
 * The layout is stored per profile as [HomeCatalogRowPreference] and synced, so a row switched off
 * on the phone is off here too. Two things stopped that working on this side:
 *
 * The television only ever applied the layout to the built-in catalogue rows. Add-on rows — which
 * is nearly all of them for anyone running a catalogue add-on — were fetched and displayed whatever
 * the layout said, so switching one off on the phone did nothing here.
 *
 * And the two clients spell an add-on row's id with a different trailing number. Both use
 * `addon:<addonId>:<type>:<catalogId>:<index>`, but the phone counts catalogues within the add-on's
 * own manifest while the television counts them across every add-on at once. The same catalogue is
 * `…:trending:3` on one and `…:trending:17` on the other, so an exact-id comparison never matched
 * and the layout was quietly ignored. Matching on everything but that number is what makes one
 * saved arrangement mean the same thing on both.
 *
 * CloudStream providers' main-page rows share the add-on shape — `addon:cloudstream.<provider>:…`,
 * see [isCloudStreamHomeRowId] — with one difference: they are off unless switched on, where every
 * other row is on unless switched off. One collection can bring dozens of providers, each with
 * several rows, and showing all of them would bury Home.
 */

private val AddonRowIdPattern = Regex("""^addon:""", RegexOption.IGNORE_CASE)

/**
 * The part of a row id that names the catalogue rather than where it happened to sit in a list.
 *
 * Built-in ids have no positional part and are returned unchanged.
 */
internal fun homeCatalogRowMatchKey(id: String): String {
    val trimmed = id.trim()
    if (!AddonRowIdPattern.containsMatchIn(trimmed)) return trimmed
    val parts = trimmed.split(":")
    return if (parts.size >= 5) parts.take(4).joinToString(":") else trimmed
}

/** The add-on a row came from, or null when the row is a built-in one. */
internal fun homeCatalogRowAddonId(id: String): String? =
    if (AddonRowIdPattern.containsMatchIn(id.trim())) {
        id.trim().split(":").getOrNull(1)?.takeIf { it.isNotBlank() }
    } else {
        null
    }

/** One row the viewer can switch on or off, as offered by the settings screen. */
data class HomeRowOption(
    val id: String,
    val title: String,
    /**
     * The second line, as a resource rather than as text.
     *
     * This is a data-layer model and the line is read by a viewer, so holding the English here
     * would pin it to English on a translated television. The settings row resolves it, which also
     * means it re-reads when the language changes.
     */
    @StringRes val subtitleRes: Int,
    /** The one argument [subtitleRes] takes, where it takes one - the add-on's own name. */
    val subtitleArg: String? = null,
    val builtin: Boolean,
    val enabled: Boolean = true,
)

/**
 * Every row this profile could show, in the order it would show them.
 *
 * Built from the catalogue registry and the installed add-ons' own manifests rather than from a
 * home load, so opening the settings screen costs nothing and lists rows that are switched off —
 * which a list built from what Home actually fetched could never do.
 *
 * Add-on ids are numbered by the catalogue's position in its own add-on's manifest, matching how
 * the phone numbers them. The match key makes that agreement unnecessary, but writing the same
 * spelling keeps a layout saved here readable by an older phone build that still compares ids
 * exactly.
 *
 * @param cloudStreamRows the rows the CloudStream providers loaded on this television offer; see
 * [cloudStreamHomeRowOptions].
 */
internal fun homeRowOptions(
    definitions: List<CatalogDefinition>,
    addons: List<AddonManifest>,
    layout: List<HomeCatalogRowPreference>,
    cloudStreamRows: List<HomeRowOption> = emptyList(),
): List<HomeRowOption> {
    val builtins = definitions.map { definition ->
        HomeRowOption(
            id = definition.id,
            title = definition.title,
            subtitleRes = when (definition.mediaType) {
                "network" -> R.string.home_row_kind_networks
                "tv" -> R.string.home_row_kind_series
                else -> R.string.home_row_kind_films
            },
            builtin = true,
        )
    }
    // Every installed add-on, not only the switched-on ones. A switched-off add-on's rows are
    // still part of this profile's arrangement, and dropping them here would both hide the fact
    // that they are kept and renumber everything below them the next time the layout was saved.
    // The settings screen greys them; Home never sees them, because a switched-off add-on produces
    // no rails to apply the layout to.
    val addonRows = addons
        .sortedBy { it.position }
        .flatMap { addon ->
            addon.manifest.catalogs.mapIndexedNotNull { index, catalog ->
                val rawType = catalog.type.trim().lowercase(Locale.US)
                if (mapAddonCatalogType(rawType) == null) return@mapIndexedNotNull null
                HomeRowOption(
                    id = "addon:${addon.id}:$rawType:${catalog.id}:$index",
                    title = buildAddonRailTitle(addon.manifest.name, catalog.name ?: catalog.id),
                    subtitleRes = R.string.home_row_from_addon,
                    subtitleArg = addon.manifest.name.ifBlank { addon.id },
                    builtin = false,
                )
            }
        }

    return applyLayoutToOptions(builtins + addonRows + cloudStreamRows, layout)
}

/** Whether a row the layout says nothing about is on: every row is, except a CloudStream one. */
private fun onByDefault(id: String): Boolean = !isCloudStreamHomeRowId(id)

private fun applyLayoutToOptions(
    options: List<HomeRowOption>,
    layout: List<HomeCatalogRowPreference>,
): List<HomeRowOption> {
    val saved = layoutByMatchKey(layout)
    val withState = options.map { option ->
        option.copy(enabled = saved[homeCatalogRowMatchKey(option.id)]?.enabled ?: onByDefault(option.id))
    }
    if (saved.isEmpty()) return withState
    return withState.sortedBy { option ->
        saved[homeCatalogRowMatchKey(option.id)]?.position ?: Int.MAX_VALUE
    }
}

/**
 * The saved layout keyed the way rows are compared.
 *
 * Later entries lose to earlier ones on a collision, which only happens when a layout carries two
 * spellings of one catalogue — the earlier is the one the viewer arranged.
 */
private fun layoutByMatchKey(layout: List<HomeCatalogRowPreference>): Map<String, HomeCatalogRowPreference> {
    val byKey = LinkedHashMap<String, HomeCatalogRowPreference>()
    layout.filter { it.id.isNotBlank() }
        .sortedBy { it.position }
        .forEach { row -> byKey.putIfAbsent(homeCatalogRowMatchKey(row.id), row) }
    return byKey
}

/** The CloudStream rows the layout switches on, which are the only ones Home fetches. */
internal fun enabledCloudStreamRowIds(layout: List<HomeCatalogRowPreference>): List<String> =
    layout.filter { it.enabled && isCloudStreamHomeRowId(it.id) }.sortedBy { it.position }.map { it.id }

/**
 * Applies the layout to rows that have actually been fetched.
 *
 * A row the layout says nothing about is shown: a catalogue the viewer has never seen is new, not
 * unwanted, and hiding it would mean an add-on they just installed appeared to do nothing. An empty
 * layout leaves the rails exactly as they were, so a profile that has never arranged anything — or
 * one whose preferences have not loaded yet — is never a reason to hide a row. CloudStream rows are
 * the exception, off unless switched on, though Home only fetches the switched-on ones anyway.
 */
internal fun applyHomeRowLayout(
    rails: List<HomeRail>,
    layout: List<HomeCatalogRowPreference>,
): List<HomeRail> {
    val saved = layoutByMatchKey(layout)
    if (saved.isEmpty()) return rails
    val visible = rails.filter { rail ->
        saved[homeCatalogRowMatchKey(rail.id)]?.enabled ?: onByDefault(rail.id)
    }
    // Stable: rows the layout knows sit in its order, and anything it does not know keeps the order
    // the home assembly gave it, after them.
    return visible.sortedBy { rail -> saved[homeCatalogRowMatchKey(rail.id)]?.position ?: Int.MAX_VALUE }
}

/**
 * Keeps account-derived rows above the customisable catalogue layout.
 *
 * The saved layout only contains catalogue ids. Passing Continue Watching and New Episodes into
 * [applyHomeRowLayout] therefore classified them as unknown and moved them after every one of the
 * 28 known catalogue rows, making both look as though they had disappeared. They are not catalogue
 * preferences and must retain their fixed Home positions.
 */
internal fun applyHomeRowLayoutKeepingPersonalRows(
    rails: List<HomeRail>,
    layout: List<HomeCatalogRowPreference>,
): List<HomeRail> {
    val personalIds = listOf("continue-watching", "new-episodes")
    val personal = personalIds.mapNotNull { id -> rails.firstOrNull { it.id == id } }
    val catalogues = rails.filterNot { it.id in personalIds }
    return personal + applyHomeRowLayout(catalogues, layout)
}

/**
 * The arrived rows and the slots still resolving as one list, in the order Home draws them.
 *
 * Home is built from two lists — the rows that have landed and the slots reserved for the ones that
 * have not — and it used to draw the second after the whole of the first. A row reserved at the top
 * and resolved late therefore spent its loading life as a skeleton at the foot of the page and then
 * jumped to the top, shifting every row the viewer was already looking at down by a shelf. Continue
 * Watching does exactly that on nearly every cold start: it is reserved first and built from an
 * account read that is routinely slower than the catalogue requests beside it.
 *
 * Ordering both halves together puts a reserved slot where its row will actually be, so the row
 * replaces its own skeleton instead of being inserted above the page.
 *
 * Reserved slots are ordered as empty rails, which is what makes one saved layout govern arrived
 * and reserved rows alike — [applyHomeRowLayoutKeepingPersonalRows] pins a reserved Continue
 * Watching to the top for the same reason it pins the arrived one — rather than being
 * reimplemented for each half. Walking [slotOrder] first is what keeps a reserved slot in its
 * declared position for a profile with no saved layout, which the layout sort alone cannot order.
 *
 * @param orderRails the caller's own row ordering, applied to the two halves together.
 */
internal fun homeShelfOrder(
    slotOrder: List<String>,
    resolved: Map<String, List<HomeRail>>,
    pending: Map<String, PendingRail>,
    orderRails: (List<HomeRail>) -> List<HomeRail>,
): List<HomeShelfSlot> {
    fun bySlotOrder(keys: Collection<String>) = keys.sortedBy { slotOrder.indexOf(it) }
    fun arrived(key: String) = resolved[key]?.filter { it.items.isNotEmpty() }

    if (pending.isEmpty()) {
        return orderRails(bySlotOrder(resolved.keys).flatMap { arrived(it).orEmpty() })
            .map(HomeShelfSlot::Loaded)
    }
    val combined = bySlotOrder(resolved.keys + pending.keys).flatMap { key ->
        arrived(key)
            // A slot with nothing in it yet stands in as an empty rail carrying its own id, so the
            // ordering below places it exactly as it will place the row that replaces it.
            ?: pending[key]?.let { listOf(HomeRail(it.id, it.title, emptyList(), titleRes = it.titleRes)) }
            ?: emptyList()
    }
    return orderRails(combined).map { rail ->
        val reserved = if (rail.id in pending.keys) pending[rail.id] else null
        reserved?.let(HomeShelfSlot::Pending) ?: HomeShelfSlot.Loaded(rail)
    }
}

/** The layout to store for [options], numbered from their current order on screen. */
internal fun homeRowLayoutOf(options: List<HomeRowOption>): List<HomeCatalogRowPreference> =
    options.mapIndexed { index, option ->
        HomeCatalogRowPreference(
            id = option.id,
            enabled = option.enabled,
            position = index,
            title = option.title,
        )
    }

/**
 * The layout to store for [options], keeping everything [layout] holds that the list did not show.
 *
 * The list only offers rows from sources present on this television, and a CloudStream source
 * switched off here — or installed only on the phone — is not among them. Saving just what was
 * listed erased those rows from the synced layout, switching them off on every device the next time
 * anything was changed here. They are carried through, after the listed rows, as they were.
 */
internal fun homeRowLayoutKeepingUnlisted(
    options: List<HomeRowOption>,
    layout: List<HomeCatalogRowPreference>,
): List<HomeCatalogRowPreference> {
    val listed = homeRowLayoutOf(options)
    val listedKeys = listed.mapTo(mutableSetOf()) { homeCatalogRowMatchKey(it.id) }
    val unlisted = layout
        .filter { it.id.isNotBlank() && homeCatalogRowMatchKey(it.id) !in listedKeys }
        .sortedBy { it.position }
    return listed + unlisted.mapIndexed { index, row -> row.copy(position = listed.size + index) }
}

/** The key StreamDek's own rows group under; no add-on id can collide with it. */
internal const val STREAMDEK_ROW_GROUP_KEY = "__streamdek__"

/** One source's worth of rows in the settings list. */
internal data class HomeRowGroup(
    val key: String,
    val title: String,
    /** Why this group's rows cannot reach Home, as a resource, or null when they can. */
    @StringRes val gatedNoteRes: Int?,
    val rows: List<HomeRowOption>,
    /** Where the source itself comes from — "CloudStream · CNC Repo" for a CloudStream plugin. */
    val sourceLabel: String? = null,
)

/**
 * Splits the row list into one group per source, in the order the sources first appear in it.
 *
 * The same shape the phone shows, so a viewer moving between the two sees one arrangement rather
 * than two. Two things can switch a whole group off from elsewhere -- the built-in catalogue
 * setting for StreamDek's own rows, and an add-on's own switch for its rows -- and in both cases
 * the group is listed greyed rather than removed, so the rows are visibly kept.
 *
 * @param cloudStreamGroups the group each CloudStream provider's rows go in, keyed by the row-id
 * source, as a group key and title — the plugin that registered it; see [cloudStreamRowGroups].
 * @param cloudStreamLabels where each of those groups comes from, keyed by group key.
 */
internal fun buildHomeRowGroups(
    options: List<HomeRowOption>,
    addons: List<AddonManifest>,
    streamDekRowsEnabled: Boolean,
    /**
     * What to call an add-on whose manifest gives no name, already in the interface language.
     *
     * Passed in rather than resolved here: this function is pure and unit-tested, and giving it a
     * Context to look a string up with would be the only reason it needed one.
     */
    fallbackAddonName: String,
    cloudStreamGroups: Map<String, Pair<String, String>> = emptyMap(),
    cloudStreamLabels: Map<String, String> = emptyMap(),
): List<HomeRowGroup> {
    val addonsById = addons.associateBy { it.id }
    val cloudStreamGroupTitles = cloudStreamGroups.values.associate { (key, title) -> key to title }
    return options
        .groupBy { option ->
            val addonId = if (option.builtin) null else homeCatalogRowAddonId(option.id)
            when {
                addonId == null -> STREAMDEK_ROW_GROUP_KEY
                isCloudStreamHomeRowId(option.id) -> cloudStreamGroups[addonId]?.first ?: addonId
                else -> addonId
            }
        }
        .map { (key, rows) ->
            val addon = addonsById[key]
            val title = if (key == STREAMDEK_ROW_GROUP_KEY) {
                "StreamDek"
            } else {
                cloudStreamGroupTitles[key]?.trim()?.takeIf { it.isNotEmpty() }
                    ?: addon?.manifest?.name?.trim()?.takeIf { it.isNotEmpty() }
                    // The add-on's own name, read from the row that carries it. This used to strip
                    // "From " off the front of the subtitle, which recovered the right answer only
                    // for as long as that subtitle was English.
                    ?: rows.firstNotNullOfOrNull { row -> row.subtitleArg?.trim()?.takeIf { it.isNotEmpty() } }
                    ?: fallbackAddonName
            }
            val gatedNoteRes = when {
                key == STREAMDEK_ROW_GROUP_KEY && !streamDekRowsEnabled -> R.string.home_row_group_hidden_builtin
                addon != null && !addon.enabled -> R.string.home_row_group_hidden_addon_off
                else -> null
            }
            HomeRowGroup(key = key, title = title, gatedNoteRes = gatedNoteRes, rows = rows, sourceLabel = cloudStreamLabels[key])
        }
}
