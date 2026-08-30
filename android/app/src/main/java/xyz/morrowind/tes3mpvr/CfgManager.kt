package xyz.morrowind.tes3mpvr

import android.content.Context
import java.io.File

class CfgManager(private val context: Context) {
    val cfgPath = "/sdcard/tes3mp-vr/openmw.cfg"

    data class ModEntry(
        val filename: String,
        var enabled: Boolean,
        val type: ModType
    )
    
    enum class ModType { ESM, ESP, OMWADDON, GRASS }

    fun readDataDirs(): List<String> {
        val dirs = mutableListOf<String>()
        val file = File(cfgPath)
        if (!file.exists()) return dirs
        file.forEachLine { line ->
            if (line.startsWith("data=")) {
                dirs.add(line.substringAfter("data=").replace("\"", ""))
            }
        }
        return dirs
    }

    fun readContentFiles(): List<ModEntry> {
        val files = mutableListOf<ModEntry>()
        val file = File(cfgPath)
        if (!file.exists()) return files
        file.forEachLine { line ->
            if (line.startsWith("content=")) {
                val filename = line.substringAfter("content=").replace("\"", "")
                val type = when {
                    filename.endsWith(".esm", ignoreCase = true) -> ModType.ESM
                    filename.endsWith(".esp", ignoreCase = true) -> ModType.ESP
                    else -> ModType.OMWADDON
                }
                files.add(ModEntry(filename, true, type))
            }
        }
        return files
    }

    fun readBSAFiles(): List<String> {
        val files = mutableListOf<String>()
        val file = File(cfgPath)
        if (!file.exists()) return files
        file.forEachLine { line ->
            if (line.startsWith("fallback-archive=")) {
                files.add(line.substringAfter("fallback-archive=").replace("\"", ""))
            }
        }
        return files
    }

    fun readGroundcover(): List<String> {
        val files = mutableListOf<String>()
        val file = File(cfgPath)
        if (!file.exists()) return files
        file.forEachLine { line ->
            if (line.startsWith("groundcover=")) {
                files.add(line.substringAfter("groundcover=").replace("\"", ""))
            }
        }
        return files
    }

    fun writeConfig(
        dataDirs: List<String>,
        contentFiles: List<ModEntry>,
        bsaFiles: List<String>,
        groundcoverFiles: List<String>
    ) {
        val file = File(cfgPath)
        file.parentFile?.mkdirs()
        
        val content = StringBuilder()
        
        dataDirs.forEach { dir ->
            content.append("data=\"$dir\"\n")
        }
        
        bsaFiles.forEach { bsa ->
            content.append("fallback-archive=$bsa\n")
        }
        
        contentFiles.filter { it.enabled }.forEach { mod ->
            content.append("content=${mod.filename}\n")
        }
        
        groundcoverFiles.forEach { grass ->
            content.append("groundcover=$grass\n")
        }
        
        file.writeText(content.toString())
    }
}
