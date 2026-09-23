package dev.maxmini.gpsplayback.ui

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.maxmini.gpsplayback.AppStore

private enum class Tab(val label: String, val glyph: String) {
    Setup("Setup", "⚙"),
    Load("Load", "⇪"),
    Edit("Edit", "✎"),
    Play("Play", "▶"),
}

class MainActivity : ComponentActivity() {
    // Bumped on every resume so screens re-check permissions / mock-app selection
    // after the user comes back from system settings.
    private var resumeCount by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                AppContent(resumeCount)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumeCount++
    }
}

@Composable
private fun AppContent(resumeCount: Int) {
    val start = if (AppStore.routes.value.isEmpty()) Tab.Setup else Tab.Play
    var tab by rememberSaveable { mutableStateOf(start) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { tab = t },
                        icon = { Text(t.glyph, style = MaterialTheme.typography.titleLarge) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.Setup -> SetupScreen(resumeCount)
                Tab.Load -> LoadScreen(onStaged = { tab = Tab.Edit })
                Tab.Edit -> EditScreen(onNeedRoute = { tab = Tab.Load }, onPlay = { tab = Tab.Play })
                Tab.Play -> PlayScreen(onNeedRoute = { tab = Tab.Load })
            }
        }
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
