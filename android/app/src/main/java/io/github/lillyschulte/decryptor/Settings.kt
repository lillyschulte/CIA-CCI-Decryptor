package io.github.lillyschulte.decryptor

import android.content.Context
import java.io.File

/** App settings, stored in SharedPreferences and read by both the UI and DecryptService. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Folder that is scanned for ROMs */
    var romFolder: String?
        get() = prefs.getString("rom_folder", null)
        set(v) = prefs.edit().putString("rom_folder", v).apply()

    /** Rebuild game CIAs as CCI, since Azahar's game list only shows CCI/3DS files */
    var gameCiaAsCci: Boolean
        get() = prefs.getBoolean("game_cia_as_cci", true)
        set(v) = prefs.edit().putBoolean("game_cia_as_cci", v).apply()

    /** Strip a leading title ID ("0004000000051500_Game") from output file names */
    var stripTitleId: Boolean
        get() = prefs.getBoolean("strip_title_id", true)
        set(v) = prefs.edit().putBoolean("strip_title_id", v).apply()

    /** Delete the encrypted original once its decrypted copy has been written */
    var deleteOriginals: Boolean
        get() = prefs.getBoolean("delete_originals", false)
        set(v) = prefs.edit().putBoolean("delete_originals", v).apply()

    /** Write decrypted updates and DLC to [updateDir] instead of next to the original */
    var sortUpdates: Boolean
        get() = prefs.getBoolean("sort_updates", false)
        set(v) = prefs.edit().putBoolean("sort_updates", v).apply()

    /** Folder for decrypted updates and DLC; null means [DEFAULT_UPDATE_FOLDER] inside the ROM folder */
    var updateFolder: String?
        get() = prefs.getString("update_folder", null)
        set(v) = prefs.edit().putString("update_folder", v).apply()

    /** Where decrypted updates and DLC go for ROMs in [romDir], or null to keep them next to the original */
    fun updateDir(romDir: File): File? =
        if (!sortUpdates) null else updateFolder?.let(::File) ?: File(romDir, DEFAULT_UPDATE_FOLDER)

    /** Every folder decrypted updates and DLC may have been sorted into, for [romDir] */
    fun allUpdateDirs(romDir: File): List<File> =
        listOfNotNull(updateFolder?.let(::File), File(romDir, DEFAULT_UPDATE_FOLDER))

    companion object {
        const val DEFAULT_UPDATE_FOLDER = "Updates and DLC"
    }
}
