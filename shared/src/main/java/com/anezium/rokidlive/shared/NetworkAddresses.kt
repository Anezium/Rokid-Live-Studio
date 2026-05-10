package com.anezium.rokidlive.shared

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkAddresses {
    fun firstIpv4Address(): String? = allIpv4Addresses()
        .asSequence()
        .sortedByDescending(::scoreAddress)
        .map { it.address }
        .firstOrNull()

    fun firstP2pIpv4Address(): String? = allIpv4Addresses()
        .asSequence()
        .filter { it.interfaceName.contains("p2p", ignoreCase = true) }
        .sortedByDescending(::scoreAddress)
        .map { it.address }
        .firstOrNull()

    fun allIpv4Addresses(): List<Ipv4InterfaceAddress> =
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { networkInterface ->
                networkInterface.inetAddresses
                    .toList()
                    .asSequence()
                    .filterIsInstance<Inet4Address>()
                    .filter { !it.isLoopbackAddress }
                    .mapNotNull { address ->
                        address.hostAddress?.let { host ->
                            Ipv4InterfaceAddress(networkInterface.name, host)
                        }
                    }
            }
            .distinct()
            .toList()

    fun describeIpv4Addresses(): String =
        allIpv4Addresses().joinToString(separator = ",") { "${it.interfaceName}=${it.address}" }
            .ifBlank { "no-ipv4" }

    private fun scoreAddress(address: Ipv4InterfaceAddress): Int {
        val name = address.interfaceName.lowercase()
        val host = address.address
        var score = 0
        when {
            name.contains("p2p") -> score += 100
            name.contains("wifi") -> score += 30
            name.startsWith("wlan") -> score += 20
            name.startsWith("rmnet") -> score -= 50
            name.startsWith("ccmni") -> score -= 50
        }
        when {
            host.startsWith("192.168.49.") -> score += 100
            host.startsWith("192.168.") -> score += 30
            host.startsWith("10.") -> score += 15
            host.startsWith("172.") -> score += 10
        }
        return score
    }
}

data class Ipv4InterfaceAddress(
    val interfaceName: String,
    val address: String
)
