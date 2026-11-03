package kittoku.osc

import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale


/**
 * A small real download through the tunnel that is up right now.
 * It reads at most [maxBytes] (the connection is cut after that), so a whole auto-pick run stays around 3 MB.
 */
internal object SpeedProbe {
    private class Source(val url: String, val needsRange: Boolean)

    private val sources = listOf(
        Source("https://speed.cloudflare.com/__down?bytes=%d", false),
        Source("https://speedtest.tele2.net/1MB.zip", true),
    )

    /** Megabits per second, or -1.0 when nothing useful could be downloaded. */
    fun measure(maxBytes: Int, maxMillis: Long, cancelled: () -> Boolean): Double {
        for (source in sources) {
            if (cancelled()) return -1.0

            val result = try {
                download(String.format(Locale.US, source.url, maxBytes), source.needsRange, maxBytes, maxMillis, cancelled)
            } catch (_: Exception) {
                -1.0
            }

            if (result > 0.0) return result
        }

        return -1.0
    }

    private fun download(
        url: String,
        needsRange: Boolean,
        maxBytes: Int,
        maxMillis: Long,
        cancelled: () -> Boolean
    ): Double {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 6000
        connection.readTimeout = 4000
        connection.useCaches = false
        connection.setRequestProperty("Accept-Encoding", "identity")

        if (needsRange) {
            connection.setRequestProperty("Range", "bytes=0-${maxBytes - 1}")
        }

        try {
            val stream = connection.inputStream
            val buffer = ByteArray(16 * 1024)
            var total = 0L
            var firstByteAt = 0L
            var now = 0L

            while (true) {
                if (cancelled()) return -1.0

                val read = stream.read(buffer)
                if (read < 0) break

                now = SystemClock.elapsedRealtime()
                if (firstByteAt == 0L) firstByteAt = now

                total += read

                if (total >= maxBytes || now - firstByteAt >= maxMillis) break
            }

            // Too little data says nothing about the speed.
            if (total < 20_000L || firstByteAt == 0L) return -1.0

            val seconds = (now - firstByteAt).coerceAtLeast(300L) / 1000.0
            return total * 8.0 / seconds / 1_000_000.0
        } finally {
            connection.disconnect()
        }
    }
}
