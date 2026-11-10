package com.nuvio.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.time.parseEpisodeReleaseLocalDate
import com.nuvio.app.core.time.parseZonedIsoDateTimeToEpochMs
import com.nuvio.app.core.ui.NuvioScreen
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.LocalScreenActive
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.notifications.isSeriesLibraryType
import com.nuvio.app.features.profiles.ProfileRepository
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.calendar_loading
import nuvio.composeapp.generated.resources.calendar_no_episodes
import nuvio.composeapp.generated.resources.calendar_no_series
import nuvio.composeapp.generated.resources.calendar_next_week
import nuvio.composeapp.generated.resources.calendar_previous_week
import nuvio.composeapp.generated.resources.calendar_this_week
import nuvio.composeapp.generated.resources.calendar_title
import org.jetbrains.compose.resources.stringResource

internal data class CalendarEpisode(
    val item: LibraryItem,
    val video: MetaVideo,
    val date: LocalDate,
    val time: LocalTime?,
)

private fun libraryKey(item: LibraryItem): String = "${item.type}:${item.id}"

private fun releaseTime(raw: String?, zone: ZoneId): LocalTime? {
    val value = raw?.trim()?.takeIf { 'T' in it } ?: return null
    return parseZonedIsoDateTimeToEpochMs(value)
        ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
        ?: runCatching { LocalDateTime.parse(value).toLocalTime() }.getOrNull()
}

internal fun calendarEpisodes(
    items: List<LibraryItem>,
    videosByItem: Map<String, List<MetaVideo>>,
    weekStart: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): Map<LocalDate, List<CalendarEpisode>> {
    val weekEnd = weekStart.plusDays(6)
    return items.flatMap { item ->
        videosByItem[libraryKey(item)].orEmpty().mapNotNull { video ->
            if (video.season == null && video.episode == null) return@mapNotNull null
            val date = parseEpisodeReleaseLocalDate(video.released)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: return@mapNotNull null
            if (date.isBefore(weekStart) || date.isAfter(weekEnd)) return@mapNotNull null
            val time = releaseTime(video.released, zone)
            CalendarEpisode(item, video, date, time)
        }
    }.groupBy(CalendarEpisode::date).mapValues { (_, episodes) ->
        episodes.sortedWith(compareBy<CalendarEpisode> { it.time ?: LocalTime.MAX }.thenBy { it.item.name })
    }
}

