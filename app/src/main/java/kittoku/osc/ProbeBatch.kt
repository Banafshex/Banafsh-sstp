package kittoku.osc

import java.io.Closeable
import java.io.InterruptedIOException
import java.util.concurrent.Executors

/** One open country's tests. Leaving, selecting a row, or reaching the deadline drops queued work
 * and closes in-flight sockets (interrupt alone does not unblock socket reads on Android). */
internal class ProbeBatch : Closeable {
    private val workers = Executors.newFixedThreadPool(16)
    private val sockets = mutableSetOf<Closeable>()
    private var closed = false

    fun execute(test: () -> Unit) = workers.execute(test)

    fun <S : Closeable, T> withSocket(socket: S, block: (S) -> T): T = socket.use {
        synchronized(sockets) {
            if (closed) throw InterruptedIOException("Test cancelled")
            sockets.add(socket)
        }
        try {
            block(socket)
        } finally {
            synchronized(sockets) { sockets.remove(socket) }
        }
    }

    override fun close() {
        val active = synchronized(sockets) {
            closed = true
            sockets.toList().also { sockets.clear() }
        }
        workers.shutdownNow()
        active.forEach { runCatching { it.close() } }
    }
}
