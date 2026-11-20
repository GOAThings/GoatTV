package cc.slokker.itv.ui.player.goatcore

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Se a ligação ativa gasta dados móveis: dados do operador (TRANSPORT_CELLULAR) ou qualquer rede marcada como com
 * limite (sem NET_CAPABILITY_NOT_METERED, ex. uma Wi-Fi de telemóvel). Wi-Fi e Ethernet normais são "sem limite". Serve
 * a definição mobile_data_quality: o HLS poupa dados enquanto [metered] for true e volta ao normal na Wi-Fi.
 */
class MeteredNetwork(context: Context) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val _metered = MutableStateFlow(false)
    val metered: StateFlow<Boolean> = _metered.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()

        override fun onLost(network: Network) = refresh()

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
    }

    /** Começa a ouvir (uma vez); sem a permissão de ver a rede, fica como "sem limite". */
    fun start() {
        val cm = cm ?: return
        runCatching {
            cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback)
        }
        refresh()
    }

    private fun refresh() {
        val cm = cm ?: return
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        // Sem rede: fica o que estava (o canal nem está a dar).
        if (caps == null) return
        _metered.value = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }
}
