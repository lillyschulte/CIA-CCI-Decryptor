package io.github.lillyschulte.decryptor

import android.content.Context
import android.os.SystemClock
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Port of decrypt.sh for a single ROM: run ctrdecrypt to produce decrypted .ncch
 * partitions next to the ROM, then makerom to rebuild them into a CCI/CIA.
 */
class Decryptor(context: Context, private val listener: Listener, private val options: Options = Options()) {

    data class Options(
        /** Rebuild game CIAs as CCI, since Azahar's game list only shows CCI/3DS files */
        val gameCiaAsCci: Boolean = true,
        /** Write decrypted updates here instead of next to the original (null = next to it) */
        val updateDir: File? = null,
    )

    interface Listener {
        fun log(line: String)
        fun stage(text: String)
        /** Progress of the current file, 0..1 */
        fun progress(fraction: Float)
    }

    private val binDir = File(context.applicationInfo.nativeLibraryDir)
    private val ctrdecrypt = File(binDir, "libctrdecrypt.so")
    private val makerom = File(binDir, "libmakerom.so")
    /** Working directory for the tools; ctrdecrypt looks for seeddb.bin here */
    private val workDir = context.filesDir

    @Volatile var isCancelled = false
        private set
    @Volatile private var current: Process? = null

    init {
        // Small file; refresh it every run so app updates ship new seeds
        context.assets.open("seeddb.bin").use { input ->
            File(workDir, "seeddb.bin").outputStream().use { input.copyTo(it) }
        }
    }

    fun cancel() {
        isCancelled = true
        current?.destroy()
    }

    /** Whether the output is a CCI. Updates and DLC stay CIA: Azahar can only install those. */
    fun buildsCci(info: RomInfo) =
        info.format == RomFormat.CCI || (options.gameCiaAsCci && info.kind == TitleKind.GAME)

    fun outputFile(rom: File, info: RomInfo): File {
        val stem = rom.nameWithoutExtension
        val name = when {
            buildsCci(info) -> "$stem-decrypted.cci"
            info.kind == TitleKind.UPDATE -> "$stem (Update)-decrypted.cia"
            info.kind == TitleKind.DLC -> "$stem (DLC)-decrypted.cia"
            else -> "$stem-decrypted.cia"
        }
        val dir = if (info.format == RomFormat.CIA && info.kind == TitleKind.UPDATE && options.updateDir != null)
            options.updateDir
        else rom.absoluteFile.parentFile
        return File(dir, name)
    }

