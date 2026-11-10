package com.nuvio.app.features.calendar

import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.details.MetaVideo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CalendarMetadataRepositoryTest {
    @Test
    fun fallbackOnlyUsesValidatedSeriesImdbIds() {
        val saved = LibraryItem(id = "tt123", type = "series", name = "Example", savedAtEpochMs = 1L,
            addonBaseUrl = null)

        assertEquals("https://v3-cinemeta.strem.io/meta/series/tt123.json", cinemetaFallbackUrl(saved))
        assertEquals("https://v3-cinemeta.strem.io/meta/series/tt123.json", cinemetaFallbackUrl(saved.copy(type = "anime")))
        assertNull(cinemetaFallbackUrl(saved.copy(type = "movie")))
        assertNull(cinemetaFallbackUrl(saved.copy(id = "tt123/../other")))
        assertNull(cinemetaFallbackUrl(saved.copy(id = "tmdb:123")))
    }

    @Test
    fun calendarDatesRequireAValidEpisodeDate() {
        val undated = MetaVideo(id = "tt123:1:1", title = "Episode", season = 1, episode = 1)
        assertFalse(listOf(undated).hasCalendarDates())
        assertFalse(listOf(undated.copy(released = "unknown")).hasCalendarDates())
        assertTrue(listOf(undated.copy(released = "2026-01-18")).hasCalendarDates())
    }
}
