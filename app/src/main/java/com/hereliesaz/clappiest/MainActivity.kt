package com.hereliesaz.clappiest

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    /** Bumped on resume so values edited in another screen (the rules) are re-read. */
    private val resumes = mutableIntStateOf(0)

    /** True between resume and pause: the live test listens only while the screen is in use. */
    private val foreground = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs(this).dropRetiredData()
        setContent {
            MaterialTheme(colorScheme = ClappiestColorScheme) {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground
                ) { Screen(Prefs(this), resumes.intValue, foreground.value) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumes.intValue++
        foreground.value = true
    }

    override fun onPause() {
        foreground.value = false
        super.onPause()
    }
}

/**
 * The update popup's state lives here, not in the screen, so rotating the phone while the check
 * is in flight (or the popup is open) does not lose it: the check is throttled for 12 hours once
 * it has answered.
 */
private object UpdatePrompt {
    var pending by mutableStateOf<UpdateChecker.Update?>(null)
}

/** Secondary text is about 4.9:1 on black (AA needs 4.5:1). */
internal val ClappiestColorScheme = darkColorScheme(
    primary = Color(0xFF498C71),
    onPrimary = Color.White,
    secondary = Color(0xFFD9472B),
    onSecondary = Color.White,
    background = Color.Black,
    onBackground = Color.White,
    surface = Color.Black,
    onSurface = Color.White,
)

