package com.hereliesaz.clappiest

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * "When to listen": the user's [Rules]. Edits save at once; the running service is told so it
 * re-evaluates. Every rule is optional, and none of them touches a sounding alarm.
 */
class RulesActivity : ComponentActivity() {
    /** Bumped on resume so location status is re-read after a trip to system settings. */
    private val resumes = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = ClappiestColorScheme) {
                Surface(
                    color = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) { RulesScreen(Prefs(this), resumes.intValue, ::finish) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumes.intValue++
    }

    companion object {
        fun open(context: Context) = context.startActivity(Intent(context, RulesActivity::class.java))

        /** One line for the main screen. */
        fun summary(r: Rules): String = when (r.count) {
            0 -> "Any time the screen is off."
            1 -> "1 rule on."
            else -> "${r.count} rules on."
        }
    }
}

@Composable
private fun RulesScreen(prefs: Prefs, resumes: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var rules by remember { mutableStateOf(prefs.rules) }
    fun update(r: Rules) {
        rules = r
        prefs.rules = r
        if (ListenerService.running) ListenerService.start(ctx)
    }

    Column(
        Modifier.fillMaxSize().background(Color.Black).safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text("When to listen", fontSize = 32.sp, fontWeight = FontWeight.Black)
        Text(
            "Every rule is optional. With none on, the app listens whenever the screen is off and it is armed. " +
                "Rules only decide when it listens; a sounding alarm is never cut off. " +
                "A rule that cannot be checked (no permission, no sensor) does not stop it listening.",
            color = MaterialTheme.colorScheme.secondary, fontSize = 13.sp,
        )

        // Hours
        RuleBlock("Hours") {
            Choices(
                rules.schedule,
                listOf(Presence.ANY to "Any time", Presence.NOT_WHEN to "Never during these hours", Presence.ONLY_WHEN to "Only during these hours"),
            ) { update(rules.copy(schedule = it)) }
            if (rules.schedule != Presence.ANY) {
                Days(rules.days) { update(rules.copy(days = it)) }
                Stepper("From", rules.startMin, 0, 1439, 30, ::clock) { update(rules.copy(startMin = it)) }
                Stepper("Until", rules.endMin, 0, 1439, 30, ::clock) { update(rules.copy(endMin = it)) }
                Note("If \"until\" is earlier than \"from\", the hours run past midnight. Switching happens within a few minutes of the time, not to the second.")
            }
        }

        // On body
        RuleBlock("On me") {
            Choices(
                rules.onBody,
                listOf(Presence.ANY to "Don't care", Presence.NOT_WHEN to "Not while it seems to be on me", Presence.ONLY_WHEN to "Only while it seems to be on me"),
            ) { update(rules.copy(onBody = it)) }
            if (rules.onBody != Presence.ANY) {
                Note(
                    "An estimate from motion and the proximity sensor, the way a phone guesses it is in a pocket. " +
                        "A phone resting in a still pocket can look like one on a table. " +
                        "It uses some battery while the screen is off."
                )
            }
        }

        // Charging
        RuleBlock("Charging") {
            Choices(
                rules.charging,
                listOf(Presence.ANY to "Don't care", Presence.NOT_WHEN to "Not while charging", Presence.ONLY_WHEN to "Only while charging"),
            ) { update(rules.copy(charging = it)) }
        }

        // Battery
        RuleBlock("Battery") {
            Stepper("Don't listen below", rules.batteryMin, 0, 50, 5, { if (it == 0) "off" else "$it%" }) { update(rules.copy(batteryMin = it)) }
        }

        // Headphones
        RuleBlock("Headphones") {
            Choices(
                rules.headphones,
                listOf(Presence.ANY to "Don't care", Presence.NOT_WHEN to "Not while headphones are connected", Presence.ONLY_WHEN to "Only while headphones are connected"),
            ) { update(rules.copy(headphones = it)) }
            if (rules.headphones != Presence.ANY) Note("Wired, USB or Bluetooth audio, including a car.")
        }

        // Places and Wi-Fi share the location permission.
        PlacesBlock(rules, resumes, ::update)
        WifiBlock(rules, resumes, ::update)

        Spacer(Modifier.height(4.dp))
        Button(onClick = onBack, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("Done") }
    }
}

private fun clock(min: Int) = "%d:%02d".format(min / 60, min % 60)

