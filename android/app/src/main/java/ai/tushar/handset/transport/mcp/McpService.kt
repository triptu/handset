package ai.tushar.handset.transport.mcp

import ai.tushar.handset.Handset
import ai.tushar.handset.R
import ai.tushar.handset.ui.MainActivity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import java.net.InetAddress

/**
 * Runs the MCP server while the user has it on. It must be a foreground service: in Doze, Android cuts a background
 * app's network, even on localhost. The wake lock and the Wi-Fi lock keep calls answering with the screen off.
 *
 * The server listens on loopback, for adb's forward, and on Tailscale, which encrypts the traffic. Wi-Fi only when the
 * user allows it, since the traffic is plain HTTP. It listens again whenever a network comes, goes or changes address.
 */
class McpService : Service() {
    private var server: McpHttpServer? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val main by lazy { Handler(mainLooper) }
    private val relisten = Runnable { listen() }

    /** An address appears a moment after its network does, so each change waits briefly, and many changes listen once */
    private val networks = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = soon()
        override fun onLost(network: Network) = soon()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = soon()

        private fun soon() {
            main.removeCallbacks(relisten)
            main.postDelayed(relisten, NETWORK_SETTLE_MS)
        }
    }
    private var defaultNetwork: Network? = null
    private val defaultNetworks = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            main.post {
                if (defaultNetwork != network) {
                    defaultNetwork = network
                    Handset.instance.relay.networkChanged()
                }
            }
        }
        override fun onLost(network: Network) {
            main.post {
                if (defaultNetwork == network) {
                    defaultNetwork = null
                    Handset.instance.relay.networkChanged()
                }
            }
        }
    }
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mcp = Handset.instance.mcp
        if (intent?.action == ACTION_STOP) {
            mcp.switch(false)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification(mcp.addresses.value), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (!mcp.shouldRun()) { stopSelf(); return START_NOT_STICKY }
        server = if (mcp.on.value) server ?: McpHttpServer(McpServer.PORT, Handset.instance.mcpHandler()) else {
            server?.stop()
            mcp.addresses.value = emptyList()
            null
        }
        if (!mcp.shouldRun(localAvailable = listen())) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!watching) {
            val everyNetwork = NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val connectivity = getSystemService(ConnectivityManager::class.java)
            defaultNetwork = connectivity.activeNetwork
            connectivity.registerNetworkCallback(everyNetwork, networks)
            connectivity.registerDefaultNetworkCallback(defaultNetworks)
            watching = true
        }
        holdLocks()
        Handset.instance.relay.start()
        return START_STICKY
    }

    /** Listens on the addresses the user allows, and says where in the notification. False when loopback's port is taken. */
    private fun listen(): Boolean {
        val server = server ?: return true
        val mcp = Handset.instance.mcp
        val reachable = Addresses.list().filter { mcp.wifi.value || !it.wifi }
        val failed = server.listen(reachable.mapTo(hashSetOf(LOOPBACK)) { InetAddress.getByName(it.host) })
        if (LOOPBACK in failed) {
            Log.e(TAG, "couldn't listen on port ${McpServer.PORT}")
            mcp.problem.value = "Couldn't listen on port ${McpServer.PORT}. Another app may be using it."
            mcp.addresses.value = emptyList()
            return false
        }
        mcp.problem.value = null
        mcp.addresses.value = reachable.filter { InetAddress.getByName(it.host) !in failed }
        Log.i(TAG, "listening on ${mcp.addresses.value.joinToString { it.host }} and loopback, port ${McpServer.PORT}")
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(mcp.addresses.value))
        return true
    }

    override fun onDestroy() {
        if (watching) {
            val connectivity = getSystemService(ConnectivityManager::class.java)
            connectivity.unregisterNetworkCallback(networks)
            connectivity.unregisterNetworkCallback(defaultNetworks)
        }
        watching = false
        main.removeCallbacks(relisten)
        Handset.instance.relay.stop()
        server?.stop()
        server = null
        Handset.instance.mcp.addresses.value = emptyList()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    private fun holdLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "handset:mcp").apply { acquire() }
        }
        if (wifiLock == null) {
            wifiLock = getSystemService(WifiManager::class.java).createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "handset:mcp").apply { acquire() }
        }
    }

    private fun notification(addresses: List<Address>): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Agent server", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, McpService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val where = Handset.instance.relay.publicMcpUrl.value ?: addresses.joinToString(", ") { "${it.host}:${McpServer.PORT}" }.ifEmpty { "On this phone only, until Tailscale connects" }
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Handset is ready for agents")
            .setContentText(where)
            .setSmallIcon(R.drawable.ic_handset)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val TAG = "Handset/Mcp"
        const val CHANNEL = "mcp_server"
        const val NOTIFICATION_ID = 8765
        const val ACTION_STOP = "ai.tushar.handset.STOP_SERVER"
        const val NETWORK_SETTLE_MS = 1_000L
        val LOOPBACK: InetAddress = InetAddress.getByName("127.0.0.1")
    }
}

/** Starts the server after a reboot or an update, when the user left it on */
class McpStartReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) Handset.instance.mcp.resume()
    }
}
