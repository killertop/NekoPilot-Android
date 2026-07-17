package io.nekohasekai.sagernet.ktx

import android.os.Parcel
import android.os.Parcelable
import com.esotericsoftware.kryo.io.ByteBufferInput
import com.esotericsoftware.kryo.io.ByteBufferOutput
import java.io.InputStream
import java.io.OutputStream

private const val MAX_KRYO_FIELD_BYTES = 8 * 1024 * 1024
private const val MAX_KRYO_COLLECTION_ITEMS = 1_000_000

private class SafeByteBufferInput(input: InputStream) : ByteBufferInput(input) {
    private fun validateLength(length: Int, bytesPerItem: Int = 1) {
        require(length in 0..MAX_KRYO_COLLECTION_ITEMS) { "Serialized collection is too large" }
        val requiredBytes = length.toLong() * bytesPerItem
        require(requiredBytes <= available().toLong()) { "Serialized data is truncated" }
    }

    override fun readBytes(length: Int): ByteArray {
        require(length in 0..MAX_KRYO_FIELD_BYTES) { "Serialized byte field is too large" }
        require(length <= available()) { "Serialized data is truncated" }
        return super.readBytes(length)
    }

    override fun readVarIntFlag(optimizePositive: Boolean): Int {
        val encodedLength = super.readVarIntFlag(optimizePositive)
        require(encodedLength in 0..(MAX_KRYO_FIELD_BYTES + 1)) {
            "Serialized string is too large"
        }
        if (encodedLength > 1) {
            require(encodedLength - 1 <= available()) { "Serialized string is truncated" }
        }
        return encodedLength
    }

    override fun readString(): String? = super.readString()?.also {
        require(it.length <= MAX_KRYO_FIELD_BYTES) { "Serialized string is too large" }
    }

    override fun readInts(length: Int): IntArray =
        super.readInts(length.also { validateLength(it, Int.SIZE_BYTES) })

    override fun readLongs(length: Int): LongArray =
        super.readLongs(length.also { validateLength(it, Long.SIZE_BYTES) })

    override fun readFloats(length: Int): FloatArray =
        super.readFloats(length.also { validateLength(it, Float.SIZE_BYTES) })

    override fun readDoubles(length: Int): DoubleArray =
        super.readDoubles(length.also { validateLength(it, Double.SIZE_BYTES) })

    override fun readShorts(length: Int): ShortArray =
        super.readShorts(length.also { validateLength(it, Short.SIZE_BYTES) })

    override fun readChars(length: Int): CharArray =
        super.readChars(length.also { validateLength(it, Char.SIZE_BYTES) })

    override fun readBooleans(length: Int): BooleanArray =
        super.readBooleans(length.also(::validateLength))
}

fun InputStream.byteBuffer(): ByteBufferInput = SafeByteBufferInput(this)
fun OutputStream.byteBuffer() = ByteBufferOutput(this)

fun ByteBufferInput.readStringList(): List<String> {
    val size = readInt()
    require(size in 0..MAX_KRYO_COLLECTION_ITEMS) { "Serialized list is too large" }
    return mutableListOf<String>().apply {
        repeat(size) {
            add(readString())
        }
    }
}

fun ByteBufferInput.readStringSet(): Set<String> {
    val size = readInt()
    require(size in 0..MAX_KRYO_COLLECTION_ITEMS) { "Serialized set is too large" }
    return linkedSetOf<String>().apply {
        repeat(size) {
            add(readString())
        }
    }
}


fun ByteBufferOutput.writeStringList(list: List<String>) {
    writeInt(list.size)
    for (str in list) writeString(str)
}

fun ByteBufferOutput.writeStringList(list: Set<String>) {
    writeInt(list.size)
    for (str in list) writeString(str)
}

fun Parcelable.marshall(): ByteArray {
    val parcel = Parcel.obtain()
    writeToParcel(parcel, 0)
    val bytes = parcel.marshall()
    parcel.recycle()
    return bytes
}

fun ByteArray.unmarshall(): Parcel {
    val parcel = Parcel.obtain()
    parcel.unmarshall(this, 0, size)
    parcel.setDataPosition(0) // This is extremely important!
    return parcel
}

fun <T> ByteArray.unmarshall(constructor: (Parcel) -> T): T {
    val parcel = unmarshall()
    val result = constructor(parcel)
    parcel.recycle()
    return result
}
