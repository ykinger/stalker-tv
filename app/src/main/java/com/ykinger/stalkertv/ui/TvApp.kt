@file:OptIn(
    androidx.tv.material3.ExperimentalTvMaterial3Api::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
)

package com.ykinger.stalkertv.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.*
import androidx.tv.material3.*
import com.ykinger.stalkertv.AppContainer
import com.ykinger.stalkertv.R
import com.ykinger.stalkertv.core.*
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.net.HttpURLConnection
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class Section(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Default.Home),
    LIVE("Live TV", Icons.Default.PlayArrow),
    MOVIES("Movies", Icons.AutoMirrored.Filled.List),
    SERIES("Series", Icons.Default.Menu),
    FAVOURITES("Favourites", Icons.Default.Favorite),
    SEARCH("Search", Icons.Default.Search),
    SETTINGS("Settings", Icons.Default.Settings),
}

@Composable
fun TvApp(container: AppContainer, demo: Boolean = true, onProfileSettings: () -> Unit = {}) {
    if (!demo) {
        ConnectedTvScreen(container, onProfileSettings)
        return
    }
    val model: CatalogueViewModel =
        viewModel(key = "catalogue-${System.identityHashCode(container)}", factory = viewModelFactory { initializer { CatalogueViewModel(container) } })
    val scenario by model.scenario.collectAsStateWithLifecycle()
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "browse") {
        composable("browse") { BrowseScreen(model, demo, onProfileSettings) { id -> nav.navigate("details/${android.net.Uri.encode(id)}") } }
        composable("details/{id}") { entry ->
            val id = requireNotNull(entry.arguments?.getString("id"))
            val detail: DetailViewModel =
                viewModel(
                    key = "$id-$scenario",
                    factory =
                        viewModelFactory { initializer { DetailViewModel(id, model.repository) } },
                )
            DetailScreen(detail, { nav.popBackStack() }, { nav.navigate("player/${android.net.Uri.encode(it)}") }, demo)
        }
        composable("player/{id}") { entry ->
            val id = requireNotNull(entry.arguments?.getString("id"))
            val playback: PlaybackViewModel =
                viewModel(
                    key = "$id-$scenario",
                    factory =
                        viewModelFactory { initializer { PlaybackViewModel(id, model.repository) } },
                )
            val state by playback.state.collectAsStateWithLifecycle()
            StateContent(state, playback::refresh) { SamplePlayer(it, demo = demo) { nav.popBackStack() } }
        }
    }
}