@Composable
internal actual fun CalendarTabScreen(
    modifier: Modifier,
    topChromePadding: Dp?,
    onPosterClick: ((LibraryItem) -> Unit)?,
) {
    val library by remember {
        LibraryRepository.ensureLoaded()
        LibraryRepository.uiState
    }.collectAsStateWithLifecycle()
    val profileId = ProfileRepository.activeProfileId
    val videosByItem = remember(profileId) { mutableStateMapOf<String, List<MetaVideo>>() }
    val series = remember(library.items) {
        library.items.filter { isSeriesLibraryType(it.type) || it.mediaCategory.equals("anime", ignoreCase = true) }
    }
    val seriesKeys = remember(series) { series.map(::libraryKey) }
    val active = LocalScreenActive.current
    var wasActive by remember(profileId) { mutableStateOf(false) }
    var loading by remember(profileId) { mutableStateOf(false) }
    var weekStartIso by rememberSaveable {
        mutableStateOf(LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString())
    }
    val weekStart = remember(weekStartIso) { LocalDate.parse(weekStartIso) }
    val days = remember(weekStart) { (0L..6L).map(weekStart::plusDays) }
    val episodes = calendarEpisodes(series, videosByItem, weekStart)
    val locale = Locale.getDefault()
    val dateFormatter = remember(locale) { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale) }
    val dayFormatter = remember(locale) { DateTimeFormatter.ofPattern("EEEE d", locale) }

    // shortcut: schedules refresh on tab activation, add periodic refresh if the calendar stays open for days.
    LaunchedEffect(active, profileId, seriesKeys) {
        if (!active) {
            wasActive = false
            return@LaunchedEffect
        }
        videosByItem.keys.toList().filterNot(seriesKeys::contains).forEach(videosByItem::remove)
        val pending = if (wasActive) series.filter { libraryKey(it) !in videosByItem } else series
        wasActive = true
        if (pending.isEmpty()) return@LaunchedEffect
        loading = true
        try {
            val semaphore = Semaphore(4)
            coroutineScope {
                pending.map { item ->
                    async {
                        val videos = semaphore.withPermit {
                            try {
                                MetaDetailsRepository.fetch(item.type, item.id, cacheResult = false, refresh = true)?.videos
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                null
                            }
                        }
                        if (videos != null) videosByItem[libraryKey(item)] = videos
                    }
                }.awaitAll()
            }
        } finally {
            loading = false
        }
    }

    NuvioScreen(modifier = modifier, topPadding = topChromePadding) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(Res.string.calendar_title), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                IconButton(onClick = { weekStartIso = weekStart.minusWeeks(1).toString() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(Res.string.calendar_previous_week))
                }
                Text(
                    "${weekStart.format(dateFormatter)} – ${weekStart.plusDays(6).format(dateFormatter)}",
                    style = MaterialTheme.typography.titleMedium,
                )
                IconButton(onClick = { weekStartIso = weekStart.plusWeeks(1).toString() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = stringResource(Res.string.calendar_next_week))
                }
                TextButton(onClick = {
                        weekStartIso = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
                    }) { Text(stringResource(Res.string.calendar_this_week)) }
            }
        }
        if (library.isLoaded && (series.isEmpty() || episodes.isEmpty() && !loading)) {
            item {
                NuvioSurfaceCard {
                    Text(
                        stringResource(if (series.isEmpty()) Res.string.calendar_no_series else Res.string.calendar_no_episodes),
                        color = MaterialTheme.nuvio.colors.textSecondary,
                    )
                }
            }
        }
        if (loading) {
            item { Text(stringResource(Res.string.calendar_loading), color = MaterialTheme.nuvio.colors.textSecondary) }
        }
        item {
            BoxWithConstraints {
                if (maxWidth < 1080.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        days.forEach { day -> CalendarDay(day, episodes[day].orEmpty(), dayFormatter, onPosterClick) }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        days.forEach { day ->
                            CalendarDay(
                                day = day,
                                episodes = episodes[day].orEmpty(),
                                formatter = dayFormatter,
                                onPosterClick = onPosterClick,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarDay(
    day: LocalDate,
    episodes: List<CalendarEpisode>,
    formatter: DateTimeFormatter,
    onPosterClick: ((LibraryItem) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            day.format(formatter),
            color = if (day == LocalDate.now()) tokens.colors.accent else tokens.colors.textPrimary,
            style = MaterialTheme.typography.titleSmall,
        )
        episodes.forEach { episode ->
            Surface(
                modifier = Modifier.fillMaxWidth().clickable { onPosterClick?.invoke(episode.item) },
                color = tokens.colors.surface,
                shape = tokens.shapes.card,
            ) {
                Row(modifier = Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AsyncImage(
                        model = episode.item.poster ?: episode.video.seasonPoster ?: episode.video.thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.width(46.dp).height(68.dp).clip(RoundedCornerShape(6.dp)),
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            episode.item.name,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        val code = listOfNotNull(
                            episode.video.season?.let { "S$it" },
                            episode.video.episode?.let { "E$it" },
                        ).joinToString(" ")
                        if (code.isNotEmpty()) Text(code, color = tokens.colors.textSecondary, style = MaterialTheme.typography.labelSmall)
                        episode.time?.let {
                            Text(it.format(DateTimeFormatter.ofPattern("HH:mm")), color = tokens.colors.textSecondary, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
