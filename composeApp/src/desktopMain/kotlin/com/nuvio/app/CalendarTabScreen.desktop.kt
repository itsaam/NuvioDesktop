package com.nuvio.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.nuvio.app.core.auth.AuthRepository
import com.nuvio.app.core.auth.AuthState
import com.nuvio.app.core.time.parseEpisodeReleaseLocalDate
import com.nuvio.app.core.time.parseZonedIsoDateTimeToEpochMs
import com.nuvio.app.core.ui.NuvioSurfaceCard
import com.nuvio.app.core.ui.LocalScreenActive
import com.nuvio.app.core.ui.nuvio
import com.nuvio.app.features.details.MetaDetailsRepository
import com.nuvio.app.features.details.MetaVideo
import com.nuvio.app.features.library.LibraryItem
import com.nuvio.app.features.library.LibraryRepository
import com.nuvio.app.features.notifications.isSeriesLibraryType
import com.nuvio.app.features.profiles.ProfileRepository
import com.nuvio.app.features.settings.AppLanguage
import com.nuvio.app.features.settings.ThemeSettingsRepository
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import nuvio.composeapp.generated.resources.Res
import nuvio.composeapp.generated.resources.calendar_no_episodes
import nuvio.composeapp.generated.resources.calendar_fetch_failed
import nuvio.composeapp.generated.resources.calendar_no_series
import nuvio.composeapp.generated.resources.calendar_next_week
import nuvio.composeapp.generated.resources.calendar_next_month
import nuvio.composeapp.generated.resources.calendar_previous_month
import nuvio.composeapp.generated.resources.calendar_previous_week
import nuvio.composeapp.generated.resources.calendar_view_month
import nuvio.composeapp.generated.resources.calendar_view_week
import nuvio.composeapp.generated.resources.calendar_no_episodes_month
import nuvio.composeapp.generated.resources.calendar_this_week
import nuvio.composeapp.generated.resources.calendar_today
import nuvio.composeapp.generated.resources.calendar_title
import org.jetbrains.compose.resources.stringResource

internal data class CalendarEpisode(
    val item: LibraryItem,
    val video: MetaVideo,
    val date: LocalDate,
    val time: LocalTime?,
)

private fun libraryKey(item: LibraryItem): String = "${item.type}:${item.id}"

private const val CalendarRefreshIntervalMs = 30L * 60L * 1_000L
private const val CalendarRetryIntervalMs = 5L * 60L * 1_000L

private object CalendarMetadataCache {
    private val videosByProfile = mutableMapOf<String, SnapshotStateMap<String, List<MetaVideo>>>()
    private val fetchedAtByProfile = mutableMapOf<String, MutableMap<String, Long>>()
    private val failedAtByProfile = mutableMapOf<String, MutableMap<String, Long>>()

    fun videos(profile: String): SnapshotStateMap<String, List<MetaVideo>> =
        videosByProfile.getOrPut(profile) { mutableStateMapOf() }

    fun fetchedAt(profile: String): MutableMap<String, Long> = fetchedAtByProfile.getOrPut(profile) { mutableMapOf() }

    fun failedAt(profile: String): MutableMap<String, Long> = failedAtByProfile.getOrPut(profile) { mutableMapOf() }
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
    val fresh = key in cachedKeys && age != null && age in 0 until CalendarRefreshIntervalMs
    val waitingToRetry = retryAge != null && retryAge in 0 until CalendarRetryIntervalMs
    !fresh && !waitingToRetry
}

internal fun weekAfterDateChange(displayedWeek: LocalDate, previousDate: LocalDate, currentDate: LocalDate): LocalDate =
    if (displayedWeek == previousDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))) {
        currentDate.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    } else {
        displayedWeek
    }

internal fun calendarDaysForMonth(month: LocalDate): List<LocalDate> {
    val first = month.withDayOfMonth(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val last = month.withDayOfMonth(1).plusMonths(1).minusDays(1)
        .with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
    return generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.toList()
}

private fun releaseTime(raw: String?, zone: ZoneId): LocalTime? {
    val value = raw?.trim()?.takeIf { 'T' in it } ?: return null
    return parseZonedIsoDateTimeToEpochMs(value)
        ?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalTime() }
        ?: runCatching { LocalDateTime.parse(value).toLocalTime() }.getOrNull()
}

