package com.ykinger.stalkertv.ui

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import java.net.URI
import java.net.NetworkInterface
import java.net.URLEncoder
import java.security.KeyStore
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONArray
import org.json.JSONException
import com.ykinger.stalkertv.BuildConfig
import com.ykinger.stalkertv.R
import com.ykinger.stalkertv.AppContainer
import com.ykinger.stalkertv.TvApplication
import com.ykinger.stalkertv.core.Category
import com.ykinger.stalkertv.core.ContentKind
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import okio.Buffer

internal const val PORTAL_TAG = "StalkerPortal"
internal const val WIRE_TAG = "VibeTvWire"
// Keep the debug build usable without emitting portal, player, or input diagnostics.
internal const val DIAGNOSTIC_LOGGING = true
private const val LOGCAT_CHUNK_SIZE = 3_000
private val wireRequestSequence = AtomicLong()

private fun logWireChunks(requestId: Long, field: String, value: String) {
    if (!BuildConfig.DEBUG) return
    val chunks = value.chunked(LOGCAT_CHUNK_SIZE).ifEmpty { listOf("") }
    chunks.forEachIndexed { index, chunk ->
        Log.d(WIRE_TAG, "id=$requestId field=$field chunk=${index + 1}/${chunks.size} $chunk")
    }
}

internal data class ViewerProfile(val id: String, val name: String)

/** Portal connection owned by one viewer, or global when ownerProfileId is null. */
internal data class PortalProfile(
    val serial: String,
    val host: String,
    val mac: String,
    val name: String = host,
    val id: String = "",
    val ownerProfileId: String? = null,
)

internal fun playlistsForViewer(playlists: List<PortalProfile>, viewerId: String): List<PortalProfile> =
    playlists.filter { it.ownerProfileId == null || it.ownerProfileId == viewerId }

private val settingsBackground = Color(0xFF101314)
private val settingsPanel = Color(0xFF24282A)
private val settingsHeader = Color(0xFF303436)
private val settingsMuted = Color(0xFFABB0B3)
private val settingsBlue = Color(0xFF168DD0)

/** Viewer names contain no credentials and stay in ordinary app-private storage. */
private class ViewerProfileStore(context: Context) {
    private val prefs = context.getSharedPreferences("viewer-profiles-v1", Context.MODE_PRIVATE)

    fun initialized(): Boolean = prefs.getBoolean("initialized", false)

    fun initialize() {
        check(prefs.edit().putBoolean("initialized", true).commit())
    }

    fun save(profiles: List<ViewerProfile>) {
        val data = JSONArray().apply {
            profiles.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
        }
        check(prefs.edit().putString("profiles", data.toString()).commit())
    }

    fun load(): List<ViewerProfile> {
        val data = prefs.getString("profiles", null) ?: return emptyList()
        val entries = JSONArray(data)
        return (0 until entries.length()).mapNotNull { index ->
            val row = entries.optJSONObject(index) ?: return@mapNotNull null
            val id = row.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val name = row.optString("name").trim().takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ViewerProfile(id, name)
        }
    }
}

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
                    .put("mac", profile.mac).put("name", profile.name)
                    .put("id", profile.id)
                    .put("owner_profile_id", profile.ownerProfileId ?: JSONObject.NULL))
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
    fun clear() {
        check(prefs.edit().clear().commit())
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
                json.optString("name").ifBlank { json.getString("host") },
                json.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
                json.optString("owner_profile_id").takeUnless { it.isBlank() || it == "null" })
        }
    }
}

internal fun endpoint(host: String): String {
    val uri = URI(if (host.contains("://")) host else "http://$host")
    require(uri.scheme in listOf("http", "https") && !uri.host.isNullOrBlank()
        && uri.userInfo == null && uri.query == null && uri.fragment == null
        && (uri.port == -1 || uri.port in 1..65535)) { "Enter a portal URL without credentials, a query or a fragment." }
    return uri.toString()
}

internal class PortalResponseFormatException : IllegalStateException("Portal returned non-JSON data.")
internal class PortalHttpException(val status: Int) : IllegalStateException("Portal returned HTTP $status.")
internal data class VerifiedPortal(val apiHost: String, val token: String, val watchdogSeconds: Int?)
internal data class PortalSession(val token: String, val watchdogSeconds: Int?)
internal data class PortalCounts(val channels: Int?, val moviesAndShows: Int?)

/** Route class only: never print a playlist URL or an API query in diagnostics. */
private fun routeKind(address: URI): String = when {
    address.path.isNullOrEmpty() || address.path == "/" -> "host"
    address.path.trimEnd('/').endsWith("/c", ignoreCase = true) -> "browser"
    address.path.endsWith(".php", ignoreCase = true) -> "api"
    else -> "other"
}

/** Local debug-only comparison identity; never store provider identifiers in source control. */
internal data class CapturedIdentity(val mac: String, val serial: String, val adid: String,
    val random: String, val hwVersion2: String, val handshakePrehash: String, val profilePrehash: String)

private fun capturedIdentity(profile: PortalProfile): CapturedIdentity? {
    if (!BuildConfig.DEBUG || BuildConfig.CAPTURED_HOST.isBlank() ||
        !URI(profile.host).host.equals(BuildConfig.CAPTURED_HOST, ignoreCase = true) ||
        !profile.mac.equals(BuildConfig.CAPTURED_MAC, ignoreCase = true)) return null
    val identity = CapturedIdentity(BuildConfig.CAPTURED_MAC, BuildConfig.CAPTURED_SERIAL,
        BuildConfig.CAPTURED_ADID, BuildConfig.CAPTURED_RANDOM, BuildConfig.CAPTURED_HW_VERSION_2,
        BuildConfig.CAPTURED_HANDSHAKE_PREHASH, BuildConfig.CAPTURED_PROFILE_PREHASH)
    return identity.takeIf { listOf(it.mac, it.serial, it.adid, it.random, it.hwVersion2,
        it.handshakePrehash, it.profilePrehash).all(String::isNotBlank) }
}

