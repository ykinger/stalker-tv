package com.ykinger.stalkertv.ui

import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.PlayArrow
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
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch

private val background = Color(0xFF101417)
private val panel = Color(0xFF202528)
private val cell = Color(0xFF292E31)
private val muted = Color(0xFFABB0B3)
private val accent = Color(0xFF55A8D7)
private val focusColor = Color(0xFFE0E3E4)

/** The connected experience is guide-first; the offline sample catalogue remains separate. */
@Composable
internal fun ConnectedTvScreen(container: AppContainer, onManagePlaylists: () -> Unit) {
    val model: CatalogueViewModel = viewModel(
        key = "connected-${System.identityHashCode(container)}",
        factory = viewModelFactory { initializer { CatalogueViewModel(container) } },
    )
    val state by model.state.collectAsStateWithLifecycle()
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
    val guideFocus = remember { FocusRequester() }
    val groupsFocus = remember { FocusRequester() }
    val menuFocus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }

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

    LaunchedEffect(area, section, state, group) {
        withFrameNanos { }
        when (area) {
            "menu" -> menuFocus.requestFocus()
            "groups" -> if (section == "TV" && state is LoadState.Ready) groupsFocus.requestFocus()
            "guide" -> if (section == "TV" && state is LoadState.Ready &&
                (state as LoadState.Ready<Catalogue>).value.titles.any {
                    it.kind == ContentKind.CHANNEL && (group == null || it.categoryId == group)
                }) guideFocus.requestFocus()
            "content" -> if (section == "Settings") guideFocus.requestFocus()
                else if (section == "Search") searchFocus.requestFocus()
        }
    }

    if (fullScreen && source != null) {
        SamplePlayer(source!!, demo = false) { fullScreen = false }
        return
    }
    BackHandler(area != "menu") {
        when (area) {
            "guide" -> area = "groups"
            "groups", "content" -> area = "menu"
        }
    }
    Row(Modifier.fillMaxSize().background(background)) {
        if (area == "menu") {
            Column(Modifier.width(210.dp).fillMaxHeight().background(panel).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("STALKER TV", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                    color = accent, modifier = Modifier.padding(bottom = 80.dp))
                val menu = listOf(
                    "Search" to Icons.Default.Search,
                    "TV" to Icons.Default.PlayArrow,
                    "Recordings" to Icons.AutoMirrored.Filled.List,
                    "My list" to Icons.Default.Favorite,
                    "Settings" to Icons.Default.Settings,
                )
                menu.forEachIndexed { index, (item, icon) ->
                    GuideAction(item, selected = section == item,
                        modifier = if (index == 0) Modifier.focusRequester(menuFocus) else Modifier,
                        icon = icon,
                        onClick = {
                            section = item
                            area = if (item == "TV") "groups" else "content"
                        })
                }
            }
        }
        if (section == "TV") {
            when (val loaded = state) {
                is LoadState.Ready -> {
                    val catalogue = loaded.value
                    val channels = catalogue.titles.filter { it.kind == ContentKind.CHANNEL }
                    val visible = channels.filter { group == null || it.categoryId == group }
                    if (area == "groups" || area == "menu") {
                        Column(Modifier.width(300.dp).fillMaxHeight().padding(start = 12.dp, top = 192.dp, end = 12.dp)) {
                            GuideAction("All channels", group == null, Modifier.focusRequester(groupsFocus)) {
                                group = null; area = "guide"
                            }
                            catalogue.categories.filter { category -> channels.any { it.categoryId == category.id } }
                                .forEach { category ->
                                    GuideAction(category.name, group == category.id) {
                                        group = category.id; area = "guide"
                                    }
                                }
                        }
                    }
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        GuideHeader(source, selectedId?.let { id -> channels.find { it.id == id } }, resolving, error)
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
                                            .onFocusChanged { focused = it.isFocused }
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
                                        repeat(2) {
                                            Box(Modifier.weight(1f).fillMaxHeight().padding(2.dp)
                                                .background(if (focused) focusColor else cell, RoundedCornerShape(4.dp)),
                                                contentAlignment = Alignment.CenterStart) {
                                                Text("No information", fontSize = 14.sp,
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
            Column(Modifier.weight(1f).fillMaxHeight().padding(40.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Text(section, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = Color.White)
                when (section) {
                    "Settings" -> {
                        GuideAction("Playlists", modifier = Modifier.focusRequester(guideFocus), onClick = onManagePlaylists)
                        GuideAction("Refresh channels", onClick = model::refresh)
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
                    else -> Text("No ${section.lowercase()} yet", color = muted)
                }
            }
        }
    }
}

@Composable
private fun GuideAction(label: String, selected: Boolean = false, modifier: Modifier = Modifier,
    icon: ImageVector? = null, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth().height(48.dp)
        .onFocusChanged { focused = it.isFocused }
        .background(if (focused) focusColor else if (selected) cell else Color.Transparent, RoundedCornerShape(5.dp))
        .clickable(onClick = onClick).focusable().padding(horizontal = 12.dp),
        contentAlignment = Alignment.CenterStart) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (icon != null) Icon(icon, contentDescription = null,
                tint = if (focused) Color.Black else Color.White, modifier = Modifier.size(20.dp))
            Text(label, fontSize = 16.sp, color = if (focused) Color.Black else Color.White)
        }
    }
}

@Composable
private fun GuideHeader(source: PlaybackSource?, title: Title?, resolving: Boolean, error: String?) {
    Row(Modifier.fillMaxWidth().height(205.dp).padding(14.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Box(Modifier.width(310.dp).fillMaxHeight().background(Color.Black, RoundedCornerShape(8.dp))) {
            if (source != null) ChannelPreview(source)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title?.name ?: "Select a channel", fontSize = 23.sp, fontWeight = FontWeight.Medium, color = Color.White)
            Text(error ?: if (resolving) "Connecting…" else "No programme information", color = muted, fontSize = 16.sp)
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
