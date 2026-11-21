package com.arch.tv

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.util.concurrent.Executors

data class AppItem(
    val label: String,
    val pkg: String,
    val launch: Intent,
    val icon: Drawable
)

class MainActivity : Activity() {

    private lateinit var grid: RecyclerView
    private val adapter = AppAdapter(
        onClick = { launchApp(it) },
        onLongClick = { showAppInfo(it) }
    )
    private val worker = Executors.newSingleThreadExecutor()

    // Refresh the list whenever an app is installed or removed
    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = loadApps()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        grid = findViewById(R.id.grid)
        grid.layoutManager = GridLayoutManager(this, 6)
        grid.adapter = adapter
        grid.setHasFixedSize(true)

        findViewById<View>(R.id.settings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_EXPORTED)

        loadApps()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        // Pressing Home while already here: jump back to the top of the grid
        grid.scrollToPosition(0)
        grid.post { grid.getChildAt(0)?.requestFocus() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // A launcher should never close itself
    }

    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        worker.shutdown()
        super.onDestroy()
    }

    private fun loadApps() {
        worker.execute {
            val pm = packageManager
            val tvApps = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0
            )
            val otherApps = pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0
            )

            val seen = HashSet<String>()
            val items = ArrayList<AppItem>()
            for (ri in tvApps + otherApps) {
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName || !seen.add(pkg)) continue
                val intent = pm.getLeanbackLaunchIntentForPackage(pkg)
                    ?: pm.getLaunchIntentForPackage(pkg)
                    ?: continue
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                items += AppItem(ri.loadLabel(pm).toString(), pkg, intent, ri.loadIcon(pm))
            }
            items.sortBy { it.label.lowercase() }

            runOnUiThread {
                adapter.submit(items)
                grid.post { grid.getChildAt(0)?.requestFocus() }
            }
        }
    }

    private fun launchApp(item: AppItem) {
        try {
            startActivity(item.launch)
        } catch (_: Exception) {
            // App vanished or can't be opened; ignore
        }
    }

    // Long-press an app to open its info page (clear data, uninstall, etc.)
    private fun showAppInfo(item: AppItem) {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${item.pkg}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

class AppAdapter(
    private val onClick: (AppItem) -> Unit,
    private val onLongClick: (AppItem) -> Unit
) : RecyclerView.Adapter<AppAdapter.VH>() {

    private var items: List<AppItem> = emptyList()

    fun submit(newItems: List<AppItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(R.id.icon)
        val label: TextView = view.findViewById(R.id.label)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
        v.setOnFocusChangeListener { view, hasFocus ->
            val s = if (hasFocus) 1.08f else 1f
            view.animate().scaleX(s).scaleY(s).setDuration(120).start()
        }
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.icon.setImageDrawable(item.icon)
        holder.label.text = item.label
        holder.itemView.setOnClickListener { onClick(item) }
        holder.itemView.setOnLongClickListener { onLongClick(item); true }
    }

    override fun getItemCount() = items.size
}
