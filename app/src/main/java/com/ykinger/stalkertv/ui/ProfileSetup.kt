package com.ykinger.stalkertv.ui

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import java.net.HttpURLConnection
import java.net.URI
import java.net.NetworkInterface
import java.net.URLEncoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray

internal data class PortalProfile(val serial: String, val host: String, val mac: String, val name: String = host)

/** Stores configuration encrypted; authentication tokens remain in memory only. */
private class ProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("portal-profile", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        return (store.getKey("portal-profile", null) as? SecretKey)
            ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder("portal-profile",
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
            }.generateKey()
    }
    fun save(profiles: List<PortalProfile>, selectedIndex: Int) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = JSONArray().apply {
            profiles.forEach { profile ->
                put(JSONObject().put("serial", profile.serial).put("host", profile.host)
                    .put("mac", profile.mac).put("name", profile.name))
            }
        }.toString().toByteArray(Charsets.UTF_8)
        check(prefs.edit().putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString("data", Base64.encodeToString(cipher.doFinal(data), Base64.NO_WRAP))
            .putInt("selected_index", selectedIndex).commit())
    }
    fun selectedIndex(): Int = prefs.getInt("selected_index", 0) // Existing saved playlists predate selection persistence.
    fun select(index: Int) {
        check(prefs.edit().putInt("selected_index", index).commit())
    }
    fun load(): List<PortalProfile> {
        val data = prefs.getString("data", null) ?: return emptyList()
        val iv = prefs.getString("iv", null) ?: error("Missing IV")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        val plaintext = String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        // Preserve profiles saved by the original single-profile implementation.
        val entries = if (plaintext.trimStart().startsWith("[")) JSONArray(plaintext)
            else JSONArray().put(JSONObject(plaintext))
        return (0 until entries.length()).map { index ->
            val json = entries.getJSONObject(index)
            PortalProfile(json.getString("serial"), json.getString("host"), json.getString("mac"),
                json.optString("name").ifBlank { json.getString("host") })
        }
    }
}

private fun endpoint(host: String): String {
    val uri = URI(if (host.contains("://")) host else "http://$host")
    require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()
        && uri.userInfo == null && uri.query == null && uri.fragment == null
        && (uri.port == -1 || uri.port in 1..65535))
    val path = uri.path.orEmpty().trimEnd('/')
    return uri.toString().trimEnd('/') + if (path.endsWith(".php")) "" else "/server/load.php"
}

internal fun portalRequest(profile: PortalProfile, query: String, token: String? = null): Any {
        val connection = URI("${profile.host}?$query").toURL().openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Cookie", "mac=${profile.mac}")
            if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
            when (connection.responseCode) {
                401, 403 -> error("Device registration or session rejected.")
                200 -> Unit
                else -> error("Portal returned an unexpected HTTP status.")
            }
            val body = connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= 1024 * 1024) { "Portal response is too large." }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            return JSONObject(String(body, Charsets.UTF_8)).get("js")
        } finally {
            connection.disconnect()
        }
}

private fun testPortal(profile: PortalProfile): String {
    fun request(query: String, token: String? = null) = portalRequest(profile, query, token) as JSONObject
    val token = request("type=stb&action=handshake&JsHttpRequest=1-xml").getString("token")
    check(token.isNotBlank() && token.none { it == '\r' || it == '\n' })
    val serialParam = if (profile.serial.isBlank()) "" else "&sn=${URLEncoder.encode(profile.serial, "UTF-8")}"
    val profileResult = request("type=stb&action=get_profile${serialParam}&JsHttpRequest=1-xml", token)
    check(!profileResult.has("error") && profileResult.has("id"))
    return token
}

/** Android often hides hardware MACs; never substitute the platform's placeholder or a demo MAC. */
private fun availableDeviceMac(): String? = try {
    NetworkInterface.getNetworkInterfaces()?.toList()
        ?.filter { it.name == "eth0" || it.name == "wlan0" }
        ?.firstNotNullOfOrNull { network ->
            network.hardwareAddress?.takeIf { it.size == 6 }?.joinToString(":") {
                "%02X".format(it.toInt() and 0xFF)
            }?.takeUnless { it == "02:00:00:00:00:00" || it == "00:00:00:00:00:00" }
        }
} catch (_: Exception) { null }

