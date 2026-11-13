package io.github.lillyschulte.decryptor

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.roundToInt

class MainActivity : Activity() {

    companion object {
        private const val REQUEST_FOLDER = 1
        private const val PREF_FOLDER = "rom_folder"
        private const val PREF_GAME_CIA_AS_CCI = "game_cia_as_cci"
    }

    private val prefs by lazy { getSharedPreferences("settings", MODE_PRIVATE) }

    private lateinit var permissionCard: View
    private lateinit var folderPath: TextView
    private lateinit var romSummary: TextView
    private lateinit var decryptButton: Button
    private lateinit var cancelButton: Button
    private lateinit var cciSwitch: Switch
    private lateinit var currentFile: TextView
    private lateinit var stage: TextView
    private lateinit var fileProgress: ProgressBar
    private lateinit var fileProgressText: TextView
    private lateinit var overallProgress: ProgressBar
    private lateinit var overallProgressText: TextView
    private lateinit var logScroll: ScrollView
    private lateinit var logText: TextView

    private var wasRunning = false
    private var shownLogLength = -1
    private val onJobChanged: () -> Unit = { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        permissionCard = findViewById(R.id.permission_card)
        folderPath = findViewById(R.id.folder_path)
        romSummary = findViewById(R.id.rom_summary)
        decryptButton = findViewById(R.id.decrypt_button)
        cancelButton = findViewById(R.id.cancel_button)
        cciSwitch = findViewById(R.id.cci_switch)
        currentFile = findViewById(R.id.current_file)
        stage = findViewById(R.id.stage)
        fileProgress = findViewById(R.id.file_progress)
        fileProgressText = findViewById(R.id.file_progress_text)
        overallProgress = findViewById(R.id.overall_progress)
        overallProgressText = findViewById(R.id.overall_progress_text)
        logScroll = findViewById(R.id.log_scroll)
        logText = findViewById(R.id.log_text)

        findViewById<Button>(R.id.grant_button).setOnClickListener { requestAllFilesAccess() }
        findViewById<Button>(R.id.folder_button).setOnClickListener { pickFolder() }
        decryptButton.setOnClickListener { startBatch() }
        cancelButton.setOnClickListener { DecryptService.cancel(this) }
        cciSwitch.isChecked = prefs.getBoolean(PREF_GAME_CIA_AS_CCI, true)
        cciSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean(PREF_GAME_CIA_AS_CCI, checked).apply()
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    override fun onResume() {
        super.onResume()
        JobState.addListener(onJobChanged)
        wasRunning = JobState.running
        shownLogLength = -1
        refreshFolder()
        render()
    }

    override fun onPause() {
        JobState.removeListener(onJobChanged)
        super.onPause()
    }

    private fun hasFileAccess() = Environment.isExternalStorageManager()

    private fun requestAllFilesAccess() {
        try {
            startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }

    @Suppress("DEPRECATION")
    private fun pickFolder() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_FOLDER)
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_FOLDER || resultCode != RESULT_OK) return
        val path = data?.data?.let { treeUriToPath(it) }
        if (path == null) {
            Toast.makeText(this, R.string.unsupported_folder, Toast.LENGTH_LONG).show()
            return
        }
        prefs.edit().putString(PREF_FOLDER, path).apply()
        refreshFolder()
    }

    /** Converts a folder picked in the system file picker to a real filesystem path. */
    private fun treeUriToPath(uri: Uri): String? {
        val docId = DocumentsContract.getTreeDocumentId(uri)
        if (docId.startsWith("raw:")) return docId.removePrefix("raw:")
        if (uri.authority != "com.android.externalstorage.documents") return null
        val volume = docId.substringBefore(':')
        val relative = docId.substringAfter(':', "")
        val root = when {
            volume.equals("primary", ignoreCase = true) -> Environment.getExternalStorageDirectory().path
            volume.equals("home", ignoreCase = true) ->
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS).path
            else -> "/storage/$volume" // SD card / USB drive
        }
        return if (relative.isEmpty()) root else "$root/$relative"
    }

    /** "/storage/emulated/0/Roms" -> "Internal storage/Roms", "/storage/9C33-6BBD/Roms" -> "SD card/Roms" */
    private fun displayPath(folder: File): String {
        val internal = Environment.getExternalStorageDirectory().path
        val path = folder.path
        return when {
            path == internal || path.startsWith("$internal/") ->
                getString(R.string.internal_storage) + path.removePrefix(internal)
            path.startsWith("/storage/") ->
                getString(R.string.sd_card) + "/" + path.removePrefix("/storage/").substringAfter('/', "")
            else -> path
        }.removeSuffix("/")
    }

    private fun selectedFolder(): File? = prefs.getString(PREF_FOLDER, null)?.let(::File)

    private fun refreshFolder() {
        permissionCard.visibility = if (hasFileAccess()) View.GONE else View.VISIBLE
        val folder = selectedFolder()
        folderPath.text = folder?.let(::displayPath) ?: getString(R.string.no_folder)

        romSummary.text = when {
            folder == null -> getString(R.string.pick_folder_hint)
            !hasFileAccess() -> getString(R.string.need_access_hint)
            !folder.isDirectory -> getString(R.string.folder_missing)
            else -> {
                val roms = Decryptor.scan(folder)
                if (roms.isEmpty()) getString(R.string.no_roms)
                else resources.getQuantityString(R.plurals.roms_found, roms.size, roms.size) +
                    "\n" + roms.joinToString("\n") { "• ${it.name}" }
            }
        }
        render()
    }

    private fun startBatch() {
        val folder = selectedFolder() ?: return pickFolder()
        if (!hasFileAccess()) return requestAllFilesAccess()
        DecryptService.start(this, folder.path, cciSwitch.isChecked)
    }

    private fun render() {
        val running = JobState.running
        val folder = selectedFolder()

        decryptButton.isEnabled = !running && folder != null && hasFileAccess() && folder.isDirectory
        cancelButton.visibility = if (running) View.VISIBLE else View.GONE
        cciSwitch.isEnabled = !running
        if (running) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        when {
            running && JobState.fileCount > 0 -> {
                currentFile.text = getString(R.string.file_of, JobState.fileIndex, JobState.fileCount, JobState.fileName)
                stage.text = JobState.stage
            }
            running -> {
                currentFile.text = getString(R.string.preparing)
                stage.text = ""
            }
            else -> {
                currentFile.text = JobState.summary.ifEmpty { getString(R.string.idle) }
                stage.text = ""
            }
        }
        val filePct = (JobState.fileProgress * 100).roundToInt()
        val overallPct = (JobState.overallProgress * 100).roundToInt()
        fileProgress.progress = (JobState.fileProgress * 1000).roundToInt()
        overallProgress.progress = (JobState.overallProgress * 1000).roundToInt()
        fileProgressText.text = getString(R.string.percent, filePct)
        overallProgressText.text = getString(R.string.percent, overallPct)

        val log = JobState.logText()
        if (log.length != shownLogLength) {
            shownLogLength = log.length
            logText.text = log
            logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }
        }

        // Rescan when a batch finishes so the list reflects the new outputs
        if (wasRunning && !running) {
            wasRunning = false
            refreshFolder()
        }
        wasRunning = running
    }
}