private val requiredPermissions = buildList {
    add(Manifest.permission.RECORD_AUDIO)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

@Composable
private fun Screen(prefs: Prefs, resumes: Int, foreground: Boolean) {
    val ctx = LocalContext.current
    var claps by remember { mutableIntStateOf(prefs.clapCount) }
    var sensitivity by remember { mutableFloatStateOf(prefs.sensitivity) }
    /** The saved value; the live test restarts on this, not on every slider step. */
    var savedSensitivity by remember { mutableFloatStateOf(prefs.sensitivity) }
    var sound by remember { mutableStateOf(prefs.alarmUri) }
    var clapToStop by remember { mutableStateOf(prefs.clapToStop) }
    var updateCheck by remember { mutableStateOf(prefs.updateCheck) }
    var denied by remember { mutableStateOf(false) }
    var explainMic by remember { mutableStateOf(false) }
    val armed = ListenerService.running
    val micGranted = remember(resumes, denied) {
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    }

    // Live test display. Held as State and read only inside LiveVisualizer, so the 50 updates a
    // second redraw the waveform and not the whole screen.
    val amplitudes = remember { mutableStateOf(FloatArray(30) { 0.1f }) }
    val recognized = remember { mutableStateOf(false) }

    fun refresh() { if (ListenerService.running) ListenerService.start(ctx) }

    // Once per launch (and at most every 12 hours, see UpdateChecker): ask Google Play for a newer version.
    LaunchedEffect(Unit) {
        Thread({
            val found = UpdateChecker(ctx, prefs).check()
            if (found != null) Handler(Looper.getMainLooper()).post { UpdatePrompt.pending = found }
        }, "update-check").start()
    }

    // The live test listens only while the app is on screen, unarmed, with the microphone allowed,
    // and restarts only when what it listens for changes.
    DisposableEffect(foreground, armed, micGranted, claps, savedSensitivity) {
        val stop = AtomicBoolean(false)
        var thread: Thread? = null
        if (foreground && micGranted && !armed) {
            thread = Thread({
                runLiveTest(claps, savedSensitivity, stop, amplitudes, recognized)
            }, "live-test-listener").apply { start() }
        }
        onDispose {
            stop.set(true)
            thread?.interrupt()
        }
    }

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
        // Some providers refuse a lasting grant; the file then works until the phone restarts, and
        // the alarm falls back to the default sound if it cannot be read later.
        runCatching { ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        prefs.alarmUri = uri; sound = uri
    }

    val soundName = remember(sound) {
        val uri = sound ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        runCatching { RingtoneManager.getRingtone(ctx, uri)?.getTitle(ctx) }.getOrNull()
            ?: uri?.lastPathSegment ?: "Default alarm"
    }

    UpdatePrompt.pending?.let { UpdateDialog(it) { UpdatePrompt.pending = null } }

    if (explainMic) {
        AlertDialog(
            onDismissRequest = { explainMic = false },
            containerColor = Color(0xFF111111),
            title = { Text("Why the microphone?") },
            text = {
                Text(
                    "The Clappiest listens for your clap code while the screen is off, so it can sound an alarm " +
                        "and help you find your phone. Sound is analysed on the phone as it arrives and thrown " +
                        "away. Nothing is recorded, saved or sent anywhere. Notifications are for the " +
                        "\"listening\" notice and the Stop button."
                )
            },
            confirmButton = {
                TextButton(onClick = { explainMic = false; permissions.launch(requiredPermissions) }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { explainMic = false }) { Text("Not now") } },
        )
    }

    Column(Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()) {
        // Everything scrolls except the ARM button, so it is always reachable on a short phone or
        // at a large font size.
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 24.dp, end = 24.dp, top = 24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text("The Clappiest", fontSize = 36.sp, fontWeight = FontWeight.Black)
            Text(
                if (armed) "Armed (listening in the background)..." else "Test your claps with the live visualizer below:",
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 14.sp
            )

            LiveVisualizer(amplitudes, recognized)

            Section("Claps") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { claps = (claps - 1).coerceAtLeast(MIN_CLAPS); prefs.clapCount = claps; refresh() },
                        modifier = Modifier.semantics { contentDescription = "Fewer claps" },
                    ) { Text("−") }
                    Text(
                        "$claps", fontSize = 40.sp, fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(horizontal = 24.dp).semantics { contentDescription = "$claps claps" },
                    )
                    OutlinedButton(
                        onClick = { claps = (claps + 1).coerceAtMost(MAX_CLAPS); prefs.clapCount = claps; refresh() },
                        modifier = Modifier.semantics { contentDescription = "More claps" },
                    ) { Text("+") }
                }
            }

            Section("Sensitivity") {
                Slider(
                    value = sensitivity,
                    onValueChange = { sensitivity = it },
                    onValueChangeFinished = { prefs.sensitivity = sensitivity; savedSensitivity = sensitivity; refresh() },
                    modifier = Modifier.semantics {
                        contentDescription = "Sensitivity"
                        stateDescription = "${(sensitivity * 100).toInt()} percent"
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = Color.White, activeTrackColor = Color.White,
                        inactiveTrackColor = Color(0xFF333333),
                    ),
                )
            }

            Section("Sound") {
                Text(soundName, fontSize = 16.sp)
                Spacer(Modifier.height(6.dp))
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

            Section("Silence") {
                SwitchRow("Clap twice to silence the alarm", clapToStop) { clapToStop = it; prefs.clapToStop = it }
                if (clapToStop) {
                    Text(
                        "The alarm pauses briefly to listen.",
                        color = MaterialTheme.colorScheme.secondary,
                        fontSize = 12.sp,
                    )
                }
            }

            Section("When to listen") {
                val summary = remember(resumes) { RulesActivity.summary(prefs.rules) }
                Text(summary, fontSize = 16.sp)
                Spacer(Modifier.height(6.dp))
                OutlinedButton(onClick = { RulesActivity.open(ctx) }) { Text("Choose when") }
            }

            Section("Updates") {
                SwitchRow("Check for updates", updateCheck) { updateCheck = it; prefs.updateCheck = it }
                Text(
                    "Asks Google Play whether a newer version exists when you open the app. " +
                        "Works for copies installed from Google Play.",
                    color = MaterialTheme.colorScheme.secondary,
                    fontSize = 12.sp,
                )
            }

            Spacer(Modifier.height(8.dp))
        }

        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (denied) Text("No microphone, no miracle.", color = MaterialTheme.colorScheme.secondary)
            Button(
                onClick = {
                    when {
                        armed -> ListenerService.stop(ctx)
                        requiredPermissions.all {
                            ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
                        } -> ListenerService.start(ctx)
                        else -> explainMic = true
                    }
                },
                modifier = Modifier.fillMaxWidth().height(56.dp),
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
}

