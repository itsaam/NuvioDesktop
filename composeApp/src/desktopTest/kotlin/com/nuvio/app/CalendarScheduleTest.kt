package com.nuvio.app

import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.library.LibraryItem
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CalendarScheduleTest {
    @Test
    fun showsOnlyEpisodesInSelectedWeekAndDoesNotInventAirtimes() {
        val item = LibraryItem(id = "tt123", type = "series", name = "Example", savedAtEpochMs = 1L)
        val episodes = calendarEpisodes(
            items = listOf(item),
            videosByItem = mapOf("series:tt123" to listOf(
                MetaVideo(id = "1", title = "First", released = "2026-11-10", season = 1, episode = 1),
                MetaVideo(id = "2", title = "Second", released = "2026-11-11T20:30:00", season = 1, episode = 2),
                MetaVideo(id = "3", title = "Later", released = "2026-11-17", season = 1, episode = 3),
                MetaVideo(id = "4", title = "Uncertain", released = "2026-11-12Tunknown", season = 1, episode = 4),
            )),
            rangeStart = LocalDate.of(2026, 11, 9),
            rangeEnd = LocalDate.of(2026, 11, 15),
        )

        assertEquals(listOf("1"), episodes[LocalDate.of(2026, 11, 10)]?.map { it.video.id })
        assertNull(episodes[LocalDate.of(2026, 11, 10)]?.single()?.time)
        assertEquals(20, episodes[LocalDate.of(2026, 11, 11)]?.single()?.time?.hour)
        assertNull(episodes[LocalDate.of(2026, 11, 17)])
        assertNull(episodes[LocalDate.of(2026, 11, 12)]?.single()?.time)
    }

    @Test
    fun reusesRecentMetadataOnReturnAndFetchesNewOrStaleShows() {
        val keys = listOf("series:first", "series:second")

        assertEquals(listOf("series:second"), calendarKeysToFetch(keys, setOf("series:first"), mapOf("series:first" to 1_000L), emptyMap(), 2_000L))
        assertEquals(keys, calendarKeysToFetch(keys, setOf("series:first"), mapOf("series:first" to 1_000L), emptyMap(), 1_802_000L))
        assertEquals(listOf("series:second"), calendarKeysToFetch(keys, emptySet(), emptyMap(), mapOf("series:first" to 1_000L), 2_000L))
        assertEquals(keys, calendarKeysToFetch(keys, emptySet(), emptyMap(), mapOf("series:first" to 1_000L), 302_000L))
    }

    @Test
    fun currentWeekAdvancesOnMondayButManualNavigationIsPreserved() {
        val sunday = LocalDate.of(2026, 11, 15)
        val monday = sunday.plusDays(1)
        val oldWeek = LocalDate.of(2026, 11, 9)

        assertEquals(monday, weekAfterDateChange(oldWeek, sunday, monday))
        assertEquals(oldWeek.minusWeeks(1), weekAfterDateChange(oldWeek.minusWeeks(1), sunday, monday))
    }
}