internal fun calendarEpisodes(
    items: List<LibraryItem>,
    videosByItem: Map<String, List<MetaVideo>>,
    rangeStart: LocalDate,
    rangeEnd: LocalDate,
    zone: ZoneId = ZoneId.systemDefault(),
): Map<LocalDate, List<CalendarEpisode>> {
    return items.flatMap { item ->
        videosByItem[libraryKey(item)].orEmpty().mapNotNull { video ->
            if (video.season == null && video.episode == null) return@mapNotNull null
            val date = parseEpisodeReleaseLocalDate(video.released)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: return@mapNotNull null
            if (date.isBefore(rangeStart) || date.isAfter(rangeEnd)) return@mapNotNull null
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
    val authState by AuthRepository.state.collectAsStateWithLifecycle()
    val userId = (authState as? AuthState.Authenticated)?.userId.orEmpty()
    val profileKey = "$userId:$profileId"
    val videosByItem = remember(profileKey) { CalendarMetadataCache.videos(profileKey) }
    val fetchedAt = remember(profileKey) { CalendarMetadataCache.fetchedAt(profileKey) }
    val failedAt = remember(profileKey) { CalendarMetadataCache.failedAt(profileKey) }
    val series = remember(library.items) {
        library.items.filter { isSeriesLibraryType(it.type) || it.mediaCategory.equals("anime", ignoreCase = true) }
    }
    val seriesKeys = remember(series) { series.map(::libraryKey) }
    val active = LocalScreenActive.current
    var loading by remember(profileKey) { mutableStateOf(false) }
    var refreshTick by remember(profileKey) { mutableStateOf(0) }
    var weekIso by rememberSaveable {
        mutableStateOf(LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString())
    }
    var monthIso by rememberSaveable { mutableStateOf(LocalDate.now().withDayOfMonth(1).toString()) }
    var monthView by rememberSaveable { mutableStateOf(false) }
    var observedTodayIso by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    val weekStart = remember(weekIso) { LocalDate.parse(weekIso) }
    val month = remember(monthIso) { LocalDate.parse(monthIso) }
    val days = remember(weekStart, month, monthView) {
        if (monthView) calendarDaysForMonth(month) else (0L..6L).map(weekStart::plusDays)
    }
    val episodes = calendarEpisodes(series, videosByItem, days.first(), days.last())
    val displayedEpisodes = if (monthView) episodes.filterKeys { it.year == month.year && it.month == month.month } else episodes
    val appLanguage by ThemeSettingsRepository.selectedAppLanguage.collectAsStateWithLifecycle()
    val locale = if (appLanguage == AppLanguage.DEVICE) Locale.getDefault() else Locale.forLanguageTag(appLanguage.code)
    val startFormatter = remember(locale) { DateTimeFormatter.ofPattern("d MMM", locale) }
    val rangeFormatter = remember(locale) { DateTimeFormatter.ofPattern("d MMM yyyy", locale) }
    val dayFormatter = remember(locale) { DateTimeFormatter.ofPattern("EEEE d MMMM", locale) }
    val monthFormatter = remember(locale) { DateTimeFormatter.ofPattern("LLLL yyyy", locale) }

    LaunchedEffect(active) {
        while (active) {
            val today = LocalDate.now()
            if (today.toString() != observedTodayIso) {
                val previousDate = LocalDate.parse(observedTodayIso)
                weekIso = weekAfterDateChange(LocalDate.parse(weekIso), previousDate, today).toString()
                if (monthView && LocalDate.parse(monthIso) == previousDate.withDayOfMonth(1)) {
                    monthIso = today.withDayOfMonth(1).toString()
                }
                observedTodayIso = today.toString()
            }
            delay(60_000L)
            if (!loading) refreshTick++
        }
    }

    LaunchedEffect(active, profileKey, seriesKeys, weekIso, monthIso, monthView, refreshTick) {
        if (!active) return@LaunchedEffect
        videosByItem.keys.toList().filterNot(seriesKeys::contains).forEach(videosByItem::remove)
        fetchedAt.keys.toList().filterNot(seriesKeys::contains).forEach(fetchedAt::remove)
        failedAt.keys.toList().filterNot(seriesKeys::contains).forEach(failedAt::remove)
        val keysToFetch = calendarKeysToFetch(
            seriesKeys, videosByItem.keys, fetchedAt, failedAt, System.currentTimeMillis(),
        )
        val pending = series.filter { libraryKey(it) in keysToFetch }
        if (pending.isEmpty()) return@LaunchedEffect
        loading = true
        try {
            val semaphore = Semaphore(4)
            coroutineScope {
                pending.map { item ->
                    async {
                        val key = libraryKey(item)
                        val videos = semaphore.withPermit {
                            try {
                                MetaDetailsRepository.fetch(item.type, item.id, cacheResult = false, refresh = true)?.videos
                            } catch (error: CancellationException) {
                                throw error
                            } catch (_: Exception) {
                                null
                            }
                        }
                        if (videos != null) {
                            fetchedAt[key] = System.currentTimeMillis()
                            failedAt.remove(key)
                            videosByItem[key] = videos
                        } else {
                            failedAt[key] = System.currentTimeMillis()
                        }
                    }
                }.awaitAll()
            }
        } finally {
            loading = false
        }
    }

    val visibleEpisodes = displayedEpisodes.toSortedMap()
    val metadataFailed = seriesKeys.any(failedAt::containsKey)
    val tokens = MaterialTheme.nuvio
    Column(
        modifier = modifier.fillMaxSize()
            .background(tokens.colors.background)
            .padding(start = tokens.spacing.screenHorizontal, end = tokens.spacing.screenHorizontal)
            .padding(top = topChromePadding ?: tokens.spacing.screenTop),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.calendar_title),
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.headlineMedium,
                color = tokens.colors.textPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            TextButton(onClick = { monthView = false }) {
                Text(stringResource(Res.string.calendar_view_week), color = if (monthView) tokens.colors.textSecondary else tokens.colors.accent)
            }
            TextButton(onClick = {
                if (!monthView) monthIso = weekStart.withDayOfMonth(1).toString()
                monthView = true
            }) {
                Text(stringResource(Res.string.calendar_view_month), color = if (monthView) tokens.colors.accent else tokens.colors.textSecondary)
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                if (monthView) monthIso = month.minusMonths(1).toString() else weekIso = weekStart.minusWeeks(1).toString()
            }) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    stringResource(if (monthView) Res.string.calendar_previous_month else Res.string.calendar_previous_week),
                    tint = tokens.colors.textPrimary,
                )
            }
            Text(
                if (monthView) month.format(monthFormatter) else "${weekStart.format(startFormatter)} – ${weekStart.plusDays(6).format(rangeFormatter)}",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.titleLarge,
                color = tokens.colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            IconButton(onClick = {
                if (monthView) monthIso = month.plusMonths(1).toString() else weekIso = weekStart.plusWeeks(1).toString()
            }) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowForward,
                    stringResource(if (monthView) Res.string.calendar_next_month else Res.string.calendar_next_week),
                    tint = tokens.colors.textPrimary,
                )
            }
            TextButton(onClick = {
                monthView = false
                weekIso = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString()
            }) {
                Text(stringResource(Res.string.calendar_this_week), color = tokens.colors.textPrimary)
            }
        }
        if (library.isLoaded && (series.isEmpty() || visibleEpisodes.isEmpty() && (!loading || metadataFailed))) {
            NuvioSurfaceCard {
                Text(
                    stringResource(
                        when {
                            series.isEmpty() -> Res.string.calendar_no_series
                            metadataFailed -> Res.string.calendar_fetch_failed
                            monthView -> Res.string.calendar_no_episodes_month
                            else -> Res.string.calendar_no_episodes
                        },
                    ),
                    color = tokens.colors.textSecondary,
                )
            }
        }
        BoxWithConstraints(modifier = Modifier.weight(1f).fillMaxWidth()) {
            val availableWidth = maxWidth
            if (availableWidth >= 1150.dp) {
                Row(modifier = Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Box(modifier = Modifier.weight(1f).fillMaxHeight().horizontalScroll(rememberScrollState())) {
                        CalendarGrid(
                            days, displayedEpisodes, locale, onPosterClick,
                            month = month.takeIf { monthView },
                            scrollMonthRows = monthView,
                            modifier = Modifier.width(maxOf(900.dp, availableWidth - 340.dp)).fillMaxHeight(),
                        )
                    }
                    Column(modifier = Modifier.width(320.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        CalendarAgenda(visibleEpisodes, dayFormatter, onPosterClick)
                    }
                }
            } else {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Box(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                        CalendarGrid(
                            days, displayedEpisodes, locale, onPosterClick,
                            month = month.takeIf { monthView },
                            scrollMonthRows = false,
                            modifier = Modifier.width(maxOf(900.dp, availableWidth)),
                        )
                    }
                    CalendarAgenda(visibleEpisodes, dayFormatter, onPosterClick)
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
private fun CalendarGrid(
    days: List<LocalDate>,
    episodes: Map<LocalDate, List<CalendarEpisode>>,
    locale: Locale,
    onPosterClick: ((LibraryItem) -> Unit)?,
    month: LocalDate?,
    scrollMonthRows: Boolean,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (1..7).forEach { number ->
                Text(
                    DayOfWeek.of(number).getDisplayName(java.time.format.TextStyle.FULL, locale),
                    modifier = Modifier.weight(1f).padding(start = 8.dp),
                    color = tokens.colors.textSecondary,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (scrollMonthRows) {
            CalendarGridRows(
                days, episodes, month, onPosterClick,
                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            )
        } else {
            CalendarGridRows(days, episodes, month, onPosterClick)
        }
    }
}

@Composable
private fun CalendarGridRows(
    days: List<LocalDate>,
    episodes: Map<LocalDate, List<CalendarEpisode>>,
    month: LocalDate?,
    onPosterClick: ((LibraryItem) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        days.chunked(7).forEach { week ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                week.forEach { day ->
                    val outsideMonth = month != null && (day.month != month.month || day.year != month.year)
                    CalendarDateCell(
                        day,
                        if (outsideMonth) emptyList() else episodes[day].orEmpty(),
                        onPosterClick,
                        dimmed = outsideMonth,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun CalendarDateCell(
    day: LocalDate,
    episodes: List<CalendarEpisode>,
    onPosterClick: ((LibraryItem) -> Unit)?,
    dimmed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    val featured = episodes.firstOrNull()
    val imageUrl = featured?.item?.poster ?: featured?.video?.seasonPoster ?: featured?.video?.thumbnail
    Surface(
        modifier = modifier.height(270.dp),
        color = tokens.colors.surface.copy(alpha = if (dimmed) 0.45f else 1f),
        shape = RoundedCornerShape(12.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize().clickable(enabled = featured != null && onPosterClick != null) {
            featured?.let { onPosterClick?.invoke(it.item) }
        }) {
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
                Box(
                    modifier = Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent, Color.Black.copy(alpha = 0.86f)),
                        ),
                    ),
                )
            }
            Column(
                modifier = Modifier.fillMaxSize().padding(10.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(day.dayOfMonth.toString(), color = if (dimmed) tokens.colors.textMuted else tokens.colors.textPrimary)
                    if (day == LocalDate.now()) {
                        Text(stringResource(Res.string.calendar_today), color = tokens.colors.accent, style = MaterialTheme.typography.labelSmall)
                    }
                }
                featured?.let { episode ->
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(episode.item.name, color = tokens.colors.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(episode.video.title, color = tokens.colors.textPrimary.copy(alpha = 0.85f), style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(episode.detailLine(), color = tokens.colors.textPrimary, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (episodes.size > 1) {
                            Text("+${episodes.size - 1}", color = tokens.colors.textPrimary, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarAgenda(
    episodes: Map<LocalDate, List<CalendarEpisode>>,
    dayFormatter: DateTimeFormatter,
    onPosterClick: ((LibraryItem) -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tokens = MaterialTheme.nuvio
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        episodes.forEach { (day, dayEpisodes) ->
            Surface(modifier = Modifier.fillMaxWidth(), color = tokens.colors.surface, shape = tokens.shapes.card) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(day.format(dayFormatter), color = tokens.colors.textPrimary, style = MaterialTheme.typography.titleMedium)
                    dayEpisodes.forEach { episode ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { onPosterClick?.invoke(episode.item) },
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AsyncImage(
                                model = episode.video.thumbnail ?: episode.item.banner ?: episode.item.poster,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.width(104.dp).height(64.dp).clip(RoundedCornerShape(8.dp)),
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(episode.detailLine(), color = tokens.colors.accent, style = MaterialTheme.typography.labelMedium)
                                Text(episode.item.name, color = tokens.colors.textPrimary, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(episode.video.title, color = tokens.colors.textSecondary, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun CalendarEpisode.detailLine(): String = listOfNotNull(
    listOfNotNull(video.season?.let { "S$it" }, video.episode?.let { "E$it" }).joinToString(" ").takeIf(String::isNotBlank),
    time?.format(DateTimeFormatter.ofPattern("HH:mm")),
).joinToString(" · ")
