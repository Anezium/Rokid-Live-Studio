package com.anezium.rokidlive.phone

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import javax.net.SocketFactory

data class YoutubeNetworkBinding(
    val socketFactory: SocketFactory?,
    val label: String
)

class YoutubeNetworkSelector(context: Context) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    fun select(): YoutubeNetworkBinding {
        val manager = connectivityManager ?: return YoutubeNetworkBinding(null, "default network")
        val activeNetwork = manager.activeNetwork
        val candidates = manager.allNetworks.mapNotNull { network ->
            val capabilities = manager.getNetworkCapabilities(network) ?: return@mapNotNull null
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return@mapNotNull null
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return@mapNotNull null
            Candidate(
                network = network,
                capabilities = capabilities,
                score = score(capabilities) + if (network == activeNetwork) ACTIVE_NETWORK_BONUS else 0
            )
        }
        val best = candidates.maxByOrNull { it.score }
            ?: return YoutubeNetworkBinding(null, "default network")
        return YoutubeNetworkBinding(best.network.socketFactory, label(best.capabilities))
    }

    private fun score(capabilities: NetworkCapabilities): Int =
        when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 300
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 250
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 240
            else -> 100
        }

    private fun label(capabilities: NetworkCapabilities): String =
        when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular internet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi internet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet internet"
            else -> "validated internet"
        }

    private data class Candidate(
        val network: Network,
        val capabilities: NetworkCapabilities,
        val score: Int
    )

    private companion object {
        private const val ACTIVE_NETWORK_BONUS = 500
    }
}
