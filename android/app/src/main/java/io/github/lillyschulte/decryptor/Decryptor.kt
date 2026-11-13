package io.github.lillyschulte.decryptor

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Port of decrypt.sh for a single ROM: run ctrdecrypt to produce decrypted .ncch
 * partitions next to the ROM, then makerom to rebuild them into a CCI/CIA.
 */
class Decryptor(context: Context, private val listener: Listener) {

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

    fun outputFile(rom: File, info: RomInfo): File {
        val stem = rom.nameWithoutExtension
        val name = when {
            info.format == RomFormat.CCI -> "$stem-decrypted.cci"
            info.kind == TitleKind.UPDATE -> "$stem (Update)-decrypted.cia"
            info.kind == TitleKind.DLC -> "$stem (DLC)-decrypted.cia"
            else -> "$stem-decrypted.cia"
        }
        return File(rom.absoluteFile.parentFile, name)
    }

    /** Returns true if the decrypted output was written. */
    fun decrypt(rom: File, info: RomInfo): Boolean {
        val dir = rom.absoluteFile.parentFile!!
        val out = outputFile(rom, info)
        // ctrdecrypt names its output <stem>.<partition>.<content id as 8 hex digits>.ncch
        val pattern = Regex("^" + Regex.escape(rom.nameWithoutExtension) + "\\.([^.]+)\\.([0-9A-Fa-f]{8})\\.ncch$")
        fun parts() = dir.listFiles { f -> pattern.matches(f.name) }?.sortedBy { it.name }.orEmpty()
        val payload = info.payloadSize.coerceAtLeast(1).toFloat()

        parts().forEach { it.delete() }
        var success = false
        try {
            listener.stage("Decrypting")
            val decrypted = run(listOf(ctrdecrypt.path, rom.absolutePath, "--no-verbose")) {
                parts().sumOf { it.length() } / payload * 0.5f
            }
            if (isCancelled) return false
            val ncch = parts()
            if (!decrypted || ncch.isEmpty()) {
                listener.log("ctrdecrypt did not produce any decrypted partitions")
                return false
            }

            val args = mutableListOf(makerom.path, "-f", if (info.format == RomFormat.CCI) "cci" else "cia")
            if (info.format == RomFormat.CIA && info.kind == TitleKind.DLC) args += "-dlc"
            args += listOf("-ignoresign", "-target", "p", "-o", out.absolutePath)
            for (p in ncch) {
                val (part, hexId) = pattern.matchEntire(p.name)!!.destructured
                val contentId = hexId.toLong(16)
                val arg = when {
                    // CCI: partition index is the hex field
                    info.format == RomFormat.CCI -> "$contentId:$contentId"
                    // Game CIA: index:index, like the desktop script
                    info.kind == TitleKind.GAME -> "$part:$part"
                    // Update/DLC: index:content id
                    else -> "$part:$contentId"
                }
                args += listOf("-i", "${p.absolutePath}:$arg")
            }
            if (info.format == RomFormat.CIA) args += listOf("-ver", info.version.toString())

            listener.stage(if (info.format == RomFormat.CCI) "Building CCI" else "Building CIA")
            out.delete()
            val built = run(args) { 0.5f + out.length() / payload * 0.5f }
            success = built && out.isFile && out.length() > 0
            if (built && !success) listener.log("makerom did not write ${out.name}")
            return success
        } finally {
            parts().forEach { it.delete() }
            if (!success) out.delete()
        }
    }

    /** Runs a tool, streaming its output to the log and polling [progress] while it runs. */
    private fun run(cmd: List<String>, progress: () -> Float): Boolean {
        if (isCancelled) return false
        val process = ProcessBuilder(cmd).directory(workDir).redirectErrorStream(true).start()
        current = process
        val reader = thread(name = "tool-output") {
            process.inputStream.bufferedReader().forEachLine { line ->
                // makerom always warns that it can't sign without Nintendo's keys; that's expected
                if (line.isNotBlank() && "Failed to sign header" !in line) listener.log("  $line")
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