@Composable
private fun PlacesBlock(rules: Rules, resumes: Int, update: (Rules) -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf("") }
    var radius by remember { mutableIntStateOf(250) }
    var message by remember { mutableStateOf<String?>(null) }
    val access = remember(resumes) { LocationAccess.of(ctx) }
    var needsLocation by remember { mutableStateOf(false) }

    RuleBlock("Places") {
        Choices(
            rules.places,
            listOf(Presence.ANY to "Don't care", Presence.NOT_WHEN to "Not at these places", Presence.ONLY_WHEN to "Only at these places"),
        ) { update(rules.copy(places = it)) }
        if (rules.places != Presence.ANY) {
            rules.placeList.forEach { p ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("${p.name} · ${p.radiusM} m", Modifier.weight(1f))
                    TextButton(onClick = { update(rules.copy(placeList = rules.placeList - p)) }) { Text("Remove") }
                }
            }
            if (rules.placeList.size < RulesCodec.MAX_PLACES) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it.take(30) }, singleLine = true,
                    label = { Text("Name, e.g. Work") }, modifier = Modifier.fillMaxWidth(),
                )
                Choices(radius, listOf(100 to "Within 100 m", 250 to "Within 250 m", 500 to "Within 500 m")) { radius = it }
                OutlinedButton(onClick = {
                    if (access == LocationAccess.NONE) { needsLocation = true; return@OutlinedButton }
                    message = "Finding where you are…"
                    currentLocation(ctx) { loc ->
                        if (loc == null) {
                            message = "No location yet. Turn location on, wait a moment and try again."
                        } else {
                            val label = name.ifBlank { "Place ${rules.placeList.size + 1}" }
                            update(rules.copy(placeList = rules.placeList + Place(label, loc.latitude, loc.longitude, radius)))
                            name = ""; message = "Saved $label."
                        }
                    }
                }) { Text("Save where I am now") }
            }
            message?.let { Note(it) }
            LocationStatus(rules.placeList.isNotEmpty(), access, resumes, needsLocation) { needsLocation = false }
        }
    }
}

@Composable
private fun WifiBlock(rules: Rules, resumes: Int, update: (Rules) -> Unit) {
    val ctx = LocalContext.current
    var typed by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    val access = remember(resumes) { LocationAccess.of(ctx) }
    var needsLocation by remember { mutableStateOf(false) }

    fun add(ssid: String) {
        val s = ssid.trim().removeSurrounding("\"")
        if (s.isBlank()) return
        if (rules.wifiList.any { RuleEngine.sameNetwork(it, s) }) { message = "$s is already saved."; return }
        update(rules.copy(wifiList = rules.wifiList + s)); typed = ""; message = "Saved $s."
    }

    RuleBlock("Wi-Fi") {
        Choices(
            rules.wifi,
            listOf(Presence.ANY to "Don't care", Presence.NOT_WHEN to "Not on these networks", Presence.ONLY_WHEN to "Only on these networks"),
        ) { update(rules.copy(wifi = it)) }
        if (rules.wifi != Presence.ANY) {
            rules.wifiList.forEach { s ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(s, Modifier.weight(1f))
                    TextButton(onClick = { update(rules.copy(wifiList = rules.wifiList - s)) }) { Text("Remove") }
                }
            }
            if (rules.wifiList.size < RulesCodec.MAX_NETWORKS) {
                OutlinedTextField(
                    value = typed, onValueChange = { typed = it.take(32) }, singleLine = true,
                    label = { Text("Network name") }, modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { add(typed) }, enabled = typed.isNotBlank()) { Text("Add") }
                    OutlinedButton(onClick = {
                        if (access == LocationAccess.NONE) { needsLocation = true; return@OutlinedButton }
                        val ssid = currentSsid(ctx)
                        if (ssid == null) message = "Not connected to Wi-Fi, or the name could not be read." else add(ssid)
                    }) { Text("Use this network") }
                }
            }
            message?.let { Note(it) }
            LocationStatus(rules.wifiList.isNotEmpty(), access, resumes, needsLocation) { needsLocation = false }
        }
    }
}

/**
 * Explains and requests location. Shown only under the place and Wi-Fi rules, which are the only
 * features that use it. Android asks in two steps: first while the app is open, then (Android 11+,
 * in system settings) "all the time", which is what checking a place with the screen off needs.
 */
