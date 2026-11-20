package com.ykinger.stalkertv.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.MediaItem
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.ykinger.stalkertv.AppContainer
import com.ykinger.stalkertv.core.Catalogue
import com.ykinger.stalkertv.core.ContentKind
import com.ykinger.stalkertv.core.PlaybackSource
import com.ykinger.stalkertv.core.ProviderResult
import com.ykinger.stalkertv.core.Title
import com.ykinger.stalkertv.core.Season
import com.ykinger.stalkertv.core.Programme
import java.time.ZoneId
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect

private val background = Color(0xFF101314)
private val panel = Color(0xFF24282A)
private val cell = Color(0xFF303436)
private val muted = Color(0xFFABB0B3)
private val accent = Color(0xFF168DD0)
private val focusColor = Color(0xFFE0E3E4)

private data class SeasonEpisodeState(
    val episodes: List<Title> = emptyList(),
    val page: Int = 0,
    val hasMore: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

internal fun nearVodEnd(lastVisible: Int, visibleItems: Int, columns: Int, posters: Int): Boolean =
    posters > 0 && lastVisible >= 0 &&
        lastVisible >= posters - 1 - maxOf(columns * 2, visibleItems)

/** The connected experience is guide-first; the offline sample catalogue remains separate. */
@Composable
internal fun ConnectedTvScreen(container: AppContainer, onManagePlaylists: () -> Unit) {
    val model: CatalogueViewModel = viewModel(
        key = "connected-${System.identityHashCode(container)}",
        factory = viewModelFactory { initializer { CatalogueViewModel(container) } },
    )
    val state by model.state.collectAsStateWithLifecycle()
    val availableVodKinds by model.vodKinds.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    var selectedId by remember { mutableStateOf<String?>(null) }
    var source by remember { mutableStateOf<PlaybackSource?>(null) }
    var resolving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var fullScreen by remember { mutableStateOf(false) }
    var group by remember { mutableStateOf<String?>(null) }
    var area by remember { mutableStateOf("guide") }
    var section by remember { mutableStateOf("TV") }
    var search by remember { mutableStateOf("") }
    var programmes by remember { mutableStateOf<Map<String, List<Programme>>>(emptyMap()) }
    var vodTitle by remember { mutableStateOf<Title?>(null) }
    var vodCategory by remember { mutableStateOf<String?>(null) }
    var movieSearch by remember { mutableStateOf("") }
    var movieSearchOpen by remember { mutableStateOf(false) }
    var movieSort by remember { mutableStateOf("added") }
    var movieSortOpen by remember { mutableStateOf(false) }
    var highlightedMovieId by remember { mutableStateOf<String?>(null) }
    var seasons by remember { mutableStateOf<List<Season>>(emptyList()) }
    val seasonEpisodes = remember(vodTitle?.id) { mutableStateMapOf<String, SeasonEpisodeState>() }
    var vodError by remember { mutableStateOf<String?>(null) }
    var vodLoading by remember { mutableStateOf(false) }
    var returnToGroups by remember { mutableStateOf(false) }
    var focusedPosterIndex by remember { mutableIntStateOf(-1) }
    var mainMenuHasFocus by remember { mutableStateOf(false) }
    var contentHasFocus by remember { mutableStateOf(false) }
    var focusSelectedVodCategory by remember { mutableStateOf(false) }
    var focusedVodCategory by remember { mutableStateOf<String?>(null) }
    var vodCategoryHasFocus by remember { mutableStateOf(false) }
    val vodKind = when (section) {
        "Movies" -> ContentKind.MOVIE
        "Shows" -> ContentKind.SERIES
        else -> null
    }
    val vodNavigationVisible = remember { MutableTransitionState(true) }
    val guideFocus = remember { FocusRequester() }
    val groupsFocus = remember { FocusRequester() }
    val menuFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    val movieSearchFocus = remember { FocusRequester() }
    val movieSortFocus = remember { FocusRequester() }
    val sortButtonFocus = remember { FocusRequester() }

    fun openSection(item: String) {
        returnToGroups = false
        section = item
        vodTitle = null
        vodCategory = null
        movieSearch = ""; movieSearchOpen = false; movieSortOpen = false
        movieSort = "added"; highlightedMovieId = null
        focusedPosterIndex = -1
        focusSelectedVodCategory = false
        source = null
        selectedId = null
        area = if (item == "TV" || item == "Movies" || item == "Shows") "groups" else "content"
        when (item) {
            "TV", "Search" -> model.loadSection(ContentKind.CHANNEL)
            "Movies" -> model.loadSection(ContentKind.MOVIE, ordering = movieSort)
            "Shows" -> model.loadSection(ContentKind.SERIES, ordering = movieSort)
        }
    }

    fun openChannel(title: Title) {
        if (selectedId == title.id && source != null) {
            fullScreen = true
            return
        }
        selectedId = title.id
        source = null
        error = null
        resolving = true
        scope.launch {
            when (val result = model.repository.resolve(title.id)) {
                is ProviderResult.Success -> if (selectedId == title.id) source = result.value
                is ProviderResult.Failure -> if (selectedId == title.id) error = result.message
                else -> if (selectedId == title.id) error = "This channel is unavailable."
            }
            if (selectedId == title.id) resolving = false
        }
    }

    fun playVod(title: Title) {
        selectedId = title.id
        source = null
        error = null
        resolving = true
        scope.launch {
            when (val result = model.repository.resolve(title.id)) {
                is ProviderResult.Success -> if (selectedId == title.id) {
                    source = result.value
                    fullScreen = true
                }
                is ProviderResult.Failure -> if (selectedId == title.id) error = result.message
                else -> if (selectedId == title.id) error = "This video is unavailable."
            }
            if (selectedId == title.id) resolving = false
        }
    }

    fun openVodCategory(kind: ContentKind, category: String?) {
        focusSelectedVodCategory = true
        vodCategory = category; vodTitle = null; area = "content"
        movieSearch = ""; movieSearchOpen = false; movieSortOpen = false
        highlightedMovieId = null; movieSort = "added"
        focusedPosterIndex = -1
        model.loadSection(kind, category, ordering = movieSort)
    }

    LaunchedEffect(section, vodCategory, movieSearch, movieSort, area, vodTitle) {
        if (vodKind == null || area != "content" || vodTitle != null) return@LaunchedEffect
        delay(350)
        model.loadSection(vodKind, vodCategory, query = movieSearch.trim(), ordering = movieSort)
    }

    LaunchedEffect(selectedId, section) {
        val id = selectedId ?: return@LaunchedEffect
        if (section == "TV" && id !in programmes) {
            val guide = (model.repository.programmes(id) as? ProviderResult.Success)?.value.orEmpty()
            programmes = programmes + (id to guide)
        }
    }
    LaunchedEffect(vodTitle) {
        seasons = emptyList()
        vodError = null
        val title = vodTitle ?: return@LaunchedEffect
        if (title.kind == ContentKind.SERIES) {
            vodLoading = true
            when (val result = model.repository.seasons(title.id)) {
                is ProviderResult.Success -> seasons = result.value
                is ProviderResult.Failure -> vodError = "Could not load seasons for this show. Try again later."
                else -> vodError = "This portal did not provide seasons for this show."
            }
            vodLoading = false
        }
    }
    fun loadEpisodes(title: Title, season: Season, next: Int) {
        val current = seasonEpisodes[season.id]
        if (current?.loading == true || (next == 1 && current?.page != null && current.page > 0) ||
            (next > 1 && current?.hasMore != true)) return
        if (next == 1 && season.episodes.isNotEmpty()) {
            seasonEpisodes[season.id] = SeasonEpisodeState(episodes = season.episodes, page = 1)
            return
        }
        seasonEpisodes[season.id] = (current ?: SeasonEpisodeState()).copy(loading = true, error = null)
        scope.launch {
            val result = model.repository.episodes(title.id, season.id, next)
            if (vodTitle?.id != title.id) return@launch
            val latest = seasonEpisodes[season.id] ?: return@launch
            seasonEpisodes[season.id] = when (result) {
                is ProviderResult.Success -> {
                    latest.copy(episodes = (latest.episodes + result.value.episodes).distinctBy { it.id },
                        page = next, hasMore = result.value.hasMore, loading = false)
                }
                else -> latest.copy(loading = false,
                    error = "Could not load episodes for this season. Try again.")
            }
        }
    }

    // A newly appended page changes state but must not steal focus from the item
    // the user is scrolling past. Only initial loading/section changes refocus.
    LaunchedEffect(area, section, state is LoadState.Ready, group, vodCategory, vodTitle, seasons, vodLoading,
        vodNavigationVisible.currentState,
        seasonEpisodes[seasons.firstOrNull()?.id]?.episodes?.isNotEmpty(),
        (state as? LoadState.Ready<Catalogue>)?.value?.titles?.any {
            it.kind == vodKind &&
                (vodCategory == null || it.categoryId == vodCategory)
        }) {
        withFrameNanos { }
        val loaded = (state as? LoadState.Ready<Catalogue>)?.value
        val target = when (area) {
            "menu" -> if (!mainMenuHasFocus) menuFocus else null
            "groups" -> if (section in listOf("TV", "Movies", "Shows") && loaded != null &&
                (section == "TV" || vodNavigationVisible.currentState)) groupsFocus else null
            "guide" -> if (section == "TV" && loaded?.titles?.any {
                it.kind == ContentKind.CHANNEL && (group == null || it.categoryId == group)
            } == true) guideFocus else null
            "content" -> if (contentHasFocus) null else when (section) {
                "Settings" -> guideFocus
                "Search" -> searchFocus
                "Movies", "Shows" -> if (loaded != null && (vodTitle != null || loaded.titles.any {
                    it.kind == vodKind &&
                        (vodCategory == null || it.categoryId == vodCategory)
                } || loaded.hasMore)) guideFocus else null
                else -> null
            }
            else -> null
        }
        // The selected category can change while an earlier frame is still being committed.
        // Focus only a target that is attached in the current composition.
        try { target?.requestFocus() } catch (_: IllegalStateException) { }
    }

    // Leaving a detail removes its focused button. Hand focus to the selected
    // category after that button has left the composition, not to a menu icon.
    LaunchedEffect(returnToGroups, vodTitle, area) {
        if (returnToGroups && vodTitle == null && area == "groups") {
            withFrameNanos { }
            try { groupsFocus.requestFocus() } catch (_: IllegalStateException) { }
            returnToGroups = false
        }
    }

    // Keep focus in the movie pane while the selected category's page loads.
    // The disappearing category cannot hand focus directly to a poster until
    // that poster exists, so use the always-visible sort button in between.
    LaunchedEffect(focusSelectedVodCategory, state, vodCategory) {
        if (!focusSelectedVodCategory) return@LaunchedEffect
        withFrameNanos { }
        val ready = (state as? LoadState.Ready<Catalogue>)?.value
        val canFocusGrid = ready != null && (ready.hasMore || ready.titles.any {
            it.kind == vodKind &&
                (vodCategory == null || it.categoryId == vodCategory)
        })
        val target = if (canFocusGrid || state is LoadState.Message) guideFocus else sortButtonFocus
        try {
            target.requestFocus()
            if (ready != null || state is LoadState.Message) focusSelectedVodCategory = false
        } catch (_: IllegalStateException) { }
    }

    if (fullScreen && source != null) {
        SamplePlayer(source!!, demo = false) { fullScreen = false }
        return
    }
    val vodBrowsing = vodKind != null && area == "content"
    vodNavigationVisible.targetState = !vodBrowsing
    val compactMenu = area != "menu"
    BackHandler(area != "menu") {
        if (movieSortOpen) {
            movieSortOpen = false
            try { sortButtonFocus.requestFocus() } catch (_: IllegalStateException) { }
        } else if (movieSearchOpen) {
            movieSearchOpen = false; movieSearch = ""
            if (vodKind != null) model.loadSection(vodKind, vodCategory, ordering = movieSort)
            try { sortButtonFocus.requestFocus() } catch (_: IllegalStateException) { }
        } else
        when (area) {
            "guide" -> area = "groups"
            "groups" -> area = "menu"
            "content" -> if (vodTitle != null) {
                    vodTitle = null; area = "groups"; returnToGroups = true
                }
                else area = if (section == "Movies" || section == "Shows") "groups" else "menu"
        }
    }
    Box(Modifier.fillMaxSize().background(background)) {
    Row(Modifier.fillMaxSize()) {
        AnimatedVisibility(vodNavigationVisible, enter = expandHorizontally(expandFrom = Alignment.Start),
            exit = shrinkHorizontally(shrinkTowards = Alignment.Start)) {
        Column(Modifier.width(if (compactMenu) 68.dp else 190.dp).fillMaxHeight().background(panel)
            .onFocusChanged {
                mainMenuHasFocus = it.hasFocus
                if (it.hasFocus && !focusSelectedVodCategory) area = "menu"
            }
            .focusGroup()
            .padding(if (compactMenu) 8.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (compactMenu) "ST" else "STALKER TV", fontSize = 22.sp,
                    fontWeight = FontWeight.Bold, color = accent,
                    modifier = Modifier.padding(bottom = 70.dp))
                val menu = buildList {
                    add("Search" to Icons.Default.Search)
                    add("TV" to ConnectedMenuIcons.tv)
                    // A partial VOD page cannot prove a section is empty. Hide it only
                    // after the unfiltered VOD catalogue is fully exhausted.
                    if (availableVodKinds == null || ContentKind.MOVIE in availableVodKinds.orEmpty() || section == "Movies")
                        add("Movies" to ConnectedMenuIcons.movies)
                    if (availableVodKinds == null || ContentKind.SERIES in availableVodKinds.orEmpty() || section == "Shows")
                        add("Shows" to ConnectedMenuIcons.shows)
                    add("Recordings" to ConnectedMenuIcons.recordings)
                    add("My list" to Icons.Default.Favorite)
                    add("Settings" to Icons.Default.Settings)
                }
                menu.forEach { (item, icon) ->
                    GuideAction(item, selected = section == item,
                        modifier = if (item == section) Modifier.focusRequester(menuFocus) else Modifier,
                        icon = icon, compact = compactMenu,
                        onClick = {
                            openSection(item)
                        })
                }
            }
        }
        if (section == "Movies" || section == "Shows") {
            val kind = requireNotNull(vodKind)
            val loaded = state as? LoadState.Ready
            val titles = loaded?.value?.titles?.filter { it.kind == kind }.orEmpty()
            val allCategory = loaded?.value?.categories?.firstOrNull { it.id == "*" }
            AnimatedVisibility(vodNavigationVisible, enter = expandHorizontally(expandFrom = Alignment.Start),
                exit = shrinkHorizontally(shrinkTowards = Alignment.Start)) {
            Column(Modifier.width(192.dp).fillMaxHeight().background(background)
                .onFocusChanged {
                    vodCategoryHasFocus = it.hasFocus
                    if (it.hasFocus && !focusSelectedVodCategory) area = "groups"
                }
                .onPreviewKeyEvent {
                    if (vodCategoryHasFocus && it.type == KeyEventType.KeyDown && it.key == Key.DirectionRight) {
                        openVodCategory(kind, focusedVodCategory)
                        true
                    } else false
                }
                .focusGroup()
                .padding(start = 10.dp, top = 192.dp, end = 10.dp).verticalScroll(rememberScrollState())) {
                Text(section, color = Color.White, fontSize = 20.sp, modifier = Modifier.padding(12.dp))
                GuideAction(allCategory?.name ?: "All $section", selected = vodCategory == null,
                    modifier = Modifier.onFocusChanged { if (it.isFocused) focusedVodCategory = null }
                        .then(if (vodCategory == null) Modifier.focusRequester(groupsFocus) else Modifier)
                    ) {
                        openVodCategory(kind, null)
                    }
                loaded?.value?.categories
                    ?.filter { it.id != "*" && vodCategoryVisible(it, kind, loaded.value.titles) }
                    ?.forEach { category ->
                        GuideAction(category.name, selected = vodCategory == category.id,
                            modifier = Modifier.onFocusChanged { if (it.isFocused) focusedVodCategory = category.id }
                                .then(if (vodCategory == category.id) Modifier.focusRequester(groupsFocus) else Modifier)
                            ) {
                            openVodCategory(kind, category.id)
                        }
                    }
            }
            }
        }
        if (section == "TV") {
            when (val loaded = state) {
                is LoadState.Ready -> {
                    val catalogue = loaded.value
                    val channels = catalogue.titles.filter { it.kind == ContentKind.CHANNEL }
                    val visible = channels.filter { group == null || it.categoryId == group }
                    val allGenre = catalogue.categories.firstOrNull { it.id == "*" }
                    Column(Modifier.width(192.dp).fillMaxHeight().padding(start = 10.dp, top = 192.dp, end = 10.dp)
                        .verticalScroll(rememberScrollState())) {
                        val categoryModifier = Modifier.onPreviewKeyEvent {
                            if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft) {
                                area = "menu"; true
                            } else false
                        }
                        GuideAction(allGenre?.name ?: "All channels", group == null,
                            Modifier.focusRequester(groupsFocus).then(categoryModifier)) {
                            group = null; area = "guide"; model.loadSection(ContentKind.CHANNEL)
                        }
                        catalogue.categories
                            .filterNot { it.id == "*" }
                            .forEach { category ->
                                GuideAction(category.name, group == category.id, categoryModifier) {
                                    group = category.id; area = "guide"; model.loadSection(ContentKind.CHANNEL, category.id)
                                }
                            }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        GuideHeader(source, selectedId?.let { id -> channels.find { it.id == id } }, resolving, error,
                            selectedId?.let { id -> programmes[id].orEmpty().firstOrNull { it.start <= java.time.Instant.now() && it.end > java.time.Instant.now() } })
                        val now = remember { LocalDateTime.now() }
                        Row(Modifier.fillMaxWidth().height(50.dp).background(background), verticalAlignment = Alignment.CenterVertically) {
                            Text(now.format(DateTimeFormatter.ofPattern("EEE, MMM d, h:mm a")),
                                color = accent, fontSize = 14.sp, modifier = Modifier.width(260.dp).padding(start = 20.dp))
                            val hour = now.withMinute(0)
                            repeat(3) { slot ->
                                Text(hour.plusMinutes(slot * 30L).format(DateTimeFormatter.ofPattern("h:mm a")),
                                    color = muted, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            }
                        }
                        if (visible.isEmpty()) {
                            Text("No channels in this group", color = muted, modifier = Modifier.padding(24.dp))
                            if (catalogue.hasMore) GuideAction("Load more channels", onClick = model::loadNextPage)
                        } else {
                            LazyColumn {
                                items(visible, key = { it.id }) { title ->
                                    var focused by remember { mutableStateOf(false) }
                                    Row(
                                        Modifier.fillMaxWidth().height(70.dp)
                                            .onPreviewKeyEvent {
                                                if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft) {
                                                    area = "groups"; true
                                                } else false
                                            }
                                            .onFocusChanged {
                                                focused = it.isFocused
                                                if (focused && title.id == visible.last().id) model.loadNextPage()
                                            }
                                            .then(if (title.id == visible.first().id) Modifier.focusRequester(guideFocus) else Modifier)
                                            .clickable { openChannel(title) }.focusable()
                                            .background(if (focused) focusColor else background),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Text("${visible.indexOf(title) + 1}", modifier = Modifier.width(38.dp).padding(start = 12.dp),
                                            color = if (focused) Color.Black else muted)
                                        Box(Modifier.size(38.dp).background(cell, RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                                            Text("TV", color = muted, fontSize = 12.sp)
                                        }
                                        Text(title.name, maxLines = 1, fontSize = 15.sp,
                                            color = if (focused) Color.Black else Color.White,
                                            modifier = Modifier.width(190.dp).padding(start = 12.dp))
                                        val entries = programmes[title.id].orEmpty().filter { it.end > java.time.Instant.now() }
                                        repeat(2) { slot ->
                                            Box(Modifier.weight(1f).fillMaxHeight().padding(2.dp)
                                                .background(if (focused) focusColor else cell, RoundedCornerShape(4.dp)),
                                                contentAlignment = Alignment.CenterStart) {
                                                Text(entries.getOrNull(slot)?.title ?: "No information", fontSize = 14.sp, maxLines = 1,
                                                    color = if (focused) Color.Black else muted,
                                                    modifier = Modifier.padding(start = 12.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                else -> Column(Modifier.weight(1f).padding(32.dp), verticalArrangement = Arrangement.Center) {
                    Text(if (loaded is LoadState.Message) loaded.text else "Loading channels…", color = Color.White)
                    Button(onClick = model::refresh) { Text("Retry") }
                    Button(onClick = onManagePlaylists) { Text("Manage playlists") }
                }
            }
        } else {
            val vodBackdrop = if (vodBrowsing) {
                vodTitle ?: (state as? LoadState.Ready<Catalogue>)?.value?.titles
                    ?.filter { it.kind == vodKind &&
                        (vodCategory == null || it.categoryId == vodCategory) }
                    ?.let { titles -> titles.firstOrNull { it.id == highlightedMovieId } ?: titles.firstOrNull() }
            } else null
            Box(Modifier.weight(1f).fillMaxHeight()) {
            if (vodBackdrop?.artworkKey != null) {
                Box(Modifier.fillMaxWidth().height(if (vodTitle != null) 500.dp else 355.dp)) {
                    Artwork(vodBackdrop, Modifier.fillMaxSize())
                    Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                        0f to background, 0.55f to background.copy(alpha = 0.80f),
                        1f to Color.Transparent)))
                    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                        0f to background.copy(alpha = 0.75f),
                        0.22f to background.copy(alpha = 0.08f),
                        0.50f to background.copy(alpha = 0.30f),
                        0.78f to background.copy(alpha = 0.72f),
                        1f to background)))
                }
            }
            Column(Modifier.fillMaxSize()
                .onFocusChanged {
                    contentHasFocus = it.hasFocus
                    if (it.hasFocus && section in listOf("Movies", "Shows") && area == "groups") area = "content"
                }
                .onPreviewKeyEvent {
                    if (vodKind != null && area == "content" && !movieSortOpen &&
                        it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft &&
                        (vodTitle != null || focusedPosterIndex == 0 ||
                            (focusedPosterIndex == -2 && (state as? LoadState.Ready<Catalogue>)
                                ?.value?.titles?.none { title -> title.kind == vodKind } == true))) {
                        area = "groups"
                        true
                    } else false
                }
                .focusGroup().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(if (vodBrowsing) 10.dp else 18.dp)) {
                if (vodBrowsing && vodTitle == null) {
                    Row(Modifier.fillMaxWidth().onFocusChanged {
                        if (!it.hasFocus && movieSearchOpen) {
                            movieSearchOpen = false
                            movieSearch = ""
                        }
                    }.focusGroup(), verticalAlignment = Alignment.CenterVertically) {
                        val categoryName = (state as? LoadState.Ready<Catalogue>)?.value?.categories
                            ?.firstOrNull { it.id == vodCategory }?.name
                        Text(categoryName ?: "All $section",
                            fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White,
                            modifier = Modifier.weight(1f))
                        if (vodTitle == null) {
                            if (movieSearchOpen) {
                                 BasicTextField(movieSearch, { movieSearch = it }, singleLine = true,
                                     textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
                                     modifier = Modifier.width(250.dp).focusRequester(movieSearchFocus)
                                         .onPreviewKeyEvent {
                                             if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionDown) {
                                                 if (!focusManager.moveFocus(FocusDirection.Down)) {
                                                     try { sortButtonFocus.requestFocus() } catch (_: IllegalStateException) { }
                                                 }
                                                 movieSearchOpen = false
                                                 movieSearch = ""
                                                 true
                                             } else false
                                         }
                                         .onFocusChanged { if (it.isFocused) focusedPosterIndex = -1 }
                                        .background(cell, RoundedCornerShape(6.dp)).padding(10.dp))
                                LaunchedEffect(movieSearchOpen) {
                                    try { movieSearchFocus.requestFocus() } catch (_: IllegalStateException) { }
                                }
                            }
                            GuideAction("Search $section", modifier = Modifier.width(56.dp)
                                .onFocusChanged { if (it.isFocused) focusedPosterIndex = -1 },
                                icon = Icons.Default.Search, compact = true) {
                                movieSortOpen = false; movieSearchOpen = !movieSearchOpen
                                if (!movieSearchOpen) movieSearch = ""
                            }
                            GuideAction("Sort $section", modifier = Modifier.width(56.dp).focusRequester(sortButtonFocus)
                                .onFocusChanged { if (it.isFocused) focusedPosterIndex = -1 },
                                icon = Icons.Default.Menu, compact = true) { movieSortOpen = !movieSortOpen }
                        }
                    }
                } else Text(section, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
                when (section) {
                    "Settings" -> {
                        GuideAction("Playlists", modifier = Modifier.focusRequester(guideFocus), onClick = onManagePlaylists)
                        GuideAction("Refresh channels", onClick = {
                            section = "TV"; group = null; model.loadSection(ContentKind.CHANNEL, force = true)
                        })
                    }
                    "Search" -> {
                        BasicTextField(search, { search = it }, singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 20.sp),
                            modifier = Modifier.fillMaxWidth().focusRequester(searchFocus)
                                .background(cell, RoundedCornerShape(6.dp)).padding(16.dp))
                        if (state is LoadState.Ready) {
                            (state as LoadState.Ready<Catalogue>).value.titles
                                .filter { it.kind == ContentKind.CHANNEL && it.name.contains(search, ignoreCase = true) }
                                .take(30).forEach { title -> GuideAction(title.name, onClick = {
                                    group = null; section = "TV"; area = "guide"; openChannel(title)
                                }) }
                        }
                    }
                    "Movies", "Shows" -> {
                        val loaded = state as? LoadState.Ready
                        val kind = requireNotNull(vodKind)
                        val loadingNext by model.loadingNext.collectAsStateWithLifecycle()
                        val pageFailed by model.pageFailed.collectAsStateWithLifecycle()
                        val gridState = key(section, vodCategory, movieSearch, movieSort) { rememberLazyGridState() }
                        val rowState = key(section, vodCategory, movieSearch, movieSort) { rememberLazyListState() }
                        var lastAutoCount by remember(section, vodCategory, movieSearch, movieSort) { mutableIntStateOf(-1) }
                        var autoFetchesAtCount by remember(section, vodCategory, movieSearch, movieSort) { mutableIntStateOf(0) }
                        var footerFocused by remember(section, vodCategory, movieSearch, movieSort) { mutableStateOf(false) }
                        var previousPosterCount by remember(section, vodCategory, movieSearch, movieSort) { mutableIntStateOf(0) }
                        var appendedFocusId by remember(section, vodCategory, movieSearch, movieSort) { mutableStateOf<String?>(null) }
                        val appendedFocus = remember(section, vodCategory, movieSearch, movieSort) { FocusRequester() }
                        if (loaded == null) {
                            Text((state as? LoadState.Message)?.text ?: "Loading titles…", color = muted)
                            GuideAction("Retry", modifier = Modifier.focusRequester(guideFocus), onClick = model::refresh)
                        } else if (vodTitle != null) {
                            val title = vodTitle!!
                            val firstEpisode = seasons.firstOrNull()?.let { seasonEpisodes[it.id]?.episodes?.firstOrNull() }
                            val detailListState = key(title.id) { rememberLazyListState() }
                            val heroFocus = remember(title.id) { FocusRequester() }
                            var heroFocused by remember(title.id) { mutableStateOf(false) }
                            LaunchedEffect(title.id, firstEpisode?.id) {
                                withFrameNanos { }
                                detailListState.scrollToItem(0)
                            }
                            LazyColumn(Modifier.fillMaxSize(), state = detailListState,
                                verticalArrangement = Arrangement.spacedBy(20.dp),
                                contentPadding = PaddingValues(bottom = 24.dp)) {
                                item(key = "hero") {
                                    Column(Modifier.fillMaxWidth().height(290.dp).padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Column(Modifier.fillMaxWidth()
                                            .focusRequester(if (title.kind == ContentKind.SERIES && firstEpisode == null)
                                                guideFocus else heroFocus)
                                            .onFocusChanged {
                                                heroFocused = it.isFocused
                                                if (it.isFocused) scope.launch { detailListState.scrollToItem(0) }
                                            }
                                            .semantics { contentDescription = if (title.kind == ContentKind.SERIES)
                                                "Show details" else "Movie details" }
                                            .focusable(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            Text(title.name, color = if (heroFocused) accent else Color.White,
                                                fontSize = 28.sp, fontWeight = FontWeight.Bold)
                                            val summary = listOfNotNull(title.year?.toString(),
                                                seasons.takeIf { title.kind == ContentKind.SERIES && it.isNotEmpty() }
                                                    ?.let { "${it.size} ${if (it.size == 1) "season" else "seasons"}" })
                                            if (summary.isNotEmpty()) Text(summary.joinToString("  •  "), color = muted)
                                            title.description?.let { Text(it, color = muted, maxLines = 3) }
                                            error?.let { Text(it, color = muted) }
                                            if (resolving) Text("Loading video…", color = muted)
                                        }
                                        Spacer(Modifier.weight(1f))
                                        if (title.kind == ContentKind.MOVIE) {
                                            GuideAction("Play movie", modifier = Modifier.width(220.dp)
                                                .focusRequester(guideFocus).focusProperties { up = heroFocus }) {
                                                playVod(title)
                                            }
                                        } else if (firstEpisode != null) {
                                            GuideAction("Play S${seasons.first().number} E1",
                                                modifier = Modifier.width(220.dp).focusRequester(guideFocus)
                                                    .focusProperties { up = heroFocus }) {
                                                playVod(firstEpisode)
                                            }
                                        }
                                    }
                                }
                                if (title.kind == ContentKind.SERIES) {
                                    if (vodLoading) item { Text("Loading seasons…", color = muted) }
                                    vodError?.let { message -> item { Text(message, color = muted) } }
                                    items(seasons, key = { it.id }) { season ->
                                        LaunchedEffect(title.id, season.id) {
                                            if (seasonEpisodes[season.id] == null) loadEpisodes(title, season, 1)
                                        }
                                        val page = seasonEpisodes[season.id]
                                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                            Text("Season ${season.number}", color = Color.White, fontSize = 20.sp)
                                            if (page?.loading == true && page.episodes.isEmpty())
                                                Text("Loading episodes…", color = muted)
                                            if (page?.error != null && page.episodes.isEmpty())
                                                GuideAction("Retry Season ${season.number}", modifier = Modifier.width(240.dp)) {
                                                    loadEpisodes(title, season, 1)
                                                }
                                            if (page?.episodes?.isNotEmpty() == true) {
                                                LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp),
                                                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)) {
                                                    items(page.episodes.size, key = { page.episodes[it].id }) { index ->
                                                        val episode = page.episodes[index]
                                                        EpisodePoster(episode.copy(artworkKey = episode.artworkKey ?: title.artworkKey),
                                                            index + 1, Modifier.width(215.dp)) { playVod(episode) }
                                                    }
                                                    if (page.hasMore || page.error != null || page.loading) {
                                                        item(key = "more") {
                                                            GuideAction(if (page.loading) "Loading…" else if (page.error != null) "Retry" else "Load more",
                                                                modifier = Modifier.width(160.dp), onClick = {
                                                                    if (!page.loading) loadEpisodes(title, season, page.page + 1)
                                                                })
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                if (title.kind == ContentKind.MOVIE) {
                                    item(key = "back") {
                                        GuideAction("Back to $section", modifier = Modifier.width(220.dp)) {
                                            vodTitle = null; area = "groups"; returnToGroups = true
                                        }
                                    }
                                }
                            }
                        } else {
                            val allTitles = loaded.value.titles.filter { it.kind == kind }
                            val titles = allTitles.filter { vodCategory == null || it.categoryId == vodCategory }
                            val otherKindOnly = vodCategory != null && titles.isEmpty() &&
                                loaded.value.titles.any { it.categoryId == vodCategory && it.kind != kind }
                            val otherSection = if (kind == ContentKind.MOVIE) "Shows" else "Movies"
                            val highlighted = titles.firstOrNull { it.id == highlightedMovieId } ?: titles.firstOrNull()
                            if (vodBrowsing && highlighted != null) {
                                Box(Modifier.fillMaxWidth().height(120.dp)) {
                                    Column(Modifier.align(Alignment.TopStart).fillMaxWidth(0.62f).padding(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(highlighted.name, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                                            color = Color.White, maxLines = 1)
                                        highlighted.year?.let { Text("$it", fontSize = 14.sp, color = muted) }
                                        highlighted.description?.let {
                                            Text(it, color = muted, fontSize = 13.sp, maxLines = 2)
                                        }
                                    }
                                }
                            }
                            if (vodBrowsing && titles.isNotEmpty()) {
                                val position = highlighted?.let { titles.indexOf(it) + 1 } ?: 1
                                val completeTitles = !loaded.value.hasMore &&
                                    loaded.value.titles.size == titles.size && !pageFailed
                                Text("$position / ${titles.size} ${if (completeTitles) section.lowercase() else "loaded"}",
                                    color = muted, fontSize = 14.sp,
                                    modifier = Modifier.fillMaxWidth().wrapContentWidth(Alignment.End)
                                        .background(background.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                                        .padding(horizontal = 8.dp, vertical = 4.dp))
                            }
                            LaunchedEffect(section, vodCategory, titles.size) {
                                if (footerFocused && titles.size > previousPosterCount) {
                                    val next = previousPosterCount
                                    appendedFocusId = titles[next].id
                                    if (vodBrowsing) rowState.scrollToItem(next) else gridState.scrollToItem(next)
                                    withFrameNanos { }
                                    try { appendedFocus.requestFocus() } catch (_: IllegalStateException) { }
                                }
                                previousPosterCount = titles.size
                            }
                            val canPrefetch = !otherKindOnly && loaded.value.hasMore && !loadingNext && !pageFailed &&
                                (lastAutoCount != titles.size || autoFetchesAtCount < 2)
                            LaunchedEffect(section, vodCategory, titles.size, canPrefetch, gridState, rowState) {
                                if (lastAutoCount != titles.size) {
                                    lastAutoCount = titles.size
                                    autoFetchesAtCount = 0
                                }
                                if (titles.isEmpty()) {
                                    if (canPrefetch) {
                                        autoFetchesAtCount++
                                        model.loadNextPage()
                                    }
                                } else {
                                    snapshotFlow {
                                        if (vodBrowsing) {
                                            val layout = rowState.layoutInfo
                                            nearVodEnd(layout.visibleItemsInfo.lastOrNull()?.index ?: -1,
                                                layout.visibleItemsInfo.size, 1, titles.size)
                                        } else {
                                            val layout = gridState.layoutInfo
                                            nearVodEnd(layout.visibleItemsInfo.lastOrNull()?.index ?: -1,
                                                layout.visibleItemsInfo.size, layout.maxSpan, titles.size)
                                        }
                                    }.collect { nearEnd ->
                                        if (nearEnd && canPrefetch) {
                                            autoFetchesAtCount++
                                            model.loadNextPage()
                                        }
                                    }
                                }
                            }
                            if (titles.isEmpty()) {
                                Text(when {
                                    loadingNext -> "Loading more $section…"
                                    otherKindOnly -> "This page contains ${otherSection.lowercase()}, not ${section.lowercase()}. Try $otherSection or load another page."
                                    movieSearch.isNotBlank() -> "No matching ${section.lowercase()}"
                                    else -> "No $section on this page"
                                }, color = muted)
                                if (loaded.value.hasMore)
                                    GuideAction(if (loadingNext) "Loading more…" else if (pageFailed) "Retry" else "Load more",
                                        modifier = Modifier.focusRequester(guideFocus)
                                            .onFocusChanged { if (it.isFocused) focusedPosterIndex = -2 },
                                        onClick = { if (!loadingNext) model.loadNextPage() })
                            }
                            else {
                                if (vodBrowsing) {
                                    BoxWithConstraints(Modifier.fillMaxSize()) {
                                        // VOD cards contain only artwork; leave room for focus scale and padding.
                                        val cardWidth = minOf(190.dp, (maxHeight - 32.dp).coerceAtLeast(80.dp) * 0.70f)
                                        LazyRow(state = rowState, modifier = Modifier.fillMaxSize().focusGroup(),
                                            contentPadding = PaddingValues(12.dp),
                                            horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                                            items(titles.size, key = { titles[it].id }) { index ->
                                                val title = titles[index]
                                                PosterCard(title, Modifier.width(cardWidth)
                                                    .then(if (index == 0) Modifier.focusRequester(guideFocus) else Modifier)
                                                    .then(if (title.id == appendedFocusId) Modifier.focusRequester(appendedFocus) else Modifier)
                                                    .onFocusChanged { if (it.isFocused) {
                                                        footerFocused = false; highlightedMovieId = title.id
                                                        focusedPosterIndex = index; area = "content"
                                                } }, showDetails = false) { vodTitle = title }
                                            }
                                            if (loaded.value.hasMore || loadingNext || footerFocused) {
                                                item(key = "load-more") {
                                                    GuideAction(when {
                                                        loadingNext -> "Loading more…"
                                                        !loaded.value.hasMore -> "End of $section"
                                                        pageFailed -> "Retry"
                                                        else -> "Load more"
                                                    }, modifier = Modifier.width(150.dp).onFocusChanged {
                                                        footerFocused = it.isFocused
                                                        if (it.isFocused) focusedPosterIndex = -2
                                                    }, onClick = { if (loaded.value.hasMore && !loadingNext) model.loadNextPage() })
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    LazyVerticalGrid(columns = GridCells.Adaptive(130.dp), state = gridState,
                                        modifier = Modifier.fillMaxSize().focusGroup(),
                                        contentPadding = PaddingValues(8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                                        verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                        gridItemsIndexed(titles, key = { _, title -> title.id }) { index, title ->
                                            PosterCard(title, Modifier.fillMaxWidth()
                                                .then(if (index == 0) Modifier.focusRequester(guideFocus) else Modifier)
                                                .then(if (title.id == appendedFocusId) Modifier.focusRequester(appendedFocus) else Modifier)
                                                .onFocusChanged { if (it.isFocused) {
                                                    footerFocused = false; highlightedMovieId = title.id
                                                } }) { vodTitle = title }
                                        }
                                        if (loaded.value.hasMore || loadingNext || footerFocused) {
                                            item(key = "load-more") {
                                                GuideAction(when {
                                                    loadingNext -> "Loading more…"
                                                    !loaded.value.hasMore -> "End of $section"
                                                    pageFailed -> "Retry"
                                                    else -> "Load more"
                                                }, modifier = Modifier.onFocusChanged { footerFocused = it.isFocused },
                                                    onClick = { if (loaded.value.hasMore && !loadingNext) model.loadNextPage() })
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    else -> Text("No ${section.lowercase()} yet", color = muted)
                }
            }
            }
        }
    }
    if (vodBrowsing && vodTitle == null && movieSortOpen) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
            Column(Modifier.width(maxWidth * 0.20f).fillMaxHeight()
                .onPreviewKeyEvent {
                    if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft) {
                        movieSortOpen = false
                        try { sortButtonFocus.requestFocus() } catch (_: IllegalStateException) { }
                        true
                    } else false
                }
                .background(panel)
                .padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Sort $section", color = Color.White, fontSize = 22.sp,
                    modifier = Modifier.padding(bottom = 18.dp))
                listOf("added" to "Last added", "rating" to "Rating", "name" to "Name")
                    .forEachIndexed { index, (value, label) ->
                        GuideAction("${if (movieSort == value) "◉" else "○"}  $label",
                            selected = movieSort == value,
                            modifier = if (index == 0) Modifier.focusRequester(movieSortFocus) else Modifier) {
                            movieSort = value; highlightedMovieId = null; movieSortOpen = false
                            model.loadSection(requireNotNull(vodKind), vodCategory,
                                query = movieSearch.trim(), ordering = movieSort)
                            try { sortButtonFocus.requestFocus() } catch (_: IllegalStateException) { }
                        }
                    }
            }
        }
        LaunchedEffect(movieSortOpen) {
            try { movieSortFocus.requestFocus() } catch (_: IllegalStateException) { }
        }
    }
    }
}

@Composable
private fun EpisodePoster(episode: Title, number: Int, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier.semantics { contentDescription = "Episode $number: ${episode.name}" },
        scale = CardDefaults.scale(focusedScale = 1.04f)) {
        Column {
            Artwork(episode, Modifier.fillMaxWidth().aspectRatio(1.7f))
            Text("E$number. ${episode.name}", modifier = Modifier.fillMaxWidth().padding(8.dp),
                maxLines = 1, fontSize = 14.sp)
        }
    }
}

@Composable
private fun GuideAction(label: String, selected: Boolean = false, modifier: Modifier = Modifier,
    icon: ImageVector? = null, compact: Boolean = false, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth().height(48.dp)
        .then(if (compact) Modifier.semantics { contentDescription = label } else Modifier)
        .onFocusChanged { focused = it.isFocused }
        .background(if (focused) focusColor else if (selected) cell else Color.Transparent, RoundedCornerShape(5.dp))
        .clickable(onClick = onClick).focusable().padding(horizontal = 12.dp),
        contentAlignment = if (compact) Alignment.Center else Alignment.CenterStart) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (icon != null) Icon(icon, contentDescription = null,
                tint = if (focused) Color.Black else Color.White, modifier = Modifier.size(20.dp))
            if (!compact) Text(label, fontSize = 16.sp, color = if (focused) Color.Black else Color.White)
        }
    }
}

@Composable
private fun GuideHeader(source: PlaybackSource?, title: Title?, resolving: Boolean, error: String?, programme: Programme?) {
    Row(Modifier.fillMaxWidth().height(205.dp).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Box(Modifier.width(310.dp).fillMaxHeight().background(Color.Black, RoundedCornerShape(8.dp))) {
            if (source != null) ChannelPreview(source)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title?.name ?: "Select a channel", fontSize = 23.sp, fontWeight = FontWeight.Medium, color = Color.White)
            Text(error ?: if (resolving) "Connecting…" else programme?.title ?: "No programme information", color = muted, fontSize = 16.sp)
            programme?.let {
                val format = DateTimeFormatter.ofPattern("h:mm a").withZone(ZoneId.systemDefault())
                Text("${format.format(it.start)} – ${format.format(it.end)}", color = muted, fontSize = 14.sp)
            }
            Text("Press OK again to watch full screen", color = muted, fontSize = 12.sp)
        }
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun ChannelPreview(source: PlaybackSource) {
    val context = LocalContext.current
    val player = remember(source) {
        val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(source.headers)
        ExoPlayer.Builder(context).setMediaSourceFactory(
            DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http))
        ).build().apply { setMediaItem(MediaItem.fromUri(source.uri)); prepare() }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    LifecycleStartEffect(player) {
        player.playWhenReady = true
        onStopOrDispose { player.playWhenReady = false }
    }
    AndroidView(factory = { viewContext ->
        PlayerView(viewContext).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            this.player = player
            useController = false
            isFocusable = false
        }
    }, update = { it.player = player }, onRelease = { it.player = null }, modifier = Modifier.fillMaxSize())
}