@Composable
private fun BrowseScreen(model: CatalogueViewModel, demo: Boolean, onProfileSettings: () -> Unit, onOpen: (String) -> Unit) {
    val state by model.state.collectAsStateWithLifecycle()
    val scenario by model.scenario.collectAsStateWithLifecycle()
    var section by rememberSaveable { mutableStateOf(Section.HOME) }
    var menuFocused by remember { mutableStateOf(true) }
    var restoreTitleId by rememberSaveable { mutableStateOf<String?>(null) }
    val menuFocus = remember { FocusRequester() }
    val contentFocus = remember { FocusRequester() }
    val itemFocus = remember { Section.entries.associateWith { FocusRequester() } }
    LaunchedEffect(Unit) { if (restoreTitleId == null) itemFocus.getValue(section).requestFocus() }
    BackHandler(enabled = !menuFocused) { itemFocus.getValue(section).requestFocus() }
    NavigationDrawer(
        modifier =
            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(24.dp),
        drawerContent = {
            Column(
                Modifier.width(158.dp)
                     .fillMaxHeight()
                     .verticalScroll(rememberScrollState())
                    .focusRequester(menuFocus)
                    .onFocusChanged { menuFocused = it.hasFocus }
                    .focusGroup(),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    "STALKER TV",
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    modifier = Modifier.padding(12.dp),
                )
                Text(
                    if (demo) "MOCK PREVIEW" else "STALKER PROFILE",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(start = 12.dp, bottom = 18.dp),
                )
                Section.entries.forEach { item ->
                    NavigationDrawerItem(
                        selected = section == item,
                        onClick = {
                            section = item
                            contentFocus.requestFocus()
                        },
                        leadingContent = { Icon(item.icon, contentDescription = null) },
                        modifier =
                            Modifier.height(44.dp)
                                .focusRequester(itemFocus.getValue(item))
                                .onFocusChanged { if (it.isFocused) section = item }
                                .onPreviewKeyEvent {
                                    if (
                                        it.type == KeyEventType.KeyDown &&
                                            it.key == Key.DirectionRight
                                    ) {
                                        contentFocus.requestFocus()
                                        true
                                    } else false
                                },
                    ) {
                        Text(item.label)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Your content. Your space.",
                    fontSize = 10.sp,
                    color = Color(0xFF95A4B8),
                    modifier = Modifier.padding(12.dp),
                )
            }
        },
    ) {
        Column(
            Modifier.fillMaxSize().padding(start = 28.dp).focusRequester(contentFocus).focusGroup()
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    section.label,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                )
                if (demo) Text("DEMO / ${scenario.name}", color = MaterialTheme.colorScheme.primary, fontSize = 11.sp)
                else Button(onClick = onProfileSettings) { Text("Manage playlists") }
            }
            Text(
                if (section == Section.LIVE) { if (demo) "Sample channels • programme times are local" else "Provider channels • programme times are local" }
                else "A preview of your next watch",
                color = Color(0xFF95A4B8),
                modifier = Modifier.padding(top = 4.dp, bottom = 18.dp),
            )
            when (section) {
                Section.SETTINGS -> if (demo) Settings(scenario, model::setScenario) else Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = onProfileSettings) { Text("Manage playlists") }
                    Button(onClick = model::refresh) { Text("Refresh channels") }
                }
                Section.FAVOURITES ->
                    MessagePanel(
                        "Your favourites will appear here. Saving favourites is planned for LIB-001."
                    ) {
                        section = Section.MOVIES
                    }
                else ->
                    StateContent(state, model::refresh) { catalogue ->
                        key(section) {
                            CatalogueContent(
                                section,
                                catalogue,
                                demo,
                                restoreTitleId,
                                { restoreTitleId = null },
                            ) { id ->
                                restoreTitleId = id
                                onOpen(id)
                            }
                        }
                    }
            }
        }
    }
}

