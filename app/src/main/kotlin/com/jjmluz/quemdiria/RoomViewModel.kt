package com.jjmluz.quemdiria

import android.app.Application
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.util.Log
import android.view.KeyEvent
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.Inet4Address

private const val TAG = "QuemDiria"

/** Where the phones find the TV: the address to show, and the full link for the QR code. */
data class Invite(val host: String, val code: String) {
    val url: String get() = "http://$host/?c=$code"
}

/**
 * Hosts the room for as long as the game is open: the [Lobby], the [WebServer] the phones talk to, and the
 * TV's address on the home network, which can change while the app is open (Wi-Fi drops and comes back).
 */
class RoomViewModel(app: Application) : AndroidViewModel(app) {
    val lobby = Lobby()
    val room: StateFlow<Room> = lobby.room

    // Requests go to logcat (`adb logcat -s QuemDiria`), to see what a phone that can't load the page sent.
    private val server = WebServer(lobby, log = { Log.i(TAG, it) }) { name ->
        try {
            app.assets.open("web/$name").use { it.readBytes() }
        } catch (e: IOException) {
            null
        }
    }

    private val _invite = MutableStateFlow<Invite?>(null)
    /** Null while the TV has no network address, or the server couldn't start. */
    val invite: StateFlow<Invite?> = _invite.asStateFlow()

    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private var port = 0

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = updateInvite()
        override fun onLost(network: Network) = updateInvite()
    }

    init {
        // Binding a socket is quick, and the screen needs the port before it can show the QR code.
        port = try {
            server.start()
        } catch (e: IOException) {
            Log.e(TAG, "Can't start the web server", e)
            0
        }
        connectivity.registerDefaultNetworkCallback(networkCallback)
        updateInvite()
    }

    private fun updateInvite() {
        val props = connectivity.getLinkProperties(connectivity.activeNetwork)
        val ip = props?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
        _invite.value = if (ip != null && port != 0) Invite("${ip.hostAddress}:$port", server.code) else null
    }

    /** What the remote's keys do. Returns true for a key it used. */
    fun onKeyDown(keyCode: Int): Boolean {
        val phase = room.value.phase
        return when {
            keyCode in OK_KEYS && phase == Phase.LOBBY -> {
                lobby.start()
                true
            }
            keyCode == KeyEvent.KEYCODE_BACK && phase == Phase.STARTED -> {
                lobby.backToLobby()
                true
            }
            else -> false
        }
    }

    override fun onCleared() {
        connectivity.unregisterNetworkCallback(networkCallback)
        server.stop()
    }

    private companion object {
        val OK_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A,
        )
    }
}
