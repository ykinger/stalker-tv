package com.ykinger.stalkertv

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.Color
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import com.ykinger.stalkertv.ui.TvApp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            MaterialTheme(
                colorScheme =
                    darkColorScheme(
                        primary = Color(0xFF83D5CC),
                        onPrimary = Color(0xFF071C1A),
                        background = Color(0xFF0B101A),
                        surface = Color(0xFF141D2C),
                        onSurface = Color(0xFFF0F3F7),
                        onBackground = Color(0xFFF0F3F7),
                    )
            ) {
                TvApp((application as TvApplication).container)
            }
        }
    }
}
