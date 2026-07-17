package io.nekohasekai.sagernet.ktx

import android.os.Bundle
import android.os.Parcelable
import androidx.core.os.BundleCompat

@Suppress("UNCHECKED_CAST")
fun <T : Parcelable> Bundle.getParcelableCompat(key: String): T? =
    BundleCompat.getParcelable(this, key, Parcelable::class.java) as? T
