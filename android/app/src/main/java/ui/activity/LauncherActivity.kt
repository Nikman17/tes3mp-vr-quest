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
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.preference.PreferenceManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.Spinner
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
    private lateinit var vrTurning:     Spinner
    private lateinit var vrResolution:  Spinner
    private lateinit var vrRefresh:     Spinner
    private lateinit var vrHud:         Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate")
        super.onCreate(savedInstanceState)
        PermissionHelper.getWriteExternalStoragePermission(this)
        // On Android 11+ request MANAGE_EXTERNAL_STORAGE so File.list() works on /sdcard
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Log.w(TAG, "MANAGE_EXTERNAL_STORAGE not granted — requesting")
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    .setData(Uri.parse("package:$packageName"))
                startActivity(intent)
            }
        }
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

        vrTurning      = findViewById(R.id.vr_turning_spinner)
        vrResolution   = findViewById(R.id.vr_resolution_spinner)
        vrRefresh      = findViewById(R.id.vr_refresh_spinner)
        vrHud          = findViewById(R.id.vr_hud_spinner)
        setupVrSpinner(vrTurning, "vr_turning", arrayOf("Snap 30°", "Snap 45°", "Smooth"))
        setupVrSpinner(vrResolution, "vr_resolution", arrayOf("100% (1440×1584)", "90%", "80%", "70%"))
        setupVrSpinner(vrRefresh, "vr_refresh", arrayOf("72 Hz", "90 Hz", "120 Hz"))
        setupVrSpinner(vrHud, "vr_hud", arrayOf("Wrist", "Top"))

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
        val dir = java.io.File(path)
        Log.d(TAG, "dir.exists=${dir.exists()} dir.isDirectory=${dir.isDirectory} canRead=${dir.canRead()}")
        val listing = dir.list()
        Log.d(TAG, "dir.list=${listing?.joinToString() ?: "NULL"}")
        Log.d(TAG, "isExternalStorageManager=${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else "n/a"}")
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
            // TES3MP has no offline mode: without a server config the engine exits
            // right after start. Be honest about it instead of silently failing.
            AlertDialog.Builder(this)
                .setTitle("Singleplayer unavailable")
                .setMessage("TES3MP is multiplayer-only: the client always connects to a server.\n\n" +
                    "Use Multiplayer mode with your LAN or online server.\n" +
                    "An embedded local server for true singleplayer is planned.")
                .setPositiveButton(android.R.string.ok) { _: DialogInterface, _: Int -> }
                .show()
            statusView.text = "Singleplayer needs a (local) server — use Multiplayer for now"
            return
        }

        writeVrSettings()

        launchInProgress = true
        setButtonsEnabled(false)
        statusView.text = "Starting…"

        startActivity(Intent(this, VrEntryActivity::class.java).apply {
            putExtra(VrEntryActivity.EXTRA_AUTO_START_GAME, true)
        })
        finish()
    }

    // ── VR settings ───────────────────────────────────────────────────

    private fun setupVrSpinner(spinner: Spinner, prefKey: String, values: Array<String>) {
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, values)
        spinner.setSelection(prefs.getInt(prefKey, 0))
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putInt(prefKey, position).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    /**
     * Writes the user settings.cfg consumed by the engine (loaded after defaults,
     * overridden only by settings-overrides-vr.cfg).
     */
    private fun writeVrSettings() {
        val turning = prefs.getInt("vr_turning", 0)
        val resolution = prefs.getInt("vr_resolution", 0)
        val refresh = prefs.getInt("vr_refresh", 0)
        val hud = prefs.getInt("vr_hud", 0)

        val smoothTurning = (turning == 2)
        val snapAngle = if (turning == 1) 45.0 else 30.0

        // Quest 2 recommended per-eye render target is 1440x1584
        val scale = when (resolution) { 1 -> 0.9; 2 -> 0.8; 3 -> 0.7; else -> 1.0 }
        val eyeX = (1440 * scale).toInt()
        val eyeY = (1584 * scale).toInt()

        val refreshRate = when (refresh) { 1 -> 90; 2 -> 120; else -> 72 }
        val hudPos = if (hud == 1) "top" else "wrist"

        val content = StringBuilder()
        content.append("# Generated by the launcher (VR SETTINGS panel). Edit there, not here.\n")
        content.append("[VR]\n")
        content.append("smooth turning = $smoothTurning\n")
        content.append("snap angle = $snapAngle\n")
        content.append("left eye resolution x = $eyeX\n")
        content.append("left eye resolution y = $eyeY\n")
        content.append("right eye resolution x = $eyeX\n")
        content.append("right eye resolution y = $eyeY\n")
        content.append("display refresh rate = $refreshRate\n")
        content.append("left hand hud position = $hudPos\n")

        val targets = listOf(
            java.io.File(Environment.getExternalStorageDirectory(), "tes3mpvr/config/settings.cfg"),
            java.io.File(filesDir, "config/settings.cfg")
        )
        for (f in targets) {
            try {
                f.parentFile?.mkdirs()
                f.writeText(content.toString())
                Log.d(TAG, "VR settings written to ${f.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write VR settings to ${f.absolutePath}", e)
            }
        }
    }

    private fun writeMultiplayerCfg(ip: String, port: String) {
        val content = "[General]\ndestinationAddress = $ip\nport = $port\npassword =\nlogLevel = 1\n\n" +
            "[Master]\naddress = master.tes3mp.com\nport = 25561\n\n" +
            "[Chat]\nkeySay = F5\nkeyChatMode = T\nx = 0\ny = 150\nw = 300\nh = 300\ndelay = 5.0\n"
        // TES3MP reads from /sdcard/tes3mpvr/config/ (USER_FILE_STORAGE = sdcard/<app_slug>/)
        // and also writes logs there, so that's the correct location
        val targets = listOf(
            java.io.File(Environment.getExternalStorageDirectory(), "tes3mpvr/config/tes3mp-client-default.cfg"),
            java.io.File(filesDir, "config/tes3mp-client-default.cfg")
        )
        for (f in targets) {
            try {
                f.parentFile?.mkdirs()
                f.writeText(content)
                Log.d(TAG, "MP cfg written to ${f.absolutePath}: $ip:$port")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to write MP cfg to ${f.absolutePath}", e)
            }
        }
    }

    private fun clearMultiplayerCfg() {
        val targets = listOf(
            java.io.File(filesDir, "config/tes3mp-client-default.cfg"),
            java.io.File(Environment.getExternalStorageDirectory(), "tes3mpvr/config/tes3mp-client-default.cfg")
        )
        for (f in targets) {
            try { f.delete() } catch (e: Exception) { /* ignore */ }
        }
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
