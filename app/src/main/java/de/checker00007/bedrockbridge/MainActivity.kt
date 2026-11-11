package de.checker00007.bedrockbridge

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.InetSocketAddress

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = ProfileStore(getSharedPreferences("servers", MODE_PRIVATE))
        setContent { BridgeScreen(store) }
    }

    private fun start(profile: Profile) {
        runCatching { BridgeService.start(this, profile) }
            .onFailure { BridgeLog.add("Bridge konnte nicht gestartet werden. App geöffnet lassen und erneut versuchen.") }
            .onSuccess {
                if (Build.VERSION.SDK_INT >= 33 &&
                    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
                    !getPreferences(MODE_PRIVATE).getBoolean("notificationAsked", false)
                ) {
                    getPreferences(MODE_PRIVATE).edit().putBoolean("notificationAsked", true).apply()
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
    }

    @Composable
    private fun BridgeScreen(store: ProfileStore) {
        var profiles by remember { mutableStateOf(store.load()) }
        var edit by remember { mutableStateOf<Profile?>(null) }
        var remove by remember { mutableStateOf<Profile?>(null) }
        var checking by remember { mutableStateOf(false) }
        val state by BridgeLog.state.collectAsState()
        val scope = rememberCoroutineScope()
        val green = Color(0xFFA4FF36)
        MaterialTheme(colorScheme = darkColorScheme(
            primary = green,
            onPrimary = Color(0xFF142000),
            background = Color(0xFF090D09),
            surface = Color(0xFF111811),
            surfaceVariant = Color(0xFF222D21),
            onSurface = Color(0xFFEEF5E9)
        )) {
            Scaffold { padding ->
                Column(
                    Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Text("checker00007", color = green, style = MaterialTheme.typography.labelLarge)
                    Text("Bedrock Bridge", style = MaterialTheme.typography.headlineLarge)
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                if (state.running) "Bridge aktiv · ${state.profile}" else "Bridge bereit",
                                color = green, style = MaterialTheme.typography.titleMedium
                            )
                            Text("Xbox und Smartphone im selben WLAN oder LAN. Der Geyser-Server kann entfernt stehen.")
                            if (state.running) {
                                OutlinedButton(onClick = { BridgeService.stop(this@MainActivity) }) {
                                    Text("Bridge stoppen")
                                }
                            }
                        }
                    }
                    Text("Serverprofile", style = MaterialTheme.typography.titleLarge)
                    if (profiles.isEmpty()) Text("Füge die Bedrock-Adresse deines Geyser-Servers hinzu.")
                    profiles.forEach { profile ->
                        key(profile.id) {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    Text(profile.name, style = MaterialTheme.typography.titleMedium)
                                    val host = if (':' in profile.host) "[${profile.host}]" else profile.host
                                    Text("$host:${profile.port} · ${profile.mode.label}")
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Button(onClick = { start(profile) }) { Text("LAN starten") }
                                        OutlinedButton(
                                            enabled = !checking,
                                            onClick = {
                                                checking = true
                                                scope.launch {
                                                    try {
                                                        withContext(Dispatchers.IO) { diagnose(profile) }
                                                    } finally {
                                                        checking = false
                                                    }
                                                }
                                            }
                                        ) { Text("Diagnose") }
                                    }
                                    Row {
                                        TextButton(onClick = { edit = profile }) { Text("Bearbeiten") }
                                        TextButton(onClick = { remove = profile }) { Text("Löschen") }
                                    }
                                }
                            }
                        }
                    }
                    OutlinedButton(onClick = { edit = Profile() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Server hinzufügen")
                    }
                    HorizontalDivider()
                    Text("Protokoll", style = MaterialTheme.typography.titleLarge)
                    if (checking) LinearProgressIndicator(Modifier.fillMaxWidth())
                    if (state.events.isEmpty()) Text("Diagnose und LAN-Ereignisse erscheinen hier.")
                    else {
                        TextButton(onClick = {
                            getSystemService(ClipboardManager::class.java).setPrimaryClip(
                                ClipData.newPlainText("Bedrock Bridge", state.events.joinToString("\n"))
                            )
                        }) { Text("Protokoll kopieren") }
                        state.events.asReversed().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            edit?.let { current ->
                key(current.id) {
                    ProfileDialog(current, { edit = null }) { updated ->
                        profiles = if (profiles.any { it.id == updated.id }) {
                            profiles.map { if (it.id == updated.id) updated else it }
                        } else profiles + updated
                        store.save(profiles)
                        edit = null
                    }
                }
            }
            remove?.let { profile ->
                AlertDialog(
                    onDismissRequest = { remove = null },
                    title = { Text("Serverprofil löschen?") },
                    text = { Text(profile.name) },
                    confirmButton = {
                        TextButton(onClick = {
                            profiles = profiles.filter { it.id != profile.id }
                            store.save(profiles)
                            remove = null
                        }) { Text("Löschen") }
                    },
                    dismissButton = { TextButton(onClick = { remove = null }) { Text("Abbrechen") } }
                )
            }
        }
    }

    private fun diagnose(profile: Profile) {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val lan = wifiLan(connectivity)
        BridgeLog.add(if (lan != null) "WLAN mit IPv4 erkannt." else "Kein WLAN mit IPv4 erkannt.")
        try {
            if (profile.mode == Mode.NETHER) {
                val status = JoinEndpoint(profile).status()
                val data = JSONObject(status)
                val protocol = data.optInt("protocol", -1)
                BridgeLog.add("NetherNet /v1/join antwortet per ${profile.signaling}.")
                if (protocol > 0 && protocol != profile.protocol) {
                    BridgeLog.add("Signaling meldet Protokoll $protocol, Profil verwendet ${profile.protocol}.")
                }
                BridgeLog.add("ICE und Xbox-Anmeldung werden erst beim Beitritt geprüft.")
            } else {
                val target = InetSocketAddress(profile.host, profile.port)
                if (target.isUnresolved) throw java.net.UnknownHostException()
                val (motd, _) = rakProbe(target)
                val fields = motd.split(';')
                BridgeLog.add("RakNet antwortet. Serverversion ${fields[3]}, Protokoll ${fields[2]}.")
            }
        } catch (error: org.json.JSONException) {
            BridgeLog.add("NetherNet /v1/join antwortet, liefert aber keine JSON-Statusdaten.")
        } catch (error: Exception) {
            BridgeLog.add(networkError(error))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfileDialog(initial: Profile, dismiss: () -> Unit, save: (Profile) -> Unit) {
    var name by remember { mutableStateOf(initial.name) }
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(initial.port.toString()) }
    var version by remember { mutableStateOf(initial.version) }
    var protocol by remember { mutableStateOf(initial.protocol.toString()) }
    var nonce by remember { mutableStateOf(initial.nonce) }
    var mode by remember { mutableStateOf(initial.mode) }
    var signaling by remember { mutableStateOf(initial.signaling) }
    var advanced by remember { mutableStateOf(false) }
    var invalid by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("Serverprofil") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfileField("Name", name, { name = it }, 80)
                ProfileField("Geyser-Adresse", host, { host = it }, 253, KeyboardType.Uri)
                ProfileField("Bedrock-Port", port, { port = it }, 5, KeyboardType.Number)
                ProfileChoice("Transport", mode.label, Mode.entries.map { it.label }) { selected ->
                    mode = Mode.entries.first { it.label == selected }
                }
                if (mode == Mode.NETHER) {
                    ProfileChoice("Signaling", signaling, listOf("HTTPS", "HTTPS 443", "HTTP")) { signaling = it }
                    TextButton(onClick = { advanced = !advanced }) {
                        Text(if (advanced) "Erweiterte Felder ausblenden" else "Version und Protokoll einstellen")
                    }
                    if (advanced) {
                        ProfileField("Bedrock-Version", version, { version = it }, 16)
                        ProfileField("Protokollnummer", protocol, { protocol = it }, 10, KeyboardType.Number)
                        ProfileField("Öffentliche Server-Nonce (optional)", nonce, { nonce = it }, 128)
                    }
                }
                if (invalid) Text(
                    "Bitte Name, Host ohne URL, Port (1–65535) und Protokollfelder prüfen.",
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val updated = initial.copy(
                    name = name.trim(), host = host.trim().removeSurrounding("[", "]"),
                    port = port.toIntOrNull() ?: 0, mode = mode,
                    version = version.trim(), protocol = protocol.toIntOrNull() ?: 0,
                    signaling = signaling, nonce = nonce.trim()
                )
                if (updated.valid()) save(updated) else invalid = true
            }) { Text("Speichern") }
        },
        dismissButton = { TextButton(onClick = dismiss) { Text("Abbrechen") } }
    )
}

@Composable
private fun ProfileField(
    label: String, value: String, update: (String) -> Unit, limit: Int,
    type: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value, { update(it.take(limit)) }, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = type), modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ProfileChoice(label: String, selected: String, choices: List<String>, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label · $selected")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { choice ->
                DropdownMenuItem(text = { Text(choice) }, onClick = {
                    onSelect(choice)
                    open = false
                })
            }
        }
    }
}
