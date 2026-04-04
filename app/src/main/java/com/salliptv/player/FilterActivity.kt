package com.salliptv.player

import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.salliptv.player.data.AppDatabase
import com.salliptv.player.databinding.ActivityFilterBinding
import com.salliptv.player.parser.CountryDetector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class FilterActivity : AppCompatActivity() {

    private companion object {
        const val TYPE_COUNTRY = 0
        const val TYPE_GROUP = 1
    }

    private lateinit var binding: ActivityFilterBinding
    private lateinit var db: AppDatabase
    private var playlistId: Int = -1
    private var adapter: FilterAdapter? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFilterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        db = AppDatabase.getInstance(this)
        playlistId = intent.getIntExtra("playlistId", -1)

        binding.rvFilters.layoutManager = LinearLayoutManager(this)

        binding.btnShowAll.setOnClickListener { adapter?.checkAll(true) }
        binding.btnInvert.setOnClickListener { adapter?.invertAll() }
        binding.btnApply.setOnClickListener { applyFilters() }

        setupButtonFocus(binding.btnShowAll, 0xFF0C1A2E.toInt(), 0xFF0C2438.toInt())
        setupButtonFocus(binding.btnInvert, 0xFF0C1A2E.toInt(), 0xFF0C2438.toInt())
        setupButtonFocus(binding.btnApply, 0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt())

        loadData()
    }

    private fun setupButtonFocus(btn: TextView, normalColor: Int, focusColor: Int) {
        btn.setOnFocusChangeListener { v, hasFocus ->
            v.animate()
                .scaleX(if (hasFocus) 1.06f else 1f)
                .scaleY(if (hasFocus) 1.06f else 1f)
                .translationZ(if (hasFocus) 8f else 0f)
                .setDuration(200)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        }
    }

    private fun loadData() {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) {
                val prefixes = db.channelDao().getCountryPrefixes(playlistId)
                val result = mutableListOf<FilterRow>()

                for (prefix in prefixes) {
                    val totalCount = db.channelDao().countByPrefix(playlistId, prefix)
                    val groups = db.channelDao().getGroupsByPrefix(playlistId, prefix)
                    val displayName = CountryDetector.getCountryName(prefix)

                    result += FilterRow(
                        type = TYPE_COUNTRY,
                        prefix = prefix,
                        displayName = displayName,
                        channelCount = totalCount,
                        enabled = true,
                        groupCount = groups.size
                    )

                    for (group in groups) {
                        if (group == null) continue
                        val groupCount = db.channelDao().countByPrefixAndGroup(playlistId, prefix, group)
                        result += FilterRow(
                            type = TYPE_GROUP,
                            prefix = prefix,
                            groupName = group,
                            channelCount = groupCount,
                            enabled = true,
                            visible = false
                        )
                    }
                }
                result
            }

            adapter = FilterAdapter(rows)
            binding.rvFilters.adapter = adapter
        }
    }

    private fun applyFilters() {
        val currentAdapter = adapter ?: return
        Toast.makeText(this, getString(R.string.loading), Toast.LENGTH_SHORT).show()

        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                db.channelDao().showAll(playlistId)
                val allRows = currentAdapter.getAllRows()

                for (row in allRows) {
                    if (row.type == TYPE_COUNTRY && !row.enabled) {
                        val hasGroupException = allRows.any { sub ->
                            sub.type == TYPE_GROUP && sub.prefix == row.prefix && sub.enabled
                        }
                        if (!hasGroupException) {
                            db.channelDao().setHiddenByPrefix(playlistId, row.prefix, true)
                        } else {
                            db.channelDao().setHiddenByPrefix(playlistId, row.prefix, true)
                            for (sub in allRows) {
                                if (sub.type == TYPE_GROUP && sub.prefix == row.prefix && sub.enabled) {
                                    db.channelDao().setHiddenByPrefixAndGroup(playlistId, sub.prefix, sub.groupName, false)
                                }
                            }
                        }
                    } else if (row.type == TYPE_COUNTRY && row.enabled) {
                        for (sub in allRows) {
                            if (sub.type == TYPE_GROUP && sub.prefix == row.prefix && !sub.enabled) {
                                db.channelDao().setHiddenByPrefixAndGroup(playlistId, sub.prefix, sub.groupName, true)
                            }
                        }
                    }
                }
            }
            Toast.makeText(this@FilterActivity, getString(R.string.filter_applied), Toast.LENGTH_SHORT).show()
            setResult(RESULT_OK)
            finish()
        }
    }

    // ==========================================
    // FILTER ROW MODEL
    // ==========================================

    data class FilterRow(
        val type: Int,
        val prefix: String = "",
        val displayName: String = "",
        val groupName: String = "",
        val channelCount: Int = 0,
        var enabled: Boolean = true,
        var expanded: Boolean = false,
        var visible: Boolean = true,
        val groupCount: Int = 0
    )

    // ==========================================
    // ADAPTER (2-level: Country → Groups)
    // ==========================================

    inner class FilterAdapter(rows: List<FilterRow>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val allRows: MutableList<FilterRow> = rows.toMutableList()
        private val visibleRows: MutableList<FilterRow> = mutableListOf()

        init {
            rebuildVisible()
        }

        fun getAllRows(): List<FilterRow> = allRows

        private fun rebuildVisible() {
            visibleRows.clear()
            for (row in allRows) {
                if (row.type == TYPE_COUNTRY || row.visible) {
                    visibleRows.add(row)
                }
            }
        }

        fun checkAll(checked: Boolean) {
            for (row in allRows) row.enabled = checked
            for (i in visibleRows.indices) notifyItemChanged(i)
        }

        fun invertAll() {
            for (row in allRows) row.enabled = !row.enabled
            // Sync country state to match its groups
            for (row in allRows) {
                if (row.type == TYPE_COUNTRY) {
                    val subs = allRows.filter { it.type == TYPE_GROUP && it.prefix == row.prefix }
                    val allChecked = subs.all { it.enabled }
                    val allUnchecked = subs.all { !it.enabled }
                    if (allChecked) row.enabled = true
                    else if (allUnchecked) row.enabled = false
                }
            }
            for (i in visibleRows.indices) notifyItemChanged(i)
        }

        private fun toggleExpand(countryRow: FilterRow) {
            val countryPos = visibleRows.indexOf(countryRow).takeIf { it >= 0 } ?: return
            countryRow.expanded = !countryRow.expanded

            var groupCount = 0
            for (row in allRows) {
                if (row.type == TYPE_GROUP && row.prefix == countryRow.prefix) {
                    row.visible = countryRow.expanded
                    groupCount++
                }
            }

            rebuildVisible()
            notifyItemChanged(countryPos)

            if (countryRow.expanded) notifyItemRangeInserted(countryPos + 1, groupCount)
            else notifyItemRangeRemoved(countryPos + 1, groupCount)
        }

        private fun onCountryCheckedChanged(countryRow: FilterRow, checked: Boolean) {
            countryRow.enabled = checked
            val countryPos = visibleRows.indexOf(countryRow)
            if (countryPos >= 0) notifyItemChanged(countryPos)
            for (row in allRows) {
                if (row.type == TYPE_GROUP && row.prefix == countryRow.prefix) {
                    row.enabled = checked
                    val pos = visibleRows.indexOf(row)
                    if (pos >= 0) notifyItemChanged(pos)
                }
            }
        }

        override fun getItemViewType(position: Int): Int = visibleRows[position].type

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            return if (viewType == TYPE_COUNTRY) {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_filter, parent, false)
                CountryViewHolder(view)
            } else {
                val view = LayoutInflater.from(parent.context).inflate(R.layout.item_filter_group, parent, false)
                GroupViewHolder(view)
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val row = visibleRows[position]

            if (row.type == TYPE_COUNTRY) {
                val ch = holder as CountryViewHolder
                val arrow = if (row.expanded) "\u25BC " else "\u25B6 "
                ch.tvPrefix.text = "$arrow${row.displayName}"
                ch.tvDetails.text = "${row.groupCount} groups"
                ch.tvCount.text = row.channelCount.toString()

                ch.cbEnabled.setOnCheckedChangeListener(null)
                ch.cbEnabled.isChecked = row.enabled
                ch.cbEnabled.setOnCheckedChangeListener { _, isChecked ->
                    onCountryCheckedChanged(row, isChecked)
                }

                ch.itemView.setOnClickListener {
                    row.enabled = !row.enabled
                    onCountryCheckedChanged(row, row.enabled)
                }

                ch.itemView.setOnKeyListener { _, keyCode, event ->
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && !row.expanded) {
                            toggleExpand(row); return@setOnKeyListener true
                        }
                        if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT && row.expanded) {
                            toggleExpand(row); return@setOnKeyListener true
                        }
                    }
                    false
                }

                ch.itemView.setOnFocusChangeListener { v, hasFocus ->
                    v.animate()
                        .scaleX(if (hasFocus) 1.05f else 1f)
                        .scaleY(if (hasFocus) 1.05f else 1f)
                        .translationZ(if (hasFocus) 6f else 0f)
                        .setDuration(200)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                }

            } else {
                val gh = holder as GroupViewHolder
                gh.tvGroupName.text = row.groupName
                gh.tvCount.text = row.channelCount.toString()

                gh.cbEnabled.setOnCheckedChangeListener(null)
                gh.cbEnabled.isChecked = row.enabled
                gh.cbEnabled.setOnCheckedChangeListener { _, isChecked ->
                    row.enabled = isChecked
                }

                gh.itemView.setOnClickListener {
                    row.enabled = !row.enabled
                    gh.cbEnabled.isChecked = row.enabled
                }

                gh.itemView.setOnFocusChangeListener { v, hasFocus ->
                    v.animate()
                        .scaleX(if (hasFocus) 1.05f else 1f)
                        .scaleY(if (hasFocus) 1.05f else 1f)
                        .translationZ(if (hasFocus) 6f else 0f)
                        .setDuration(200)
                        .setInterpolator(android.view.animation.DecelerateInterpolator())
                        .start()
                }
            }
        }

        override fun getItemCount(): Int = visibleRows.size

        inner class CountryViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cbEnabled: CheckBox = view.findViewById(R.id.cb_enabled)
            val tvPrefix: TextView = view.findViewById(R.id.tv_prefix)
            val tvDetails: TextView = view.findViewById(R.id.tv_details)
            val tvCount: TextView = view.findViewById(R.id.tv_count)
        }

        inner class GroupViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val cbEnabled: CheckBox = view.findViewById(R.id.cb_enabled)
            val tvGroupName: TextView = view.findViewById(R.id.tv_group_name)
            val tvCount: TextView = view.findViewById(R.id.tv_count)
        }
    }
}
