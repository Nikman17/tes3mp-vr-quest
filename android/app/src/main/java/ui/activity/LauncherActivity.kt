/*
    TES3MP VR Launcher — main entry screen
    Uses Android native ACTION_OPEN_DOCUMENT_TREE for directory picking.
*/

package ui.activity

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.preference.PreferenceManager
import android.provider.DocumentsContract
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

import com.libopenmw.openmw.R
import file.GameInstaller
import permission.PermissionHelper

private const val TAG = "LauncherActivity"

private const val REQ_PICK_DIR     = 1001
private const val PREF_MODE        = "tes3mp_mode"
private const val PREF_SERVER_IP   = "tes3mp_server_ip"
private const val PREF_SERVER_PORT = "tes3mp_server_port"
private const val MODE_SP          = "singleplayer"
private const val MODE_MP          = "multiplayer"
private const val DEFAULT_PORT     = "25565"

class LauncherActivity : AppCompatActivity() {

    private lateinit var prefs: SharedPreferences
    private var launchInProgress = false

    private lateinit var pathView:      TextView
    private lateinit var statusView:    TextView
    private lateinit var selectDataBtn: Button
    private lateinit var modsBtn:       Button
    private lateinit var settingsBtn:   Button
    private lateinit var launchBtn:     Button
    private lateinit var modeGroup:     RadioGroup
    private lateinit var serverSection: LinearLayout
    private lateinit var serverIpEdit:  EditText
    private lateinit var serverPortEdit:EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate")
        super.onCreate(savedInstanceState)
        PermissionHelper.getWriteExternalStoragePermission(this)
        setContentView(R.layout.launcher)

        prefs = PreferenceManager.getDefaultSharedPreferences(this)

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

