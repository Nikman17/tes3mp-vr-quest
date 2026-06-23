/*
    TES3MP VR Launcher — main entry screen
    Replaces the minimal xyz.morrowind.tes3mpvr.LauncherActivity.
    Shows: game-data path, mod manager, SP/MP switch, server IP/port, settings, launch.
*/

package ui.activity

import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.preference.PreferenceManager
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

import com.codekidlabs.storagechooser.StorageChooser
import com.libopenmw.openmw.R
import file.GameInstaller
import permission.PermissionHelper

private const val TAG = "LauncherActivity"

// SharedPreference keys
private const val PREF_MODE       = "tes3mp_mode"          // "singleplayer" | "multiplayer"
private const val PREF_SERVER_IP  = "tes3mp_server_ip"
private const val PREF_SERVER_PORT= "tes3mp_server_port"
private const val MODE_SP = "singleplayer"
private const val MODE_MP = "multiplayer"
private const val DEFAULT_PORT    = "25565"

class LauncherActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private var launchInProgress = false

    // Views
    private lateinit var pathView:        TextView
    private lateinit var statusView:      TextView
    private lateinit var selectDataBtn:   Button
    private lateinit var modsBtn:         Button
    private lateinit var settingsBtn:     Button
    private lateinit var launchBtn:       Button
    private lateinit var modeGroup:       RadioGroup
    private lateinit var serverSection:   LinearLayout
    private lateinit var serverIpEdit:    EditText
    private lateinit var serverPortEdit:  EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate")
        super.onCreate(savedInstanceState)
        PermissionHelper.getWriteExternalStoragePermission(this)
        setContentView(R.layout.launcher)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)

        // Bind views
        pathView       = findViewById(R.id.game_data_path)
        statusView     = findViewById(R.id.status_message)
        selectDataBtn  = findViewById(R.id.select_data_button)
        modsBtn        = findViewById(R.id.manage_mods_button)
        settingsBtn    = findViewById(R.id.settings_button)
        launchBtn      = findViewById(R.id.launch_game_button)
        modeGroup      = findViewById(R.id.mode_radio_group)
        serverSection  = findViewById(R.id.server_section)
        serverIpEdit   = findViewById(R.id.server_ip_edit)
        serverPortEdit = findViewById(R.id.server_port_edit)

        // Restore saved mode + server settings
        restoreMode()
        serverIpEdit.setText(prefs.getString(PREF_SERVER_IP, ""))
        serverPortEdit.setText(prefs.getString(PREF_SERVER_PORT, DEFAULT_PORT))

        // Show/hide server section when mode changes
        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val isMP = (checkedId == R.id.radio_multiplayer)
            serverSection.visibility = if (isMP) View.VISIBLE else View.GONE
            saveMode(if (isMP) MODE_MP else MODE_SP)
            statusView.text = ""
        }

        // Game data path display
        updatePathDisplay()

        // Buttons
        selectDataBtn.setOnClickListener {
            if (!launchInProgress) selectGameData()
        }

        modsBtn.setOnClickListener {
            if (!launchInProgress)
                startActivity(Intent(this, ModsActivity::class.java))
        }

        settingsBtn.setOnClickListener {
            if (!launchInProgress)
                startActivity(Intent(this, MainActivity::class.java))
        }

        launchBtn.setOnClickListener {
            if (!launchInProgress) {
                saveServerPrefs()
                checkAndLaunch()
            }
        }

        Log.d(TAG, "onCreate complete")
    }

    override fun onResume() {
        super.onResume()
        updatePathDisplay()
        launchInProgress = false
        setButtonsEnabled(true)
    }

    // ── helpers ──────────────────────────────────────────────────────

    private fun restoreMode() {
        val mode = prefs.getString(PREF_MODE, MODE_SP) ?: MODE_SP
        if (mode == MODE_MP) {
            modeGroup.check(R.id.radio_multiplayer)
            serverSection.visibility = View.VISIBLE
        } else {
            modeGroup.check(R.id.radio_singleplayer)
            serverSection.visibility = View.GONE
        }
    }

    private fun saveMode(mode: String) {
        prefs.edit().putString(PREF_MODE, mode).apply()
    }

    private fun saveServerPrefs() {
        prefs.edit()
            .putString(PREF_SERVER_IP,   serverIpEdit.text.toString().trim())
            .putString(PREF_SERVER_PORT, serverPortEdit.text.toString().trim().ifEmpty { DEFAULT_PORT })
            .apply()
    }

    private fun updatePathDisplay() {
        val path = prefs.getString("game_files", "") ?: ""
        pathView.text = if (path.isEmpty()) "(not configured)" else path
    }

    private fun setButtonsEnabled(enabled: Boolean) {
        selectDataBtn.isEnabled  = enabled
        modsBtn.isEnabled        = enabled
        settingsBtn.isEnabled    = enabled
        launchBtn.isEnabled      = enabled
    }

    private fun selectGameData() {
        Log.d(TAG, "selectGameData")
        val chooser = StorageChooser.Builder()
            .withActivity(this)
            .withFragmentManager(fragmentManager)
            .withMemoryBar(true)
            .allowCustomPath(true)
            .setType(StorageChooser.DIRECTORY_CHOOSER)
            .build()
        chooser.show()
        chooser.setOnSelectListener { path -> setupData(path) }
    }

    private fun setupData(path: String) {
        Log.d(TAG, "setupData: $path")
        var gameFiles = ""
        val inst = GameInstaller(path)
        if (inst.check()) {
            inst.setNomedia()
            if (!inst.convertIni(prefs.getString("pref_encoding", GameInstaller.DEFAULT_CHARSET_PREF)!!)) {
                showError(R.string.data_error_title, R.string.ini_error_message)
            } else {
                gameFiles = path
            }
        } else {
            showError(R.string.data_error_title, R.string.data_error_message, "https://omw.xyz.is/game.html")
        }
        prefs.edit().putString("game_files", gameFiles).apply()
        updatePathDisplay()
        statusView.text = if (gameFiles.isEmpty()) "Invalid game data path" else "Game data configured!"
    }

    private fun checkAndLaunch() {
        Log.d(TAG, "checkAndLaunch")
        val path = prefs.getString("game_files", "") ?: ""
        if (path.isEmpty()) {
            statusView.text = "Please select game data first"
            return
        }

        val isMP = (modeGroup.checkedRadioButtonId == R.id.radio_multiplayer)
        if (isMP) {
            val ip = serverIpEdit.text.toString().trim()
            if (ip.isEmpty()) {
                statusView.text = "Please enter server IP address"
                return
            }
            // Write tes3mp-client-default.cfg so the engine connects on startup
            writeMultiplayerCfg(ip, serverPortEdit.text.toString().trim().ifEmpty { DEFAULT_PORT })
        } else {
            // Ensure multiplayer is disabled in client cfg
            writeSingleplayerCfg()
        }

        launchInProgress = true
        setButtonsEnabled(false)
        statusView.text = "Starting..."

        val intent = Intent(this, VrEntryActivity::class.java)
        intent.putExtra(VrEntryActivity.EXTRA_AUTO_START_GAME, true)
        startActivity(intent)
        finish()
    }

    private fun writeMultiplayerCfg(ip: String, port: String) {
        try {
            val cfgDir = java.io.File(android.os.Environment.getExternalStorageDirectory(), "tes3mpvr/config")
            cfgDir.mkdirs()
            val cfg = java.io.File(cfgDir, "tes3mp-client-default.cfg")
            cfg.writeText("""
                [General]
                address = $ip
                port = $port
                """.trimIndent() + "\n")
            Log.d(TAG, "Multiplayer cfg written: $ip:$port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write multiplayer cfg", e)
        }
    }

    private fun writeSingleplayerCfg() {
        try {
            val cfgDir = java.io.File(android.os.Environment.getExternalStorageDirectory(), "tes3mpvr/config")
            cfgDir.mkdirs()
            val cfg = java.io.File(cfgDir, "tes3mp-client-default.cfg")
            if (cfg.exists()) cfg.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear multiplayer cfg", e)
        }
    }

    private fun showError(title: Int, message: Int, url: String? = null) {
        val dialog = AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _: DialogInterface, _: Int -> }
        if (url != null) {
            dialog.setNeutralButton(R.string.dialog_howto) { _, _ -> openUrl(url) }
        }
        dialog.show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
        } catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle(R.string.no_browser_title)
                .setMessage(getString(R.string.no_browser_message, url))
                .setPositiveButton(android.R.string.ok) { _, _ -> }
                .show()
        }
    }
}
