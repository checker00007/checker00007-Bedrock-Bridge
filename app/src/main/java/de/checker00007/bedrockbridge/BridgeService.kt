package de.checker00007.bedrockbridge

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal data class BridgeState(
    val running: Boolean = false,
    val profile: String? = null,
    val events: List<String> = emptyList()
)

internal object BridgeLog {
    private val mutable = MutableStateFlow(BridgeState())
    val state = mutable.asStateFlow()

    @Synchronized
    fun status(running: Boolean, profile: String? = null) {
        mutable.value = mutable.value.copy(running = running, profile = profile)
    }

    @Synchronized
    fun add(message: String) {
        val timestamp = java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
        mutable.value = mutable.value.copy(events = (mutable.value.events + "$timestamp  $message").takeLast(60))
    }
}

internal data class LanNetwork(val network: Network, val local: LinkAddress) {
    fun permits(client: InetSocketAddress): Boolean {
        val address = client.address as? Inet4Address ?: return false
        if (address == local.address || address.isAnyLocalAddress || address.isMulticastAddress ||
            address.isLoopbackAddress || local.prefixLength !in 1..32) return false
        val expected = local.address.address
        val actual = address.address
        for (i in 0 until local.prefixLength) {
            val mask = 1 shl (7 - i % 8)
            if ((expected[i / 8].toInt() xor actual[i / 8].toInt()) and mask != 0) return false
        }
        return true
    }
}

internal fun wifiLan(manager: ConnectivityManager): LanNetwork? {
    @Suppress("DEPRECATION")
    val candidates = listOfNotNull(manager.activeNetwork) + manager.allNetworks.toList()
    return candidates.distinct().firstNotNullOfOrNull { network ->
        val caps = manager.getNetworkCapabilities(network)
        val ipv4 = manager.getLinkProperties(network)?.linkAddresses?.firstOrNull {
            it.address is Inet4Address && !it.address.isLinkLocalAddress
        }
        if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true && ipv4 != null) {
            LanNetwork(network, ipv4)
        } else null
    }
}

class BridgeService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private var current: Session? = null
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) = checkLan()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) = checkLan()
    }

    private fun checkLan() {
        handler.post {
            val session = current ?: return@post
            if (wifiLan(connectivity) != session.network) {
                BridgeLog.add("WLAN oder lokale IP hat sich geändert. Bridge bei Bedarf neu starten.")
                stopCurrent()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            stopCurrent()
            return START_NOT_STICKY
        }
        val profile = runCatching {
            Profile.parse(JSONObject(intent?.getStringExtra(PROFILE).orEmpty()))
        }.getOrNull()?.takeIf(Profile::valid)
        if (profile == null) {
            stopCurrent()
            return START_NOT_STICKY
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Bedrock Bridge", NotificationManager.IMPORTANCE_LOW)
        )
        startForeground(1, notification(profile))
        current?.close()
        current = null
        val network = wifiLan(connectivity)
        if (network == null) {
            BridgeLog.add("Kein WLAN mit IPv4. Smartphone und Xbox mit demselben LAN verbinden.")
            stopCurrent()
            return START_NOT_STICKY
        }

        try {
            if (!registered) {
                connectivity.registerNetworkCallback(
                    NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                    callback
                )
                registered = true
            }
            val session = Session(profile, network)
            current = session
            BridgeLog.status(true, profile.name)
            session.thread.start()
        } catch (error: Exception) {
            BridgeLog.add(networkError(error))
            stopCurrent()
        }
        return START_NOT_STICKY
    }

    private fun notification(profile: Profile): Notification {
        val open = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, BridgeService::class.java).setAction(STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentTitle("Bedrock Bridge läuft")
            .setContentText(profile.name)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Stoppen", stop).build())
            .build()
    }

    private fun stopCurrent() {
        current?.close()
        current = null
        BridgeLog.status(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        current?.close()
        current = null
        if (registered) connectivity.unregisterNetworkCallback(callback)
        BridgeLog.status(false)
        super.onDestroy()
    }

    private inner class Session(val profile: Profile, val network: LanNetwork) {
        private val lan = DatagramSocket(null)
        private val signaling = ThreadPoolExecutor(1, 2, 15L, TimeUnit.SECONDS, ArrayBlockingQueue(2))
        private val lock = applicationContext.getSystemService(WifiManager::class.java)
            .createMulticastLock("bedrock-bridge").apply { setReferenceCounted(false) }

        val thread = Thread({
            try {
                lock.acquire()
                val port = if (profile.mode == Mode.NETHER) NetherWire.PORT else RakWire.PORT
                lan.broadcast = true
                lan.bind(InetSocketAddress("0.0.0.0", port))
                val allowed: (InetSocketAddress) -> Boolean = network::permits
                if (profile.mode == Mode.NETHER) {
                    NetherBridge(lan, profile, signaling, allowed, BridgeLog::add).run()
                } else {
                    val target = InetSocketAddress(profile.host, profile.port)
                    if (target.isUnresolved) throw java.net.UnknownHostException()
                    require(!(target.address == network.local.address && target.port == port)) {
                        "Zieladresse zeigt auf diese Bridge."
                    }
                    LegacyBridge(lan, target, profile, allowed, BridgeLog::add).use { it.run() }
                }
            } catch (error: Exception) {
                if (!lan.isClosed) BridgeLog.add(networkError(error))
            } finally {
                close()
                if (lock.isHeld) lock.release()
                handler.post { if (current === this) stopCurrent() }
            }
        }, "bedrock-lan")

        fun close() {
            lan.close()
            signaling.shutdownNow()
        }
    }

    companion object {
        private const val PROFILE = "profile"
        private const val STOP = "stop"
        private const val CHANNEL = "bedrock_bridge"

        internal fun start(context: Context, profile: Profile) {
            val intent = Intent(context, BridgeService::class.java).putExtra(PROFILE, profile.json().toString())
            ContextCompat.startForegroundService(context, intent)
        }

        internal fun stop(context: Context) {
            context.stopService(Intent(context, BridgeService::class.java))
            BridgeLog.add("Bridge gestoppt.")
        }
    }
}
