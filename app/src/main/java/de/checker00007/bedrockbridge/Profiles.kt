package de.checker00007.bedrockbridge

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal enum class Mode(val label: String) {
    NETHER("NetherNet (experimentell)"),
    LEGACY("RakNet (ältere Clients)")
}

internal data class Profile(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "Mein Geyser Server",
    val host: String = "",
    val port: Int = 19132,
    val mode: Mode = Mode.NETHER,
    val version: String = "26.60",
    val protocol: Int = 2225,
    val signaling: String = "HTTPS",
    val nonce: String = ""
) {
    fun json() = JSONObject().put("id", id).put("name", name).put("host", host)
        .put("port", port).put("mode", mode.name).put("version", version)
        .put("protocol", protocol).put("signaling", signaling).put("nonce", nonce)

    fun valid(): Boolean = name.isNotBlank() && name.length <= 80 && validHost(host) &&
        port in 1..65535 && (mode == Mode.LEGACY || (
            version.matches(Regex("[0-9.]{2,16}")) && protocol > 0 &&
                signaling in setOf("HTTPS", "HTTPS 443", "HTTP") &&
                nonce.matches(Regex("[a-fA-F0-9]{0,128}"))
            ))

    companion object {
        fun parse(o: JSONObject) = Profile(
            id = o.getString("id"), name = o.getString("name"), host = o.getString("host"),
            port = o.getInt("port"), mode = Mode.valueOf(o.getString("mode")),
            version = o.getString("version"), protocol = o.getInt("protocol"),
            signaling = o.getString("signaling"), nonce = o.optString("nonce")
        )

        fun validHost(value: String): Boolean {
            if (value.isEmpty() || value.length > 253) return false
            if (':' in value) {
                return '%' !in value && value.matches(Regex("[0-9a-fA-F:.]+")) &&
                    runCatching { java.net.InetAddress.getByName(value) }.isSuccess
            }
            return value.removeSuffix(".").split('.').all {
                it.matches(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?"))
            }
        }
    }
}

internal class ProfileStore(private val prefs: SharedPreferences) {
    fun load(): List<Profile> {
        val stored = prefs.getString("profiles", null)
        if (stored != null) {
            val array = runCatching { JSONArray(stored) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull {
                runCatching { Profile.parse(array.getJSONObject(it)) }.getOrNull()?.takeIf(Profile::valid)
            }
        }
        val host = prefs.getString("host", "").orEmpty()
        val legacy = Profile(
            name = prefs.getString("name", "").orEmpty().ifBlank { "Mein Geyser Server" },
            host = host,
            port = prefs.getString("port", "")?.toIntOrNull() ?: 19132,
            mode = Mode.LEGACY
        )
        return listOf(legacy).filter(Profile::valid).also(::save)
    }

    fun save(profiles: List<Profile>) {
        val array = JSONArray()
        profiles.forEach { array.put(it.json()) }
        prefs.edit().putString("profiles", array.toString()).apply()
    }
}
