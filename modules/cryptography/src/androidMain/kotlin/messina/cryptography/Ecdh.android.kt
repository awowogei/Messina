package messina.cryptography

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement

private const val CURVE_NAME = "secp256r1"
private const val FIELD_SIZE = 32

private val p256Params: ECParameterSpec =
    AlgorithmParameters.getInstance("EC").run {
        init(ECGenParameterSpec(CURVE_NAME))
        getParameterSpec(ECParameterSpec::class.java)
    }

actual class EcdhKeyPair internal constructor(private val keyPair: KeyPair) {
    actual val publicKey: ByteArray
        get() {
            val point = (keyPair.public as ECPublicKey).w
            val out = ByteArray(1 + 2 * FIELD_SIZE)
            out[0] = 0x04
            point.affineX.toFixedBytes().copyInto(out, 1)
            point.affineY.toFixedBytes().copyInto(out, 1 + FIELD_SIZE)
            return out
        }

    actual fun sharedKey(peerPublicKey: ByteArray): ByteArray {
        val agreement = KeyAgreement.getInstance("ECDH").apply {
            init(keyPair.private)
            doPhase(decodePublicKey(peerPublicKey), true)
        }
        return agreement.generateSecret()
    }

    actual companion object {
        actual fun generate(): EcdhKeyPair {
            val generator = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec(CURVE_NAME))
            }
            return EcdhKeyPair(generator.generateKeyPair())
        }
    }
}

private fun decodePublicKey(encoded: ByteArray): ECPublicKey {
    require(encoded.size == 1 + 2 * FIELD_SIZE && encoded[0] == 0x04.toByte()) {
        "Expected a 65-byte uncompressed P-256 point (0x04 || X || Y)"
    }
    val x = BigInteger(1, encoded.copyOfRange(1, 1 + FIELD_SIZE))
    val y = BigInteger(1, encoded.copyOfRange(1 + FIELD_SIZE, encoded.size))
    val spec = ECPublicKeySpec(ECPoint(x, y), p256Params)
    return KeyFactory.getInstance("EC").generatePublic(spec) as ECPublicKey
}

private fun BigInteger.toFixedBytes(): ByteArray {
    val raw = toByteArray()
    return when (raw.size) {
        FIELD_SIZE -> raw
        // Drop the leading 0x00 sign byte BigInteger adds for high-bit values.
        FIELD_SIZE + 1 if raw[0] == 0.toByte() -> raw.copyOfRange(1, raw.size)
        // Left-pad shorter values with zeros.
        else -> ByteArray(FIELD_SIZE).also { raw.copyInto(it, FIELD_SIZE - raw.size) }
    }
}
