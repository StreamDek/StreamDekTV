package com.streamdek.tv.nativeapp.data

const val DEFAULT_FUSION_BADGE_URL = "https://pastebin.com/raw/5xiu5fLL"
const val MAX_FUSION_BADGE_URLS = 3

const val FUSION_BADGE_LANGUAGE_GROUP_ID = "gl"

private val SPECIAL_FUSION_BADGE_GROUP = FusionBadgeGroup(id = "", name = "Special")

private val fusionBadgePatternCache = HashMap<String, Regex?>()

data class FusionBadgeGroupMatches(
    val group: FusionBadgeGroup,
    val badges: List<FusionBadgeFilter>,
)

// Filter patterns are PCRE-style strings prefixed with `(?i)` for case-insensitive
// matching. Kotlin's Regex doesn't support inline `(?i)`, so it's stripped and replaced
// with RegexOption.IGNORE_CASE. Some patterns use lookbehind assertions the engine may
// reject — those filters are skipped rather than crashing the match pass.
private fun compileFusionBadgePattern(pattern: String): Regex? = try {
    if (pattern.startsWith("(?i)")) {
        Regex(pattern.substring(4), RegexOption.IGNORE_CASE)
    } else {
        Regex(pattern, RegexOption.IGNORE_CASE)
    }
} catch (e: Exception) {
    null
}

private fun getCompiledFusionBadgePattern(pattern: String): Regex? =
    fusionBadgePatternCache.getOrPut(pattern) { compileFusionBadgePattern(pattern) }

private fun fusionBadgeStreamSearchText(stream: AddonStream): String =
    listOfNotNull(stream.name, stream.title, stream.behaviorHints?.filename).joinToString(" ")

/** Match a stream's metadata against one or more Fusion badge sources, grouped in source group order. */
fun matchFusionBadges(stream: AddonStream, sources: List<FusionBadgeSource>): List<FusionBadgeGroupMatches> {
    val text = fusionBadgeStreamSearchText(stream)
    if (text.isBlank() || sources.isEmpty()) return emptyList()

    val groupOrder = mutableListOf<FusionBadgeGroup>()
    val groupMap = LinkedHashMap<String, FusionBadgeGroup>()
    val matchesByGroup = LinkedHashMap<String, MutableList<FusionBadgeFilter>>()
    val seenKeys = HashSet<String>()

    for (source in sources) {
        for (group in source.groups) {
            if (!groupMap.containsKey(group.id)) {
                groupMap[group.id] = group
                groupOrder.add(group)
            }
        }
    }

    for (source in sources) {
        for (filter in source.filters) {
            if (filter.isEnabled == false) continue
            val key = filter.imageURL.ifBlank { "${filter.groupId}:${filter.id}" }
            if (key in seenKeys) continue

            val regex = getCompiledFusionBadgePattern(filter.pattern) ?: continue
            if (!regex.containsMatchIn(text)) continue
            seenKeys.add(key)

            if (!groupMap.containsKey(filter.groupId)) {
                val newGroup = if (filter.groupId.isNotBlank()) {
                    FusionBadgeGroup(id = filter.groupId, name = filter.groupId)
                } else {
                    SPECIAL_FUSION_BADGE_GROUP
                }
                groupMap[filter.groupId] = newGroup
                groupOrder.add(newGroup)
            }

            matchesByGroup.getOrPut(filter.groupId) { mutableListOf() }.add(filter)
        }
    }

    return groupOrder
        .map { group -> FusionBadgeGroupMatches(group, matchesByGroup[group.id].orEmpty()) }
        .filter { it.badges.isNotEmpty() }
}

/** Flatten grouped matches into a single ordered list of badges for row rendering. */
fun flattenFusionBadges(groups: List<FusionBadgeGroupMatches>): List<FusionBadgeFilter> =
    groups.flatMap { it.badges }

fun countEnabledFilters(source: FusionBadgeSource): Int =
    source.filters.count { it.isEnabled != false }

fun countGroupsWithFilters(source: FusionBadgeSource): Int =
    source.filters.map { it.groupId }.distinct().size

fun groupSourceFilters(source: FusionBadgeSource): List<FusionBadgeGroupMatches> {
    val groupOrder = mutableListOf<FusionBadgeGroup>()
    val groupMap = LinkedHashMap<String, FusionBadgeGroup>()
    val byGroup = LinkedHashMap<String, MutableList<FusionBadgeFilter>>()

    for (group in source.groups) {
        if (!groupMap.containsKey(group.id)) {
            groupMap[group.id] = group
            groupOrder.add(group)
        }
    }

    for (filter in source.filters) {
        if (!groupMap.containsKey(filter.groupId)) {
            val newGroup = if (filter.groupId.isNotBlank()) {
                FusionBadgeGroup(id = filter.groupId, name = filter.groupId)
            } else {
                SPECIAL_FUSION_BADGE_GROUP
            }
            groupMap[filter.groupId] = newGroup
            groupOrder.add(newGroup)
        }
        byGroup.getOrPut(filter.groupId) { mutableListOf() }.add(filter)
    }

    return groupOrder
        .map { group -> FusionBadgeGroupMatches(group, byGroup[group.id].orEmpty()) }
        .filter { it.badges.isNotEmpty() }
}
