package com.uninorte.locator.obd

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.SystemClock
import java.net.InetAddress

// Nunca se cambia la red del proceso: cada socket se ata a su transporte.
class RedesObd(private val contexto: Context, private val ssid: String, private val clave: String) {
    private val gestor = contexto.getSystemService(ConnectivityManager::class.java)
    @Volatile var wifi: Network? = null
        private set
    @Volatile private var ultimaWifiObd: Network? = null
    @Volatile private var celular: Network? = null
    @Volatile var errorWifi = false
        private set
    @Volatile private var cerrado = false
    @Volatile private var proximoIntentoWifi = 0L
    private var callbackWifi: ConnectivityManager.NetworkCallback? = null
    private var callbackCelular: ConnectivityManager.NetworkCallback? = null

    @Suppress("DEPRECATION")
    @Synchronized fun solicitarWifi() {
        if (cerrado || callbackWifi != null || SystemClock.elapsedRealtime() < proximoIntentoWifi) return
        errorWifi = false
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { aceptarWifi(network) }
            override fun onCapabilitiesChanged(network: Network, capacidades: NetworkCapabilities) {
                aceptarWifi(network)
            }
            override fun onLost(network: Network) {
                if (wifi == network) {
                    wifi = null
                    errorWifi = true
                    if (Build.VERSION.SDK_INT >= 29) liberarWifi()
                }
            }
            override fun onUnavailable() {
                errorWifi = true
                proximoIntentoWifi = SystemClock.elapsedRealtime() + 5_000
                liberarWifi()
            }
        }
        val peticion = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (Build.VERSION.SDK_INT >= 29) {
            val especificador = WifiNetworkSpecifier.Builder().setSsid(ssid)
            if (clave.isNotEmpty()) especificador.setWpa2Passphrase(clave)
            peticion.setNetworkSpecifier(especificador.build())
        }
        callbackWifi = callback
        try {
            if (Build.VERSION.SDK_INT >= 29) gestor.requestNetwork(peticion.build(), callback)
            else {
                gestor.registerNetworkCallback(peticion.build(), callback)
                gestor.allNetworks.forEach { aceptarWifi(it) }
            }
        } catch (e: Exception) {
            liberarWifi()
            errorWifi = true
            proximoIntentoWifi = SystemClock.elapsedRealtime() + 5_000
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun aceptarWifi(network: Network) {
        if (cerrado) return
        val capacidades = gestor.getNetworkCapabilities(network) ?: return
        if (!capacidades.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return
        // En 24–28 solo se admite la red elegida manualmente, nunca cualquier Wi-Fi.
        if (Build.VERSION.SDK_INT < 29) {
            val nombre = contexto.applicationContext.getSystemService(WifiManager::class.java)
                .connectionInfo.ssid.trim('"')
            if (nombre != ssid) { if (wifi == network) wifi = null; return }
        }
        ultimaWifiObd = network
        wifi = network
        errorWifi = false
    }

    @Synchronized fun solicitarCelular() {
        if (cerrado || callbackCelular != null) return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { if (!cerrado) celular = network }
            override fun onLost(network: Network) { if (celular == network) celular = null }
            override fun onUnavailable() { liberarCelular() }
        }
        callbackCelular = callback
        try {
            gestor.requestNetwork(NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), callback)
        } catch (_: Exception) { liberarCelular() }
    }

    fun redEnvio(): Network? {
        celular?.let { return it }
        val defecto = gestor.activeNetwork ?: return null
        val capacidades = gestor.getNetworkCapabilities(defecto) ?: return null
        // Rechazar también Wi-Fi no validada: evita una carrera antes de onAvailable del OBD.
        return defecto.takeIf { it != wifi && it != ultimaWifiObd &&
            capacidades.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capacidades.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
    }

    fun tieneCelular() = celular != null

    @Suppress("DEPRECATION")
    fun gateway(network: Network): String? {
        gestor.getLinkProperties(network)?.routes?.firstOrNull {
            it.isDefaultRoute && it.gateway?.address?.size == 4 && !it.gateway!!.isAnyLocalAddress
        }?.gateway?.hostAddress?.let { return it }
        val dhcp = contexto.applicationContext.getSystemService(WifiManager::class.java).dhcpInfo ?: return null
        val direccion = dhcp.gateway.takeIf { it != 0 } ?: dhcp.serverAddress.takeIf { it != 0 } ?: return null
        return InetAddress.getByAddress(ByteArray(4) { (direccion ushr (8 * it)).toByte() }).hostAddress
    }

    @Synchronized private fun liberarWifi() {
        callbackWifi?.let { runCatching { gestor.unregisterNetworkCallback(it) } }
        callbackWifi = null
        wifi = null
    }
    @Synchronized private fun liberarCelular() {
        callbackCelular?.let { runCatching { gestor.unregisterNetworkCallback(it) } }
        callbackCelular = null
        celular = null
    }
    @Synchronized fun cerrar() { cerrado = true; liberarWifi(); liberarCelular() }
}