@Composable
fun ProfileSetup(onDemo: @Composable () -> Unit) {
    val context = LocalContext.current
    val store = remember { ProfileStore(context) }
    var profiles by remember { mutableStateOf<List<PortalProfile>>(emptyList()) }
    var loadingProfiles by remember { mutableStateOf(true) }
    var screen by remember { mutableStateOf("landing") }
    var serial by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var mac by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var selectedProfile by remember { mutableStateOf<PortalProfile?>(null) }
    var sessionToken by remember { mutableStateOf<String?>(null) }
    var reconnectProfile by remember { mutableStateOf<PortalProfile?>(null) }
    var processedChannels by remember { mutableStateOf<Int?>(null) }
    var processedName by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val firstFocus = remember { FocusRequester() }
    // A newly created activity starts at Home, not a restored player/details route.
    val homeInstance = remember { java.util.UUID.randomUUID().toString() }
    fun connect(profile: PortalProfile) {
        selectedProfile = profile
        sessionToken = null
        reconnectProfile = profile
        busy = true
        screen = "connecting"
        message = "Connecting…"
        scope.launch {
            try {
                val token = withContext(Dispatchers.IO) {
                    val token = testPortal(profile)
                    store.select(profiles.indexOf(profile).also { require(it >= 0) })
                    token
                }
                sessionToken = token
                reconnectProfile = null
                message = "Connection successful."
                screen = "home"
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                selectedProfile = null
                message = "Connection failed. Check the host, registration and server, then retry."
            } finally { busy = false }
        }
    }
    LaunchedEffect(Unit) {
        val detected = withContext(Dispatchers.IO) { availableDeviceMac() }
        if (mac.isBlank() && detected != null) mac = detected
    }
    LaunchedEffect(Unit) {
        try {
            val (saved, index) = withContext(Dispatchers.IO) { store.load() to store.selectedIndex() }
            profiles = saved
            if (index in saved.indices) connect(saved[index])
        } catch (_: Exception) { message = "Saved playlist could not be read. Add it again." }
        finally { loadingProfiles = false }
    }
    LaunchedEffect(screen, busy) {
        if (screen != "demo" && screen != "home" && !(screen == "connecting" && busy)) {
            withFrameNanos { }
            // Connection and playlist loading can replace the screen before its
            // first focus target is attached. Do not crash during that transition.
            try { firstFocus.requestFocus() } catch (_: IllegalStateException) { }
        }
    }
    BackHandler(screen != "landing" && screen != "home" && screen != "connecting") {
        if (!busy) screen = when (screen) {
            "form" -> "protocol"
            "protocol" -> if (profiles.isEmpty()) "landing" else "settings"
            "home" -> "settings"
            else -> "landing"
        }
    }
    if (screen == "demo") {
        onDemo()
        return
    }
    val connectedProfile = selectedProfile
    val connectedToken = sessionToken
    if (screen == "home" && connectedProfile != null && connectedToken != null) {
        val repository = remember(connectedProfile, connectedToken) {
            StalkerContentRepository(connectedProfile, connectedToken)
        }
        val container = remember(repository) { com.ykinger.stalkertv.AppContainer(repository) }
        key(homeInstance) {
            TvApp(container, demo = false, onProfileSettings = { screen = "settings" })
        }
        return
    }
    when (screen) {
        "processed" -> Row(Modifier.fillMaxSize().background(Color(0xFF101417))) {
            Box(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF202528)).padding(40.dp),
                contentAlignment = Alignment.CenterStart) {
                Text("Playlist is processed", fontSize = 32.sp, color = Color.White)
            }
            Column(Modifier.weight(0.8f).fillMaxHeight().padding(36.dp),
                verticalArrangement = Arrangement.Center) {
                Text(processedChannels?.let { "Channels: $it" } ?: "Playlist saved", fontSize = 21.sp, color = Color.White)
                Spacer(Modifier.height(24.dp))
                ProfileField("Playlist name", processedName, !busy) { processedName = it }
                if (message.startsWith("Could not save playlist name") || message.startsWith("Enter a playlist name"))
                    Text(message, color = Color.White)
            }
            Column(Modifier.width(160.dp).fillMaxHeight().padding(24.dp),
                verticalArrangement = Arrangement.Center) {
                Button(enabled = !busy, onClick = {
                    val profile = connectedProfile ?: return@Button
                    val name = processedName.trim()
                    if (name.isEmpty() || name.length > 100) {
                        message = "Enter a playlist name (up to 100 characters)."
                        return@Button
                    }
                    busy = true
                    scope.launch {
                        try {
                            val renamed = profile.copy(name = name)
                            val updated = profiles.map { if (it == profile) renamed else it }
                            withContext(Dispatchers.IO) { store.save(updated, updated.indexOf(renamed)) }
                            profiles = updated
                            selectedProfile = renamed
                            screen = "home"
                        } catch (_: Exception) {
                            message = "Could not save playlist name. Please retry."
                        } finally { busy = false }
                    }
                }, modifier = Modifier.focusRequester(firstFocus)) { Text("Done") }
                Spacer(Modifier.height(12.dp))
                Button(onClick = { screen = "settings" }) { Text("Playlists") }
            }
        }
        "connecting" -> Box(Modifier.fillMaxSize().background(Color(0xFF0B101A)), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(if (busy) "Connecting to playlist…" else message)
                if (!busy) {
                    Button(onClick = { reconnectProfile?.let(::connect) }, modifier = Modifier.focusRequester(firstFocus)) { Text("Retry connection") }
                    Button(onClick = { screen = "settings" }) { Text("Manage playlists") }
                    Button(onClick = { screen = "demo" }) { Text("Open offline demo") }
                }
            }
        }
        "landing" -> Box(Modifier.fillMaxSize().background(Color(0xFF101417)), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth(0.82f).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Text("STALKER TV", fontSize = 30.sp, color = Color(0xFF55A8D7))
                if (loadingProfiles) Text("Loading playlists…")
                else if (profiles.isEmpty()) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Stalker TV does not provide any content", fontSize = 25.sp, color = Color.White)
                    Text("Add your own playlist to start watching", color = Color(0xFFABB0B3))
                }
                else {
                    Text("Choose a playlist", fontSize = 28.sp)
                    profiles.forEachIndexed { index, playlist ->
                        Button(enabled = !busy, onClick = { connect(playlist) }) {
                            Text("${playlist.name} · ${playlist.host}")
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(enabled = !loadingProfiles, onClick = { screen = "protocol" }, modifier = Modifier.focusRequester(firstFocus)) { Text("Add playlist") }
                    Button(onClick = { screen = "settings" }) { Text("Settings") }
                    Button(onClick = { screen = "demo" }) { Text("Open offline demo") }
                }
                if (message.isNotEmpty()) Text(message)
            }
        }
        "settings" -> Column(Modifier.fillMaxSize().background(Color(0xFF101417)).padding(40.dp)
            .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Settings", fontSize = 28.sp)
            Text(if (loadingProfiles) "Loading playlists…" else if (profiles.isEmpty()) "No playlists saved." else "Saved playlists")
            profiles.forEachIndexed { index, profile ->
                Column(Modifier.fillMaxWidth().background(Color(0xFF202528)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)) {
                     Text("Playlist ${index + 1} · ${profile.name}")
                    Text("Host: ${profile.host}")
                    Text("Serial ID: ${profile.serial.ifBlank { "Not provided" }}")
                    Text("MAC: ${profile.mac}")
                    if (selectedProfile == profile && sessionToken != null) Text("Selected · Connected")
                    Button(enabled = !busy, onClick = { connect(profile) }) { Text("Select and connect playlist ${index + 1}") }
                }
            }
            Button(enabled = !loadingProfiles && !busy, onClick = { message = ""; screen = "protocol" }, modifier = Modifier.focusRequester(firstFocus)) {
                Text(if (profiles.isEmpty()) "Add playlist" else "Add another playlist")
            }
            if (selectedProfile != null) {
                Button(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        try {
                            withContext(Dispatchers.IO) { store.select(-1) }
                            selectedProfile = null
                            sessionToken = null
                            reconnectProfile = null
                            message = "Disconnected. No playlist selected."
                        } catch (_: Exception) {
                            message = "Could not update playlist selection. Please retry."
                        } finally { busy = false }
                    }
                }) { Text("Deselect playlist") }
            } else Text("No playlist selected. Not connected.")
            Button(enabled = !busy, onClick = { screen = if (sessionToken != null) "home" else "landing" }) { Text("Back") }
            if (message.isNotEmpty()) Text(message)
        }
        "protocol", "form" -> Row(Modifier.fillMaxSize().background(Color(0xFF101417))) {
            Box(Modifier.weight(1.1f).fillMaxHeight().background(Color(0xFF202528)).padding(30.dp),
                contentAlignment = Alignment.CenterStart) {
                Text(if (screen == "protocol") "Add playlist" else "Stalker Portal", fontSize = 32.sp, color = Color.White)
            }
            if (screen == "protocol") {
                Column(Modifier.weight(1f).fillMaxHeight().background(Color(0xFF101417)).padding(32.dp),
                    verticalArrangement = Arrangement.Center) {
                    Text("Playlist type", fontSize = 24.sp, color = Color.White)
                    Spacer(Modifier.height(22.dp))
                    Button(onClick = { message = ""; screen = "form" }, modifier = Modifier.focusRequester(firstFocus)) { Text("Stalker Portal") }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { screen = if (profiles.isEmpty()) "landing" else "settings" }) { Text("Back") }
                }
            } else {
                Column(Modifier.weight(1.15f).fillMaxHeight().background(Color(0xFF101417)).padding(24.dp)
                    .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Connection details", fontSize = 22.sp, color = Color.White)
                    ProfileField("Portal host/IP", host, !busy, Modifier.focusRequester(firstFocus)) { host = it }
                    Text("Docker emulator: http://10.0.2.2:8080", fontSize = 12.sp)
                    ProfileField("MAC address", mac, !busy) { mac = it }
                    Text("Use your provider-registered MAC. Docker demo: 00:1A:79:00:00:01", fontSize = 12.sp)
                    ProfileField("Serial ID (optional)", serial, !busy) { serial = it }
                    if (message.isNotEmpty()) Text(message)
                }
                Column(Modifier.weight(0.55f).fillMaxHeight().background(Color(0xFF101417)).padding(20.dp),
                    verticalArrangement = Arrangement.Center) {
                    Button(enabled = !busy, onClick = {
                    val candidate = try {
                        require(serial.length <= 256)
                        require(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}").matches(mac.trim()))
                        PortalProfile(serial.trim(), endpoint(host.trim()), mac.trim().uppercase())
                    } catch (_: Exception) {
                        message = "Enter a valid HTTP(S) host and MAC in AA:BB:CC:DD:EE:FF format. Serial ID is optional."
                        return@Button
                    }
                    if (profiles.any { it.host == candidate.host && it.mac == candidate.mac && it.serial == candidate.serial }) {
                        message = "This playlist is already saved."
                        return@Button
                    }
                    busy = true
                    selectedProfile = candidate
                    sessionToken = null
                    message = "Testing connection…"
                    scope.launch {
                        try {
                            val updated = profiles + candidate
                            val (token, count) = withContext(Dispatchers.IO) {
                                val token = testPortal(candidate)
                                store.save(updated, updated.lastIndex)
                                val result = StalkerContentRepository(candidate, token).catalogue()
                                token to (result as? com.ykinger.stalkertv.core.ProviderResult.Success)?.value?.titles
                                    ?.count { it.kind == com.ykinger.stalkertv.core.ContentKind.CHANNEL }
                            }
                            profiles = updated
                            sessionToken = token
                            processedChannels = count
                            processedName = URI(candidate.host).host ?: "Stalker Portal"
                            serial = ""; host = ""; mac = ""
                            message = "Connection verified. Playlist saved securely."
                            screen = "processed"
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (_: java.net.SocketTimeoutException) {
                            message = "Connection timed out. Check the host and retry."
                        } catch (_: java.net.ConnectException) {
                            message = "Cannot connect. Check the host and that Docker is running."
                        } catch (_: Exception) {
                            message = "Connection or save failed. Check registration, host, and portal response."
                        } finally { busy = false }
                    }
                    }) { Text(if (busy) "Testing…" else "Next") }
                    Spacer(Modifier.height(12.dp))
                    Button(enabled = !busy, onClick = { screen = "protocol" }) { Text("Back") }
                }
            }
        }
    }
}

@Composable
private fun ProfileField(label: String, value: String, enabled: Boolean, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Text(label, color = Color(0xFFABB0B3))
    BasicTextField(value = value, onValueChange = onChange, enabled = enabled,
        singleLine = true, textStyle = TextStyle(color = if (focused) Color.Black else Color.White),
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }
            .background(if (focused) Color(0xFFE0E3E4) else Color(0xFF292E31), androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
            .padding(16.dp))
}
