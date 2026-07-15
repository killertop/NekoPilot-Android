package io.nekohasekai.sagernet.widget

import android.content.Context
import android.util.AttributeSet
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnIoDispatcher
import moe.matsuri.nb4a.ui.SimpleMenuPreference

class GroupPreference
@JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = R.attr.dropdownPreferenceStyle
) : SimpleMenuPreference(context, attrs, defStyle, 0) {

    @Volatile
    private var groupNames = emptyMap<Long, String>()

    init {
        runOnIoDispatcher {
            val groups = SagerDatabase.groupDao.allGroups()
            val names = groups.associate { it.id to it.displayName() }
            onMainDispatcher {
                groupNames = names
                entries = groups.map { names.getValue(it.id) }.toTypedArray()
                entryValues = groups.map { "${it.id}" }.toTypedArray()
                notifyChanged()
            }
        }
    }

    override fun getSummary(): CharSequence? {
        if (!value.isNullOrBlank() && value != "0") {
            return value.toLongOrNull()?.let(groupNames::get) ?: super.getSummary()
        }
        return super.getSummary()
    }

}
