package com.sales.android.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

object ConnectivityMonitor {

    private const val TAG = "ConnectivityMonitor"
    private const val DNS_SERVER = "8.8.8.8"
    private const val DNS_PORT = 53
    private const val TIMEOUT_MS = 1500

    /**
     * Verifica si el dispositivo tiene conexion fisica (Wi-Fi, Datos Moviles, Ethernet).
     */
    fun isNetworkConnected(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * Realiza una comprobacion logica real abriendo un socket TCP al DNS de Google (8.8.8.8)
     * en el puerto 53 para descartar falsos positivos de red (ej. portales cautivos de hoteles).
     */
    suspend fun hasInternetAccess(): Boolean = withContext(Dispatchers.IO) {
        if (Thread.currentThread().isInterrupted) return@withContext false
        
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(DNS_SERVER, DNS_PORT), TIMEOUT_MS)
                Log.d(TAG, "Conexión TCP real con internet exitosa.")
                true
            }
        } catch (e: IOException) {
            Log.w(TAG, "Falso positivo detectado: Conectado a red pero sin acceso real a internet: ${e.message}")
            false
        }
    }
}
