package cloud.nalet.chino.mobile

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toCValues
import kotlinx.cinterop.usePinned
import platform.CoreCrypto.CC_MD5
import platform.CoreCrypto.CC_MD5_DIGEST_LENGTH
import platform.CoreCrypto.CC_SHA256
import platform.CoreCrypto.CC_SHA256_DIGEST_LENGTH
import platform.posix.uint8_tVar

@OptIn(ExperimentalForeignApi::class)
actual fun md5Hex(input: String): String = memScoped {
    val bytes = input.encodeToByteArray()
    val out = allocArray<uint8_tVar>(CC_MD5_DIGEST_LENGTH)
    bytes.usePinned { pinned ->
        CC_MD5(pinned.addressOf(0), bytes.size.toUInt(), out)
    }
    buildString(CC_MD5_DIGEST_LENGTH * 2) {
        for (i in 0 until CC_MD5_DIGEST_LENGTH) {
            val v = out[i].toInt() and 0xFF
            if (v < 16) append('0')
            append(v.toString(16))
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun sha256Hex(input: String): String = memScoped {
    val bytes = input.encodeToByteArray()
    val out = allocArray<uint8_tVar>(CC_SHA256_DIGEST_LENGTH)
    bytes.usePinned { pinned ->
        CC_SHA256(pinned.addressOf(0), bytes.size.toUInt(), out)
    }
    buildString(CC_SHA256_DIGEST_LENGTH * 2) {
        for (i in 0 until CC_SHA256_DIGEST_LENGTH) {
            val v = out[i].toInt() and 0xFF
            if (v < 16) append('0')
            append(v.toString(16))
        }
    }
}
