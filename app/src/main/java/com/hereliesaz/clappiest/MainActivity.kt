package com.hereliesaz.clappiest

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlin.math.sqrt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = ClappiestColorScheme) {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground
                ) { Screen(Prefs(this)) }
            }
        }
    }
}

private val ClappiestColorScheme = darkColorScheme(
    primary = Color(0xFF498C71),
    onPrimary = Color.White,
    secondary = Color(0xFFBB361C),
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
private fun Screen(prefs: Prefs) {
    val ctx = LocalContext.current
    var triggerMode by remember { mutableStateOf(prefs.triggerMode) }
    var claps by remember { mutableIntStateOf(prefs.clapCount) }
    var sensitivity by remember { mutableFloatStateOf(prefs.sensitivity) }
    var sound by remember { mutableStateOf(prefs.alarmUri) }
    var isRecording by remember { mutableStateOf(false) }
    var hasCustomSound by remember { mutableStateOf(prefs.customSoundBytes != null) }
    var clapToStop by remember { mutableStateOf(prefs.clapToStop) }
    var updateCheck by remember { mutableStateOf(prefs.updateCheck) }
    var update by remember { mutableStateOf<UpdateChecker.Update?>(null) }
    var denied by remember { mutableStateOf(false) }
    val armed = ListenerService.running

    // Live test visualizer state
    var visualizerAmplitudes by remember { mutableStateOf(FloatArray(30) { 0.1f }) }
    var isRecognizedFlash by remember { mutableStateOf(false) }

    fun refresh() { if (ListenerService.running) ListenerService.start(ctx) }

    // Once per launch (and at most every 12 hours, see UpdateChecker): ask Google Play for a newer version.
    LaunchedEffect(Unit) {
        Thread({
            val found = UpdateChecker(ctx, prefs).check()
            if (found != null) Handler(Looper.getMainLooper()).post { update = found }
        }, "update-check").start()
    }

    // Live test listener when screen / activity is active
    DisposableEffect(triggerMode, prefs.sensitivity, prefs.clapCount, prefs.customSoundBytes) {
        var testThread: Thread? = null
        val hasPermission = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (hasPermission && !armed) {
            testThread = Thread({
                val sampleRate = 16000
                val minBuf = AudioRecord.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                val rec = runCatching {
                    AudioRecord(
                        MediaRecorder.AudioSource.VOICE_RECOGNITION,
                        sampleRate,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                        maxOf(minBuf, sampleRate * 5)
                    )
                }.getOrNull()

                if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) {
                    val detector: Any = if (prefs.triggerMode == "custom") {
                        val bytes = prefs.customSoundBytes
                        val shorts = if (bytes != null) {
                            ShortArray(bytes.size / 2) { i ->
                                ((bytes[i * 2].toInt() and 0xFF) or (bytes[i * 2 + 1].toInt() shl 8)).toShort()
                            }
                        } else ShortArray(0)
                        CustomSoundDetector(shorts, prefs.sensitivity)
                    } else {
                        ClapDetector(sampleRate, prefs.clapCount, prefs.sensitivity)
                    }

                    val buf = ShortArray(sampleRate / 50) // 20ms frames
                    try {
                        rec.startRecording()
                        val me = Thread.currentThread()
                        val amps = FloatArray(30) { 0.1f }
                        var ampIndex = 0

                        while (testThread === me) {
                            val n = rec.read(buf, 0, buf.size)
                            if (n <= 0) break

                            // Compute RMS for visualizer
                            var sumSq = 0.0
                            for (i in 0 until n) {
                                val v = buf[i].toDouble()
                                sumSq += v * v
                            }
                            val rms = sqrt(sumSq / n).toFloat()
                            val normalized = (rms / 10000f).coerceIn(0.05f, 1f)

                            amps[ampIndex] = normalized
                            ampIndex = (ampIndex + 1) % amps.size

                            val matched = when (detector) {
                                is ClapDetector -> detector.process(buf, n)
                                is CustomSoundDetector -> detector.process(buf, n)
                                else -> false
                            }

                            Handler(Looper.getMainLooper()).post {
                                visualizerAmplitudes = amps.clone()
                                if (matched) {
                                    isRecognizedFlash = true
                                    Handler(Looper.getMainLooper()).postDelayed({
                                        isRecognizedFlash = false
                                    }, 1200)
                                }
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        runCatching { rec.stop() }
                        rec.release()
                    }
                }
            }, "live-test-listener").apply { start() }
        }
        onDispose {
            testThread?.let {
                val t = it
                testThread = null
                t.interrupt()
            }
        }
    }

    val permissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        denied = result[Manifest.permission.RECORD_AUDIO] != true
        if (!denied) ListenerService.start(ctx)
    }

    val recordPermissions = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            isRecording = true
            recordCustomSound(ctx) {
                isRecording = false
                hasCustomSound = true
                triggerMode = prefs.triggerMode
                refresh()
            }
        }
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
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        prefs.alarmUri = uri; sound = uri
    }

    val soundName = remember(sound) {
        val uri = sound ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        runCatching { RingtoneManager.getRingtone(ctx, uri)?.getTitle(ctx) }.getOrNull()
            ?: uri?.lastPathSegment ?: "Default alarm"
    }

    update?.let { UpdateDialog(it) { update = null } }

    Column(
        Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text("The Clappiest", fontSize = 36.sp, fontWeight = FontWeight.Black)
        Text(
            if (armed) "Armed (Listening in background)..." else "Test your claps or whistle with the live visualizer below:",
            color = MaterialTheme.colorScheme.secondary,
            fontSize = 14.sp
        )

        // Live Waveform Visualizer
        Column(Modifier.fillMaxWidth().height(100.dp).background(Color(0xFF111111), shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("LIVE VISUALIZER", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                if (isRecognizedFlash) {
                    Text("RECOGNIZED! 🎯", color = Color.Green, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(8.dp))
            WaveformView(
                amplitudes = visualizerAmplitudes,
                isRecognized = isRecognizedFlash,
                modifier = Modifier.fillMaxWidth().weight(1f)
            )
        }

        // Trigger Mode selector
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { triggerMode = "claps"; prefs.triggerMode = "claps"; refresh() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (triggerMode == "claps") MaterialTheme.colorScheme.primary else Color.DarkGray,
                    contentColor = Color.White
                )
            ) {
                Text("Claps")
            }
            Button(
                onClick = { triggerMode = "custom"; prefs.triggerMode = "custom"; refresh() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (triggerMode == "custom") MaterialTheme.colorScheme.primary else Color.DarkGray,
                    contentColor = Color.White
                )
            ) {
                Text("Custom Sound / Whistle")
            }
        }

        if (triggerMode == "claps") {
            Section("Claps") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = {
                        claps = (claps - 1).coerceAtLeast(MIN_CLAPS); prefs.clapCount = claps; refresh()
                    }) { Text("−") }
                    Text(
                        "$claps", fontSize = 40.sp, fontWeight = FontWeight.Black,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                    OutlinedButton(onClick = {
                        claps = (claps + 1).coerceAtMost(MAX_CLAPS); prefs.clapCount = claps; refresh()
                    }) { Text("+") }
                }
            }
        } else {
            Section("Custom Sound / Whistle") {
                Text(
                    if (isRecording) "Listening... make your sound now!"
                    else if (hasCustomSound) "Custom sound recorded ✓"
                    else "No sound recorded yet.",
                    color = if (hasCustomSound) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = {
                        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                            isRecording = true
                            recordCustomSound(ctx) {
                                isRecording = false
                                hasCustomSound = true
                                triggerMode = prefs.triggerMode
                                refresh()
                            }
                        } else {
                            recordPermissions.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    enabled = !isRecording
                ) {
                    Text(if (isRecording) "Recording..." else "Record Whistle / Sound")
                }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Clap twice to silence the alarm", fontSize = 16.sp, modifier = Modifier.weight(1f))
                Switch(checked = clapToStop, onCheckedChange = { clapToStop = it; prefs.clapToStop = it })
            }
            if (clapToStop) {
                Text(
                    "The alarm pauses briefly to listen.",
                    color = MaterialTheme.colorScheme.secondary,
                    fontSize = 12.sp,
                )
            }
        }

        Section("Updates") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Check for updates", fontSize = 16.sp, modifier = Modifier.weight(1f))
                Switch(checked = updateCheck, onCheckedChange = { updateCheck = it; prefs.updateCheck = it })
            }
            Text(
                "Asks Google Play whether a newer version exists when you open the app. " +
                    "Works for copies installed from Google Play.",
                color = MaterialTheme.colorScheme.secondary,
                fontSize = 12.sp,
            )
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

