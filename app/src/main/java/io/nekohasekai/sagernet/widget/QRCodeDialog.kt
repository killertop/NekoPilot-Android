package io.nekohasekai.sagernet.widget

import android.app.Dialog
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.databinding.LayoutQrCodeDialogBinding
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ui.MainActivity
import java.nio.charset.StandardCharsets

class QRCodeDialog() : DialogFragment() {

    companion object {
        private const val KEY_URL = "io.nekohasekai.sagernet.QRCodeDialog.KEY_URL"
        private const val KEY_NAME = "io.nekohasekai.sagernet.QRCodeDialog.KEY_NAME"
        private val iso88591 = StandardCharsets.ISO_8859_1.newEncoder()
    }

    constructor(url: String, displayName: String) : this() {
        arguments = bundleOf(
            Pair(KEY_URL, url), Pair(KEY_NAME, displayName)
        )
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val url = requireArguments().getString(KEY_URL).orEmpty()
        val displayName = requireArguments().getString(KEY_NAME).orEmpty()
            .ifBlank { getString(R.string.app_name) }

        return runCatching {
            val size = resources.getDimensionPixelSize(R.dimen.qrcode_size)
            val binding = LayoutQrCodeDialogBinding.inflate(layoutInflater).apply {
                qrName.text = displayName
                qrDescription.text = getString(R.string.qr_share_description)
                qrCode.contentDescription = getString(R.string.qr_code_content_description, displayName)
                qrCode.setImageBitmap(createQrBitmap(url, size))
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.qr_share_title)
                .setView(binding.root)
                .setNeutralButton(R.string.action_copy) { _, _ ->
                    val copied = SagerNet.trySetPrimaryClip(url)
                    (activity as? MainActivity)?.snackbar(
                        if (copied) R.string.copy_success else R.string.copy_failed
                    )?.show()
                }
                .setPositiveButton(R.string.action_close, null)
                .create()
        }.getOrElse { error ->
            Logs.w(error)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.error_title)
                .setMessage(error.readableMessage)
                .setPositiveButton(android.R.string.ok, null)
                .create()
        }
    }

    /**
     * Based on:
     * https://android.googlesource.com/platform/
     * packages/apps/Settings/+/0d706f0/src/com/android/settings/wifi/qrcode/QrCodeGenerator.java
     */
    private fun createQrBitmap(url: String, size: Int): Bitmap {
        val hints = mutableMapOf<EncodeHintType, Any>()
        if (!iso88591.canEncode(url)) hints[EncodeHintType.CHARACTER_SET] = StandardCharsets.UTF_8.name()
        val qrBits = MultiFormatWriter().encode(url, BarcodeFormat.QR_CODE, size, size, hints)
        val pixels = IntArray(size * size) { index ->
            val x = index % size
            val y = index / size
            if (qrBits.get(x, y)) Color.BLACK else Color.WHITE
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.RGB_565)
    }
}