/** A labelled switch that TalkBack reads as one control: label, role and state together. */
@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@Composable
private fun LiveVisualizer(amplitudes: State<FloatArray>, recognized: State<Boolean>) {
    Column(
        Modifier.fillMaxWidth().height(100.dp)
            .background(Color(0xFF111111), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .padding(12.dp)
            .clearAndSetSemantics { contentDescription = if (recognized.value) "Live test: clap code recognised" else "Live test waveform" },
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("LIVE VISUALIZER", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            if (recognized.value) {
                Text("RECOGNIZED! 🎯", color = Color.Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.height(8.dp))
        WaveformView(
            amplitudes = amplitudes.value,
            isRecognized = recognized.value,
            modifier = Modifier.fillMaxWidth().weight(1f)
        )
    }
}

/**
 * The live test: listens while the app is on screen and unarmed, drawing the level and flashing
 * when the clap code is heard. Stops when [stop] is set. Audio is measured and dropped.
 */
@SuppressLint("MissingPermission") // Checked by the caller.
private fun runLiveTest(
    claps: Int, sensitivity: Float, stop: AtomicBoolean,
    amplitudes: androidx.compose.runtime.MutableState<FloatArray>,
    recognized: androidx.compose.runtime.MutableState<Boolean>,
) {
    val main = Handler(Looper.getMainLooper())
    val sampleRate = 16000
    val minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
    val rec = runCatching {
        AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuf, sampleRate * 5),
        )
    }.getOrNull() ?: return
    if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }

    val detector = ClapDetector(sampleRate, claps, sensitivity)
    val buf = ShortArray(sampleRate / 50) // 20 ms
    val amps = FloatArray(30) { 0.1f }
    var ampIndex = 0
    val clearFlash = Runnable { recognized.value = false }
    try {
        rec.startRecording()
        while (!stop.get()) {
            val n = rec.read(buf, 0, buf.size)
            if (n <= 0) break
            var sumSq = 0.0
            for (i in 0 until n) { val v = buf[i].toDouble(); sumSq += v * v }
            amps[ampIndex] = (sqrt(sumSq / n).toFloat() / 10000f).coerceIn(0.05f, 1f)
            ampIndex = (ampIndex + 1) % amps.size
            val matched = detector.process(buf, n)
            val snapshot = amps.clone()
            main.post {
                amplitudes.value = snapshot
                if (matched) {
                    recognized.value = true
                    main.removeCallbacks(clearFlash)
                    main.postDelayed(clearFlash, 1200)
                }
            }
        }
    } catch (_: Exception) {
    } finally {
        runCatching { rec.stop() }
        rec.release()
        main.post { recognized.value = false }
    }
}

/** The "update available" popup. Plays no part in listening; it only points the user at the update. */
@Composable
private fun UpdateDialog(update: UpdateChecker.Update, onDone: () -> Unit) {
    val ctx = LocalContext.current
    fun later() {
        UpdateChecker(ctx, Prefs(ctx)).snooze(update)
        onDone()
    }
    AlertDialog(
        onDismissRequest = ::later,
        containerColor = Color(0xFF111111),
        title = { Text("Update available") },
        text = { Text("A new version is available on Google Play.") },
        confirmButton = {
            TextButton(onClick = { UpdateChecker(ctx, Prefs(ctx)).open(); onDone() }) { Text("Update") }
        },
        dismissButton = { TextButton(onClick = ::later) { Text("Later") } },
    )
}

@Composable
private fun WaveformView(
    amplitudes: FloatArray,
    isRecognized: Boolean,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val width = size.width
        val height = size.height
        val barWidth = width / amplitudes.size.coerceAtLeast(1)
        val baseColor = Color.Yellow
        val activeColor = Color.Green

        for (i in amplitudes.indices) {
            val amp = amplitudes[i].coerceIn(0f, 1f)
            val barHeight = (amp * height).coerceAtLeast(4f)
            val x = i * barWidth
            val y = (height - barHeight) / 2f

            // Base color is yellow until loud enough / recognized, changing that part of the waveform green
            val color = if (isRecognized || amp > 0.4f) activeColor else baseColor

            drawRect(
                color = color,
                topLeft = Offset(x + 2f, y),
                size = Size(barWidth - 4f, barHeight)
            )
        }
    }
}

@Composable
private fun Section(label: String, content: @Composable () -> Unit) {
    Column {
        Text(label.uppercase(), color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp, letterSpacing = 2.sp)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

private const val MIN_CLAPS = 2
private const val MAX_CLAPS = 10
