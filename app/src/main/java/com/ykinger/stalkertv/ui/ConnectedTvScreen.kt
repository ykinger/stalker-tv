package com.ykinger.stalkertv.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.flow.collect

private val background = Color(0xFF101314)
private val panel = Color(0xFF24282A)
private val cell = Color(0xFF303436)
private val muted = Color(0xFFABB0B3)
private val accent = Color(0xFF168DD0)
private val focusColor = Color(0xFFE0E3E4)

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
    var seasons by remember { mutableStateOf<List<Season>>(emptyList()) }
    var vodError by remember { mutableStateOf<String?>(null) }
    var vodLoading by remember { mutableStateOf(false) }
    val guideFocus = remember { FocusRequester() }
    val groupsFocus = remember { FocusRequester() }
    val menuFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }

    fun openSection(item: String) {
        section = item
        vodTitle = null
        vodCategory = null
        source = null
        selectedId = null
        area = if (item == "TV" || item == "Movies" || item == "Shows") "groups" else "content"
        when (item) {
            "TV", "Search" -> model.loadSection(ContentKind.CHANNEL)
            "Movies" -> model.loadSection(ContentKind.MOVIE)
            "Shows" -> model.loadSection(ContentKind.SERIES)
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
                is ProviderResult.Failure -> vodError = result.message
                else -> vodError = "No episodes available."
            }
            vodLoading = false
        }
    }

    // A newly appended page changes state but must not steal focus from the item
    // the user is scrolling past. Only initial loading/section changes refocus.
    LaunchedEffect(area, section, state is LoadState.Ready, group, vodCategory, vodTitle, seasons, vodLoading,
        (state as? LoadState.Ready<Catalogue>)?.value?.titles?.any {
            it.kind == (if (section == "Movies") ContentKind.MOVIE else ContentKind.SERIES) &&
                (vodCategory == null || it.categoryId == vodCategory)
        }) {
        withFrameNanos { }
        val loaded = (state as? LoadState.Ready<Catalogue>)?.value
        val target = when (area) {
            "menu" -> menuFocus
            "groups" -> if (section in listOf("TV", "Movies", "Shows") && loaded != null) groupsFocus else null
            "guide" -> if (section == "TV" && loaded?.titles?.any {
                it.kind == ContentKind.CHANNEL && (group == null || it.categoryId == group)
            } == true) guideFocus else null
            "content" -> when (section) {
                "Settings" -> guideFocus
                "Search" -> searchFocus
                "Movies", "Shows" -> if (loaded != null && (vodTitle != null || loaded.titles.any {
                    it.kind == (if (section == "Movies") ContentKind.MOVIE else ContentKind.SERIES) &&
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

    if (fullScreen && source != null) {
        SamplePlayer(source!!, demo = false) { fullScreen = false }
        return
    }
    val compactMenu = area != "menu"
    BackHandler(area != "menu") {
        when (area) {
            "guide" -> area = "groups"
            "groups" -> area = "menu"
            "content" -> if (vodTitle != null) vodTitle = null
                else area = if (section == "Movies" || section == "Shows") "groups" else "menu"
        }
    }
    Row(Modifier.fillMaxSize().background(background)) {
        Column(Modifier.width(if (compactMenu) 68.dp else 190.dp).fillMaxHeight().background(panel)
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
        if (section == "Movies" || section == "Shows") {
            val kind = if (section == "Movies") ContentKind.MOVIE else ContentKind.SERIES
            val loaded = state as? LoadState.Ready
            val titles = loaded?.value?.titles?.filter { it.kind == kind }.orEmpty()
            val allCategory = loaded?.value?.categories?.firstOrNull { it.id == "*" }
            Column(Modifier.width(192.dp).fillMaxHeight().background(background)
                .padding(start = 10.dp, top = 192.dp, end = 10.dp).verticalScroll(rememberScrollState())) {
                Text(section, color = Color.White, fontSize = 20.sp, modifier = Modifier.padding(12.dp))
                GuideAction(allCategory?.name ?: "All $section", selected = vodCategory == null,
                    modifier = Modifier.focusRequester(groupsFocus).onPreviewKeyEvent {
                        if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft) {
                            area = "menu"; true
                        } else false
                    }) {
                        vodCategory = null; vodTitle = null; area = "content"; model.loadSection(kind)
                    }
                loaded?.value?.categories
                    ?.filter { it.id != "*" && vodCategoryVisible(it, kind, loaded.value.titles) }
                    ?.forEach { category ->
                        GuideAction(category.name, selected = vodCategory == category.id,
                            modifier = Modifier.onPreviewKeyEvent {
                                if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft) {
                                    area = "menu"; true
                                } else false
                            }) {
                            vodCategory = category.id; vodTitle = null; area = "content"
                            model.loadSection(kind, category.id)
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
            Column(Modifier.weight(1f).fillMaxHeight().padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(section, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
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
                        val kind = if (section == "Movies") ContentKind.MOVIE else ContentKind.SERIES
                        val loadingNext by model.loadingNext.collectAsStateWithLifecycle()
                        val pageFailed by model.pageFailed.collectAsStateWithLifecycle()
                        val gridState = key(section, vodCategory) { rememberLazyGridState() }
                        var lastAutoCount by remember(section, vodCategory) { mutableIntStateOf(-1) }
                        var autoFetchesAtCount by remember(section, vodCategory) { mutableIntStateOf(0) }
                        if (loaded == null) {
                            Text((state as? LoadState.Message)?.text ?: "Loading titles…", color = muted)
                            GuideAction("Retry", modifier = Modifier.focusRequester(guideFocus), onClick = model::refresh)
                        } else if (vodTitle != null) {
                            val title = vodTitle!!
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                    Artwork(title, Modifier.width(155.dp).aspectRatio(0.70f))
                                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(title.name, color = accent, fontSize = 24.sp)
                                        title.year?.let { Text("$it", color = muted) }
                                        title.description?.let { Text(it, color = muted) }
                                        error?.let { Text(it, color = muted) }
                                        if (resolving) Text("Loading video…", color = muted)
                                        if (title.kind == ContentKind.MOVIE) {
                                            GuideAction("Play movie", modifier = Modifier.focusRequester(guideFocus)) { playVod(title) }
                                        }
                                    }
                                }
                                if (title.kind == ContentKind.SERIES) {
                                    if (vodLoading) Text("Loading episodes…", color = muted)
                                    vodError?.let { Text(it, color = muted) }
                                    if (seasons.isNotEmpty()) {
                                        var first = true
                                        seasons.forEach { season ->
                                            Text("Season ${season.number}", color = accent, fontSize = 20.sp)
                                            season.episodes.forEach { episode ->
                                                GuideAction(episode.name, modifier = if (first) Modifier.focusRequester(guideFocus) else Modifier) {
                                                    playVod(episode)
                                                }
                                                first = false
                                            }
                                        }
                                    }
                                }
                                GuideAction("Back to $section", modifier = if (title.kind == ContentKind.SERIES &&
                                    seasons.all { it.episodes.isEmpty() }) Modifier.focusRequester(guideFocus) else Modifier) {
                                    vodTitle = null
                                }
                            }
                        } else {
                            val allTitles = loaded.value.titles.filter { it.kind == kind }
                            val titles = allTitles.filter { vodCategory == null || it.categoryId == vodCategory }
                            val canPrefetch = loaded.value.hasMore && !loadingNext && !pageFailed &&
                                (lastAutoCount != titles.size || autoFetchesAtCount < 2)
                            LaunchedEffect(section, vodCategory, titles.size, canPrefetch, gridState) {
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
                                        val layout = gridState.layoutInfo
                                        val lastVisible = layout.visibleItemsInfo.lastOrNull()?.index ?: -1
                                        nearVodEnd(lastVisible, layout.visibleItemsInfo.size, layout.maxSpan, titles.size)
                                    }.collect { nearEnd ->
                                        if (nearEnd && canPrefetch) {
                                            autoFetchesAtCount++
                                            model.loadNextPage()
                                        }
                                    }
                                }
                            }
                            if (titles.isEmpty()) {
                                Text(if (loadingNext) "Loading more $section…" else "No $section on this page", color = muted)
                                if (loaded.value.hasMore && !loadingNext)
                                    GuideAction(if (pageFailed) "Retry" else "Load more", modifier = Modifier.focusRequester(guideFocus), onClick = model::loadNextPage)
                            }
                            else LazyVerticalGrid(
                                columns = GridCells.Adaptive(130.dp),
                                state = gridState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(8.dp),
                                horizontalArrangement = Arrangement.spacedBy(18.dp),
                                verticalArrangement = Arrangement.spacedBy(18.dp),
                            ) {
                                gridItems(titles, key = { it.id }) { title ->
                                    PosterCard(title, Modifier.fillMaxWidth()
                                        .then(if (title.id == titles.first().id) Modifier.focusRequester(guideFocus) else Modifier)
                                        .onPreviewKeyEvent {
                                            if (it.type == KeyEventType.KeyDown && it.key == Key.DirectionLeft &&
                                                title.id == titles.first().id) { area = "groups"; true } else false
                                        }) { vodTitle = title }
                                }
                                if (loaded.value.hasMore) {
                                    item(key = "load-more") {
                                        if (loadingNext) Text("Loading more…", color = muted)
                                        else GuideAction(if (pageFailed) "Retry" else "Load more",
                                            onClick = model::loadNextPage)
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
