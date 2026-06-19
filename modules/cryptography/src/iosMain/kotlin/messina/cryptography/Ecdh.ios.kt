@file:OptIn(ExperimentalForeignApi::class)

package messina.cryptography

import kotlinx.cinterop.*
import platform.CoreFoundation.*
import platform.Security.*

actual class EcdhKeyPair(private val privateKey: SecKeyRef) {
    actual val publicKey: ByteArray by lazy {
        val pubKey = SecKeyCopyPublicKey(privateKey)
            ?: error("Failed to copy public key")
        try {
            pubKey.toRawBytes()
        } finally {
            CFRelease(pubKey)
        }
    }

    actual fun sharedKey(peerPublicKey: ByteArray): ByteArray {
        val publicKey = importPublicKey(peerPublicKey)
            ?: error("Failed to import peer public key")

        try {
            return computeSharedKey(privateKey, publicKey)
        } finally {
            CFRelease(publicKey)
        }
    }

    actual companion object {
        actual fun generate(): EcdhKeyPair {
            val attributes = CFDictionaryCreateMutable(null, 2, null, null)!!
            CFDictionarySetValue(
                attributes,
                kSecAttrKeyType,
                kSecAttrKeyTypeECSECPrimeRandom
            )
            CFDictionarySetValue(
                attributes,
                kSecAttrKeySizeInBits,
                CFNumberCreate(null, kCFNumberIntType, cValuesOf(256))
            )

            memScoped {
                val errorRef = alloc<CFErrorRefVar>()
                val privateKey = SecKeyCreateRandomKey(attributes, errorRef.ptr)
                    ?: run {
                        val msg = CFErrorCopyDescription(errorRef.value)
                            ?.let { CFStringGetCStringPtr(it, kCFStringEncodingUTF8)?.toKString() }
                        error("Key generation failed: $msg")
                    }
                CFRelease(attributes)
                return EcdhKeyPair(privateKey)
            }
        }
    }
}

private fun SecKeyRef.toRawBytes(): ByteArray = memScoped {
    val errorRef = alloc<CFErrorRefVar>()
    val data = SecKeyCopyExternalRepresentation(this@toRawBytes, errorRef.ptr)
        ?: error("Failed to export key")
    try {
        val length = CFDataGetLength(data).toInt()
        val bytes = CFDataGetBytePtr(data)!!
        ByteArray(length) { bytes[it].toByte() }
    } finally {
        CFRelease(data)
    }
}

private fun importPublicKey(rawBytes: ByteArray): SecKeyRef? {
    val keyData = rawBytes.toCFData()

    val attrs = CFDictionaryCreateMutable(null, 3, null, null)!!
    CFDictionarySetValue(attrs, kSecAttrKeyType, kSecAttrKeyTypeECSECPrimeRandom)
    CFDictionarySetValue(attrs, kSecAttrKeyClass, kSecAttrKeyClassPublic)
    CFDictionarySetValue(
        attrs, kSecAttrKeySizeInBits,
        CFNumberCreate(null, kCFNumberIntType, cValuesOf(256))
    )

    return memScoped {
        val errorRef = alloc<CFErrorRefVar>()
        val key = SecKeyCreateWithData(keyData, attrs, errorRef.ptr)
        CFRelease(attrs)
        CFRelease(keyData)
        key
    }
}

private fun computeSharedKey(privateKey: SecKeyRef, peerPublicKey: SecKeyRef): ByteArray {
    val params = CFDictionaryCreateMutable(null, 1, null, null)!!
    CFDictionarySetValue(
        params,
        kSecKeyKeyExchangeParameterRequestedSize,
        CFNumberCreate(null, kCFNumberIntType, cValuesOf(32))
    )

    return memScoped {
        val errorRef = alloc<CFErrorRefVar>()
        val sharedData = SecKeyCopyKeyExchangeResult(
            privateKey,
            kSecKeyAlgorithmECDHKeyExchangeStandard,  // raw X coordinate, no KDF
            peerPublicKey,
            params,
            errorRef.ptr
        ) ?: run {
            val msg = CFErrorCopyDescription(errorRef.value)
                ?.let { CFStringGetCStringPtr(it, kCFStringEncodingUTF8)?.toKString() }
            error("ECDH key exchange failed: $msg")
        }
        CFRelease(params)

        try {
            val length = CFDataGetLength(sharedData).toInt()
            val bytes = CFDataGetBytePtr(sharedData)!!
            ByteArray(length) { bytes[it].toByte() }
        } finally {
            CFRelease(sharedData)
        }
    }
}

private fun ByteArray.toCFData(): CFDataRef = usePinned { pinned ->
    CFDataCreate(null, pinned.addressOf(0).reinterpret(), size.convert())
        ?: error("CFDataCreate failed")
}