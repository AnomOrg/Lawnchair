/*
 *     This file is part of Lawnchair Launcher.
 *
 *     Lawnchair Launcher is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     Lawnchair Launcher is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with Lawnchair Launcher.  If not, see <https://www.gnu.org/licenses/>.
 */

package ch.deletescape.lawnchair.iconpack

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.support.v7.widget.LinearLayoutManager
import android.support.v7.widget.RecyclerView
import android.text.TextUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import ch.deletescape.lawnchair.LawnchairLauncher
import ch.deletescape.lawnchair.isVisible
import ch.deletescape.lawnchair.lawnchairPrefs
import ch.deletescape.lawnchair.settings.ui.SettingsBaseActivity
import com.android.launcher3.LauncherModel
import com.android.launcher3.R
import com.android.launcher3.util.ComponentKey
import com.android.launcher3.util.LooperExecutor
import kotlinx.android.synthetic.main.activity_new_backup.*
import java.lang.ref.WeakReference

class EditIconActivity : SettingsBaseActivity() {

    private val originalIcon by lazy { findViewById<ImageView>(R.id.originalIcon) }
    private val divider by lazy { findViewById<View>(R.id.divider) }
    private val iconRecyclerView by lazy { findViewById<RecyclerView>(R.id.iconRecyclerView) }
    private val iconPackRecyclerView by lazy { findViewById<RecyclerView>(R.id.iconPackRecyclerView) }
    private val iconPackManager = IconPackManager.getInstance(this)
    private val component by lazy {
        if (intent.hasExtra(EXTRA_COMPONENT)) {
            ComponentKey(intent.getParcelableExtra<ComponentName>(EXTRA_COMPONENT), intent.getParcelableExtra(EXTRA_USER))
        } else null
    }
    private val isFolder by lazy { intent.getBooleanExtra(EXTRA_FOLDER, false) }
    private val iconPacks by lazy {
        listOf(IconPackInfo("")) + iconPackManager.getPackProviders()
                .map { IconPackInfo(it) }.sortedBy { it.title }
    }
    private val iconAdapter by lazy { IconAdapter() }
    private val icons = arrayListOf<AdapterItem>(LoadingItem())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_edit_icon)

        title = intent.getStringExtra(EXTRA_TITLE)

        LooperExecutor(LauncherModel.getUiWorkerLooper()).execute {
            val packs = iconPacks.map { it.getIconPack() }
            runOnUiThread(::bindViews)
            if (isFolder) {
                packs.forEach {
                    it.ensureInitialLoadComplete()
                    it.getAllIcons({
                        list ->
                        // Max 3 icons per pack
                        list.mapNotNull { it as? IconPack.Entry }.take(3).forEach { entry ->
                            runOnUiThread {
                                val item = IconItem(entry, it is DefaultPack, it.displayName)
                                val index = icons.size - 1
                                if (index >= 0) {
                                    icons.add(index, item)
                                    iconAdapter.notifyItemInserted(index)
                                }
                            }
                        }
                    }, {
                        false
                    }, {
                        // Filter for folder icons
                        it.contains("folder", true)
                    })
                }
                runOnUiThread {
                    icons.removeAt(icons.size - 1)
                    iconAdapter.notifyItemRemoved(icons.size)
                }
            } else if (component != null) {
                packs.forEach {
                    it.ensureInitialLoadComplete()
                    val entry = it.getEntryForComponent(component!!)
                    if (entry != null) {
                        runOnUiThread {
                            val item = IconItem(entry, it is DefaultPack, it.displayName)
                            val index = icons.size - 1
                            if (index >= 0) {
                                icons.add(index, item)
                                iconAdapter.notifyItemInserted(index)
                            }
                        }
                    }
                }
                runOnUiThread {
                    icons.removeAt(icons.size - 1)
                    iconAdapter.notifyItemRemoved(icons.size)
                }
            }
        }
    }

    private fun bindViews() {
        originalIcon.setImageDrawable(LawnchairLauncher.currentEditIcon)
        originalIcon.setOnClickListener { onSelectIcon(null) }

        iconPackRecyclerView.adapter = IconPackAdapter()
        iconPackRecyclerView.layoutManager = LinearLayoutManager(this)

        if (component != null) {
            iconRecyclerView.adapter = iconAdapter
            iconRecyclerView.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        } else {
            divider.visibility = View.GONE
            iconRecyclerView.visibility = View.GONE
        }
    }

    fun onSelectIcon(entry: IconPack.Entry?) {
        val customEntry = entry?.toCustomEntry()
        setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_ENTRY, customEntry?.toPackString()))
        finish()
    }

    fun onSelectIconPack(packageName: String) {
        startActivityForResult(IconPickerActivity.newIntent(this, packageName), CODE_PICK_ICON)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == CODE_PICK_ICON && resultCode == Activity.RESULT_OK) {
            val entryString = data?.getStringExtra(EditIconActivity.EXTRA_ENTRY) ?: return
            setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_ENTRY, entryString))
            finish()
        }
    }

    inner class IconAdapter : RecyclerView.Adapter<IconAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return when (viewType) {
                0 -> Holder(LayoutInflater.from(parent.context).inflate(R.layout.icon_suggestion_item, parent, false))
                else -> LoadingHolder(LayoutInflater.from(parent.context).inflate(R.layout.icon_loading, parent, false))
            }
        }

        override fun getItemViewType(position: Int): Int {
            return when (icons[position]) {
                is IconItem -> 0
                else -> 1
            }
        }

        override fun getItemCount() = icons.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(icons[position])
        }

        open inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView), View.OnClickListener {

            init {
                itemView.setOnClickListener(this)
            }

            open fun bind(item: AdapterItem) {
                val entry = (item as IconItem).entry
                try {
                    itemView.visibility = View.VISIBLE
                    (itemView as ImageView).setImageDrawable(entry.drawable)
                } catch (e: Exception) {
                    itemView.visibility = View.GONE
                }
            }

            override fun onClick(v: View) {
                onSelectIcon((icons[adapterPosition] as IconItem).entry)
            }
        }

        inner class LoadingHolder(itemView: View) : Holder(itemView) {

            init {
                itemView.setOnClickListener(null)
            }

            override fun bind(item: AdapterItem) {

            }
        }
    }

    inner class IconPackAdapter : RecyclerView.Adapter<IconPackAdapter.Holder>() {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            return Holder(LayoutInflater.from(parent.context).inflate(R.layout.icon_pack_item, parent, false))
        }

        override fun getItemCount() = iconPacks.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(iconPacks[position])
        }

        inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView), View.OnClickListener {

            private val icon = itemView.findViewById<ImageView>(android.R.id.icon)
            private val title = itemView.findViewById<TextView>(android.R.id.title)
            private val packageName = itemView.findViewById<TextView>(android.R.id.text1)

            init {
                itemView.setOnClickListener(this)
            }

            fun bind(info: IconPackInfo) {
                icon.setImageDrawable(info.icon)
                title.text = info.title
                packageName.isVisible = lawnchairPrefs.showDebugInfo && !TextUtils.isEmpty(info.packageName)
                if (packageName.isVisible) {
                    packageName.text = info.packageName
                }
            }

            override fun onClick(v: View) {
                onSelectIconPack(iconPacks[adapterPosition].packageName)
            }
        }
    }

    inner class IconPackInfo(val name: String) {

        val info = IconPackList.PackInfo.forPackage(this@EditIconActivity, name)

        val icon = info.displayIcon
        val title = info.displayName
        val packageName = info.packageName

        var packRef: WeakReference<IconPack>? = null

        fun getIconPack(): IconPack {
            if (packRef?.get() == null) {
                packRef = WeakReference(iconPackManager.getIconPack(name, true, false))
            }
            return packRef!!.get()!!
        }
    }

    abstract class AdapterItem : Comparable<AdapterItem>

    class IconItem(val entry: IconPack.Entry, val isDefault: Boolean, val title: String) : AdapterItem() {

        override fun compareTo(other: AdapterItem): Int {
            if (other is IconItem) {
                if (isDefault) return -1
                if (other.isDefault) return 1
                return title.compareTo(other.title)
            }
            return -1
        }
    }

    class LoadingItem : AdapterItem() {

        override fun compareTo(other: AdapterItem): Int {
            return 1
        }
    }

    companion object {

        const val CODE_PICK_ICON = 0
        const val EXTRA_ENTRY = "entry"
        const val EXTRA_TITLE = "title"
        const val EXTRA_COMPONENT = "component"
        const val EXTRA_USER = "user"
        const val EXTRA_FOLDER = "is_folder"

        fun newIntent(context: Context, title: String, isFolder: Boolean, componentKey: ComponentKey? = null): Intent {
            return Intent(context, EditIconActivity::class.java).apply {
                putExtra(EXTRA_TITLE, title)
                componentKey?.run {
                    putExtra(EXTRA_COMPONENT, componentName)
                    putExtra(EXTRA_USER, user)
                    putExtra(EXTRA_FOLDER, isFolder)
                }
            }
        }
    }
}
