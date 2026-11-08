package com.clipsync.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.clipsync.app.service.SYNC_PORT
import java.io.IOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/** The PC answered, and the answer was "no" - retrying another address of the same PC won't change it. */
class PcRejectedException(val httpCode: Int, message: String) : Exception(message)

/** None of the PC's addresses could be reached; the message already lists the per-address reasons. */
class PcUnreachableException(message: String) : IOException(message)

/**
 * "Не удалось подключиться" with no reason is the worst possible failure
 * mode for a LAN app: the cause is almost never in the code, it's in the
 * network (firewall, VPN, wrong adapter, guest Wi-Fi), and each of those
 * needs a different fix. This turns the exception chain into a sentence that
 * tells the user which one to check. The raw exception is also logged under
 * the tag "ClipSync" (`adb logcat -s ClipSync`).
 */
object NetworkErrors {

    private const val HINT_TIMEOUT =
        "ПК не ответил (таймаут). Чаще всего: (1) брандмауэр Windows блокирует порт $SYNC_PORT - сеть должна " +
            "быть «Частной», а ClipSync разрешён для частных сетей; (2) телефон и ПК в разных сетях " +
            "(гостевая Wi-Fi, изоляция клиентов); (3) включён VPN на телефоне или на ПК."

    fun explain(e: Throwable): String {
        val chain = generateSequence(e) { t -> t.cause?.takeIf { it !== t } }.take(8).toList()

        chain.firstOrNull { it is PcRejectedException }?.let { return it.message ?: "ПК отклонил запрос" }
        chain.firstOrNull { it is PcUnreachableException }?.let { return it.message ?: "ПК недоступен" }
        chain.firstOrNull { it is InvalidQrException }?.let { return it.message ?: "Неверный QR-код" }

        if (chain.any { it is PinMismatchException }) {
            return "Сертификат ПК не совпал с тем, что был в QR: на этом адресе отвечает другое устройство " +
                "или ПК пересоздал сертификат. Откройте на ПК «Добавить устройство» и отсканируйте новый QR."
        }

        val timeout = chain.firstOrNull { it is SocketTimeoutException }
        if (timeout != null) {
            val msg = timeout.message.orEmpty()
            return if (msg.contains("connect", ignoreCase = true)) {
                HINT_TIMEOUT
            } else {
                "Соединение с ПК установлено, но он не ответил вовремя. Возможно, ClipSync на ПК завис " +
                    "или на этом порту отвечает не ClipSync."
            }
        }

        val connect = chain.firstOrNull { it is ConnectException || it is NoRouteToHostException }
        if (connect != null) {
            val msg = connect.message.orEmpty()
            return when {
                msg.contains("ECONNREFUSED", true) || msg.contains("refused", true) ->
                    "ПК отказал в соединении: ClipSync на ПК не запущен, его сервер не стартовал " +
                        "(порт $SYNC_PORT занят?) или адрес принадлежит другому устройству."
                msg.contains("ENETUNREACH", true) || msg.contains("unreachable", true) || connect is NoRouteToHostException ->
                    "Нет маршрута до ПК: телефон в другой сети, либо трафик уходит в VPN. " +
                        "Подключите телефон к той же Wi-Fi, что и ПК, и отключите VPN."
                else -> "Не удаётся подключиться к ПК: $msg"
            }
        }

        if (chain.any { it is UnknownHostException }) return "Неверный адрес ПК"

        val ssl = chain.firstOrNull { it is SSLException }
        if (ssl != null) {
            val msg = ssl.message.orEmpty()
            return when {
                msg.contains("WRONG_VERSION_NUMBER", true) || msg.contains("does not look like a TLS", true) ->
                    "По этому адресу отвечает не HTTPS-сервер ClipSync (возможно, на ПК старая версия " +
                        "без TLS). Обновите приложение на ПК."
                else -> "Ошибка защищённого соединения с ПК: $msg"
            }
        }

        return e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }

    data class LocalAddress(val kind: String, val ip: String)

    /**
     * This phone's own IPv4 addresses, labelled by network type. Shown on the
     * diagnostics screen because the single most useful fact when the PC
     * "can't see" the phone is whether the phone and the PC are even on the
     * same subnet - and whether a VPN is capturing traffic.
     */
    fun localAddresses(context: Context): List<LocalAddress> {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return emptyList()
        val result = ArrayList<LocalAddress>()
        @Suppress("DEPRECATION")
        val networks = cm.allNetworks
        for (network in networks) {
            val caps = cm.getNetworkCapabilities(network)
            val kind = when {
                caps == null -> "сеть"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "моб. сеть"
                else -> "сеть"
            }
            val props = cm.getLinkProperties(network) ?: continue
            for (linkAddress in props.linkAddresses) {
                val a = linkAddress.address
                if (a is Inet4Address && !a.isLoopbackAddress && !a.isLinkLocalAddress) {
                    a.hostAddress?.let { result.add(LocalAddress(kind, it)) }
                }
            }
        }
        return result
    }
}
