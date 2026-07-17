package io.nekohasekai.sagernet.ktx

import android.app.Activity
import android.content.Context
import android.text.InputType
import android.view.LayoutInflater
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutNumberInputBinding

fun Context.alert(text: String): AlertDialog {
    return MaterialAlertDialogBuilder(this).setTitle(R.string.error_title)
        .setMessage(text)
        .setPositiveButton(android.R.string.ok, null)
        .create()
}

fun Fragment.alert(text: String) = requireContext().alert(text)

fun Context.showNumberInputDialog(
    @StringRes titleRes: Int,
    @StringRes hintRes: Int,
    initialValue: Int,
    @StringRes validationErrorRes: Int,
    min: Int? = null,
    max: Int? = null,
    onValidValue: (Int) -> Unit,
): AlertDialog {
    val binding = LayoutNumberInputBinding.inflate(LayoutInflater.from(this))
    binding.numberInputLayout.hint = getString(hintRes)
    binding.numberInput.apply {
        inputType = InputType.TYPE_CLASS_NUMBER
        setText(initialValue.toString())
        setSelection(text?.length ?: 0)
    }

    val dialog = MaterialAlertDialogBuilder(this)
        .setTitle(titleRes)
        .setView(binding.root)
        .setNegativeButton(android.R.string.cancel, null)
        .setPositiveButton(android.R.string.ok, null)
        .create()

    dialog.setOnShowListener {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val value = binding.numberInput.text?.toString()?.trim()?.toIntOrNull()
            if (value == null || (min != null && value < min) || (max != null && value > max)) {
                binding.numberInputLayout.error = getString(validationErrorRes)
                binding.numberInput.requestFocus()
                return@setOnClickListener
            }

            onValidValue(value)
            dialog.dismiss()
        }
    }
    dialog.show()
    return dialog
}

fun AlertDialog.tryToShow() {
    try {
        val activity = context as Activity
        if (!activity.isFinishing) {
            show()
        }
    } catch (e: Exception) {
        Logs.e(e)
    }
}
