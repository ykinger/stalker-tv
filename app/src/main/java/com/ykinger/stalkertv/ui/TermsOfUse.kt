package com.ykinger.stalkertv.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.Text
import kotlinx.coroutines.launch

internal const val TERMS_VERSION = 1
internal const val TERMS_PREFERENCES = "vibetv-terms"

private data class TermsSection(val heading: String, val body: String)

private val termsSections = listOf(
    TermsSection("About VibeTV",
        "These Terms of Use are an agreement between you and Vio Technologies concerning your use of VibeTV. By selecting Accept, you confirm that you have read and agree to these Terms."),
    TermsSection("Media player only",
        "VibeTV is a media player. Vio Technologies does not provide, host, sell, or distribute television channels, playlists, streams, or other media content. You must add access details supplied by a provider you are authorized to use."),
    TermsSection("Your responsibility for content",
        "You are solely responsible for every playlist, portal, stream, and item of content you access through VibeTV. You confirm that you own the necessary rights or have permission to access that content, and that your use complies with all applicable laws. You must not use VibeTV to access, copy, stream, or distribute content without authorization."),
    TermsSection("Third-party services",
        "VibeTV does not control or endorse third-party playlists, portals, providers, or content. Vio Technologies is not affiliated with providers you configure in the app. Third-party services may have their own terms, privacy practices, availability, and technical requirements."),
    TermsSection("License",
        "Vio Technologies grants you a limited, personal, non-exclusive, non-transferable, and revocable license to use VibeTV on devices you own or control. You may not resell, redistribute, modify, reverse-engineer, interfere with, or attempt to extract the source code of the app except where applicable law expressly permits it."),
    TermsSection("Availability and no warranty",
        "VibeTV is provided on an “as is” and “as available” basis. To the extent permitted by law, Vio Technologies makes no warranty that the app will be uninterrupted, error-free, compatible with every provider or media format, or suitable for a particular purpose. Nothing in these Terms limits consumer rights that cannot lawfully be waived."),
    TermsSection("Limitation of liability",
        "To the maximum extent permitted by law, Vio Technologies is not responsible for losses or damages caused by third-party content, providers, service outages, inaccurate playlist data, device incompatibility, or your unauthorized use of content. Nothing in these Terms excludes liability that cannot lawfully be excluded."),
    TermsSection("Indemnification",
        "To the maximum extent permitted by law, you agree to reimburse Vio Technologies for reasonable costs and damages arising from claims caused by content or services you add, your violation of these Terms, or your infringement of another party’s rights."),
    TermsSection("Termination",
        "You may stop using VibeTV at any time. Vio Technologies may suspend or terminate your license if you materially breach these Terms. Provisions that are intended to survive termination, including responsibility for content, limitation of liability, and indemnification, will continue to apply."),
    TermsSection("Changes to these Terms",
        "Vio Technologies may update these Terms from time to time. If a material update requires renewed consent, VibeTV may ask you to review and accept the updated version before continuing."),
    TermsSection("Severability and language",
        "If any part of these Terms is found unenforceable, the remaining parts remain in effect. If these Terms are provided in multiple languages, the English version controls in the event of a conflict."),
)

@Composable
fun TermsGate(onDecline: () -> Unit, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val preferences = remember(context) {
        context.getSharedPreferences(TERMS_PREFERENCES, Context.MODE_PRIVATE)
    }
    var accepted by remember {
        mutableStateOf(preferences.getInt("accepted_version", 0) >= TERMS_VERSION)
    }
    if (accepted) {
        content()
    } else {
        TermsOfUseScreen(onDecline = onDecline, onAccept = {
            val saved = preferences.edit()
                .putInt("accepted_version", TERMS_VERSION)
                .putLong("accepted_at", System.currentTimeMillis())
                .commit()
            if (saved) accepted = true
        })
    }
}

@Composable
private fun TermsOfUseScreen(onDecline: () -> Unit, onAccept: () -> Unit) {
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val acceptFocus = remember { FocusRequester() }
    BackHandler(onBack = onDecline)
    LaunchedEffect(Unit) { acceptFocus.requestFocus() }

    Box(Modifier.fillMaxSize().background(Color(0xFF101314)).onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        val amount = when (event.key) {
            Key.DirectionDown -> 320f
            Key.DirectionUp -> -320f
            else -> return@onPreviewKeyEvent false
        }
        scope.launch { scroll.animateScrollTo((scroll.value + amount).toInt().coerceIn(0, scroll.maxValue)) }
        true
    }) {
        Text("Terms of Use", color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 28.dp))
        Column(Modifier.fillMaxSize().padding(start = 72.dp, end = 72.dp, top = 92.dp, bottom = 124.dp)
            .verticalScroll(scroll).focusable(), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            termsSections.forEach { section ->
                Text(section.heading, color = Color(0xFFE0E3E4), fontSize = 22.sp,
                    fontWeight = FontWeight.Bold)
                Text(section.body, color = Color(0xFFB7C0C4), fontSize = 17.sp, lineHeight = 25.sp)
            }
            Text("By selecting Accept, you agree to these Terms of Use.", color = Color.White,
                fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .background(Color(0xFF181C1E)).padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Use ↑ and ↓ to review the terms", color = Color(0xFFABB0B3), fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onDecline, modifier = Modifier.width(210.dp)) { Text("Decline and exit") }
                Button(onClick = onAccept, modifier = Modifier.width(140.dp).focusRequester(acceptFocus)) {
                    Text("Accept")
                }
            }
        }
    }
}
