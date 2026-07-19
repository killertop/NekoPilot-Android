/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package moe.matsuri.nb4a.ui

import android.content.Context
import android.util.AttributeSet
import androidx.preference.ListPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * A list preference presented as a Material single-choice dialog.
 *
 * The previous spinner implementation anchored its popup to an invisible, wrap-content
 * view at the left edge of preference cards. Besides looking detached from the selected
 * setting, long translations could cover unrelated controls. A dialog gives every list
 * setting the same readable and accessible interaction surface.
 */


open class SimpleMenuPreference
@JvmOverloads constructor(
    context: Context?,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = androidx.preference.R.attr.dialogPreferenceStyle,
    defStyleRes: Int = 0
) : ListPreference(context!!, attrs, defStyleAttr, defStyleRes) {

    override fun onClick() {
        val choices = entries ?: return
        val values = entryValues ?: return
        MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .setSingleChoiceItems(choices, findIndexOfValue(value)) { dialog, which ->
                if (which in values.indices) {
                    val selectedValue = values[which].toString()
                    if (callChangeListener(selectedValue)) value = selectedValue
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
