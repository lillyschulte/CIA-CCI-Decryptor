package io.github.lillyschulte.decryptor

import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet

/** Progress of the running batch, shared between DecryptService and the UI. */
object JobState {
    @Volatile var running = false
    @Volatile var fileIndex = 0
    @Volatile var fileCount = 0
    @Volatile var fileName = ""
    @Volatile var stage = ""
    @Volatile var fileProgress = 0f
    @Volatile var overallProgress = 0f
    /** Result line shown once a batch finishes */
    @Volatile var summary = ""

    private const val MAX_LOG_LINES = 1000
    private val logLines = ArrayDeque<String>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var notifyPending = false

    fun reset() {
        fileIndex = 0
        fileCount = 0
        fileName = ""
        stage = ""
        fileProgress = 0f
        overallProgress = 0f
        summary = ""
        synchronized(logLines) { logLines.clear() }
        changed()
    }

    fun log(line: String) {
        Log.i("Decryptor", line)
        synchronized(logLines) {
            logLines.addLast(line)
            if (logLines.size > MAX_LOG_LINES) logLines.removeFirst()
        }
        changed()
    }

    fun logText(): String = synchronized(logLines) { logLines.joinToString("\n") }

    fun addListener(l: () -> Unit) = listeners.add(l)
    fun removeListener(l: () -> Unit) = listeners.remove(l)

    /** Notify listeners on the main thread, coalescing bursts of updates. */
    fun changed() {
        if (notifyPending) return
        notifyPending = true
        main.postDelayed({
            notifyPending = false
            listeners.forEach { it() }
        }, 100)
    }
}