private fun encoded(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

internal fun capturedHandshakeQuery(identity: CapturedIdentity): String =
    "type=stb&action=handshake&token=&prehash=${encoded(identity.handshakePrehash)}"

internal fun capturedProfileQuery(identity: CapturedIdentity, timestamp: Long): String {
    val version = "ImageDescription: 0.2.18-r22-pub-270; ImageDate: Tue Dec 19 11:33:53 EET 2017; " +
        "PORTAL version: 5.6.1; API Version: JS API version: 328; " +
        "STB API version: 134; Player Engine version: 0x566"
    val metrics = JSONObject().put("mac", identity.mac).put("sn", identity.serial)
        .put("model", "MAG270").put("type", "STB").put("uid", "")
        .put("random", identity.random).toString()
    return "type=stb&action=get_profile&ver=${encoded(version)}&sn=${encoded(identity.serial)}" +
        "&stb_type=MAG270&client_type=STB&image_version=0.2.18&device_id=&device_id2=" +
        "&auth_second_step=1&hw_version=1.7-BD-00&not_valid_token=0" +
        "&metrics=${encoded(metrics)}&hw_version_2=${encoded(identity.hwVersion2)}" +
        "&timestamp=$timestamp&api_signature=262&prehash=${encoded(identity.profilePrehash)}"
}

internal fun capturedHeaders(builder: Request.Builder, identity: CapturedIdentity): Request.Builder =
    builder.apply { capturedHeaderValues(identity).forEach { (name, value) -> header(name, value) } }

private fun capturedHeaderValues(identity: CapturedIdentity): Map<String, String> = mapOf(
    "Cookie" to "mac=${identity.mac}; timezone=America%2FMontreal; adid=${identity.adid}",
    "User-Agent" to "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 2 rev: 250 Safari/533.3",
    "X-User-Agent" to "Model: MAG270; Link: WiFi",
    "Connection" to "Keep-Alive",
)

/** Identical explicit portal identity headers for API and opted-in direct movie playback. */
internal fun portalHeaders(profile: PortalProfile, token: String?): Map<String, String> {
    val identity = capturedIdentity(profile)
    val headers = if (identity == null) mapOf(
        "Cookie" to "mac=${profile.mac}; stb_lang=en; timezone=${encoded(TimeZone.getDefault().id)}",
        "User-Agent" to "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG250 stbapp ver: 4 rev: 1812 Safari/533.3",
        "Accept" to "application/json, text/javascript, */*; q=0.01",
    ) else capturedHeaderValues(identity)
    return if (token == null) headers else headers + ("Authorization" to "Bearer $token")
}

/** STB browser-entry headers for VOD; Host must remain the actual request destination. */
internal fun vodHeaders(profile: PortalProfile, token: String?): Map<String, String> {
    val uri = URI(profile.host)
    val path = uri.path.removeSuffix("/server/load.php").removeSuffix("/portal.php")
    val browser = if (path != uri.path) "${uri.scheme}://${uri.rawAuthority}${path.trimEnd('/')}/c/" else null
    // OkHttp adds Accept-Encoding: gzip on the wire and transparently decompresses
    // responses only if we leave this header unset on the application request.
    return portalHeaders(profile, token) + (browser?.let { mapOf("Referer" to it) } ?: emptyMap())
}

internal fun portalRequest(profile: PortalProfile, query: String, token: String? = null,
    container: AppContainer = AppContainer()): Any {
    // Routine diagnostics remain sanitized. A dedicated debug-only wire stream is
    // enabled below for episode/detail and VOD create_link troubleshooting.
    val params = "https://localhost/?$query".toHttpUrl()
    val type = params.queryParameter("type")?.takeIf {
        it in setOf("stb", "itv", "vod", "series", "account_info", "watchdog", "epg")
    } ?: "other"
    val action = params.queryParameter("action")?.takeIf {
        it in setOf("handshake", "get_profile", "get_main_info", "get_genres",
            "get_categories", "get_all_channels", "get_ordered_list", "get_seasons",
            "get_series_info", "get_episodes", "get_series_data", "create_link",
            "get_short_epg", "get_epg_info", "get_simple_data_table", "get_events")
    } ?: "other"
    val stage = "$type.$action"
    val wireRequestId = if (BuildConfig.DEBUG && type == "vod" &&
        ((action == "get_ordered_list" && params.queryParameter("movie_id") != null) ||
            action == "create_link")) wireRequestSequence.incrementAndGet() else null
    var address = URI("${profile.host}?$query")
    val visited = mutableSetOf<URI>()
    try {
        repeat(6) { hop ->
            check(visited.add(address)) { "Portal redirect loop." }
            val started = System.nanoTime()
            val request = Request.Builder().url(address.toString()).apply {
                val headers = if (type == "vod" && action != "create_link") vodHeaders(profile, token)
                    else portalHeaders(profile, token)
                headers.forEach { (name, value) -> header(name, value) }
            }.build()
            wireRequestId?.let { requestId ->
                logWireChunks(requestId, "request.method", request.method)
                logWireChunks(requestId, "request.url", request.url.toString())
                // Portal operations use GET. Authentication headers and cookies
                // are intentionally excluded from this diagnostic stream.
                logWireChunks(requestId, "request.body", "<empty: GET request>")
            }
            val client = if (type == "stb") container.handshakeClient else container.portalClient
            client.newCall(request).execute().use { response ->
                val status = response.code
                wireRequestId?.let { requestId ->
                    logWireChunks(requestId, "response.status", status.toString())
                }
                if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG,
                    "stage=$stage http=$status hop=$hop route=${routeKind(address)} " +
                        "content=${when {
                            response.header("Content-Type")?.startsWith("application/json", ignoreCase = true) == true -> "json"
                            response.header("Content-Type")?.startsWith("text/html", ignoreCase = true) == true -> "html"
                            response.header("Content-Type") == null -> "missing"
                            else -> "other"
                        }} elapsedMs=${(System.nanoTime() - started) / 1_000_000}")
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val location = response.header("Location")
                    check(hop < 5) { "Too many portal redirects." }
                    address = portalRedirect(address, location)
                    wireRequestId?.let { requestId ->
                        logWireChunks(requestId, "response.body", "<not read: redirect>")
                    }
                    if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG,
                        "stage=$stage redirectHop=${hop + 1} targetRoute=${routeKind(address)}")
                    return@repeat
                }
                if (status != 200) {
                    wireRequestId?.let { requestId ->
                        val errorBuffer = Buffer()
                        response.body?.source()?.let { source ->
                            while (errorBuffer.size <= 1024L * 1024 &&
                                source.read(errorBuffer, 1024L * 1024 + 1 - errorBuffer.size) != -1L) { }
                        }
                        val errorBody = if (errorBuffer.size <= 1024L * 1024)
                            errorBuffer.readString(Charsets.UTF_8)
                        else "<response body exceeds 1 MiB limit>"
                        logWireChunks(requestId, "response.body", errorBody)
                    }
                }
                if (status != 200) throw PortalHttpException(status)
                val buffer = Buffer()
                response.body?.source()?.let { source ->
                    while (buffer.size <= 1024L * 1024 &&
                        source.read(buffer, 1024L * 1024 + 1 - buffer.size) != -1L) { }
                }
                check(buffer.size <= 1024 * 1024) { "Portal response is too large." }
                val body = buffer.readByteArray()
                val response = String(body, Charsets.UTF_8)
                wireRequestId?.let { requestId ->
                    logWireChunks(requestId, "response.body", response)
                }
                if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG, "stage=$stage bytes=${body.size} hop=$hop")
                if (!response.trimStart().startsWith("{")) {
                    if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG, "stage=$stage format=nonJson hop=$hop")
                    throw PortalResponseFormatException()
                }
                val result = try {
                    JSONObject(response).get("js")
                } catch (error: JSONException) {
                    if (stage == "stb.handshake") throw PortalResponseFormatException()
                    throw error
                }
                if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG, "stage=$stage parsed=${result.javaClass.simpleName}")
                return result
            }
        }
        error("Too many portal redirects.")
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.w(PORTAL_TAG, "stage=$stage failure=${error.javaClass.simpleName}")
        throw error
    }
}

