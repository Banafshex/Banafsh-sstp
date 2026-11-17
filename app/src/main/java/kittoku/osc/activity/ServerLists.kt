package kittoku.osc.activity

import android.content.Context
import kittoku.osc.Protocol
import kittoku.osc.R
import kittoku.osc.ServerEndpoint
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.Future

// Each service has its own list file. SSTP: the full list first; the short list that older
// versions use stays as a fallback (for example before the first refresh that creates servers_v2.json).
// The same files come from several places in turn: one blocked host must not break the page.
// raw.githubusercontent.com is a usual casualty of filtering in Iran while the browser may still open
// it (its own DNS over HTTPS and HTTP/3 take another path than this app's plain TLS).
private fun listUrls(protocol: Protocol): List<String> {
    val paths = when (protocol) {
        Protocol.L2TP -> listOf("servers_l2tp.json")
        Protocol.SSTP -> listOf("servers_v2.json", "servers.json")
    }

    val mirrors = listOf(
        "https://raw.githubusercontent.com/Banafshex/Banafsh-sstp/main/",
        "https://cdn.jsdelivr.net/gh/Banafshex/Banafsh-sstp@main/",
        "https://testingcf.jsdelivr.net/gh/Banafshex/Banafsh-sstp@main/",
        "https://api.github.com/repos/Banafshex/Banafsh-sstp/contents/",
    )

    return paths.flatMap { path -> mirrors.map { it + path } }
}

/** One hardened GET for the list files: the raw file, or null on any failure. Blocks: call off the main thread. */
private fun httpGetListFile(address: String): String? {
    var connection: HttpURLConnection? = null

    return try {
        connection = (URL(address).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 10000
            setRequestProperty("User-Agent", "Banafsh-sstp")

            // The GitHub API hands out the raw file only when it is asked for it.
            if (address.contains("api.github.com")) {
                setRequestProperty("Accept", "application/vnd.github.raw")
            }
        }

        if (connection.responseCode != 200) {
            null
        } else {
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        }
    } catch (e: Exception) {
        null
    } finally {
        connection?.disconnect()
    }
}

internal data class FreeServer(
    val endpoint: ServerEndpoint,
    val port: Int,
    val country: String,
    val countryCode: String,
    val speedMbps: Double,
    val sessions: Int,
)

/** The last list (or APK seed) is immediately usable, even if every mirror is unreachable. */
internal class ServerCatalog(context: Context, private val protocol: Protocol) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("banafsh_ui", Context.MODE_PRIVATE)
    private val key = "servers_cache_${protocol.listKey}"
    private val timeKey = "${key}_updated"

    data class Local(val servers: List<FreeServer>, val fresh: Boolean)

    fun local(): Local {
        val cached = parse(prefs.getString(key, null))
        if (cached.isNotEmpty()) {
            val age = System.currentTimeMillis() - prefs.getLong(timeKey, 0)
            return Local(cached, age in 0L until 5 * 60_000L)
        }
        val raw = if (protocol == Protocol.L2TP) R.raw.servers_l2tp else R.raw.servers
        val bundled = app.resources.openRawResource(raw).bufferedReader(Charsets.UTF_8).use { it.readText() }
        return Local(parse(bundled), false)
    }

    companion object {
        private val downloads = Executors.newFixedThreadPool(2)
        private val inFlight = mutableMapOf<Protocol, Future<List<FreeServer>>>()
    }

    // A quick OK must not keep cancelling the refresh at mirror #1, leaving only an old APK seed.
    // The download holds only application context. Pickers may stop waiting or join it on reopen.
    fun refreshAsync(): Future<List<FreeServer>> = synchronized(inFlight) {
        inFlight[protocol]?.takeUnless { it.isDone } ?: downloads.submit<List<FreeServer>> { refresh() }
            .also { inFlight[protocol] = it }
    }

    private fun refresh(): List<FreeServer> {
        for (url in listUrls(protocol)) {
            if (Thread.currentThread().isInterrupted) break
            val text = httpGetListFile(url) ?: continue
            val parsed = cacheResponse(text)
            if (parsed.isNotEmpty()) return parsed
        }
        return emptyList()
    }

    fun cacheResponse(text: String): List<FreeServer> = parse(text).also {
        if (it.isNotEmpty()) prefs.edit().putString(key, text).putLong(timeKey, System.currentTimeMillis()).apply()
    }

    private fun parse(text: String?): List<FreeServer> {
        if (text.isNullOrBlank()) return emptyList()
        val array = try { JSONArray(text) } catch (_: org.json.JSONException) { return emptyList() }
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val host = obj.optString("host").trim()
                val port = obj.optInt("port", 0)
                val code = obj.optString("country")
                if (host.isEmpty() || code == "RU" || (protocol == Protocol.SSTP && port !in 1..65535)) continue
                add(FreeServer(
                    ServerEndpoint(host, obj.optString("ip")), port, obj.optString("country_name"), code,
                    obj.optDouble("speed_mbps", 0.0), obj.optInt("sessions", 0)
                ))
            }
        }
    }
}
