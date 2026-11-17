package kittoku.osc

import android.content.Context

/** Device-local hints for a server chosen from a catalog; never route a manual/imported profile to an old IP. */
internal class CatalogSelection(context: Context, private val now: () -> Long = System::currentTimeMillis) {
    // Volunteer DNS names can move to another address. Do not pin one list snapshot indefinitely.
    private val maxIpAgeMs = 24 * 60 * 60_000L
    private val prefs = context.getSharedPreferences("banafsh_ui", Context.MODE_PRIVATE)

    fun listFor(host: String): String? =
        if (host == prefs.getString("server_host", null)) prefs.getString("server_list", null) else null

    fun endpoint(host: String, protocol: Protocol): ServerEndpoint {
        val age = now() - prefs.getLong("server_ip_saved_at", 0)
        val useIp = age in 0L until maxIpAgeMs && listFor(host) == protocol.listKey
        return ServerEndpoint(host, if (useIp) prefs.getString("server_ip", null) else null)
    }

    fun save(endpoint: ServerEndpoint, country: String, protocol: Protocol, measuredPing: Int) {
        prefs.edit().putString("server_host", endpoint.host).putString("server_ip", endpoint.ip)
            .putString("server_country", country).putString("server_list", protocol.listKey)
            .putLong("server_ip_saved_at", now()).putInt("server_ping", measuredPing).apply()
    }

    fun clear() {
        prefs.edit().remove("server_host").remove("server_ip").remove("server_country")
            .remove("server_list").remove("server_ip_saved_at").remove("server_ping").apply()
    }
}
