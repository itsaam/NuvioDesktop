package com.nuvio.app.features.shuffle

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.nuvio.app.core.ui.NuvioTheme
import com.nuvio.app.features.details.MetaDetails
import com.nuvio.app.features.details.MetaVideo
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.random_episode_title
import nuvio.composeapp.generated.resources.shuffle_this_season
import nuvio.composeapp.generated.resources.shuffle_stop
import org.junit.Rule
import org.jetbrains.compose.resources.stringResource
import kotlin.test.Test

class EpisodeShuffleSheetDesktopTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun thisSeasonOnlyPreviewsAnEpisodeFromTheSelectedSeason() {
        val details = MetaDetails("show", "series", "Show", videos = listOf(
            MetaVideo("show:1:1", "First season episode", season = 1, episode = 1),
            MetaVideo("show:2:1", "Second season episode", season = 2, episode = 1),
        ))
        val labels = show(details, currentSeason = 2)

        compose.onNodeWithText(labels.thisSeason).performClick()
        compose.onNodeWithText("Second season episode").assertIsDisplayed()
    }

    @Test
    fun activeShuffleCanBeStoppedWithoutEpisodeMetadata() {
        val labels = show(MetaDetails("show", "series", "Show"), currentSeason = null,
            settings = EpisodeShuffleSettings(enabled = true))

        compose.onNodeWithText(labels.stop).assertIsDisplayed()
    }

    private data class Labels(val title: String, val thisSeason: String, val stop: String)

    private fun show(
        details: MetaDetails,
        currentSeason: Int?,
        settings: EpisodeShuffleSettings = EpisodeShuffleSettings(),
    ): Labels {
        lateinit var labels: Labels
        compose.setContent {
            NuvioTheme {
                labels = Labels(stringResource(Res.string.random_episode_title),
                    stringResource(Res.string.shuffle_this_season), stringResource(Res.string.shuffle_stop))
                EpisodeShuffleSheet(details, currentSeason, settings, { true }, emptySet(), emptyList(), false,
                    onDismiss = {}, onPlay = {}, onPlayManually = null, onStartFromBeginning = {})
            }
        }
        compose.waitForIdle()
        compose.waitUntil(5_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText(labels.title)).fetchSemanticsNodes().isNotEmpty()
        }
        return labels
    }
}
