/*
    Embedded TES3MP server for singleplayer.

    TES3MP has no offline mode - the client always talks to a server. For the
    "Singleplayer" launcher mode we run the arm64 tes3mp-server binary (shipped
    as libtes3mp-server.so) as a child process listening on 127.0.0.1 and point
    the client at it. CoreScripts live on /sdcard so saves persist and users can
    tweak server config.lua like on desktop.
*/

package utils

import android.content.Context
import android.os.Environment
import android.util.Log
import java.io.File

object LocalServer {

    private const val TAG = "LocalServer"
    private const val SERVER_SUBDIR = "tes3mpvr/server"
    private const val STAMP_NAME = ".version_stamp"

    private var process: Process? = null

    fun serverDir(): File = File(Environment.getExternalStorageDirectory(), SERVER_SUBDIR)

    /** Copies assets/server -> /sdcard/tes3mpvr/server when missing or app updated. */
    fun ensureFiles(ctx: Context, versionCode: Int): Boolean {
        val dir = serverDir()
        val stamp = File(dir, STAMP_NAME)
        if (stamp.exists() && stamp.readText().trim() == versionCode.toString())
            return true
        return try {
            copyAssetDir(ctx, "server", dir)
            stamp.writeText(versionCode.toString())
            Log.i(TAG, "Server files deployed to $dir")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to deploy server files", e)
            false
        }
    }

    private fun copyAssetDir(ctx: Context, assetPath: String, dst: File) {
        val entries = ctx.assets.list(assetPath) ?: return
        if (entries.isEmpty()) {
            // it's a file
            dst.parentFile?.mkdirs()
            ctx.assets.open(assetPath).use { input ->
                dst.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        dst.mkdirs()
        for (name in entries)
            copyAssetDir(ctx, "$assetPath/$name", File(dst, name))
    }

    /** Starts the server and waits until the scripts are up (OnServerPostInit). */
    fun start(ctx: Context): String? {
        stop()

        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libtes3mp-server.so")
        if (!bin.exists())
            return "Server binary missing: ${bin.absolutePath}"

        val home = serverDir()
        val logFile = File(home, "local-server.log")
        logFile.delete()

        return try {
            val pb = ProcessBuilder(bin.absolutePath)
                .directory(home)
                .redirectErrorStream(true)
                .redirectOutput(logFile)
            pb.environment()["HOME"] = home.absolutePath
            val proc = pb.start()
            process = proc

            // Server boot is quick (pure Lua CoreScripts); poll the log for readiness
            for (i in 0 until 40) {
                Thread.sleep(250)
                if (!proc.isAlive)
                    return "Server exited early — see ${logFile.absolutePath}"
                if (logFile.exists() && logFile.readText().contains("OnServerPostInit")) {
                    Log.i(TAG, "Local server ready after ${(i + 1) * 250} ms")
                    return null
                }
            }
            if (proc.isAlive) null // running but slow to log; let the client try
            else "Server exited early — see ${logFile.absolutePath}"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start local server", e)
            "Failed to start local server: ${e.message}"
        }
    }

    /** Stops our child (if any) and any orphaned server left from a killed game process. */
    fun stop() {
        process?.destroyForcibly()
        process = null
        killOrphans()
    }

    private fun killOrphans() {
        val proc = File("/proc")
        val myUidPids = proc.listFiles { f -> f.isDirectory && f.name.toIntOrNull() != null } ?: return
        for (p in myUidPids) {
            try {
                val cmdline = File(p, "cmdline").readText()
                if (cmdline.contains("libtes3mp-server.so")) {
                    val pid = p.name.toInt()
                    Log.i(TAG, "Killing orphaned local server pid=$pid")
                    android.os.Process.sendSignal(pid, 9)
                }
            } catch (_: Exception) {
                // other uids' /proc entries are unreadable; ignore
            }
        }
    }
}
