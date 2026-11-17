package kittoku.osc.activity

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.CheckedTextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.doAfterTextChanged
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executors
import kittoku.osc.LocaleFix
import kittoku.osc.R
import kittoku.osc.databinding.ActivityAppPickerBinding
import kittoku.osc.preference.AppRouting
import kittoku.osc.preference.OscPrefKey
import kittoku.osc.preference.accessor.getSetPrefValue
import kittoku.osc.preference.accessor.setSetPrefValue

class AppPickerActivity : AppCompatActivity() {
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LocaleFix.wrap(newBase))
    }

    private data class App(val pkg: String, val label: String, val icon: Drawable)
    private val prefs by lazy { PreferenceManager.getDefaultSharedPreferences(this) }
    private val selected by lazy { getSetPrefValue(OscPrefKey.ROUTE_SELECTED_APPS, prefs).toMutableSet() }
    private val worker = Executors.newSingleThreadExecutor()
    private val adapter = AppAdapter()
    private lateinit var binding: ActivityAppPickerBinding
    private var apps: List<App>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAppPickerBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.applyTvSafeArea()
        binding.btnBack.setOnClickListener { finish() }
        binding.tvRule.setText(if (AppRouting.read(prefs).mode == AppRouting.Mode.ONLY_SELECTED)
            R.string.tv_only_selected else R.string.tv_bypass_selected)
        binding.rvApps.layoutManager = LinearLayoutManager(this)
        binding.rvApps.adapter = adapter
        binding.rvApps.itemAnimator = null
        binding.etSearch.doAfterTextChanged { filter() }
        // A single-line EditText otherwise consumes Down to move its cursor on some TV keyboards.
        binding.etSearch.setOnKeyListener { _, code, event ->
            code == KeyEvent.KEYCODE_DPAD_DOWN && event.action == KeyEvent.ACTION_DOWN && focusResults()
        }
        binding.etSearch.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                WindowCompat.getInsetsController(window, binding.root).hide(WindowInsetsCompat.Type.ime())
                focusResults()
                true
            } else false
        }

        // Only query launch activities declared in <queries>. Some TV apps have no phone launcher at all.
        worker.execute {
            val pm = packageManager
            val collator = Collator.getInstance(Locale(LocaleFix.language(this)))
            val installed = listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)
                .flatMap { pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(it), 0) }
                .map { it.activityInfo.applicationInfo }
                .distinctBy { it.packageName }
                .filterNot { it.packageName == packageName }
                .map { App(it.packageName, pm.getApplicationLabel(it).toString(), pm.getApplicationIcon(it)) }
                .sortedWith(compareBy(collator) { app: App -> app.label }.thenBy { it.pkg })
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                apps = installed
                filter()
            }
        }
    }

    private fun focusResults(): Boolean = binding.rvApps.getChildAt(0)?.requestFocus() == true

    private fun filter() {
        val installed = apps ?: return
        val query = binding.etSearch.text.toString().trim()
        adapter.rows = installed.filter { it.label.contains(query, true) || it.pkg.contains(query, true) }
        binding.tvLoading.setText(R.string.bs_bp_none)
        binding.tvLoading.visibility = if (adapter.rows.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private class Holder(val row: CheckedTextView) : RecyclerView.ViewHolder(row)

    private inner class AppAdapter : RecyclerView.Adapter<Holder>() {
        var rows: List<App> = emptyList()
            set(value) { field = value; notifyDataSetChanged() }

        override fun getItemCount() = rows.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_selectable_app, parent, false) as CheckedTextView
        )

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val app = rows[position]
            holder.row.apply {
                text = app.label
                val size = resources.getDimensionPixelSize(R.dimen.tv_app_icon)
                app.icon.setBounds(0, 0, size, size)
                setCompoundDrawablesRelative(app.icon, null, null, null)
                isChecked = app.pkg in selected
                setOnClickListener {
                    toggle()
                    if (isChecked) selected.add(app.pkg) else selected.remove(app.pkg)
                    setSetPrefValue(selected.toSet(), OscPrefKey.ROUTE_SELECTED_APPS, prefs)
                }
            }
        }
    }
}
