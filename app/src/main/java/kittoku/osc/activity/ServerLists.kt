package kittoku.osc.activity

import kittoku.osc.Protocol
import org.json.JSONArray
import java.net.HttpURLConnection
import java.net.URL

// Each service has its own list file. SSTP: the full list first; the short list that older
// versions use stays as a fallback (for example before the first refresh that creates servers_v2.json).
// The same files come from several places in turn: one blocked host must not break the page.
// raw.githubusercontent.com is a usual casualty of filtering in Iran while the browser may still open
// it (its own DNS over HTTPS and HTTP/3 take another path than this app's plain TLS).
internal fun listUrls(protocol: Protocol): List<String> {
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
internal fun httpGetListFile(address: String): String? {
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

/** The hosts of the first list of this service that loads, or null when none does. Blocks: call off the main thread. */
internal fun fetchServerHosts(protocol: Protocol): Set<String>? {
    for (url in listUrls(protocol)) {
        val text = httpGetListFile(url) ?: continue

        try {
            val array = JSONArray(text)
            val hosts = HashSet<String>()

            for (i in 0 until array.length()) {
                val host = array.optJSONObject(i)?.optString("host").orEmpty()
                if (host.isNotEmpty()) hosts.add(host)
            }

            if (hosts.isNotEmpty()) return hosts
        } catch (_: Exception) {
        }
    }

    return null
}
