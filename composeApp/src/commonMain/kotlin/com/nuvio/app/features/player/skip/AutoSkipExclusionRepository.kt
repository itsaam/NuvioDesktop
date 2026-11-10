package com.nuvio.app.features.player.skip

import co.touchlab.kermit.Logger
import com.nuvio.app.features.notifications.normalizeSeriesType
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.isDesktop
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val autoSkipExclusionJson = Json { ignoreUnknownKeys = true }

internal fun encodeAutoSkipExclusions(shows: Map<String, Set<AutoSkipSegmentType>>): String =
    autoSkipExclusionJson.encodeToString(
        shows.mapValues { (_, types) -> types.map { it.storedValue }.sorted() },
    )

internal fun decodeAutoSkipExclusions(payload: String): Map<String, Set<AutoSkipSegmentType>> =
    autoSkipExclusionJson.decodeFromString<Map<String, List<String>>>(payload).mapValues { (_, stored) ->
        stored.mapNotNull { AutoSkipSegmentType.fromStoredValue(it) }.toSet()
    }

internal fun Map<String, Set<AutoSkipSegmentType>>.protectedTypesFor(type: String, id: String): Set<AutoSkipSegmentType> {
    val key = AutoSkipExclusionRepository.key(type, id) ?: return emptySet()
    // Legacy Dev builds stored bare IDs before series types were added to the key.
    return this[key] ?: this[id.trim()].orEmpty()
}

internal object AutoSkipExclusionRepository {
    private val log = Logger.withTag("AutoSkipExclusionRepository")
    private var loadedProfileId: Int? = null
    private val state = MutableStateFlow<Map<String, Set<AutoSkipSegmentType>>>(emptyMap())
    val protectedByShow = state.asStateFlow()

    fun isEligible(type: String): Boolean = normalizeSeriesType(type).let { it == "series" || it == "anime" }

    fun key(type: String, id: String): String? =
        if (isEligible(type) && id.isNotBlank()) "${normalizeSeriesType(type)}:${id.trim()}" else null

    fun ensureLoaded() {
        if (loadedProfileId != ProfileRepository.activeProfileId) onProfileChanged()
    }

    fun onProfileChanged() {
        loadedProfileId = ProfileRepository.activeProfileId
        state.value = readProfile(ProfileRepository.activeProfileId)
    }

    fun protectedTypes(profileId: Int, type: String, id: String): Set<AutoSkipSegmentType> {
        if (!isDesktop) return emptySet()
        if (key(type, id) == null) return emptySet()
        val shows = if (loadedProfileId == profileId) state.value else readProfile(profileId)
        return shows.protectedTypesFor(type, id)
    }

    fun save(type: String, id: String, protectedTypes: Set<AutoSkipSegmentType>): Boolean {
        if (!isDesktop) return false
        val key = key(type, id) ?: return false
        ensureLoaded()
        val current = state.value - id.trim()
        val next = if (protectedTypes.isEmpty()) current - key
            else current + (key to protectedTypes)
        return runCatching {
            AutoSkipExclusionStorage.save(ProfileRepository.activeProfileId, encodeAutoSkipExclusions(next))
            state.value = next
        }.onFailure { log.e(it) { "Failed to save auto-skip exclusion" } }.isSuccess
    }

    fun removeProfile(profileId: Int) {
        runCatching { AutoSkipExclusionStorage.remove(profileId) }
            .onFailure { log.e(it) { "Failed to remove auto-skip exclusions for profile $profileId" } }
        if (loadedProfileId == profileId) state.value = emptyMap()
    }

    fun clearLocalState() {
        loadedProfileId = null
        state.value = emptyMap()
    }

    private fun readProfile(profileId: Int): Map<String, Set<AutoSkipSegmentType>> = runCatching {
        val payload = AutoSkipExclusionStorage.load(profileId) ?: return@runCatching emptyMap()
        decodeAutoSkipExclusions(payload)
    }.getOrElse {
        log.e(it) { "Failed to load auto-skip exclusions for profile $profileId" }
        emptyMap()
    }
}

internal expect object AutoSkipExclusionStorage {
    fun load(profileId: Int): String?
    fun save(profileId: Int, payload: String)
    fun remove(profileId: Int)
}
