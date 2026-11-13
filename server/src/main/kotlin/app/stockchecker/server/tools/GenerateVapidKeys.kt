package app.stockchecker.server.tools

import com.interaso.webpush.VapidKeys
import java.security.interfaces.ECPrivateKey
import java.util.Base64

/** Prints a new VAPID key pair (URL-safe base64, uncompressed point / raw scalar) as .env lines. */
fun main() {
    val keys = VapidKeys.generate()
    val encoder = Base64.getUrlEncoder().withoutPadding()
    println("VAPID_PUBLIC_KEY=${encoder.encodeToString(keys.applicationServerKey)}")
    println("VAPID_PRIVATE_KEY=${encoder.encodeToString(rawPrivate(keys.privateKey))}")
    println("VAPID_SUBJECT=mailto:you@example.com")
}

private fun rawPrivate(key: ECPrivateKey): ByteArray {
    val bytes = key.s.toByteArray()
    return when {
        bytes.size == 32 -> bytes
        bytes.size > 32 -> bytes.copyOfRange(bytes.size - 32, bytes.size)
        else -> ByteArray(32 - bytes.size) + bytes
    }
}
