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
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.RadioButton
import android.widget.TextView
import androidx.preference.ListPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R

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

    private val entrySummaries: Array<CharSequence>?

    init {
        val typedArray = context!!.obtainStyledAttributes(
            attrs,
            R.styleable.SimpleMenuPreference,
            defStyleAttr,
            defStyleRes,
        )
        entrySummaries = typedArray.getTextArray(R.styleable.SimpleMenuPreference_entrySummaries)
        typedArray.recycle()
    }

    override fun onClick() {
        val choices = entries ?: return
        val values = entryValues ?: return
        val summaries = entrySummaries
        val selectedIndex = findIndexOfValue(value)
        val builder = MaterialAlertDialogBuilder(context).setTitle(title)

        if (summaries != null && summaries.size >= choices.size) {
            builder.setAdapter(ChoiceSummaryAdapter(context, choices, summaries, selectedIndex)) { dialog, which ->
                if (which in values.indices) {
                    val selectedValue = values[which].toString()
                    if (callChangeListener(selectedValue)) value = selectedValue
                }
                dialog.dismiss()
            }
        } else {
            builder.setSingleChoiceItems(choices, selectedIndex) { dialog, which ->
                if (which in values.indices) {
                    val selectedValue = values[which].toString()
                    if (callChangeListener(selectedValue)) value = selectedValue
                }
                dialog.dismiss()
            }
        }

        builder.setNegativeButton(android.R.string.cancel, null).show()
    }

    private class ChoiceSummaryAdapter(
        context: Context,
        private val choices: Array<CharSequence>,
        private val summaries: Array<CharSequence>,
        private val selectedIndex: Int,
    ) : BaseAdapter() {

        private val inflater = LayoutInflater.from(context)

        override fun getCount() = choices.size

        override fun getItem(position: Int) = choices[position]

        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: inflater.inflate(R.layout.simple_menu_choice_item, parent, false)
            view.findViewById<RadioButton>(R.id.choice_radio).apply {
                isChecked = position == selectedIndex
                isClickable = false
                isFocusable = false
            }
            view.findViewById<TextView>(R.id.choice_title).text = choices[position]
            view.findViewById<TextView>(R.id.choice_summary).text = summaries[position]
            return view
        }
    }
}
