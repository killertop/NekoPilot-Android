package moe.matsuri.nb4a.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.EditText
import androidx.core.content.res.TypedArrayUtils
import androidx.core.view.isVisible
import androidx.preference.EditTextPreference
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.DEFAULT_CONNECTION_TEST_CONCURRENCY
import io.nekohasekai.sagernet.MAX_CONNECTION_TEST_CONCURRENCY
import io.nekohasekai.sagernet.MIN_CONNECTION_TEST_CONCURRENCY
import io.nekohasekai.sagernet.database.DataStore

class UrlTestPreference
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = TypedArrayUtils.getAttr(
        context, R.attr.editTextPreferenceStyle,
        android.R.attr.editTextPreferenceStyle
    ),
    defStyleRes: Int = 0
) : EditTextPreference(context, attrs, defStyleAttr, defStyleRes) {

    var concurrent: EditText? = null

    init {
        dialogLayoutResource = R.layout.layout_urltest_preference_dialog

        setOnBindEditTextListener {
            concurrent = it.rootView.findViewById(R.id.edit_concurrent)
            concurrent?.apply {
                setText(DataStore.connectionTestConcurrent.toString())
            }
            it.rootView.findViewById<android.widget.CompoundButton>(R.id.download_test)?.apply {
                isChecked = DataStore.connectionTestDownload
            }
            it.rootView.findViewById<View>(R.id.concurrent_layout)?.isVisible = true
        }

        setOnPreferenceChangeListener { _, _ ->
            concurrent?.apply {
                var newConcurrent = text?.toString()?.toIntOrNull()
                if (newConcurrent == null || newConcurrent <= 0) {
                    newConcurrent = DEFAULT_CONNECTION_TEST_CONCURRENCY
                }
                newConcurrent = newConcurrent.coerceIn(
                    MIN_CONNECTION_TEST_CONCURRENCY,
                    MAX_CONNECTION_TEST_CONCURRENCY,
                )
                DataStore.connectionTestConcurrent = newConcurrent
            }
            concurrent?.rootView?.findViewById<android.widget.CompoundButton>(R.id.download_test)?.let {
                DataStore.connectionTestDownload = it.isChecked
            }
            true
        }
    }

}