internal fun testPortal(profile: PortalProfile, container: AppContainer): VerifiedPortal {
    var lastFailure: Exception? = null
    for ((index, candidate) in portalApiCandidates(profile.host).withIndex()) {
        val apiProfile = profile.copy(host = candidate)
        if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG,
            "stage=portal.discovery candidate=$index route=${routeKind(URI(candidate))} trying=true")
        try {
            val session = verifyPortal(apiProfile, container)
            if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG, "stage=portal.discovery candidate=$index verified=true")
            return VerifiedPortal(candidate, session.token, session.watchdogSeconds)
        } catch (error: PortalResponseFormatException) {
            lastFailure = error
            if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG,
                "stage=portal.discovery candidate=$index rejected=invalidResponse")
        } catch (error: PortalHttpException) {
            if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG,
                "stage=portal.discovery candidate=$index rejected=http status=${error.status}")
            if (error.status !in listOf(404, 405, 500, 502, 503, 504)) throw error
            lastFailure = error
        }
    }
    throw lastFailure ?: PortalResponseFormatException()
}

/** Read the server-reported totals from each filtered first page. */
internal fun portalCounts(profile: PortalProfile, verified: VerifiedPortal,
    container: AppContainer): PortalCounts {
    val apiProfile = profile.copy(host = verified.apiHost)
    fun total(kind: String, filter: String): Int? = try {
        val result = portalRequest(apiProfile,
            "type=$kind&action=get_ordered_list&$filter&p=1" +
                (if (kind == "itv") "&sortby=number&fav=0&search=" else "") +
                "&JsHttpRequest=1-xml", verified.token, container)
        (result as? JSONObject)?.optInt("total_items", -1)?.takeIf { it >= 0 }.also {
            if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG,
                "stage=portal.counts kind=$kind totalKnown=${it != null}")
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.w(PORTAL_TAG,
            "stage=portal.counts kind=$kind failure=${error.javaClass.simpleName}")
        null
    }
    return PortalCounts(total("itv", "genre=*"), total("vod", "category=*"))
}

/** The same authenticated profile query is used for verification and resume health checks. */
internal fun portalProfileQuery(profile: PortalProfile): String {
    val identity = capturedIdentity(profile)
    if (identity != null) return capturedProfileQuery(identity, System.currentTimeMillis() / 1000)
    val serialParam = if (profile.serial.isBlank()) "" else "&sn=${URLEncoder.encode(profile.serial, "UTF-8")}"
    return "type=stb&action=get_profile${serialParam}" +
        "&stb_type=MAG250&hw_version=1.7-BD-00&image_version=218&client_type=STB" +
        "&JsHttpRequest=1-xml"
}

internal fun verifyPortal(profile: PortalProfile, container: AppContainer): PortalSession {
    fun request(query: String, token: String? = null) =
        portalRequest(profile, query, token, container) as? JSONObject ?: throw PortalResponseFormatException()
    val identity = capturedIdentity(profile)
    val token = try {
        request(identity?.let(::capturedHandshakeQuery)
            ?: "type=stb&action=handshake&token=&JsHttpRequest=1-xml").getString("token")
            .also { check(it.isNotBlank() && it.none { char -> char == '\r' || char == '\n' }) }
    } catch (error: Exception) {
        if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.w(PORTAL_TAG, "stage=stb.handshake.validate failure=${error.javaClass.simpleName}")
        throw error
    }
    if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG, "stage=stb.handshake.validate tokenPresent=true")
    val profileResult = try {
        val profileResult = request(portalProfileQuery(profile), token)
        if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG,
            "stage=stb.get_profile.validate hasId=${profileResult.has("id")} " +
                "hasError=${profileResult.has("error")} status=${profileResult.optInt("status", -1)} " +
                "messageKind=${when (profileResult.optString("msg")) {
                    "Authentication request" -> "registration"
                    "Time out of sync" -> "clock"
                    else -> "other"
                }}")
        if (profileResult.has("error") || !profileResult.has("id")) {
            // A browser /c entry may answer with JSON that is not a usable API
            // profile. Try discovered routes only in that case, not for an API
            // endpoint that rejected the account.
            if (URI(profile.host).path.trimEnd('/').endsWith("/c", ignoreCase = true))
                throw PortalResponseFormatException()
            error("Portal did not return a valid profile.")
        }
        profileResult
    } catch (error: Exception) {
        if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.w(PORTAL_TAG, "stage=stb.get_profile.validate failure=${error.javaClass.simpleName}")
        throw error
    }
    return PortalSession(token, profileResult.optInt("watchdog_timeout", 0).takeIf { it in 15..3600 })
}

