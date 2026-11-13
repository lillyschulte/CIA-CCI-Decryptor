package io.github.lillyschulte.decryptor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import java.io.File
import kotlin.concurrent.thread

/** Runs a batch in the foreground so it survives the screen turning off or switching apps. */
class DecryptService : Service() {

    companion object {
        private const val EXTRA_FOLDER = "folder"
        private const val ACTION_CANCEL = "cancel"
        private const val CHANNEL = "progress"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context, folder: String) {
            context.startForegroundService(
                Intent(context, DecryptService::class.java).putExtra(EXTRA_FOLDER, folder)
            )
        }

        fun cancel(context: Context) {
            context.startService(Intent(context, DecryptService::class.java).setAction(ACTION_CANCEL))
        }
    }

    private var decryptor: Decryptor? = null
    private var lastNotify = 0L
    // For overall progress: bytes finished before the current file, current file size, total
    private var doneBytes = 0L
    private var currentBytes = 0L
    private var totalBytes = 1f

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            decryptor?.cancel()
            if (!JobState.running) stopSelf()
            return START_NOT_STICKY
        }
        val folder = intent?.getStringExtra(EXTRA_FOLDER)
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        if (JobState.running || folder == null) return START_NOT_STICKY

        JobState.reset()
        JobState.running = true
        JobState.changed()
        val wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Decryptor:batch")
        wakeLock.acquire(12 * 60 * 60 * 1000L)

        thread(name = "decrypt-batch") {
            try {
                runBatch(File(folder))
            } catch (e: Exception) {
                JobState.log("Error: ${e.message ?: e.javaClass.simpleName}")
                JobState.summary = "Stopped because of an error"
            } finally {
                JobState.running = false
                JobState.stage = ""
                JobState.changed()
                decryptor = null
                if (wakeLock.isHeld) wakeLock.release()
                stopForeground(STOP_FOREGROUND_REMOVE)
                showFinishedNotification()
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun runBatch(dir: File) {
        // Read once, so changing settings mid-batch doesn't mix behaviours
        val settings = Settings(this)
        val options = Decryptor.Options(
            gameCiaAsCci = settings.gameCiaAsCci,
            stripTitleId = settings.stripTitleId,
            updateDir = settings.updateDir(dir),
        )
        val deleteOriginals = settings.deleteOriginals
        val d = Decryptor(this, object : Decryptor.Listener {
            override fun log(line: String) = JobState.log(line)
            override fun stage(text: String) {
                JobState.stage = text
                JobState.changed()
                updateNotification(force = true)
            }
            override fun progress(fraction: Float) {
                JobState.fileProgress = fraction
                JobState.overallProgress = (doneBytes + fraction * currentBytes) / totalBytes
                JobState.changed()
                updateNotification()
            }
        }, options)
        decryptor = d

        JobState.log("Folder: ${dir.path}")
        options.updateDir?.let { JobState.log("Updates and DLC go to: ${it.path}") }
        if (deleteOriginals) JobState.log("Encrypted originals are deleted after decrypting")
        val roms = Decryptor.scan(dir)
        if (roms.isEmpty()) {
            JobState.summary = "No .3ds, .cci or .cia files found"
            JobState.log(JobState.summary)
            return
        }

        // Read headers first so we know what to do and how much work there is
        var skipped = 0
        // Output paths already taken by an earlier ROM in this batch (lowercase: shared storage
        // is case-insensitive), so two ROMs that map to the same name can't overwrite each other
        val claimed = HashMap<String, String>()
        val tasks = roms.mapNotNull { rom ->
            val info = try {
                RomInfo.read(rom)
            } catch (e: Exception) {
                JobState.log("Skipping ${rom.name}: ${e.message}")
                skipped++
                return@mapNotNull null
            }
            val out = d.outputFile(rom, info)
            val key = out.path.lowercase()
            val reason = when {
                !info.encrypted -> "already decrypted"
                info.format == RomFormat.CIA && info.kind == TitleKind.OTHER ->
                    "unsupported title type %016x".format(info.titleId)
                out.exists() -> "${out.name} already exists"
                key in claimed -> "${claimed[key]} is also saved as ${out.name}. Rename one of them and run again"
                else -> null
            }
            if (reason != null) {
                JobState.log("Skipping ${rom.name}: $reason")
                skipped++
                null
            } else {
                claimed[key] = rom.name
                Task(rom, info, out)
            }
        }

        totalBytes = tasks.sumOf { it.info.payloadSize }.coerceAtLeast(1).toFloat()
        doneBytes = 0L
        var ok = 0
        var failed = 0
        var deleted = 0
        JobState.fileCount = tasks.size

        for ((i, task) in tasks.withIndex()) {
            if (d.isCancelled) break
            val (rom, info, out) = task
            JobState.fileIndex = i + 1
            JobState.fileName = rom.name
            JobState.fileProgress = 0f
            JobState.log("")
            JobState.log("[${i + 1}/${tasks.size}] ${rom.name}")

            currentBytes = info.payloadSize
            val start = SystemClock.elapsedRealtime()
            val success = d.decrypt(rom, info)

            doneBytes += info.payloadSize
            JobState.fileProgress = 1f
            JobState.overallProgress = doneBytes / totalBytes
            val secs = (SystemClock.elapsedRealtime() - start) / 1000
            when {
                success -> {
                    ok++
                    JobState.log("Done in ${secs}s: ${out.name}")
                    if (deleteOriginals) {
                        if (rom.delete()) {
                            deleted++
                            JobState.log("Deleted ${rom.name}")
                        } else JobState.log("Could not delete ${rom.name}")
                    }
                }
                d.isCancelled -> JobState.log("Cancelled")
                else -> {
                    failed++
                    JobState.log("Failed: ${rom.name}")
                }
            }
            JobState.changed()
        }

        JobState.summary = buildString {
            append(if (d.isCancelled) "Cancelled. " else "Finished. ")
            append("$ok decrypted")
            if (failed > 0) append(", $failed failed")
            if (skipped > 0) append(", $skipped skipped")
            if (deleted > 0) append(", $deleted originals deleted")
        }
        JobState.log("")
        JobState.log(JobState.summary)
    }

    private data class Task(val rom: File, val info: RomInfo, val out: File)

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW)
        )
    }

    private fun openAppIntent() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification(): Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, DecryptService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE
        )
        val title = if (JobState.fileCount > 0)
            "${JobState.stage.ifEmpty { "Working" }} ${JobState.fileIndex}/${JobState.fileCount}"
        else getString(R.string.preparing)
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(JobState.fileName)
            .setProgress(1000, (JobState.overallProgress * 1000).toInt(), JobState.fileCount == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(Notification.Action.Builder(null, getString(R.string.cancel), cancel).build())
            .build()
    }

    private fun updateNotification(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotify < 1000) return
        lastNotify = now
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    private fun showFinishedNotification() {
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(JobState.summary)
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID + 1, n)
    }
}
