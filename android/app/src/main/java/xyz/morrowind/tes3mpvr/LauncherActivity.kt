package xyz.morrowind.tes3mpvr

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.libopenmw.openmw.R

class LauncherActivity : AppCompatActivity() {

    private lateinit var cfgManager: CfgManager
    private lateinit var adapter: ModListAdapter
    private lateinit var recyclerView: RecyclerView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_launcher)

        cfgManager = CfgManager(this)
        
        recyclerView = findViewById(R.id.recyclerViewMods)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val mods = cfgManager.readContentFiles().toMutableList()
        adapter = ModListAdapter(mods) {
            saveConfig()
        }
        recyclerView.adapter = adapter

        val touchHelper = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                rv: RecyclerView,
                from: RecyclerView.ViewHolder,
                to: RecyclerView.ViewHolder
            ): Boolean {
                adapter.moveItem(from.adapterPosition, to.adapterPosition)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, dir: Int) {}
        })
        touchHelper.attachToRecyclerView(recyclerView)

        findViewById<Button>(R.id.btnLaunchGame).setOnClickListener {
            saveConfig()
            startActivity(Intent(this, MainActivity::class.java))
        }
        
        findViewById<Button>(R.id.btnServerBrowser).setOnClickListener {
            startActivity(Intent(this, ServerBrowserActivity::class.java))
        }
    }

    private fun saveConfig() {
        cfgManager.writeConfig(
            cfgManager.readDataDirs(),
            adapter.getItems(),
            cfgManager.readBSAFiles(),
            cfgManager.readGroundcover()
        )
    }
}