/** Android often hides hardware MACs; never substitute the platform's placeholder or a fabricated MAC. */
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
fun ProfileSetup() {
    val context = LocalContext.current
    val container = remember(context) { (context.applicationContext as TvApplication).container }
    val store = remember { ProfileStore(context) }
    val viewerStore = remember { ViewerProfileStore(context) }
    val categoryStore = remember { VodCategoryKindStore(context) }
    val channelIndexStore = remember { ChannelIndexStore(context) }
    var viewerProfiles by remember { mutableStateOf<List<ViewerProfile>>(emptyList()) }
    var activeViewer by remember { mutableStateOf<ViewerProfile?>(null) }
    var viewerName by remember { mutableStateOf("") }
    var playlists by remember { mutableStateOf<List<PortalProfile>>(emptyList()) }
    var loadingProfiles by remember { mutableStateOf(true) }
    var screen by remember { mutableStateOf("viewerChooser") }
    var playlistReturnScreen by remember { mutableStateOf("landing") }
    var playlistForEveryone by remember { mutableStateOf(false) }
    var serial by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var mac by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var selectedProfile by remember { mutableStateOf<PortalProfile?>(null) }
    var sessionToken by remember { mutableStateOf<String?>(null) }
    var sessionApiHost by remember { mutableStateOf<String?>(null) }
    var sessionWatchdogSeconds by remember { mutableStateOf<Int?>(null) }
    // Bumped only by a manual Connect / Refresh playlist so those actions always
    // install a new token instead of reusing the current in-memory session.
    var sessionEpoch by remember { mutableIntStateOf(0) }
    var sessionCategoryKinds by remember { mutableStateOf<Map<String, ContentKind>>(emptyMap()) }
    var sessionVodCategories by remember { mutableStateOf<List<Category>?>(null) }
    var sessionChannelIndex by remember { mutableStateOf<List<ChannelIndexEntry>?>(null) }
    var scanningCategories by remember { mutableStateOf(false) }
    var scannedCategories by remember { mutableIntStateOf(0) }
    var totalCategories by remember { mutableIntStateOf(0) }
    var indexingChannels by remember { mutableStateOf(false) }
    var indexedChannels by remember { mutableIntStateOf(0) }
    var channelIndexNotice by remember { mutableStateOf<String?>(null) }
    var reconnectProfile by remember { mutableStateOf<PortalProfile?>(null) }
    var reconnectRefreshCategories by remember { mutableStateOf(false) }
    var processedCounts by remember { mutableStateOf<PortalCounts?>(null) }
    var processedName by remember { mutableStateOf("") }
    var editingIndex by remember { mutableIntStateOf(-1) }
    var editName by remember { mutableStateOf("") }
    var editHost by remember { mutableStateOf("") }
    var editMac by remember { mutableStateOf("") }
    var editSerial by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val firstFocus = remember { FocusRequester() }
    // A newly created activity starts at Home, not a restored player/details route.
    val homeInstance = remember { java.util.UUID.randomUUID().toString() }
    /** Replace the map only after every category probe completes successfully. */
    suspend fun scanAndStoreCategoryKinds(profile: PortalProfile,
        verified: VerifiedPortal): ScannedVodCategories {
        scanningCategories = true
        scannedCategories = 0
        totalCategories = 0
        return try {
            val scanned = scanVodCategoryKinds(profile, verified, container) { done, total ->
                scannedCategories = done
                totalCategories = total
            }
            withContext(Dispatchers.IO) { categoryStore.save(profile, scanned) }
            scanned
        } finally {
            scanningCategories = false
        }
    }
    /**
     * Reuses a completed index, or builds and persists it once. A failed build
     * never replaces a previous index and never blocks the playlist connection.
     */
    suspend fun loadOrBuildChannelIndex(profile: PortalProfile, verified: VerifiedPortal,
        force: Boolean): List<ChannelIndexEntry>? {
        val existing = withContext(Dispatchers.IO) { channelIndexStore.load(profile) }
        if (!force && existing != null) return existing
        indexingChannels = true
        indexedChannels = existing?.size ?: 0
        channelIndexNotice = null
        return try {
            val entries = scanChannelIndex(profile, verified, container) { indexedChannels = it }
            withContext(Dispatchers.IO) { channelIndexStore.save(profile, entries) }
            indexedChannels = entries.size
            entries
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.w(PORTAL_TAG,
                "stage=channel.index failure=${error.javaClass.simpleName}")
            channelIndexNotice = "Channel search is unavailable. Refresh the playlist to rebuild its index."
            existing
        } finally {
            indexingChannels = false
        }
    }
    /** Connect and Refresh playlist both replace the whole in-memory session. */
    fun installSession(verified: VerifiedPortal) {
        sessionApiHost = verified.apiHost
        sessionWatchdogSeconds = verified.watchdogSeconds
        sessionToken = verified.token
        sessionEpoch++
    }
    fun connect(profile: PortalProfile, refreshCategoryMap: Boolean = false) {
        val viewer = activeViewer ?: return
        selectedProfile = profile
        sessionToken = null
        sessionApiHost = null
        sessionWatchdogSeconds = null
        sessionCategoryKinds = emptyMap()
        sessionVodCategories = null
        sessionChannelIndex = null
        scanningCategories = false
        scannedCategories = 0
        totalCategories = 0
        indexingChannels = false
        indexedChannels = 0
        channelIndexNotice = null
        reconnectProfile = profile
        reconnectRefreshCategories = refreshCategoryMap
        busy = true
        screen = "connecting"
        message = "Connecting…"
        scope.launch {
            var stage = "portal.verify"
            try {
                val verified = withContext(Dispatchers.IO) {
                    val result = testPortal(profile, container)
                    stage = "profile.store.select"
                    val index = playlists.indexOf(profile).also { require(it >= 0) }
                    require(profile.ownerProfileId == null || profile.ownerProfileId == viewer.id)
                    if (store.selectedIndex() != index) store.select(index)
                    result
                }
                if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG, "stage=playlist.connect verified=true")
                if (refreshCategoryMap) {
                    stage = "playlist.scan"
                    // The category scan and the complete channel index are independent.
                    coroutineScope {
                        val categories = async { scanAndStoreCategoryKinds(profile, verified) }
                        val channels = async { loadOrBuildChannelIndex(profile, verified, force = true) }
                        val scan = categories.await()
                        sessionCategoryKinds = scan.kinds
                        sessionVodCategories = scan.categories
                        sessionChannelIndex = channels.await()
                    }
                } else {
                    val stored = withContext(Dispatchers.IO) {
                        categoryStore.load(profile).orEmpty() to categoryStore.loadCategories(profile)
                    }
                    sessionCategoryKinds = stored.first
                    sessionVodCategories = stored.second
                    stage = "channel.index"
                    sessionChannelIndex = loadOrBuildChannelIndex(profile, verified, force = false)
                }
                installSession(verified)
                reconnectProfile = null
                message = "Connection successful."
                screen = "home"
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.w(PORTAL_TAG, "stage=$stage failure=${error.javaClass.simpleName}")
                selectedProfile = null
                message = if (error is PortalHttpException && error.status == 429)
                    "The portal is limiting requests (HTTP 429). Wait before retrying."
                else "Connection failed. Check the host, registration and server, then retry."
            } finally {
                scanningCategories = false
                indexingChannels = false
                busy = false
            }
        }
    }
    fun edit(index: Int) {
        val profile = playlists[index]
        editingIndex = index
        editName = profile.name
        editHost = profile.host
        editMac = profile.mac
        editSerial = profile.serial
        message = ""
    }
    LaunchedEffect(Unit) {
        val detected = withContext(Dispatchers.IO) { availableDeviceMac() }
        if (mac.isBlank() && detected != null) mac = detected
    }
    LaunchedEffect(Unit) {
        try {
            val (savedViewers, savedPlaylists) = withContext(Dispatchers.IO) {
                // This development checkpoint intentionally starts the new viewer
                // model clean instead of migrating the old single-playlist state.
                if (!viewerStore.initialized()) {
                    store.clear()
                    categoryStore.clear()
                    channelIndexStore.clear()
                    viewerStore.initialize()
                }
                viewerStore.load() to store.load()
            }
            viewerProfiles = savedViewers
            playlists = savedPlaylists
            screen = if (savedViewers.isEmpty()) "viewerCreate" else "viewerChooser"
        } catch (error: Exception) {
            if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.w(PORTAL_TAG, "stage=profile.store.load failure=${error.javaClass.simpleName}")
            message = "Saved playlist could not be read. Add it again."
        }
        finally { loadingProfiles = false }
    }
    LaunchedEffect(screen, busy) {
        if (screen != "home" && !(screen == "connecting" && busy)) {
            withFrameNanos { }
            // Connection and playlist loading can replace the screen before its
            // first focus target is attached. Do not crash during that transition.
            try { firstFocus.requestFocus() } catch (_: IllegalStateException) { }
        }
    }
    val connectedProfile = selectedProfile
    val connectedToken = sessionToken
    val activeApiHost = sessionApiHost
    // Manual Connect / Refresh playlist bumps sessionEpoch and replaces the whole
    // holder. Silent recovery only swaps the token inside the current holder.
    val portalSession = remember(connectedProfile, connectedToken, activeApiHost, sessionEpoch) {
        if (connectedProfile == null || connectedToken == null || activeApiHost == null) null else {
            val active = connectedProfile.copy(host = activeApiHost)
            PortalSessionHolder(
                initialToken = connectedToken,
                healthQuery = portalProfileQuery(active),
                transport = { query, token -> portalRequest(active, query, token, container) },
                refreshSession = { verifyPortal(active, container).token },
            )
        }
    }
    LaunchedEffect(portalSession, sessionWatchdogSeconds) {
        val session = portalSession ?: return@LaunchedEffect
        val interval = sessionWatchdogSeconds ?: return@LaunchedEffect
        while (true) {
            delay(interval * 1_000L)
            try {
                withContext(Dispatchers.IO) { session.keepAlive() }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.w(PORTAL_TAG,
                    "stage=watchdog.get_events failure=${error.javaClass.simpleName}")
            }
        }
    }
    // Keep-alive timers do not survive process suspension. Revalidate on resume
    // so a returned TV does not discover an expired token on its first action.
    LifecycleResumeEffect(portalSession) {
        val session = portalSession
        if (session != null) scope.launch(Dispatchers.IO) { session.healthCheck() }
        onPauseOrDispose { }
    }
    BackHandler(screen == "landing" && !busy) {
        activeViewer = null
        selectedProfile = null
        sessionToken = null
        sessionApiHost = null
        sessionWatchdogSeconds = null
        sessionCategoryKinds = emptyMap()
        sessionVodCategories = null
        sessionChannelIndex = null
        screen = "viewerChooser"
    }
    BackHandler(screen !in listOf("landing", "home", "connecting", "viewerChooser", "viewerCreate")) {
        if (!busy) screen = when (screen) {
            "settings" -> if (editingIndex >= 0) {
                editingIndex = -1
                "settings"
            } else if (sessionToken != null) "home" else "landing"
            "form" -> "playlistScope"
            "playlistScope" -> "protocol"
            "protocol" -> playlistReturnScreen
            "home" -> "settings"
            else -> "landing"
        }
    }
    val viewer = activeViewer
    val visiblePlaylists = remember(playlists, viewer) {
        if (viewer == null) emptyList()
        else playlistsForViewer(playlists, viewer.id)
    }
    if (screen == "home" && connectedProfile != null && connectedToken != null &&
        activeApiHost != null && portalSession != null) {
        val session = portalSession
        val repository = remember(session, sessionCategoryKinds, sessionVodCategories, sessionChannelIndex) {
            StalkerContentRepository(connectedProfile.copy(host = activeApiHost), session,
                sessionCategoryKinds, initialVodCategories = sessionVodCategories,
                onVodCategoriesLoaded = { categories ->
                    categoryStore.saveCategories(connectedProfile, categories)
                }, initialChannelIndex = sessionChannelIndex)
        }
        val container = remember(repository) { com.ykinger.stalkertv.AppContainer(repository) }
        key(homeInstance) {
            TvApp(container, profileName = viewer?.name.orEmpty(),
                sessionStatus = session.status,
                onProfileSettings = { screen = "settings" },
                onSwitchProfile = {
                    selectedProfile = null
                    sessionToken = null
                    sessionApiHost = null
                    sessionWatchdogSeconds = null
                    sessionCategoryKinds = emptyMap()
                    sessionVodCategories = null
                    sessionChannelIndex = null
                    activeViewer = null
                    screen = "viewerChooser"
                })
        }
        return
    }
    when (screen) {
        "viewerChooser" -> Box(Modifier.fillMaxSize().background(settingsBackground),
            contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth(0.72f).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Image(painterResource(R.drawable.logo_full), contentDescription = "VibeTV",
                    contentScale = ContentScale.Fit, modifier = Modifier.height(96.dp))
                Text("Who's watching?", fontSize = 30.sp, color = Color.White)
                if (loadingProfiles) TvInlineStatus("Loading profiles…", loading = true)
                else {
                    viewerProfiles.forEachIndexed { index, profile ->
                        Button(onClick = {
                            activeViewer = profile
                            selectedProfile = null
                            message = ""
                            screen = "landing"
                        }, modifier = if (index == 0) Modifier.focusRequester(firstFocus) else Modifier) {
                            Text(profile.name)
                        }
                    }
                    Button(onClick = { viewerName = ""; message = ""; screen = "viewerCreate" },
                        modifier = if (viewerProfiles.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier) {
                        Text("Add profile")
                    }
                }
            }
        }
        "viewerCreate" -> Row(Modifier.fillMaxSize().background(settingsBackground)) {
            Box(Modifier.weight(1f).fillMaxHeight().background(settingsHeader).padding(40.dp),
                contentAlignment = Alignment.CenterStart) {
                Text("Create profile", fontSize = 32.sp, color = Color.White)
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(36.dp),
                verticalArrangement = Arrangement.Center) {
                Text("Name this viewer", fontSize = 24.sp, color = Color.White)
                Spacer(Modifier.height(18.dp))
                ProfileField("Profile name", viewerName, !busy, Modifier.focusRequester(firstFocus)) {
                    viewerName = it
                }
                if (message.isNotEmpty()) Text(message, color = Color(0xFFF3D5B3))
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(enabled = !busy, onClick = {
                        val name = viewerName.trim()
                        if (name.isEmpty() || name.length > 50 ||
                            viewerProfiles.any { it.name.equals(name, ignoreCase = true) }) {
                            message = "Enter a unique profile name (up to 50 characters)."
                            return@Button
                        }
                        busy = true
                        scope.launch {
                            try {
                                val created = ViewerProfile(java.util.UUID.randomUUID().toString(), name)
                                val updated = viewerProfiles + created
                                withContext(Dispatchers.IO) { viewerStore.save(updated) }
                                viewerProfiles = updated
                                activeViewer = created
                                viewerName = ""
                                message = ""
                                val hasAvailable = playlists.any {
                                    it.ownerProfileId == null || it.ownerProfileId == created.id
                                }
                                screen = if (hasAvailable) "landing" else {
                                    playlistReturnScreen = "landing"
                                    "protocol"
                                }
                            } catch (_: Exception) {
                                message = "Could not save this profile. Please retry."
                            } finally { busy = false }
                        }
                    }) { Text("Create") }
                    if (viewerProfiles.isNotEmpty()) Button(enabled = !busy, onClick = {
                        message = ""; screen = "viewerChooser"
                    }) { Text("Cancel") }
                }
            }
        }
        "processed" -> Row(Modifier.fillMaxSize().background(settingsBackground)) {
            Box(Modifier.weight(1f).fillMaxHeight().background(settingsHeader).padding(40.dp),
                contentAlignment = Alignment.CenterStart) {
                Text("Playlist is processed", fontSize = 32.sp, color = Color.White)
            }
            Column(Modifier.weight(0.8f).fillMaxHeight().padding(36.dp),
                verticalArrangement = Arrangement.Center) {
                Text("Playlist saved · Portal catalogue", fontSize = 21.sp, color = Color.White)
                Text("Channels: ${processedCounts?.channels ?: "Count unavailable"}", color = Color.White)
                Text("Movies + shows: ${processedCounts?.moviesAndShows ?: "Count unavailable"}", color = Color.White)
                Text("Movie and show categories classified during scan", color = settingsMuted)
                Text("Channel search index: ${sessionChannelIndex?.size?.toString() ?: "Unavailable"}",
                    color = settingsMuted)
                channelIndexNotice?.let { Text(it, color = Color(0xFFF3D5B3)) }
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
                            val updated = playlists.map { if (it == profile) renamed else it }
                            withContext(Dispatchers.IO) { store.save(updated, updated.indexOf(renamed)) }
                            playlists = updated
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
        "connecting" -> Box(Modifier.fillMaxSize().background(settingsBackground), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val scanning = busy && scanningCategories
                val indexing = busy && indexingChannels
                TvStatusPanel(
                    when {
                        !busy -> "Could not connect"
                        scanning && indexing -> "Preparing search indexes…"
                        scanning -> "Scanning categories…"
                        indexing -> "Indexing channels…"
                        else -> "Connecting to playlist…"
                    },
                    when {
                        !busy -> message
                        scanning && totalCategories > 0 ->
                            "Scanning $scannedCategories of $totalCategories categories"
                        scanning -> "Reading the portal category list"
                        indexing -> "Indexed $indexedChannels channels for search"
                        else -> "Checking your portal and preparing the guide"
                    },
                    loading = busy, modifier = Modifier.width(480.dp), shape = TvStatusShape.NONE,
                    retryLabel = "Retry connection",
                    onRetry = if (busy) null else {
                        { reconnectProfile?.let { connect(it, reconnectRefreshCategories) } }
                    },
                    retryModifier = Modifier.focusRequester(firstFocus),
                    progress = if (scanning && totalCategories > 0)
                        scannedCategories.toFloat() / totalCategories else null)
                if (indexing) {
                    TvInlineStatus("Indexed $indexedChannels channels", loading = true)
                }
                channelIndexNotice?.let { Text(it, color = Color(0xFFF3D5B3)) }
                if (!busy) {
                    Button(onClick = { screen = "settings" }) { Text("Manage playlists") }
                }
            }
        }
        "landing" -> Box(Modifier.fillMaxSize().background(settingsBackground), contentAlignment = Alignment.Center) {
            Column(Modifier.fillMaxWidth(0.82f).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(22.dp)) {
                Image(painterResource(R.drawable.logo_full), contentDescription = "VibeTV",
                    contentScale = ContentScale.Fit, modifier = Modifier.height(96.dp))
                Text(viewer?.name.orEmpty(), fontSize = 18.sp, color = settingsMuted)
                if (loadingProfiles) TvInlineStatus("Loading playlists…", loading = true)
                else if (visiblePlaylists.isEmpty()) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("VibeTV does not provide any content", fontSize = 25.sp, color = Color.White)
                    Text("Add your own playlist to start watching", color = settingsMuted)
                }
                else {
                    Text("Choose a playlist", fontSize = 28.sp, color = Color.White)
                    visiblePlaylists.forEach { playlist ->
                        Button(enabled = !busy, onClick = { connect(playlist) }) {
                             Text(playlist.name + if (playlist.ownerProfileId == null) " · Everyone" else "")
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(enabled = !loadingProfiles, onClick = {
                        playlistReturnScreen = "landing"
                        screen = "protocol"
                    }, modifier = Modifier.focusRequester(firstFocus)) { Text("Add playlist") }
                    Button(onClick = { screen = "settings" }) { Text("Settings") }
                    Button(onClick = { activeViewer = null; screen = "viewerChooser" }) { Text("Switch profile") }
                }
                if (message.isNotEmpty()) Text(message, color = settingsMuted)
            }
        }
        "settings" -> Row(Modifier.fillMaxSize().background(Color(0xFF0A0D0E))) {
            Column(Modifier.weight(0.9f).fillMaxHeight().background(Color(0xFF15191A))
                .padding(start = 36.dp, top = 34.dp, end = 28.dp, bottom = 28.dp)
                .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Image(painterResource(R.drawable.logo_full_horizontal), contentDescription = "VibeTV",
                    contentScale = ContentScale.Fit, modifier = Modifier.height(28.dp))
                Text("${viewer?.name.orEmpty()} · Playlists", color = Color.White, fontSize = 28.sp)
                Spacer(Modifier.height(8.dp))
                if (loadingProfiles) TvInlineStatus("Loading playlists…", loading = true)
                else if (visiblePlaylists.isEmpty()) Text("No playlists available.", color = Color(0xFFD0DADC))
                visiblePlaylists.forEachIndexed { visibleIndex, profile ->
                    val index = playlists.indexOf(profile)
                    Button(enabled = !busy, onClick = { edit(index) },
                        modifier = if (visibleIndex == 0) Modifier.fillMaxWidth().focusRequester(firstFocus) else Modifier.fillMaxWidth()) {
                        Text(profile.name + if (profile.ownerProfileId == null) " · Everyone" else "")
                    }
                }
                Button(enabled = !loadingProfiles && !busy, onClick = {
                    editingIndex = -1
                    message = ""
                    playlistReturnScreen = "settings"
                    screen = "protocol"
                }, modifier = if (visiblePlaylists.isEmpty()) Modifier.focusRequester(firstFocus) else Modifier) {
                    Text(if (visiblePlaylists.isEmpty()) "Add playlist" else "Add another playlist")
                }
                Button(enabled = !busy, onClick = {
                    if (editingIndex >= 0) editingIndex = -1
                    else screen = if (sessionToken != null) "home" else "landing"
                }) { Text("Back") }
            }
            Column(Modifier.weight(1.5f).fillMaxHeight().background(settingsPanel)
                .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val original = playlists.getOrNull(editingIndex)
                Box(Modifier.fillMaxWidth().background(settingsHeader).padding(26.dp)) {
                    Text(original?.name ?: "Playlists", color = Color.White, fontSize = 27.sp)
                }
                Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (original == null) {
                    Text("Select a playlist to view or edit its details", color = Color.White, fontSize = 23.sp)
                    Text("Connection details stay hidden until you choose a playlist.", color = Color(0xFFB7C8C6))
                    if (selectedProfile == null) Text("No playlist selected. Not connected.", color = Color(0xFFB7C8C6))
                } else {
                    Text("Stalker Portal parameters", color = settingsBlue, fontSize = 18.sp)
                    Text(if (original.ownerProfileId == null) "Available to everyone"
                        else "Private to ${viewer?.name.orEmpty()}", color = settingsMuted)
                    if (selectedProfile == original && sessionToken != null)
                        Text("Selected · Connected", color = Color(0xFFACB6BB))
                    ProfileField("Playlist name", editName, !busy) { editName = it }
                    ProfileField("Portal URL/host", editHost, !busy) { editHost = it }
                    ProfileField("MAC address", editMac, !busy) { editMac = it }
                    ProfileField("Serial ID (optional)", editSerial, !busy) { editSerial = it }
                    if (message.isNotEmpty()) Text(message, color = Color(0xFFF3D5B3))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(enabled = !busy, onClick = {
                        val candidate = try {
                            val name = editName.trim()
                            require(name.isNotEmpty() && name.length <= 100 && editSerial.length <= 256)
                            require(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}").matches(editMac.trim()))
                            val address = editHost.trim()
                            original.copy(name = name,
                                host = if (address == original.host) original.host else endpoint(address),
                                mac = editMac.trim().uppercase(), serial = editSerial.trim())
                        } catch (_: Exception) {
                            message = "Enter a name (up to 100 characters), valid HTTP(S) portal URL, and MAC in AA:BB:CC:DD:EE:FF format."
                            return@Button
                        }
                        if (playlists.withIndex().any { it.index != editingIndex &&
                                (it.value.ownerProfileId == null || it.value.ownerProfileId == viewer?.id) &&
                                it.value.host == candidate.host && it.value.mac == candidate.mac &&
                                it.value.serial == candidate.serial }) {
                            message = "This playlist is already saved."
                            return@Button
                        }
                        val index = editingIndex
                        val wasSelected = selectedProfile == original
                        val credentialsChanged = candidate.host != original.host || candidate.mac != original.mac ||
                            candidate.serial != original.serial
                        busy = true
                        scope.launch {
                            var reconnect = false
                            try {
                                val updated = playlists.toMutableList().apply { this[index] = candidate }
                                withContext(Dispatchers.IO) {
                                    store.save(updated, if (wasSelected) index else store.selectedIndex())
                                    if (credentialsChanged) channelIndexStore.remove(original)
                                }
                                playlists = updated
                                if (wasSelected) {
                                    selectedProfile = candidate
                                    if (credentialsChanged) {
                                        sessionToken = null
                                        sessionApiHost = null
                                        sessionWatchdogSeconds = null
                                        sessionCategoryKinds = emptyMap()
                                        sessionVodCategories = null
                                        sessionChannelIndex = null
                                        reconnectProfile = candidate
                                    }
                                }
                                if (reconnectProfile == original) reconnectProfile = candidate
                                editingIndex = -1
                                message = "Playlist saved securely."
                                reconnect = wasSelected && credentialsChanged
                            } catch (error: Exception) {
                                if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.w(PORTAL_TAG, "stage=profile.store.edit failure=${error.javaClass.simpleName}")
                                message = "Could not save playlist. Please retry."
                            } finally { busy = false }
                            if (reconnect) connect(candidate, refreshCategoryMap = true)
                        }
                    }) { Text("Save") }
                    Button(enabled = !busy, onClick = { connect(original) }) { Text("Connect") }
                    Button(enabled = !busy, onClick = { connect(original, refreshCategoryMap = true) }) {
                        Text("Refresh playlist")
                    }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(enabled = !busy && selectedProfile == original, onClick = {
                        busy = true
                        scope.launch {
                            try {
                                withContext(Dispatchers.IO) { store.select(-1) }
                                selectedProfile = null
                                sessionToken = null
                                sessionApiHost = null
                                sessionWatchdogSeconds = null
                                sessionCategoryKinds = emptyMap()
                                sessionVodCategories = null
                                sessionChannelIndex = null
                                reconnectProfile = null
                                message = "No playlist selected. Not connected."
                            } catch (_: Exception) {
                                message = "Could not update playlist selection. Please retry."
                            } finally { busy = false }
                        }
                    }) { Text("Deselect") }
                    Button(enabled = !busy, onClick = { editingIndex = -1; message = "" }) { Text("Cancel") }
                    }
                }
                if (original == null && message.isNotEmpty()) Text(message, color = Color(0xFFF3D5B3))
                }
            }
        }
        "playlistScope" -> Row(Modifier.fillMaxSize().background(settingsBackground)) {
            Box(Modifier.weight(1f).fillMaxHeight().background(settingsHeader).padding(40.dp),
                contentAlignment = Alignment.CenterStart) {
                Text("Who can use this playlist?", fontSize = 32.sp, color = Color.White)
            }
            Column(Modifier.weight(1f).fillMaxHeight().background(settingsPanel).padding(36.dp),
                verticalArrangement = Arrangement.Center) {
                Text("Playlist access", fontSize = 24.sp, color = Color.White)
                Text("You can change connection details later from Settings.", color = settingsMuted)
                Spacer(Modifier.height(22.dp))
                Button(onClick = {
                    playlistForEveryone = false
                    message = ""
                    screen = "form"
                }, modifier = Modifier.focusRequester(firstFocus)) {
                    Text("Just me")
                }
                Spacer(Modifier.height(12.dp))
                Button(onClick = {
                    playlistForEveryone = true
                    message = ""
                    screen = "form"
                }) { Text("Everyone") }
                Spacer(Modifier.height(12.dp))
                Button(onClick = { screen = "protocol" }) { Text("Back") }
            }
        }
        "protocol", "form" -> Row(Modifier.fillMaxSize().background(settingsBackground)) {
            Box(Modifier.weight(1.1f).fillMaxHeight().background(settingsHeader).padding(30.dp),
                contentAlignment = Alignment.CenterStart) {
                Text(if (screen == "protocol") "Add playlist" else "Stalker Portal", fontSize = 32.sp, color = Color.White)
            }
            if (screen == "protocol") {
                Column(Modifier.weight(1f).fillMaxHeight().background(settingsPanel).padding(32.dp),
                    verticalArrangement = Arrangement.Center) {
                    Text("Playlist type", fontSize = 24.sp, color = Color.White)
                    Spacer(Modifier.height(22.dp))
                    Button(onClick = { message = ""; screen = "playlistScope" }, modifier = Modifier.focusRequester(firstFocus)) { Text("Stalker Portal") }
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { screen = playlistReturnScreen }) { Text("Back") }
                }
            } else {
                Column(Modifier.weight(1.15f).fillMaxHeight().background(settingsPanel).padding(24.dp)
                    .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Connection details", fontSize = 22.sp, color = Color.White)
                    ProfileField("Portal URL/host", host, !busy, Modifier.focusRequester(firstFocus)) { host = it }
                    Text("Enter your provider URL or host, for example https://portal.example.com/c/", fontSize = 12.sp, color = settingsMuted)
                    ProfileField("MAC address", mac, !busy) { mac = it }
                    Text("Use the MAC address registered with your provider.", fontSize = 12.sp, color = settingsMuted)
                    ProfileField("Serial ID (optional)", serial, !busy) { serial = it }
                    if (message.isNotEmpty()) Text(message, color = Color(0xFFF3D5B3))
                    if (busy && scanningCategories) {
                        val progressMessage = if (totalCategories > 0)
                            "Scanning $scannedCategories of $totalCategories categories"
                        else "Reading the portal category list"
                        TvInlineStatus(progressMessage, loading = true)
                        if (totalCategories > 0) TvProgressBar(
                            scannedCategories.toFloat() / totalCategories,
                            Modifier.fillMaxWidth())
                    }
                    if (busy && indexingChannels) {
                        TvInlineStatus("Indexed $indexedChannels channels for search", loading = true)
                    }
                }
                Column(Modifier.weight(0.55f).fillMaxHeight().background(settingsBackground).padding(20.dp),
                    verticalArrangement = Arrangement.Center) {
                    Button(enabled = !busy, onClick = {
                    val candidate = try {
                        require(serial.length <= 256)
                        require(Regex("(?i)[0-9a-f]{2}(:[0-9a-f]{2}){5}").matches(mac.trim()))
                        PortalProfile(serial.trim(), endpoint(host.trim()), mac.trim().uppercase(),
                            id = java.util.UUID.randomUUID().toString(),
                            ownerProfileId = if (playlistForEveryone) null else viewer?.id
                                ?: error("No viewer selected"))
                    } catch (_: Exception) {
                        message = "Enter a valid HTTP(S) portal URL and MAC in AA:BB:CC:DD:EE:FF format. Serial ID is optional."
                        return@Button
                    }
                    if (visiblePlaylists.any { it.host == candidate.host && it.mac == candidate.mac && it.serial == candidate.serial }) {
                        message = "This playlist is already saved."
                        return@Button
                    }
                    busy = true
                    processedCounts = null
                    selectedProfile = candidate
                    sessionToken = null
                    sessionApiHost = null
                    sessionWatchdogSeconds = null
                    sessionCategoryKinds = emptyMap()
                    sessionVodCategories = null
                    sessionChannelIndex = null
                    scanningCategories = false
                    scannedCategories = 0
                    totalCategories = 0
                    indexingChannels = false
                    indexedChannels = 0
                    channelIndexNotice = null
                    message = "Testing connection…"
                    scope.launch {
                        var stage = "portal.verify"
                        try {
                            val verified = withContext(Dispatchers.IO) { testPortal(candidate, container) }
                            stage = "playlist.scan"
                            // First Add builds both indexes concurrently; an index
                            // failure must not prevent the playlist from being saved.
                            val (scan, channelIndex) = coroutineScope {
                                val categories = async { scanAndStoreCategoryKinds(candidate, verified) }
                                val channels = async { loadOrBuildChannelIndex(candidate, verified, force = true) }
                                categories.await() to channels.await()
                            }
                            stage = "portal.counts"
                            val counts = withContext(Dispatchers.IO) {
                                portalCounts(candidate, verified, container)
                            }
                            stage = "profile.store.save"
                            val updated = playlists + candidate
                            withContext(Dispatchers.IO) { store.save(updated, updated.lastIndex) }
                            if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG, "stage=$stage saved=true")
                            playlists = updated
                            sessionCategoryKinds = scan.kinds
                            sessionVodCategories = scan.categories
                            sessionChannelIndex = channelIndex
                            installSession(verified)
                            processedCounts = counts
                            processedName = URI(candidate.host).host ?: "Stalker Portal"
                            serial = ""; host = ""; mac = ""
                            message = "Connection verified. Playlist saved securely."
                            screen = "processed"
                        } catch (cancelled: kotlinx.coroutines.CancellationException) {
                            throw cancelled
                        } catch (error: java.net.SocketTimeoutException) {
                            if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.w(PORTAL_TAG, "stage=$stage failure=${error.javaClass.simpleName}")
                            message = "Connection timed out. Check the host and retry."
                        } catch (error: java.net.ConnectException) {
                            if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.w(PORTAL_TAG, "stage=$stage failure=${error.javaClass.simpleName}")
                            message = "Cannot connect. Check the host and that Docker is running."
                        } catch (error: PortalResponseFormatException) {
                            if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.w(PORTAL_TAG, "stage=$stage failure=${error.javaClass.simpleName}")
                            message = "The portal returned non-JSON data, not a Stalker API response. Check the provider's portal address."
                        } catch (error: PortalHttpException) {
                            if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.w(PORTAL_TAG, "stage=$stage failure=${error.javaClass.simpleName}")
                            message = if (error.status == 429)
                                "The portal is limiting requests (HTTP 429). Wait before retrying."
                            else "Connection failed (HTTP ${error.status}). Check the provider's portal response."
                        } catch (error: Exception) {
                            if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.w(PORTAL_TAG, "stage=$stage failure=${error.javaClass.simpleName}")
                            message = "Connection or save failed. Check registration, host, and portal response."
                        } finally {
                            indexingChannels = false
                            busy = false
                        }
                    }
                    }) { Text(if (busy) "Testing…" else "Next") }
                    Spacer(Modifier.height(12.dp))
                    Button(enabled = !busy, onClick = { screen = "playlistScope" }) { Text("Back") }
                }
            }
        }
    }
}

