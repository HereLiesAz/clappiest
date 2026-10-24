package com.hereliesaz.clappiest

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/**
 * Settings and the arm switch. Settings changes while armed restart the service so the
 * detector picks them up.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = Mono) {
                // Surface supplies the white content color; bare Text defaults to black on black.
                Surface(color = Color.Black, contentColor = Color.White) { Screen(Prefs(this)) }
            }
        }
    }
}

private val Mono = darkColorScheme(
    primary = Color.White, onPrimary = Color.Black,
    background = Color.Black, onBackground = Color.White,
    surface = Color.Black, onSurface = Color.White,
    secondary = Color(0xFF888888),
)

private val requiredPermissions = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

@Composable
private fun Screen(prefs: Prefs) {
    val ctx = LocalContext.current
    var claps by remember { mutableIntStateOf(prefs.clapCount) }
    var sensitivity by remember { mutableFloatStateOf(prefs.sensitivity) }
    var sound by remember { mutableStateOf(prefs.alarmUri) }
    var denied by remember { mutableStateOf(false) }
    val armed = ListenerService.running

    fun refresh() { if (ListenerService.running) ListenerService.start(ctx) }

    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        denied = result[Manifest.permission.RECORD_AUDIO] != true
        if (!denied) ListenerService.start(ctx)
    }

    val ringtonePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { r ->
        if (r.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        @Suppress("DEPRECATION")
        val uri = r.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        prefs.alarmUri = uri; sound = uri
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        // Keep read access across reboots; the alarm may fire weeks from now.
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs.alarmUri = uri; sound = uri
    }

    val soundName = remember(sound) {
        val uri = sound ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        runCatching { RingtoneManager.getRingtone(ctx, uri)?.getTitle(ctx) }.getOrNull()
            ?: uri?.lastPathSegment ?: "Default alarm"
    }

    Column(
        Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        Text("Clappiest", fontSize = 40.sp, fontWeight = FontWeight.Black)
        Text(
            "Lost in the dark, found by applause.",
            color = MaterialTheme.colorScheme.secondary,
        )

        Section("Claps") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    claps = (claps - 1).coerceAtLeast(MIN_CLAPS); prefs.clapCount = claps; refresh()
                }) { Text("−") }
                Text(
                    "$claps", fontSize = 48.sp, fontWeight = FontWeight.Black,
                    modifier = Modifier.padding(horizontal = 28.dp),
                )
                OutlinedButton(onClick = {
                    claps = (claps + 1).coerceAtMost(MAX_CLAPS); prefs.clapCount = claps; refresh()
                }) { Text("+") }
            }
        }

        Section("Sensitivity") {
            Slider(
                value = sensitivity,
                onValueChange = { sensitivity = it },
                onValueChangeFinished = { prefs.sensitivity = sensitivity; refresh() },
                colors = SliderDefaults.colors(
                    thumbColor = Color.White, activeTrackColor = Color.White,
                    inactiveTrackColor = Color(0xFF333333),
                ),
            )
        }

        Section("Sound") {
            Text(soundName, fontSize = 18.sp)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = {
                    ringtonePicker.launch(
                        Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALL)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, false)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, sound)
                    )
                }) { Text("System") }
                OutlinedButton(onClick = { filePicker.launch(arrayOf("audio/*")) }) { Text("File") }
            }
        }

        Spacer(Modifier.weight(1f))

        if (denied) Text("No microphone, no miracle.", color = MaterialTheme.colorScheme.secondary)
        Button(
            onClick = {
                when {
                    armed -> ListenerService.stop(ctx)
                    requiredPermissions.all {
                        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
                    } -> ListenerService.start(ctx)
                    else -> permissions.launch(requiredPermissions)
                }
            },
            modifier = Modifier.fillMaxWidth().height(64.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (armed) Color.Black else Color.White,
                contentColor = if (armed) Color.White else Color.Black,
            ),
            border = if (armed) ButtonDefaults.outlinedButtonBorder(enabled = true) else null,
        ) {
            Text(if (armed) "DISARM" else "ARM", fontWeight = FontWeight.Black, fontSize = 18.sp)
        }
        Text(
            if (armed) "Listening whenever the screen is off." else "Deaf.",
            color = MaterialTheme.colorScheme.secondary,
        )
    }
}

@Composable
private fun Section(label: String, content: @Composable () -> Unit) {
    Column {
        Text(label.uppercase(), color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

private const val MIN_CLAPS = 2
private const val MAX_CLAPS = 10
