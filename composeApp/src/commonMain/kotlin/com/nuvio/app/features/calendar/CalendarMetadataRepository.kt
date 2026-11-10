package com.nuvio.app.features.calendar

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaDetailsParser
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.addons.fetchAddonResponseText
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.notifications.isSeriesLibraryType
import com.nuvio.app.features.watchprogress.WatchProgressClock
import com.nuvio.app.core.time.parseEpisodeReleaseLocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val RefreshIntervalMs = 30L * 60L * 1_000L
private const val RetryIntervalMs = 5L * 60L * 1_000L
private const val CinemetaBaseUrl = "https://v3-cinemeta.strem.io"
private val ImdbId = Regex("^tt\\d+$")

internal fun calendarLibraryKey(item: LibraryItem): String = "${item.type}:${item.id}"

internal fun cinemetaFallbackUrl(item: LibraryItem): String? =
    if ((isSeriesLibraryType(item.type) || item.type.equals("anime", ignoreCase = true)) && ImdbId.matches(item.id)) {
        "$CinemetaBaseUrl/meta/series/${item.id}.json"
    } else null

internal fun List<MetaVideo>.hasCalendarDates(): Boolean = any { video ->
    (video.season != null || video.episode != null) && parseEpisodeReleaseLocalDate(video.released) != null
}

internal fun calendarKeysToFetch(
    seriesKeys: List<String>,
    cachedKeys: Set<String>,
    fetchedAt: Map<String, Long>,
    failedAt: Map<String, Long>,
    nowEpochMs: Long,
): List<String> = seriesKeys.filter { key ->
    val age = fetchedAt[key]?.let { nowEpochMs - it }
    val retryAge = failedAt[key]?.let { nowEpochMs - it }
    val fresh = key in cachedKeys && age != null && age in 0 until RefreshIntervalMs
    val waitingToRetry = retryAge != null && retryAge in 0 until RetryIntervalMs
    !fresh && !waitingToRetry
}

internal class CalendarMetadataState {
    val videos: SnapshotStateMap<String, List<MetaVideo>> = mutableStateMapOf()
    val failedAt: SnapshotStateMap<String, Long> = mutableStateMapOf()
    val fetchedAt = mutableMapOf<String, Long>()
    var loading by mutableStateOf(false)
    var invalidated = false
        private set

    fun clear() {
        invalidated = true
        videos.clear()
        failedAt.clear()
        fetchedAt.clear()
        loading = false
    }
}

internal object CalendarMetadataRepository {
    private val states = mutableMapOf<String, CalendarMetadataState>()

    fun state(profileKey: String): CalendarMetadataState =
        states.getOrPut(profileKey) { CalendarMetadataState() }

    fun clearLocalState() {
        states.values.forEach(CalendarMetadataState::clear)
        states.clear()
    }

    suspend fun refresh(state: CalendarMetadataState, series: List<LibraryItem>) {
        if (state.invalidated) return
        val keys = series.map(::calendarLibraryKey)
        state.videos.keys.toList().filterNot(keys::contains).forEach(state.videos::remove)
        state.fetchedAt.keys.toList().filterNot(keys::contains).forEach(state.fetchedAt::remove)
        state.failedAt.keys.toList().filterNot(keys::contains).forEach(state.failedAt::remove)
        val pending = calendarKeysToFetch(
            keys, state.videos.keys, state.fetchedAt, state.failedAt, WatchProgressClock.nowEpochMs(),
        ).toSet()
        if (pending.isEmpty()) return
        state.loading = true
        try {
            val semaphore = Semaphore(4)
            coroutineScope {
                series.filter { calendarLibraryKey(it) in pending }.map { item ->
                    async {
                        val key = calendarLibraryKey(item)
                        val videos = semaphore.withPermit { fetchVideos(item) }
                        val now = WatchProgressClock.nowEpochMs()
                        if (state.invalidated) return@async
                        if (videos != null) {
                            state.fetchedAt[key] = now
                            state.failedAt.remove(key)
                            state.videos[key] = videos
                        } else {
                            state.failedAt[key] = now
                        }
                    }
                }.awaitAll()
            }
        } finally {
            state.loading = false
        }
    }

    private suspend fun fetchVideos(item: LibraryItem): List<MetaVideo>? {
        val primary = fetchPrimaryVideos(item)
        if (primary?.hasCalendarDates() == true) return primary
        val fallbackUrl = cinemetaFallbackUrl(item) ?: return primary

        return try {
            withTimeoutOrNull(5_000L) {
                MetaDetailsParser.parse(fetchAddonResponseText(fallbackUrl), useCinemetaAirDates = true).videos
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun fetchPrimaryVideos(item: LibraryItem): List<MetaVideo>? = try {
        // Calendar freshness is independent of the detail screen's unbounded metadata cache.
        MetaDetailsRepository.fetch(item.type, item.id, cacheResult = false, refresh = true)?.videos
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }
}