@Composable
private fun LocationStatus(inUse: Boolean, access: LocationAccess, resumes: Int, request: Boolean, dismissRequest: () -> Unit) {
    val ctx = LocalContext.current
    var step by remember(resumes) { mutableStateOf(0) } // 1 = explain, 2 = point at settings
    val foreground = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        step = if (LocationAccess.of(ctx) == LocationAccess.WHILE_IN_USE) 2 else 0
    }
    val background = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { step = 0 }

    LaunchedEffect(request) { if (request) step = 1 }

    Note(
        when (access) {
            LocationAccess.ALWAYS -> "Location: allowed all the time."
            LocationAccess.WHILE_IN_USE -> "Location: only while the app is open. To use this rule with the screen off, choose \"Allow all the time\"."
            LocationAccess.NONE -> "Location: not allowed. This rule can't be checked, so it won't stop the app listening."
        }
    )
    if (inUse && access != LocationAccess.ALWAYS) {
        OutlinedButton(onClick = { step = if (access == LocationAccess.NONE) 1 else 2 }) { Text("Allow location") }
    }

    if (step == 1) {
        AlertDialog(
            onDismissRequest = { step = 0; dismissRequest() },
            containerColor = Color(0xFF111111),
            title = { Text("Use your location?") },
            text = {
                Text(
                    "The Clappiest uses your location, including when the app is closed or the screen is off, only to " +
                        "check whether you are at a place you saved or on a Wi-Fi network you saved, so it can pause " +
                        "listening. Your location is not stored, shared or sent anywhere."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    dismissRequest()
                    foreground.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    step = 0
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { step = 0; dismissRequest() }) { Text("Not now") } },
        )
    }
    if (step == 2) {
        AlertDialog(
            onDismissRequest = { step = 0 },
            containerColor = Color(0xFF111111),
            title = { Text("Allow all the time") },
            text = {
                Text(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                        "Android asks for this in settings. Open Permissions → Location and choose \"Allow all the time\"."
                    else
                        "Android will ask once more. Choose \"Allow all the time\"."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    step = 0
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        ctx.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)),
                        )
                    } else {
                        background.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                }) { Text("Continue") }
            },
            dismissButton = { TextButton(onClick = { step = 0 }) { Text("Not now") } },
        )
    }
}

@Composable
private fun RuleBlock(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title.uppercase(), color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp, letterSpacing = 2.sp)
        content()
    }
}

@Composable
private fun Note(text: String) =
    Text(text, color = MaterialTheme.colorScheme.secondary, fontSize = 12.sp)

/** Radio rows: one choice, announced to TalkBack as selected or not. */
@Composable
private fun <T> Choices(value: T, options: List<Pair<T, String>>, onPick: (T) -> Unit) {
    Column {
        options.forEach { (v, label) ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = v == value, role = Role.RadioButton, onClick = { onPick(v) })
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = v == value, onClick = null)
                Text(label, Modifier.padding(start = 12.dp), fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun Days(mask: Int, onChange: (Int) -> Unit) {
    val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            names.forEachIndexed { i, n ->
                val on = (mask shr i) and 1 == 1
                Button(
                    onClick = { onChange(mask xor (1 shl i)) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                    modifier = Modifier.weight(1f).height(40.dp).semantics { stateDescription = if (on) "On" else "Off" },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (on) MaterialTheme.colorScheme.primary else Color.DarkGray, contentColor = Color.White,
                    ),
                ) { Text(n.take(2), fontSize = 13.sp) }
            }
        }
        if (mask == 0) Note("No days chosen: this rule does nothing.")
    }
}

@Composable
private fun Stepper(label: String, value: Int, min: Int, max: Int, step: Int, show: (Int) -> String, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), fontSize = 16.sp)
        OutlinedButton(onClick = { onChange((value - step).coerceAtLeast(min)) }) { Text("−") }
        Text(show(value), Modifier.padding(horizontal = 16.dp), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        OutlinedButton(onClick = { onChange((value + step).coerceAtMost(max)) }) { Text("+") }
    }
}

/** Best current position, or null. Needs location permission, which the caller has checked. */
@SuppressLint("MissingPermission")
private fun currentLocation(context: Context, done: (Location?) -> Unit) {
    val lm = context.getSystemService(LocationManager::class.java)
    val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter {
        runCatching { lm.isProviderEnabled(it) }.getOrDefault(false)
    }
    fun lastKnown() = (providers + LocationManager.PASSIVE_PROVIDER)
        .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
        .maxByOrNull { it.time }
    val provider = providers.firstOrNull()
    if (provider == null) { done(lastKnown()); return }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        runCatching {
            lm.getCurrentLocation(provider, null, context.mainExecutor) { loc -> done(loc ?: lastKnown()) }
        }.onFailure { done(lastKnown()) }
    } else {
        done(lastKnown())
    }
}

/** Name of the connected Wi-Fi network, or null. Needs location permission. */
@Suppress("DEPRECATION")
private fun currentSsid(context: Context): String? = runCatching {
    val info = context.getSystemService(WifiManager::class.java).connectionInfo
    info?.ssid?.takeIf { info.networkId != -1 && it != WifiManager.UNKNOWN_SSID }?.removeSurrounding("\"")
}.getOrNull()
