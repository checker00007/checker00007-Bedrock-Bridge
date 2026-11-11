package de.checker00007.bedrockbridge

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("servers", Activity.MODE_PRIVATE)
        setContent {
            val green = Color(0xFF62E68C)
            val colors = darkColorScheme(primary = green, background = Color(0xFF101711), surface = Color(0xFF1B2920))
            var name by remember { mutableStateOf(prefs.getString("name", "Mein Geyser Server") ?: "") }
            var host by remember { mutableStateOf(prefs.getString("host", "") ?: "") }
            var port by remember { mutableStateOf(prefs.getString("port", "19132") ?: "19132") }
            var message by remember { mutableStateOf("LAN-Bridge noch nicht implementiert") }
            MaterialTheme(colorScheme = colors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text("checker00007", style = MaterialTheme.typography.headlineMedium, color = green)
                        Text("BEDROCK BRIDGE · v0.1", style = MaterialTheme.typography.titleMedium)
                        Text("Minecraft Java + GeyserMC / Xbox Series")
                        OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Servername") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("Geyser-Adresse") }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("Bedrock-Port") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                        Button(onClick = {
                            val validPort = port.toIntOrNull()?.takeIf { it in 1..65535 }
                            if (host.isBlank() || validPort == null) message = "Bitte gültige Adresse und Port eingeben"
                            else { prefs.edit().putString("name", name).putString("host", host.trim()).putString("port", port).apply(); message = "Serverprofil lokal gespeichert" }
                        }, modifier = Modifier.fillMaxWidth()) { Text("Serverprofil speichern") }
                        Text(message, color = green)
                        Text("Xbox-Verbindung: folgt nach Protokolltest", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
