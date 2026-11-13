package io.github.lillyschulte.decryptor

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.File

class SettingsActivity : Activity() {

    companion object {
        private const val REQUEST_UPDATE_FOLDER = 1
    }

    private val settings by lazy { Settings(this) }

    private lateinit var updateFolderRow: View
    private lateinit var updateFolderPath: TextView
    private lateinit var updateFolderReset: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        findViewById<View>(R.id.back_button).setOnClickListener { finish() }

        bindSwitch(R.id.cci_switch, settings.gameCiaAsCci) { settings.gameCiaAsCci = it }
        bindSwitch(R.id.strip_title_id_switch, settings.stripTitleId) { settings.stripTitleId = it }
        bindSwitch(R.id.delete_switch, settings.deleteOriginals) { settings.deleteOriginals = it }
        bindSwitch(R.id.sort_updates_switch, settings.sortUpdates) {
            settings.sortUpdates = it
            renderUpdateFolder()
        }

        updateFolderRow = findViewById(R.id.update_folder_row)
        updateFolderPath = findViewById(R.id.update_folder_path)
        updateFolderReset = findViewById(R.id.update_folder_reset)
        findViewById<Button>(R.id.update_folder_button).setOnClickListener {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_UPDATE_FOLDER)
        }
        updateFolderReset.setOnClickListener {
            settings.updateFolder = null
            renderUpdateFolder()
        }
        renderUpdateFolder()
    }

    private fun bindSwitch(id: Int, value: Boolean, onChange: (Boolean) -> Unit) {
        findViewById<Switch>(id).apply {
            isChecked = value
            setOnCheckedChangeListener { _, checked -> onChange(checked) }
        }
    }

    private fun renderUpdateFolder() {
        updateFolderRow.visibility = if (settings.sortUpdates) View.VISIBLE else View.GONE
        val custom = settings.updateFolder
        updateFolderPath.text = if (custom != null) Folders.display(this, File(custom))
        else getString(R.string.update_folder_default, Settings.DEFAULT_UPDATE_FOLDER)
        updateFolderReset.visibility = if (custom != null) View.VISIBLE else View.GONE
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_UPDATE_FOLDER || resultCode != RESULT_OK) return
        val path = data?.data?.let { Folders.treeUriToPath(it) }
        if (path == null) {
            Toast.makeText(this, R.string.unsupported_folder, Toast.LENGTH_LONG).show()
            return
        }
        settings.updateFolder = path
        renderUpdateFolder()
    }
}
