package io.nekohasekai.sagernet.ktx

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

const val MAX_PROFILE_IMPORT_BYTES = 32 * 1024 * 1024
const val MAX_PROFILE_ZIP_ENTRY_BYTES = 4 * 1024 * 1024
const val MAX_PROFILE_ZIP_ENTRIES = 256
const val MAX_PROFILE_ENTRIES = 20_000
const val MAX_PROFILE_LINK_CHARS = 64 * 1024
const val MAX_ASSET_IMPORT_BYTES = 256L * 1024 * 1024

fun InputStream.readBytesLimited(maxBytes: Int, description: String = "Input"): ByteArray {
    require(maxBytes > 0)
    val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= maxBytes) { "$description is too large" }
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}

fun ByteArray.decodeUtf8Strict(description: String = "Input"): String = try {
    StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(this))
        .toString()
} catch (error: Exception) {
    throw IllegalArgumentException("$description is not valid UTF-8", error)
}

fun InputStream.readUtf8Limited(maxBytes: Int, description: String = "Input"): String =
    readBytesLimited(maxBytes, description).decodeUtf8Strict(description)

fun InputStream.copyToLimited(
    output: OutputStream,
    maxBytes: Long,
    description: String = "Input",
): Long {
    require(maxBytes > 0)
    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
    var total = 0L
    while (true) {
        val count = read(buffer)
        if (count < 0) break
        total += count
        require(total <= maxBytes) { "$description is too large" }
        output.write(buffer, 0, count)
    }
    return total
}
