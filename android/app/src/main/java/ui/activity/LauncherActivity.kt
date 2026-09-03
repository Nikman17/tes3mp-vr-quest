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
private const val PREF_CHAR_NAME   = "character_name"
private const val MODE_SP          = "singleplayer"
private const val MODE_MP          = "multiplayer"
private const val DEFAULT_PORT     = "25565"
// Bumped when the preset contents change so users get re-offered once.
private const val PREF_NIRN_OFFERED = "nirn_preset_offered_v4"

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
    private lateinit var charNameEdit:  EditText
    private lateinit var vrTurning:     Spinner
    private lateinit var vrResolution:  Spinner
    private lateinit var vrRefresh:     Spinner
    private lateinit var vrHud:         Spinner
    private lateinit var vrGraphics:    Spinner

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
        vrGraphics = findViewById(R.id.vr_graphics_spinner)
        setupVrSpinner(vrGraphics, "vr_graphics", arrayOf("Performance", "Balanced", "Quality"))
        val showFps = findViewById<android.widget.CheckBox>(R.id.vr_show_fps)
        showFps.isChecked = prefs.getBoolean("vr_show_fps", false)
        showFps.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("vr_show_fps", checked).apply()
        }
        setupVrSpinner(findViewById(R.id.vr_foveation_spinner), "vr_foveation",
            arrayOf("Off", "Low", "Medium", "High", "High (dynamic)"), default = 4)
        setupVrSpinner(findViewById(R.id.vr_boost_spinner), "vr_boost",
            arrayOf("Standard", "High clocks", "Boost"), default = 1)

        restoreMode()
        serverIpEdit.setText(prefs.getString(PREF_SERVER_IP, ""))
        serverPortEdit.setText(prefs.getString(PREF_SERVER_PORT, DEFAULT_PORT))
        charNameEdit = findViewById(R.id.character_name_edit)
        charNameEdit.setText(prefs.getString(PREF_CHAR_NAME, ""))

        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            val isMP = (checkedId == R.id.radio_multiplayer)
            serverSection.visibility = if (isMP) View.VISIBLE else View.GONE
            updateModeHint(isMP)
            prefs.edit().putString(PREF_MODE, if (isMP) MODE_MP else MODE_SP).apply()
            statusView.text = ""
        }
        updateModeHint(modeGroup.checkedRadioButtonId == R.id.radio_multiplayer)

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

        handleAutomationExtras(intent)
    }

    /**
     * ADB automation hooks (used for headless testing over `adb shell am start`):
     *   --ez apply_nirn true      apply the Project Nirn load-order preset
     *   --es set_mode sp|mp       select the engine mode
     *   --es set_ip <addr>        fill in (and persist) the server IP
     *   --es set_port <port>      fill in (and persist) the server port
     *   --ez auto_play true       press PLAY after processing the above
     */
    private fun handleAutomationExtras(i: Intent?) {
        i ?: return
        if (i.getBooleanExtra("apply_nirn", false)) {
            val path = prefs.getString("game_files", "") ?: ""
            if (path.isNotEmpty()) {
                applyNirnPreset(GameInstaller(path).findDataFiles())
                statusView.text = "Project Nirn load order applied"
            }
        }
        when (i.getStringExtra("set_mode")) {
            "sp" -> modeGroup.check(R.id.radio_singleplayer)
            "mp" -> modeGroup.check(R.id.radio_multiplayer)
        }
        i.getStringExtra("set_ip")?.let { serverIpEdit.setText(it) }
        i.getStringExtra("set_port")?.let { serverPortEdit.setText(it) }
        i.getStringExtra("set_name")?.let { charNameEdit.setText(it) }
        if (i.hasExtra("set_ip") || i.hasExtra("set_port") || i.hasExtra("set_name"))
            saveServerPrefs()
        if (i.getBooleanExtra("auto_play", false))
            launchBtn.post { launchBtn.performClick() }
    }

    override fun onResume() {
        super.onResume()
        updatePathDisplay()
        launchInProgress = false
        setButtonsEnabled(true)

        // One-time offer for already-configured installs (e.g. after an app update)
        if (!prefs.getBoolean(PREF_NIRN_OFFERED, false)) {
            val path = prefs.getString("game_files", "") ?: ""
            if (path.isNotEmpty()) {
                prefs.edit().putBoolean(PREF_NIRN_OFFERED, true).apply()
                offerNirnPreset(GameInstaller(path).findDataFiles())
            }
        }
    }

    private fun updateModeHint(isMP: Boolean) {
        findViewById<TextView>(R.id.mode_hint)?.text = if (isMP)
            "TES3MP engine — join a dedicated server and play co-op."
        else
            "Vanilla OpenMW-VR engine — full quest and save compatibility."
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

        offerNirnPreset(inst.findDataFiles())
    }

    // ── Project Nirn load-order preset ────────────────────────────────

    /** Correct load order for the Project Nirn modpack. */
    private fun offerNirnPreset(dataFiles: String) {
        if (!java.io.File(dataFiles, "Nirn_Core.esp").exists())
            return
        AlertDialog.Builder(this)
            .setTitle("Project Nirn detected")
            .setMessage("Apply the recommended Project Nirn load order?\n\n" +
                "• 14 plugins (Morrowind … Nirn_Core.esp)\n" +
                "• 10 BSA archives (001–006, HD, PBR)\n" +
                "• grass plugin routed to groundcover")
            .setPositiveButton("Apply") { _, _ ->
                applyNirnPreset(dataFiles)
                statusView.text = "Project Nirn load order applied"
            }
            .setNegativeButton("Skip") { _, _ -> }
            .show()
    }

    private fun applyNirnPreset(dataFiles: String) {
        // Official Project Nirn load order (14 plugins, exact sequence).
        val plugins = listOf(
            "Morrowind.esm", "Tribunal.esm", "Bloodmoon.esm", "GFM.esm",
            "Rebirth_Main.esm", "OAAB_Data.esm", "Tamriel_Data.esm", "TR_Mainland.esm",
            "Cyr_Main.esm", "Sky_Main.esm", "Wares-base.esm", "NOD_Core.esm",
            "TDoO_Main.esm", "Nirn_Core.esp")
        // BSA archives: 001-006 mandatory, then HD texture/normal-map + PBR packs.
        val archives = listOf(
            "Nirn_Pack_001.bsa", "Nirn_Pack_002.bsa", "Nirn_Pack_003.bsa",
            "Nirn_Pack_004.bsa", "Nirn_Pack_005.bsa", "Nirn_Pack_006.bsa",
            "Nirn_Pack_HD_001.bsa", "Nirn_Pack_HD_002.bsa", "Nirn_Pack_HD_003.bsa",
            "Nirn_Pack_PBR_001.bsa")
        val grass = listOf("Nirn_Grass.omwaddon")

        val db = mods.ModsDatabaseOpenHelper.getInstance(this)
        applyOrder(mods.ModType.Plugin, dataFiles, db, plugins, disable = grass)
        applyOrder(mods.ModType.Resource, dataFiles, db, archives)
        applyOrder(mods.ModType.Groundcover, dataFiles, db, grass)
        Log.i(TAG, "Project Nirn preset applied")
    }

    /**
     * Enables [wanted] files (that exist) in the given order at the top of the list;
     * everything else keeps its relative order below. [disable] entries are force-disabled
     * (e.g. the grass plugin must load via groundcover=, not content=).
     */
    private fun applyOrder(type: mods.ModType, dataFiles: String,
                           db: mods.ModsDatabaseOpenHelper,
                           wanted: List<String>, disable: List<String> = emptyList()) {
        val collection = mods.ModsCollection(type, dataFiles, db)
        var order = 0
        for (name in wanted) {
            val mod = collection.mods.find { it.filename == name } ?: continue
            mod.enabled = true
            mod.order = order++
            mod.dirty = true
        }
        collection.mods
            .filter { it.filename !in wanted }
            .sortedBy { it.order }
            .forEach { mod ->
                mod.order = order++
                if (mod.filename in disable && mod.enabled) mod.enabled = false
                mod.dirty = true
            }
        collection.mods
            .filter { it.filename in disable }
            .forEach { if (it.enabled) { it.enabled = false; it.dirty = true } }
        collection.update()
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
        } else if (!java.io.File(applicationInfo.nativeLibraryDir, "libopenmw-sp.so").exists()) {
            statusView.text = "Singleplayer engine is missing from this build — try Multiplayer"
            return
        }
        // Singleplayer runs the vanilla OpenMW-VR engine (libopenmw-sp.so):
        // full quest/script compatibility, no networking layer, no server needed.
        // GameActivity picks the engine from the saved tes3mp_mode preference.
        proceedToGame()
    }

    private fun proceedToGame() {
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

    private fun setupVrSpinner(spinner: Spinner, prefKey: String, values: Array<String>, default: Int = 0) {
        // The panel background is dark; the stock spinner item text is dark too,
        // which made the closed spinner look empty ("broken"). Force light text.
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, values) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getView(position, convertView, parent) as TextView
                v.setTextColor(0xFFFFFFFF.toInt())
                v.textSize = 13f
                return v
            }
            override fun getDropDownView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getDropDownView(position, convertView, parent) as TextView
                v.setTextColor(0xFF000000.toInt())
                return v
            }
        }
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = adapter
        spinner.setSelection(prefs.getInt(prefKey, default))
        spinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                prefs.edit().putInt(prefKey, position).apply()
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
    }

    /**
     * Merges the launcher-owned VR keys into the user settings.cfg. Other lines
     * (including changes made from the in-game settings window, which saves to
     * the same file) are preserved.
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

        // Foveation spinner: 0 Off, 1..3 static low..high, 4 = high dynamic
        val fovPref = prefs.getInt("vr_foveation", 4)
        val fovLevel = if (fovPref == 4) 3 else fovPref
        val fovDynamic = (fovPref == 4)
        // Boost spinner: 0 Standard (no hint), 1 sustained-high, 2 boost
        val perfLevel = when (prefs.getInt("vr_boost", 1)) { 0 -> -1; 2 -> 3; else -> 2 }

        val managedKeys = listOf(
            "smooth turning", "snap angle",
            "left eye resolution x", "left eye resolution y",
            "right eye resolution x", "right eye resolution y",
            "display refresh rate", "left hand hud position",
            "show fps", "foveation level", "foveation dynamic",
            "cpu level", "gpu level"
        )
        val ourLines = listOf(
            "smooth turning = $smoothTurning",
            "snap angle = $snapAngle",
            "left eye resolution x = $eyeX",
            "left eye resolution y = $eyeY",
            "right eye resolution x = $eyeX",
            "right eye resolution y = $eyeY",
            "display refresh rate = $refreshRate",
            "left hand hud position = $hudPos",
            "show fps = ${prefs.getBoolean("vr_show_fps", false)}",
            "foveation level = $fovLevel",
            "foveation dynamic = $fovDynamic",
            "cpu level = $perfLevel",
            "gpu level = $perfLevel"
        )

        val charName = prefs.getString(PREF_CHAR_NAME, "")!!.trim()

        // Graphics presets: keep decent view range, sacrifice effects first.
        // Quality additionally enables normal maps -> parallax (Nirn HD/PBR
        // packs ship height data in the normal-map alpha channel).
        val graphics = prefs.getInt("vr_graphics", 1)
        val viewingDistance = when (graphics) { 0 -> 2048; 2 -> 5120; else -> 4096 }
        val distantTerrain = graphics >= 1
        val normalMaps = graphics == 2
        val grassDensity = when (graphics) { 0 -> 0.25; 2 -> 1.0; else -> 0.5 }

        // section -> (managed keys, lines the launcher owns)
        val sections = linkedMapOf(
            "VR" to Pair(managedKeys, ourLines),
            "General" to Pair(listOf("player name"),
                if (charName.isEmpty()) emptyList() else listOf("player name = $charName")),
            "Camera" to Pair(listOf("viewing distance"),
                listOf("viewing distance = $viewingDistance")),
            "Shadows" to Pair(listOf("enable shadows"),
                listOf("enable shadows = false")),
            "Terrain" to Pair(listOf("distant terrain"),
                listOf("distant terrain = $distantTerrain")),
            "Groundcover" to Pair(listOf("enabled", "density", "rendering distance"),
                listOf("enabled = ${graphics >= 1}",
                       "density = $grassDensity",
                       "rendering distance = ${if (graphics == 2) 2048 else 1024}")),
            "Shaders" to Pair(
                listOf("auto use object normal maps", "auto use terrain normal maps"),
                listOf("auto use object normal maps = $normalMaps",
                       "auto use terrain normal maps = $normalMaps")),
            "Water" to Pair(listOf("shader", "refraction"),
                listOf("shader = false", "refraction = false"))
        )
        val allKeys = sections.values.flatMap { it.first }

        val targets = listOf(
            java.io.File(Environment.getExternalStorageDirectory(), "tes3mpvr/config/settings.cfg"),
            java.io.File(filesDir, "config/settings.cfg")
        )
        for (f in targets) {
            try {
                f.parentFile?.mkdirs()
                var kept = (if (f.exists()) f.readLines() else emptyList())
                    .filter { line ->
                        val t = line.trim()
                        allKeys.none { k -> t.startsWith("$k =") || t.startsWith("$k=") }
                    }
                for ((name, sec) in sections) {
                    if (sec.second.isNotEmpty() && kept.none { it.trim() == "[$name]" })
                        kept = kept + listOf("[$name]")
                }
                val out = StringBuilder()
                for (line in kept) {
                    out.append(line).append('\n')
                    val t = line.trim()
                    if (t.startsWith("[") && t.endsWith("]")) {
                        sections[t.substring(1, t.length - 1)]?.second
                            ?.forEach { out.append(it).append('\n') }
                    }
                }
                f.writeText(out.toString())
                Log.d(TAG, "VR settings merged into ${f.absolutePath}")
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
            .putString(PREF_CHAR_NAME,   charNameEdit.text.toString().trim())
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