        restoreMode()
        serverIpEdit.setText(prefs.getString(PREF_SERVER_IP, ""))
        serverPortEdit.setText(prefs.getString(PREF_SERVER_PORT, DEFAULT_PORT))

        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val isMP = (checkedId == R.id.radio_multiplayer)
            serverSection.visibility = if (isMP) View.VISIBLE else View.GONE
            prefs.edit().putString(PREF_MODE, if (isMP) MODE_MP else MODE_SP).apply()
            statusView.text = ""
        }

        updatePathDisplay()

        selectDataBtn.setOnClickListener {
            if (!launchInProgress) openDirectoryPicker()
        }
        modsBtn.setOnClickListener {
            if (!launchInProgress) startActivity(Intent(this, ModsActivity::class.java))
        }
        settingsBtn.setOnClickListener {
            if (!launchInProgress) startActivity(Intent(this, MainActivity::class.java))
        }
        launchBtn.setOnClickListener {
            if (!launchInProgress) {
                saveServerPrefs()
                checkAndLaunch()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updatePathDisplay()
        launchInProgress = false
        setButtonsEnabled(true)
    }

    // ── directory picker ──────────────────────────────────────────────

    private fun openDirectoryPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            // Start at SD card root if possible
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent, REQ_PICK_DIR)
    }

    @Deprecated("Uses deprecated onActivityResult API, required for older AGP")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_PICK_DIR || resultCode != Activity.RESULT_OK) return

        val treeUri = data?.data ?: run {
            statusView.text = "No folder selected"
            return
        }

        // Persist permission so we can access the folder after reboot
        try {
            contentResolver.takePersistableUriPermission(
                treeUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (e: SecurityException) {
            Log.w(TAG, "Could not persist URI permission: ${e.message}")
        }

        // Convert the tree URI to a real filesystem path
        val path = treeUriToPath(treeUri)
        if (path == null) {
            statusView.text = "Cannot resolve folder path — try a path under /sdcard"
            return
        }

        setupData(path)
    }

    /**
     * Converts an ACTION_OPEN_DOCUMENT_TREE Uri to an absolute filesystem path.
     *
     * The document ID for primary storage looks like "primary:Morrowind" or
     * "primary:Games/Morrowind". We resolve it against the external storage root.
     */
    private fun treeUriToPath(uri: Uri): String? {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            Log.d(TAG, "treeUriToPath: docId=$docId, uri=$uri")

            when {
                // "primary:some/path" — standard sdcard
                docId.startsWith("primary:") -> {
                    val rel = docId.removePrefix("primary:")
                    val root = Environment.getExternalStorageDirectory().absolutePath
                    if (rel.isEmpty()) root else "$root/$rel"
                }
                // Raw absolute path (some ROMs)
                docId.startsWith("/") -> docId
                // Fallback: try stripping any authority prefix "xxxxx:path"
                docId.contains(":") -> {
                    val rel = docId.substringAfter(":")
                    val root = Environment.getExternalStorageDirectory().absolutePath
                    if (rel.isEmpty()) root else "$root/$rel"
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "treeUriToPath failed: ${e.message}")
            null
        }
    }

    // ── data setup ────────────────────────────────────────────────────

    private fun setupData(path: String) {
        Log.d(TAG, "setupData: $path")
        val inst = GameInstaller(path)
        if (!inst.check()) {
            statusView.text = "Invalid folder — need Morrowind.ini + Data Files inside"
            showError(R.string.data_error_title, R.string.data_error_message,
                "https://omw.xyz.is/game.html")
            prefs.edit().putString("game_files", "").apply()
            updatePathDisplay()
            return
        }

        inst.setNomedia()
        val encoding = prefs.getString("pref_encoding", GameInstaller.DEFAULT_CHARSET_PREF)!!
        if (!inst.convertIni(encoding)) {
            showError(R.string.data_error_title, R.string.ini_error_message)
            prefs.edit().putString("game_files", "").apply()
            updatePathDisplay()
            return
        }

        prefs.edit().putString("game_files", path).apply()
        updatePathDisplay()
        statusView.text = "Game data configured!"
        Log.d(TAG, "setupData: OK, path saved")
    }

    // ── launch ────────────────────────────────────────────────────────

    private fun checkAndLaunch() {
        val path = prefs.getString("game_files", "") ?: ""
        if (path.isEmpty()) {
            statusView.text = "Please select a game data folder first"
            return
        }
        if (!GameInstaller(path).check()) {
            statusView.text = "Game data folder is no longer valid — please re-select"
            return
        }

        val isMP = (modeGroup.checkedRadioButtonId == R.id.radio_multiplayer)
        if (isMP) {
            val ip = serverIpEdit.text.toString().trim()
            if (ip.isEmpty()) {
                statusView.text = "Please enter the server IP address"
                return
            }
            writeMultiplayerCfg(ip, serverPortEdit.text.toString().trim().ifEmpty { DEFAULT_PORT })
        } else {
            clearMultiplayerCfg()
        }

        launchInProgress = true
        setButtonsEnabled(false)
        statusView.text = "Starting…"

        startActivity(Intent(this, VrEntryActivity::class.java).apply {
            putExtra(VrEntryActivity.EXTRA_AUTO_START_GAME, true)
        })
        finish()
    }

    private fun writeMultiplayerCfg(ip: String, port: String) {
        try {
            val cfgDir = java.io.File(Environment.getExternalStorageDirectory(), "tes3mpvr/config")
            cfgDir.mkdirs()
            java.io.File(cfgDir, "tes3mp-client-default.cfg").writeText(
                "[General]\naddress = $ip\nport = $port\n"
            )
            Log.d(TAG, "MP cfg written: $ip:$port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write MP cfg", e)
        }
    }

    private fun clearMultiplayerCfg() {
        try {
            java.io.File(
                Environment.getExternalStorageDirectory(), "tes3mpvr/config/tes3mp-client-default.cfg"
            ).delete()
        } catch (e: Exception) { /* ignore */ }
    }

    // ── helpers ───────────────────────────────────────────────────────

    private fun restoreMode() {
        if ((prefs.getString(PREF_MODE, MODE_SP) ?: MODE_SP) == MODE_MP) {
            modeGroup.check(R.id.radio_multiplayer)
            serverSection.visibility = View.VISIBLE
        } else {
            modeGroup.check(R.id.radio_singleplayer)
            serverSection.visibility = View.GONE
        }
    }

    private fun saveServerPrefs() {
        prefs.edit()
            .putString(PREF_SERVER_IP,   serverIpEdit.text.toString().trim())
            .putString(PREF_SERVER_PORT, serverPortEdit.text.toString().trim().ifEmpty { DEFAULT_PORT })
            .apply()
    }

    private fun updatePathDisplay() {
        val p = prefs.getString("game_files", "") ?: ""
        pathView.text = if (p.isEmpty()) "(not configured)" else p
    }

    private fun setButtonsEnabled(on: Boolean) {
        selectDataBtn.isEnabled = on
        modsBtn.isEnabled       = on
        settingsBtn.isEnabled   = on
        launchBtn.isEnabled     = on
    }

    private fun showError(title: Int, msg: Int, url: String? = null) {
        val b = AlertDialog.Builder(this)
            .setTitle(title).setMessage(msg)
            .setPositiveButton(android.R.string.ok) { _: DialogInterface, _: Int -> }
        if (url != null)
            b.setNeutralButton(R.string.dialog_howto) { _, _ -> openUrl(url) }
        b.show()
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (e: Exception) {
            AlertDialog.Builder(this)
                .setTitle(R.string.no_browser_title)
                .setMessage(getString(R.string.no_browser_message, url))
                .setPositiveButton(android.R.string.ok) { _, _ -> }.show()
        }
    }
}
