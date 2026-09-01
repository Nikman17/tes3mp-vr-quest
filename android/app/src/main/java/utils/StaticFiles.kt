package utils

import android.content.Context
import android.preference.PreferenceManager
import android.util.Log
import com.libopenmw.openmw.BuildConfig
import constants.Constants
import file.utils.CopyFilesFromAssets
import java.io.File
import java.io.IOException

/**
 * Deploys the per-engine static payload (resources + global config) from APK
 * assets into private storage.
 *
 * Two engines ship in the APK with NON-interchangeable payloads:
 *  - assets/libopenmw    -> TES3MP 0.47 client (multiplayer)
 *  - assets/libopenmw-sp -> vanilla OpenMW-VR (singleplayer)
 *
 * Every entry point that can start the game (launcher prep, VrShell crash
 * relaunch of GameActivity, etc.) must call [ensure] so the deployed payload
 * always matches the engine that is about to load. A stale payload is not
 * cosmetic: e.g. the TES3MP version handshake reads resources/version, so an
 * SP payload under an MP engine yields "Version mismatch!" on connect.
 */
object StaticFiles {

    private const val TAG = "StaticFiles"

    fun isSinglePlayerMode(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getString("tes3mp_mode", "singleplayer") != "multiplayer"

    /**
     * Stamp value for the currently expected payload: app version + engine
     * flavor + APK install time (so every reinstall redeploys — the payload
     * content, e.g. the pinned resources/version handshake hash, can change
     * between builds that share a version code).
     */
    fun stamp(context: Context): String {
        val installed = try {
            context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        } catch (e: Exception) {
            0L
        }
        return "${BuildConfig.VERSION_CODE}-${if (isSinglePlayerMode(context)) "sp" else "mp"}-$installed"
    }

    /** Re-deploys static files when version or engine flavor changed. */
    fun ensure(context: Context) {
        val expected = stamp(context)
        val current = try {
            File(Constants.VERSION_STAMP).readText().trim()
        } catch (e: Exception) {
            ""
        }
        if (current == expected && hasRequiredFiles()) {
            Log.d(TAG, "ensure: payload up to date ($expected)")
            return
        }
        Log.i(TAG, "ensure: redeploying static files ('$current' -> '$expected')")
        reinstall(context)
    }

    // Launcher-generated files living in GLOBAL_CONFIG that must survive a
    // payload redeploy (they are not part of the APK assets).
    private val PRESERVED = listOf("tes3mp-client-default.cfg", "settings.cfg")

    /** Wipes and re-extracts the payload for the active mode; writes the stamp. */
    fun reinstall(context: Context) {
        val saved = PRESERVED.mapNotNull { name ->
            val f = File(Constants.GLOBAL_CONFIG, name)
            if (f.exists()) name to f.readBytes() else null
        }

        remove()

        val assetBase = if (isSinglePlayerMode(context)) "libopenmw-sp" else "libopenmw"
        val copier = CopyFilesFromAssets(context)
        copier.copy("$assetBase/resources", Constants.RESOURCES)
        copier.copy("$assetBase/openmw", Constants.GLOBAL_CONFIG)

        for ((name, bytes) in saved)
            File(Constants.GLOBAL_CONFIG, name).writeBytes(bytes)

        File(Constants.USER_CONFIG).mkdirs()
        val userCfg = File(Constants.USER_OPENMW_CFG)
        if (!userCfg.exists())
            userCfg.writeText("# This is the user openmw.cfg. Feel free to modify it as you wish.\n")

        File(Constants.VERSION_STAMP).writeText(stamp(context))
    }

    /** Removes the deployed payload (also invalidates the stamp). */
    fun remove() {
        File(Constants.VERSION_STAMP).delete()
        File(Constants.GLOBAL_CONFIG).deleteRecursively()
        File(Constants.RESOURCES).deleteRecursively()
    }

    fun hasRequiredFiles(): Boolean {
        val alchemyLayout = File(Constants.RESOURCES, "mygui/openmw_alchemy_window.layout")
        val hasAlchemyFilterEdit = try {
            alchemyLayout.exists() && alchemyLayout.readText().contains("name=\"FilterEdit\"")
        } catch (e: IOException) {
            false
        }

        return File(Constants.OPENMW_BASE_CFG).exists()
            && File(Constants.DEFAULTS_BIN).exists()
            && File(Constants.RESOURCES).exists()
            && File(Constants.RESOURCES, "mygui/core_vr.xml").exists()
            && File(Constants.RESOURCES, "mygui/openmw_layers_vr.xml").exists()
            && File(Constants.RESOURCES, "mygui/openmw_hud_vr.layout").exists()
            && hasAlchemyFilterEdit
            && File(Constants.GLOBAL_CONFIG, "settings-overrides-vr.cfg").exists()
            && File(Constants.GLOBAL_CONFIG, "xrcontrollersuggestions.xml").exists()
    }
}