    /** Returns true if the decrypted output was written. */
    fun decrypt(rom: File, info: RomInfo): Boolean {
        val dir = rom.absoluteFile.parentFile!!
        val out = outputFile(rom, info)
        // makerom writes here; renamed to `out` only on success so a killed run never
        // leaves a truncated "-decrypted" file that later runs would skip
        val partial = File(out.parentFile, out.name + ".part")
        // ctrdecrypt names its output <stem>.<partition>.<content id as 8 hex digits>.ncch
        val pattern = Regex("^" + Regex.escape(rom.nameWithoutExtension) + "\\.([^.]+)\\.([0-9A-Fa-f]{8})\\.ncch$")
        fun parts() = dir.listFiles { f -> pattern.matches(f.name) }?.sortedBy { it.name }.orEmpty()
        val payload = info.payloadSize.coerceAtLeast(1).toFloat()

        // Clean up leftovers from an earlier run that was killed
        parts().forEach { it.delete() }
        partial.delete()
        var success = false
        try {
            if (!out.parentFile!!.isDirectory && !out.parentFile!!.mkdirs()) {
                listener.log("Could not create folder ${out.parentFile!!.path}")
                return false
            }
            listener.stage("Decrypting")
            val decryptStart = SystemClock.elapsedRealtime()
            val decrypted = run(listOf(ctrdecrypt.path, rom.absolutePath, "--no-verbose")) {
                parts().sumOf { it.length() } / payload * 0.5f
            }
            if (isCancelled) return false
            val ncch = parts()
            if (!decrypted || ncch.isEmpty()) {
                listener.log("ctrdecrypt did not produce any decrypted partitions")
                return false
            }

            val cci = buildsCci(info)
            val args = mutableListOf(makerom.path, "-f", if (cci) "cci" else "cia")
            if (!cci && info.kind == TitleKind.DLC) args += "-dlc"
            args += listOf("-ignoresign", "-target", "p", "-o", partial.absolutePath)
            for (p in ncch) {
                val (part, hexId) = pattern.matchEntire(p.name)!!.destructured
                val contentId = hexId.toLong(16)
                val arg = when {
                    // CCI: partition index is the hex field
                    info.format == RomFormat.CCI -> "$contentId:$contentId"
                    // Game CIA, rebuilt as CIA or CCI: content index:content index. For a CCI the
                    // content index is the partition (0 game, 1 manual, 2 download play child)
                    info.kind == TitleKind.GAME -> "$part:$part"
                    // Update/DLC: index:content id
                    else -> "$part:$contentId"
                }
                args += listOf("-i", "${p.absolutePath}:$arg")
            }
            if (!cci) args += listOf("-ver", info.version.toString())

            listener.stage(if (cci) "Building CCI" else "Building CIA")
            // makerom builds the whole file in memory and writes it at the end, so the output size
            // can't show progress. Estimate from time instead: on the Thor, building takes ~1.25x
            // as long as decrypting the same ROM.
            val buildStart = SystemClock.elapsedRealtime()
            val expectedBuildMs = ((buildStart - decryptStart) * 1.25f).coerceAtLeast(1000f)
            val built = run(args) {
                val estimate = (SystemClock.elapsedRealtime() - buildStart) / expectedBuildMs
                0.5f + 0.5f * estimate.coerceAtMost(0.95f)
            }
            if (!built) return false
            if (!partial.isFile || partial.length() == 0L) {
                listener.log("makerom did not write ${out.name}")
                return false
            }
            out.delete()
            success = partial.renameTo(out)
            if (!success) listener.log("Could not rename ${partial.name} to ${out.name}")
            return success
        } finally {
            parts().forEach { it.delete() }
            partial.delete()
        }
    }

    /** Runs a tool, streaming its output to the log and polling [progress] while it runs. */
    private fun run(cmd: List<String>, progress: () -> Float): Boolean {
        if (isCancelled) return false
        val process = ProcessBuilder(cmd).directory(workDir).redirectErrorStream(true).start()
        current = process
        val reader = thread(name = "tool-output") {
            try {
                process.inputStream.bufferedReader().forEachLine { line ->
                    // Skip expected noise: makerom can't sign without Nintendo's keys, and
                    // ctrdecrypt prints the path of every temporary .ncch it writes
                    if (line.isNotBlank() && "Failed to sign header" !in line && !line.endsWith(".ncch")) {
                        listener.log("  $line")
                    }
                }
            } catch (_: java.io.IOException) {
                // Cancelling destroys the process, which closes this stream mid-read
            }
        }
        while (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
            if (isCancelled) process.destroy()
            listener.progress(progress().coerceIn(0f, 0.99f))
        }
        reader.join(1000)
        current = null
        val code = process.exitValue()
        if (code != 0 && !isCancelled) listener.log("${File(cmd[0]).name.removePrefix("lib").removeSuffix(".so")} exited with code $code")
        return code == 0 && !isCancelled
    }

    companion object {
        private val EXTENSIONS = setOf("3ds", "cci", "cia")

        /** ROMs in [dir] (not recursive), skipping our own *-decrypted outputs */
        fun scan(dir: File): List<File> =
            dir.listFiles()
                ?.filter {
                    it.isFile && it.extension.lowercase() in EXTENSIONS &&
                        !it.nameWithoutExtension.contains("decrypted", ignoreCase = true)
                }
                ?.sortedBy { it.name.lowercase() }
                .orEmpty()
    }
}