@Composable
private fun ProfileField(label: String, value: String, enabled: Boolean, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    val editorFocus = remember { FocusRequester() }
    val fieldFocus = remember { FocusRequester() }
    var returnAfterEditing by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(editing) {
        if (editing) {
            if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG, "stage=profile.input field=$label event=keyboard_request")
            editorFocus.requestFocus()
            keyboard?.show()
        }
    }
    LaunchedEffect(editing, returnAfterEditing) {
        if (!editing && returnAfterEditing) {
            fieldFocus.requestFocus()
            returnAfterEditing = false
        }
    }
    BackHandler(editing) {
        if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG, "stage=profile.input field=$label event=back")
        editing = false; returnAfterEditing = true; keyboard?.hide()
    }
    Text(label, color = settingsMuted)
    Box(modifier.fillMaxWidth().focusRequester(fieldFocus)
        .onFocusChanged {
            focused = it.hasFocus
            if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG,
                "stage=profile.input field=$label event=field_focus active=${it.hasFocus} editing=$editing")
            if (!it.hasFocus) editing = false
        }
        .semantics { contentDescription = "Edit $label" }
        .clickable(enabled = enabled) {
            if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG, "stage=profile.input field=$label event=activate")
            editing = true
        }
        .background(if (focused) Color(0xFFE0E3E4) else Color(0xFF292E31),
            androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
        .padding(16.dp)) {
        if (editing) {
            BasicTextField(value = value, onValueChange = {
                if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG, "stage=profile.input field=$label event=text_change")
                onChange(it)
            }, enabled = enabled,
                singleLine = true, textStyle = TextStyle(color = Color.Black),
                modifier = Modifier.fillMaxWidth().focusRequester(editorFocus).onFocusChanged {
                    if (BuildConfig.DEBUG && DIAGNOSTIC_LOGGING) Log.d(PORTAL_TAG,
                        "stage=profile.input field=$label event=editor_focus active=${it.isFocused}")
                })
        } else {
            Text(value.ifBlank { "Press OK to edit" },
                color = if (focused) Color.Black else if (value.isBlank()) settingsMuted else Color.White)
        }
    }
}
