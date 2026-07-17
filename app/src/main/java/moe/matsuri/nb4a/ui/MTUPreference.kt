package moe.matsuri.nb4a.ui

import android.content.Context
import android.util.AttributeSet
import androidx.preference.ListPreference
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.showNumberInputDialog

class MTUPreference
@JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = R.attr.dropdownPreferenceStyle
) : ListPreference(context, attrs, defStyle, 0) {

    init {
        setSummaryProvider {
            value.toString()
        }
        dialogLayoutResource = R.layout.layout_mtu_help
    }

    fun showCustomDialog() {
        val initialValue = value?.toIntOrNull()?.takeIf { it in 1000..10000 } ?: 9000
        context.showNumberInputDialog(
            titleRes = R.string.custom_mtu,
            hintRes = R.string.mtu,
            initialValue = initialValue,
            validationErrorRes = R.string.mtu_value_invalid,
            min = 1000,
            max = 10000,
        ) { mtu ->
            val newValue = mtu.toString()
            if (callChangeListener(newValue)) {
                value = newValue
            }
        }
    }
}