@Composable
private fun CatalogueContent(
    section: Section,
    catalogue: Catalogue,
    demo: Boolean,
    restoreTitleId: String?,
    onRestored: () -> Unit,
    onOpen: (String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    Column {
        if (section == Section.SEARCH) {
            BasicTextField(
                query,
                { query = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
                modifier =
                    Modifier.fillMaxWidth()
                        .background(Color(0xFF1D2B3E), RoundedCornerShape(8.dp))
                        .padding(16.dp),
            )
            Text(
                "Type a title using your keyboard or TV input method",
                color = Color(0xFF95A4B8),
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(bottom = 14.dp),
            ) {
                FilterChip(selected = category == null, onClick = { category = null }) {
                    Text("All")
                }
                catalogue.categories.forEach { group ->
                    FilterChip(selected = category == group.id, onClick = { category = group.id }) {
                        Text(group.name)
                    }
                }
            }
        }
        val titles =
            catalogue.titles.filter { title ->
                val kindMatches =
                    when (section) {
                        Section.LIVE -> title.kind == ContentKind.CHANNEL
                        Section.MOVIES -> title.kind == ContentKind.MOVIE
                        Section.SERIES -> title.kind == ContentKind.SERIES
                         Section.HOME -> !demo || title.kind != ContentKind.CHANNEL
                        else -> true
                    }
                kindMatches &&
                    (category == null || title.categoryId == category) &&
                    title.name.contains(query, ignoreCase = true)
            }
        if (titles.isEmpty()) {
            MessagePanel(if (!demo && section in listOf(Section.MOVIES, Section.SERIES)) "No ${section.label.lowercase()} available from this connection yet." else "No titles match your selection.") {
                query = ""
                category = null
            }
            return@Column
        }
        val gridState = rememberLazyGridState()
        val cardFocus = remember(titles) { titles.associate { it.id to FocusRequester() } }
        LaunchedEffect(restoreTitleId, titles) {
            val index = titles.indexOfFirst { it.id == restoreTitleId }
            if (index >= 0) {
                gridState.scrollToItem(index)
                withFrameNanos {}
                cardFocus.getValue(titles[index].id).requestFocus()
                onRestored()
            }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(if (section == Section.LIVE) 200.dp else 120.dp),
            state = gridState,
            contentPadding = PaddingValues(10.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize().focusRestorer(),
        ) {
            items(titles, key = { it.id }) { title ->
                PosterCard(title, Modifier.fillMaxWidth().focusRequester(cardFocus.getValue(title.id))) { onOpen(title.id) }
            }
        }
    }
}

@Composable
internal fun PosterCard(title: Title, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier, scale = CardDefaults.scale(focusedScale = 1.04f)) {
        Column {
            Artwork(title, Modifier.fillMaxWidth().aspectRatio(if (title.kind == ContentKind.CHANNEL) 2.3f else 0.70f))
            Column(Modifier.padding(10.dp)) {
                Text(title.name, maxLines = 2, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(if (title.kind == ContentKind.CHANNEL) "View guide & watch"
                    else "${title.year ?: ""}  •  ${title.kind.name.lowercase()}",
                    fontSize = 10.sp, color = Color(0xFF95A4B8))
            }
        }
    }
}

@Composable
internal fun Artwork(title: Title, modifier: Modifier = Modifier) {
    val drawable =
        when (title.artworkKey) {
            "mountain" -> R.drawable.poster_mountain
            "ocean" -> R.drawable.poster_ocean
            "orbit" -> R.drawable.poster_orbit
            else -> null
        }
    val remote by produceState<ImageBitmap?>(null, title.artworkKey) {
        val address = title.artworkKey ?: return@produceState
        if (drawable != null) return@produceState
        value = withContext(Dispatchers.IO) {
            try {
                val uri = URI(address)
                if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null)
                    return@withContext null
                val connection = uri.toURL().openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 5000
                    connection.readTimeout = 5000
                    connection.instanceFollowRedirects = false
                    if (connection.responseCode != 200 ||
                        !connection.contentType.orEmpty().startsWith("image/")) return@withContext null
                    val data = connection.inputStream.use { input ->
                        val output = java.io.ByteArrayOutputStream()
                        val buffer = ByteArray(8192)
                        while (output.size() <= 2_000_000) {
                            val count = input.read(buffer, 0, minOf(buffer.size, 2_000_001 - output.size()))
                            if (count < 0) break
                            output.write(buffer, 0, count)
                        }
                        output.toByteArray()
                    }
                    if (data.size > 2_000_000) return@withContext null
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
                    val options = BitmapFactory.Options().apply {
                        inSampleSize = (maxOf(bounds.outWidth, bounds.outHeight) / 512).coerceAtLeast(1)
                    }
                    BitmapFactory.decodeByteArray(data, 0, data.size, options)?.asImageBitmap()
                } finally { connection.disconnect() }
            } catch (_: Exception) { null }
        }
    }
    Box(modifier.background(Color(0xFF24354A)), contentAlignment = Alignment.Center) {
        if (drawable != null)
            Image(
                painterResource(drawable),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        else if (remote != null)
            Image(bitmap = remote!!, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize())
        Text(
            if (drawable == null && remote == null) title.name else title.name.uppercase(),
            fontSize = 19.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun Settings(scenario: MockScenario, onChange: (MockScenario) -> Unit) {
    Column(
        Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Mock provider", style = MaterialTheme.typography.titleLarge)
        Text(
            "No portal connection or credentials are used. Choose a scenario, then open a content section.",
            color = Color(0xFF95A4B8),
        )
        MockScenario.entries.forEach { value ->
            Button(onClick = { onChange(value) }) {
                Text(
                    "${if (scenario == value) "✓  " else ""}${value.name.lowercase().replaceFirstChar { it.uppercase() }}"
                )
            }
        }
        Text(
            "Scenario settings reset when the app process restarts. Favourites and history are not implemented yet.",
            fontSize = 12.sp,
        )
        Text(
            "Sample playback: Big Buck Bunny © Blender Foundation • CC BY 3.0. Bundled excerpt; fictional titles all play this same sample.",
            fontSize = 12.sp,
        )
    }
}

@Composable
fun <T> StateContent(state: LoadState<T>, retry: () -> Unit, content: @Composable (T) -> Unit) {
    when (state) {
        LoadState.Loading -> MessagePanel("Loading sample content…", "Refresh", retry)
        is LoadState.Message -> MessagePanel(state.text, "Retry", retry)
        is LoadState.Ready -> content(state.value)
    }
}

@Composable
private fun MessagePanel(message: String, action: String = "Browse movies", onAction: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, modifier = Modifier.padding(bottom = 20.dp))
        Button(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun DetailScreen(model: DetailViewModel, onBack: () -> Unit, onPlay: (String) -> Unit, demo: Boolean = true) {
    val state by model.state.collectAsStateWithLifecycle()
    val firstFocus = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(36.dp)) {
        StateContent(state, model::refresh) { detail ->
            LaunchedEffect(detail.title.id) { firstFocus.requestFocus() }
            Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                Artwork(detail.title, Modifier.width(210.dp).fillMaxHeight())
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    item {
                        Button(onClick = onBack, modifier = Modifier.focusRequester(firstFocus)) {
                            Text("Back")
                        }
                    }
                    item { Text(detail.title.name, style = MaterialTheme.typography.headlineLarge) }
                    item {
                        Text(
                            detail.title.description ?: "No description available.",
                            color = Color(0xFFB4C1D2),
                        )
                    }
                    item {
                        Text(
                            if (demo) "MOCK CONTENT • Playback uses Big Buck Bunny sample media" else "Provider content",
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 12.sp,
                        )
                    }
                    if (detail.title.kind == ContentKind.SERIES) {
                        detail.seasons.forEach { season ->
                            item {
                                Text(
                                    "Season ${season.number}",
                                    style = MaterialTheme.typography.titleLarge,
                                )
                            }
                            items(season.episodes, key = { it.id }) { episode ->
                                Button(onClick = { onPlay(episode.id) }) {
                                    Text("Play ${episode.name}")
                                }
                            }
                        }
                        if (detail.seasons.isEmpty()) item { Text("No episodes available.") }
                    } else {
                        item {
                            Button(onClick = { onPlay(detail.title.id) }) { Text(if (demo) "Play sample" else "Watch channel") }
                        }
                    }
                    if (detail.title.kind == ContentKind.CHANNEL) {
                        item {
                            Text("Programme guide", style = MaterialTheme.typography.titleLarge)
                        }
                        if (detail.programmes.isEmpty())
                            item {
                                Text(
                                    if (demo) "No guide data available. You can still play this channel's sample." else "No guide data available. You can still watch this channel."
                                )
                            }
                        items(detail.programmes, key = { it.id }) { programme ->
                            val time =
                                DateTimeFormatter.ofPattern("HH:mm")
                                    .withZone(ZoneId.systemDefault())
                            var focused by remember { mutableStateOf(false) }
                            Text(
                                "${time.format(programme.start)} – ${time.format(programme.end)}   ${programme.title}",
                                modifier =
                                    Modifier.fillMaxWidth()
                                        .onFocusChanged { focused = it.isFocused }
                                        .border(
                                            if (focused) 2.dp else 0.dp,
                                            if (focused) MaterialTheme.colorScheme.primary
                                            else Color.Transparent,
                                        )
                                        .focusable()
                                        .padding(12.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
