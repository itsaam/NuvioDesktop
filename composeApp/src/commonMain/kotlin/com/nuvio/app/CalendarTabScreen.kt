package com.nuvio.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.nuvio.app.features.library.LibraryItem

@Composable
internal expect fun CalendarTabScreen(
    modifier: Modifier = Modifier,
    topChromePadding: Dp? = null,
    onPosterClick: ((LibraryItem) -> Unit)? = null,
)
