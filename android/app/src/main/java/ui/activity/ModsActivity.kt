/*
    Copyright (C) 2019 Ilya Zhuravlev

    This file is part of OpenMW-Android.

    OpenMW-Android is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    OpenMW-Android is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with OpenMW-Android.  If not, see <https://www.gnu.org/licenses/>.
*/

package ui.activity

import com.libopenmw.openmw.R

import androidx.appcompat.app.AppCompatActivity
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import com.google.android.material.floatingactionbutton.FloatingActionButton
import android.widget.ViewFlipper
import com.google.android.material.tabs.TabLayout
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import file.GameInstaller
import mods.*
import android.view.MenuItem
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

private const val REQUEST_IMPORT_MOD = 1001

class ModsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_mods)

        setSupportActionBar(findViewById(R.id.mods_toolbar))

        // Enable the "back" icon in the action bar
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Switch tabs between plugins/resources
        val tabLayout = findViewById<TabLayout>(R.id.tabLayout)
        val flipper = findViewById<ViewFlipper>(R.id.flipper)
        tabLayout.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                flipper.displayedChild = tab.position
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {
            }

            override fun onTabReselected(tab: TabLayout.Tab) {
            }
        })

        // Set up adapters for the lists
        setupModList(findViewById(R.id.list_mods), ModType.Plugin)
        setupModList(findViewById(R.id.list_resources), ModType.Resource)
        setupModList(findViewById(R.id.list_groundcover), ModType.Groundcover)

        // FAB: import a mod archive (zip) into the Data Files folder
        findViewById<FloatingActionButton>(R.id.fab_import).setOnClickListener {
            val dataFiles = GameInstaller.getDataFiles(this)
            if (dataFiles.isEmpty() || !File(dataFiles).exists()) {
                Toast.makeText(this, R.string.import_mod_no_data_files, Toast.LENGTH_LONG).show()
            } else {
                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                startActivityForResult(intent, REQUEST_IMPORT_MOD)
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_IMPORT_MOD && resultCode == Activity.RESULT_OK) {
            val uri = data?.data
            if (uri != null) {
                importModFromUri(uri)
            }
        }
    }

    /**
     * Connects a user-interface RecyclerView to underlying mod data on the disk
     * @param list The list displayed to the user
     * @param type Type of the mods this list will contain
     */
    private fun setupModList(list: RecyclerView, type: ModType) {
        val dataFiles = GameInstaller.getDataFiles(this)

        val linearLayoutManager = LinearLayoutManager(this)
        linearLayoutManager.orientation = RecyclerView.VERTICAL
        list.layoutManager = linearLayoutManager

        // Set up the adapter using the specified ModsCollection
        val adapter = ModsAdapter(ModsCollection(type, dataFiles, database))

        // Set up the drag-and-drop callback
        val callback = ModMoveCallback(adapter)
        val touchHelper = ItemTouchHelper(callback)
        touchHelper.attachToRecyclerView(list)

        adapter.touchHelper = touchHelper

        list.adapter = adapter
    }

    /**
     * Extracts a zip archive from the given URI into the Data Files directory.
     * Shows a toast on success/failure and reloads the activity.
     */
    private fun importModFromUri(uri: Uri) {
        val dataFiles = GameInstaller.getDataFiles(this)
        val destDir = File(dataFiles)

        Thread {
            try {
                val inputStream: InputStream = contentResolver.openInputStream(uri)
                    ?: throw Exception("Cannot open file")

                ZipInputStream(inputStream.buffered()).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val name = File(entry.name).name  // flatten to filename only
                            if (name.isNotEmpty()) {
                                val outFile = File(destDir, name)
                                outFile.outputStream().use { out -> zip.copyTo(out) }
                                Log.i("ModsImport", "Extracted: $name")
                            }
                        }
                        zip.closeEntry()
                        entry = zip.nextEntry
                    }
                }

                runOnUiThread {
                    Toast.makeText(this, R.string.import_mod_success, Toast.LENGTH_LONG).show()
                    recreate()  // refresh the mod lists
                }
            } catch (e: Exception) {
                Log.e("ModsImport", "Import failed", e)
                runOnUiThread {
                    Toast.makeText(
                        this,
                        getString(R.string.import_mod_error, e.message ?: "unknown error"),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }.start()
    }

    /**
     * Makes the "back" icon in the actionbar perform the back operation
     */
    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }
}

