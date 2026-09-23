package dev.maxmini.gpsplayback.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.maxmini.gpsplayback.AppStore
import dev.maxmini.gpsplayback.core.model.LatLon
import dev.maxmini.gpsplayback.core.playback.Fix
import dev.maxmini.gpsplayback.mock.MockLocationSink

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** Open Developer options, or About phone if they aren't enabled yet. */
private fun openDeveloperOptions(context: Context) {
    try {
        context.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS))
    } catch (e: ActivityNotFoundException) {
        context.startActivity(Intent(Settings.ACTION_DEVICE_INFO_SETTINGS))
    }
}

@Composable
fun SetupScreen(resumeCount: Int) {
    val context = LocalContext.current
    val settings by AppStore.settings.collectAsState()
    // Re-evaluated on every recomposition triggered by resumeCount / permission results.
    var permissionResults by remember { mutableIntStateOf(0) }
    val recheck = resumeCount + permissionResults

    val locationOk = remember(recheck) { granted(context, Manifest.permission.ACCESS_FINE_LOCATION) }
    val notifOk = remember(recheck) {
        Build.VERSION.SDK_INT < 33 || granted(context, Manifest.permission.POST_NOTIFICATIONS)
    }
    val mockOk = remember(recheck) { MockLocationSink.isSelectedMockApp(context) }

    val requestPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { permissionResults++ }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section("1. Location permission") {
            Muted("Android requires it for a location foreground service, even though this app only writes locations.")
            Check(locationOk, "Granted") {
                requestPermissions.launch(
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                )
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            Section("2. Notifications") {
                Muted("Shows playback progress and Play/Pause/Stop while you're in another app.")
                Check(notifOk, "Granted") {
                    requestPermissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                }
            }
        }
        Section("3. Mock location app") {
            Muted(
                "Enable Developer options (Settings → About phone → tap Build number 7 times), " +
                    "then Developer options → Select mock location app → GPS Playback.",
            )
            Check(mockOk, "Selected") { openDeveloperOptions(context) }
        }
        Section("Options") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Mock Play Services fused location")
                    Muted("Most apps, including Google Maps, read location through Play Services.")
                }
                Switch(
                    checked = settings.mockFused,
                    onCheckedChange = { v -> AppStore.setSettings { it.copy(mockFused = v) } },
                )
            }
        }
        TestFix(enabled = mockOk)
    }
}

@Composable
private fun Check(ok: Boolean, okLabel: String, fix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "✓ $okLabel" else "✗ Not yet", Modifier.weight(1f))
        if (!ok) Button(onClick = fix) { Text("Fix") }
    }
}

/**
 * Phase-1 smoke test: push one fixed position so you can confirm, in any maps
 * app, that mocking works on this device before playing a whole route.
 */
@Composable
private fun TestFix(enabled: Boolean) {
    val context = LocalContext.current
    val settings by AppStore.settings.collectAsState()
    var sink by remember { mutableStateOf<MockLocationSink?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) {
        onDispose { sink?.stop() }
    }
    Section("Test") {
        Muted("Pins the device to Apple Park, Cupertino. Open a maps app to check, then Clear.")
        Row {
            Button(
                enabled = enabled && sink == null,
                onClick = {
                    val s = MockLocationSink(context, settings.mockFused) { message = it }
                    message = try {
                        s.start()
                        s.push(Fix(LatLon(37.3349, -122.0090), bearingDegrees = null, speedMps = 0.0, accuracyMeters = 3.0))
                        sink = s
                        "Sent. Keep this screen open while you check."
                    } catch (e: SecurityException) {
                        "Not allowed: ${e.message}"
                    }
                },
            ) { Text("Send test fix") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(enabled = sink != null, onClick = {
                sink?.stop()
                sink = null
                message = "Cleared."
            }) { Text("Clear") }
        }
        message?.let { Muted(it) }
    }
}
