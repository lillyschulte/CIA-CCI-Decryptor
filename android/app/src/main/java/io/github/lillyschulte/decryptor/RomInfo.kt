package io.github.lillyschulte.decryptor

import java.io.File
import java.io.RandomAccessFile

enum class RomFormat { CCI, CIA }

enum class TitleKind { GAME, UPDATE, DLC, OTHER }

/**
 * Header info needed to decide how to rebuild a ROM. Replaces what the desktop
 * script read from ctrtool's text output.
 */
data class RomInfo(
    val format: RomFormat,
    val titleId: Long,
    /** TMD title version (CIA only) */
    val version: Int,
    val encrypted: Boolean,
    /** Bytes of actual content (NCSD partitions / CIA contents), used for progress */
    val payloadSize: Long,
) {
    val kind: TitleKind
        get() = when ((titleId ushr 32).toInt()) {
            0x00040000, 0x00040002 -> TitleKind.GAME // application, demo
            0x0004000E -> TitleKind.UPDATE
            0x0004008C -> TitleKind.DLC
            else -> TitleKind.OTHER
        }

    companion object {
        private const val MEDIA_UNIT = 0x200L

        fun read(file: File): RomInfo = RandomAccessFile(file, "r").use { f ->
            if (f.ascii(0x100, 4) == "NCSD") readCci(f)
            else if (file.extension.equals("cia", ignoreCase = true)) readCia(f)
            else throw IllegalArgumentException("not a CCI/3DS or CIA file")
        }

        private fun readCci(f: RandomAccessFile): RomInfo {
            val hdr = f.bytes(0, 0x200)
            var payload = 0L
            var firstPartition = -1L
            for (i in 0 until 8) {
                val off = hdr.le32(0x120 + i * 8) * MEDIA_UNIT
                val size = hdr.le32(0x124 + i * 8) * MEDIA_UNIT
                if (size == 0L) continue
                if (firstPartition < 0) firstPartition = off
                payload += size
            }
            require(firstPartition >= 0) { "CCI has no partitions" }
            return RomInfo(
                format = RomFormat.CCI,
                titleId = hdr.le64(0x108),
                version = 0,
                encrypted = ncchEncrypted(f, firstPartition),
                payloadSize = payload,
            )
        }

        private fun readCia(f: RandomAccessFile): RomInfo {
            val h = f.bytes(0, 0x20)
            val tmdOff = align(h.le32(0x0)) + align(h.le32(0x8)) + align(h.le32(0xC))
            val contentOff = tmdOff + align(h.le32(0x10))
            val contentSize = h.le64(0x18)

            val sigLen = when (f.bytes(tmdOff, 4).be32(0)) {
                0x10000L, 0x10003L -> 0x200 + 0x3C // RSA-4096 (SHA-1 / SHA-256)
                0x10001L, 0x10004L -> 0x100 + 0x3C // RSA-2048 (SHA-1 / SHA-256)
                0x10002L, 0x10005L -> 0x3C + 0x40  // ECDSA (SHA-1 / SHA-256)
                else -> throw IllegalArgumentException("unknown TMD signature type")
            }
            val tmdHdr = tmdOff + 4 + sigLen
            val th = f.bytes(tmdHdr, 0xC4)
            // First content chunk record follows the header and 64 content info records
            val chunk = f.bytes(tmdHdr + 0xC4 + 0x900, 0x30)
            val titleKeyEncrypted = chunk.be16(6) and 0x1 != 0

            return RomInfo(
                format = RomFormat.CIA,
                titleId = th.be64(0x4C),
                version = th.be16(0x9C),
                encrypted = titleKeyEncrypted || ncchEncrypted(f, contentOff),
                payloadSize = contentSize,
            )
        }

        /** NCCH flags[7] bit 2 = NoCrypto */
        private fun ncchEncrypted(f: RandomAccessFile, ncchOff: Long): Boolean {
            val n = f.bytes(ncchOff, 0x200)
            if (String(n, 0x100, 4, Charsets.US_ASCII) != "NCCH") return true
            return n[0x18F].toInt() and 0x4 == 0
        }

        private fun align(x: Long) = (x + 63) and 63L.inv()

        private fun RandomAccessFile.bytes(off: Long, n: Int): ByteArray {
            seek(off)
            return ByteArray(n).also { readFully(it) }
        }

        private fun RandomAccessFile.ascii(off: Long, n: Int) =
            String(bytes(off, n), Charsets.US_ASCII)

        private fun ByteArray.u(i: Int) = this[i].toLong() and 0xFF
        private fun ByteArray.le32(i: Int) = u(i) or (u(i + 1) shl 8) or (u(i + 2) shl 16) or (u(i + 3) shl 24)
        private fun ByteArray.le64(i: Int) = le32(i) or (le32(i + 4) shl 32)
        private fun ByteArray.be16(i: Int) = ((u(i) shl 8) or u(i + 1)).toInt()
        private fun ByteArray.be32(i: Int) = (u(i) shl 24) or (u(i + 1) shl 16) or (u(i + 2) shl 8) or u(i + 3)
        private fun ByteArray.be64(i: Int) = (be32(i) shl 32) or be32(i + 4)
    }
}