@SuppressLint("MissingPermission")
private fun recordCustomSound(context: Context, onRecorded: () -> Unit) {
    Thread({
        val sampleRate = 16000
        val durationMs = 1500
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(bufferSize, sampleRate * 2)
        )
        if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release()
            return@Thread
        }

        val totalSamples = (sampleRate * durationMs) / 1000
        val shortBuffer = ShortArray(totalSamples)
        var readCount = 0

        try {
            audioRecord.startRecording()
            while (readCount < totalSamples) {
                val n = audioRecord.read(shortBuffer, readCount, totalSamples - readCount)
                if (n <= 0) break
                readCount += n
            }
        } catch (_: Exception) {
        } finally {
            runCatching { audioRecord.stop() }
            audioRecord.release()
        }

        if (readCount > 0) {
            val byteBuffer = ByteArray(readCount * 2)
            for (i in 0 until readCount) {
                val s = shortBuffer[i]
                byteBuffer[i * 2] = (s.toInt() and 0xFF).toByte()
                byteBuffer[i * 2 + 1] = ((s.toInt() shr 8) and 0xFF).toByte()
            }
            val prefs = Prefs(context)
            prefs.customSoundBytes = byteBuffer
            prefs.triggerMode = "custom"
            Handler(Looper.getMainLooper()).post { onRecorded() }
        }
    }, "sound-recorder").start()
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
