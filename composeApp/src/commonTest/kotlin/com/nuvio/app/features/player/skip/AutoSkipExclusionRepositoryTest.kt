package com.nuvio.app.features.player.skip

import kotlin.test.Test
import kotlin.test.assertEquals

class AutoSkipExclusionRepositoryTest {
    @Test
    fun chosenSegmentsSurviveSerializationAndUnknownValuesAreIgnored() {
        val shows = mapOf(
            "series:tt123" to setOf(AutoSkipSegmentType.OUTRO),
            "anime:456" to setOf(AutoSkipSegmentType.INTRO, AutoSkipSegmentType.RECAP),
        )

        assertEquals(shows, decodeAutoSkipExclusions(encodeAutoSkipExclusions(shows)))
        assertEquals(
            setOf(AutoSkipSegmentType.OUTRO),
            decodeAutoSkipExclusions("""{"series:tt123":["outro","future-type"]}""")["series:tt123"],
        )
        assertEquals("series:123", AutoSkipExclusionRepository.key("tv", "123"))
        assertEquals("anime:123", AutoSkipExclusionRepository.key("anime", "123"))
        val legacy = decodeAutoSkipExclusions("""{"tt123":["intro","recap"]}""")
        assertEquals(setOf(AutoSkipSegmentType.INTRO, AutoSkipSegmentType.RECAP), legacy.protectedTypesFor("series", "tt123"))
        assertEquals(setOf(AutoSkipSegmentType.INTRO, AutoSkipSegmentType.RECAP), legacy.protectedTypesFor("anime", "tt123"))
    }
}
