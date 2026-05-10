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
                score = score(capabilities) + if (network == activeNetwork) ACTIVE_NETWORK_BONUS else 0,
                isVpn = capabilities.isVpn()
            )
        }
        val preferredCandidates = candidates.filterNot { it.isVpn }.ifEmpty { candidates }
        val best = preferredCandidates.maxByOrNull { it.score }
            ?: return YoutubeNetworkBinding(null, "default network")
        return YoutubeNetworkBinding(best.network.socketFactory, label(best.capabilities))
    }

    private fun score(capabilities: NetworkCapabilities): Int =
        when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 300
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 250
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 240
            else -> 100
        } + if (capabilities.isVpn()) VPN_PENALTY else 0

    private fun label(capabilities: NetworkCapabilities): String =
        when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular internet (no VPN)"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi internet (no VPN)"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet internet"
            capabilities.isVpn() -> "VPN internet"
            else -> "validated internet"
        }

    private fun NetworkCapabilities.isVpn(): Boolean =
        hasTransport(NetworkCapabilities.TRANSPORT_VPN) ||
            !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)

    private data class Candidate(
        val network: Network,
        val capabilities: NetworkCapabilities,
        val score: Int,
        val isVpn: Boolean
    )

    private companion object {
        private const val ACTIVE_NETWORK_BONUS = 500
        private const val VPN_PENALTY = -1_000
    }
}
