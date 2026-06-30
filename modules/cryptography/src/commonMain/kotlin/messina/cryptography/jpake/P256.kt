package messina.cryptography.jpake

import com.ionspin.kotlin.bignum.integer.BigInteger
import com.ionspin.kotlin.bignum.integer.Sign

/**
 * Minimal P-256 (secp256r1 / prime256v1) curve arithmetic over the prime field.
 *
 * EC-JPAKE needs arbitrary-point addition and scalar multiplication by custom
 * scalars, which neither the JCA nor the iOS Security framework expose. This
 * provides exactly those operations in pure common code so the J-PAKE protocol
 * has a single, testable source of truth across platforms.
 *
 * Field arithmetic is mod [P]; scalar arithmetic on points is mod the group
 * order [N].
 */
object P256 {
    private const val FIELD_SIZE = 32

    val P = hex("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFF")
    val A = hex("FFFFFFFF00000001000000000000000000000000FFFFFFFFFFFFFFFFFFFFFFFC")
    val B = hex("5AC635D8AA3A93E7B3EBBD55769886BC651D06B0CC53B0F63BCE3C3E27D2604B")
    val N = hex("FFFFFFFF00000000FFFFFFFFFFFFFFFFBCE6FAADA7179E84F3B9CAC2FC632551")
    private val GX = hex("6B17D1F2E12C4247F8BCE6E563A440F277037D812DEB33A0F4A13945D898C296")
    private val GY = hex("4FE342E2FE1A7F9B8EE7EB4A7C0F9E162BCE33576B315ECECBB6406837BF51F5")

    val generator: EcPoint = EcPoint(GX, GY)

    /** A point on the curve in affine coordinates, or the point at infinity. */
    class EcPoint private constructor(
        val x: BigInteger,
        val y: BigInteger,
        val isInfinity: Boolean,
    ) {
        constructor(x: BigInteger, y: BigInteger) : this(x, y, false)

        operator fun plus(other: EcPoint): EcPoint {
            if (isInfinity) return other
            if (other.isInfinity) return this
            if (x == other.x) {
                return if ((y + other.y).mod(P).isZero()) INFINITY else double()
            }
            val dy = (other.y - y).mod(P)
            val dx = (other.x - x).mod(P)
            val slope = (dy * dx.modInverse(P)).mod(P)
            return fromSlope(slope, other.x)
        }

        operator fun unaryMinus(): EcPoint =
            if (isInfinity) this else EcPoint(x, (P - y).mod(P))

        operator fun minus(other: EcPoint): EcPoint = this + (-other)

        fun double(): EcPoint {
            if (isInfinity || y.isZero()) return INFINITY
            val three = BigInteger.fromInt(3)
            val two = BigInteger.fromInt(2)
            val num = (three * x * x + A).mod(P)
            val den = (two * y).mod(P)
            val slope = (num * den.modInverse(P)).mod(P)
            return fromSlope(slope, x)
        }

        private fun fromSlope(slope: BigInteger, otherX: BigInteger): EcPoint {
            val rx = (slope * slope - x - otherX).mod(P)
            val ry = (slope * (x - rx) - y).mod(P)
            return EcPoint(rx, ry)
        }

        /** Scalar multiplication [k]·this, with [k] reduced mod the group order. */
        operator fun times(k: BigInteger): EcPoint {
            val scalar = k.mod(N)
            var result = INFINITY
            var addend = this
            var bits = scalar
            while (!bits.isZero()) {
                if (bits.and(BigInteger.ONE) == BigInteger.ONE) {
                    result += addend
                }
                addend = addend.double()
                bits = bits.shr(1)
            }
            return result
        }

        /** 64-byte affine encoding: X || Y, each 32 bytes big-endian (no prefix). */
        fun encodeRaw(): ByteArray {
            val out = ByteArray(2 * FIELD_SIZE)
            scalarToBytes(x).copyInto(out, 0)
            scalarToBytes(y).copyInto(out, FIELD_SIZE)
            return out
        }

        /** 65-byte uncompressed encoding: 0x04 || X || Y. */
        fun encodeUncompressed(): ByteArray {
            val out = ByteArray(1 + 2 * FIELD_SIZE)
            out[0] = 0x04
            encodeRaw().copyInto(out, 1)
            return out
        }

        override fun equals(other: Any?): Boolean {
            if (other !is EcPoint) return false
            if (isInfinity || other.isInfinity) return isInfinity == other.isInfinity
            return x == other.x && y == other.y
        }

        override fun hashCode(): Int =
            if (isInfinity) 0 else x.hashCode() * 31 + y.hashCode()

        companion object {
            val INFINITY = EcPoint(BigInteger.ZERO, BigInteger.ZERO, true)
        }
    }

    /** Decode a 64-byte raw (X || Y) affine point. */
    fun decodeRaw(bytes: ByteArray): EcPoint {
        require(bytes.size == 2 * FIELD_SIZE) {
            "Expected a 64-byte raw point (X || Y), got ${bytes.size}"
        }
        val x = bytesToBigInteger(bytes, 0, FIELD_SIZE)
        val y = bytesToBigInteger(bytes, FIELD_SIZE, FIELD_SIZE)
        return EcPoint(x, y)
    }

    /** Decode a 65-byte uncompressed (0x04 || X || Y) point. */
    fun decodeUncompressed(bytes: ByteArray): EcPoint {
        require(bytes.size == 1 + 2 * FIELD_SIZE && bytes[0] == 0x04.toByte()) {
            "Expected a 65-byte uncompressed point (0x04 || X || Y)"
        }
        return decodeRaw(bytes.copyOfRange(1, bytes.size))
    }

    private fun hex(value: String): BigInteger =
        BigInteger.parseString(value, 16)

    private fun bytesToBigInteger(bytes: ByteArray, offset: Int, length: Int): BigInteger =
        BigInteger.fromByteArray(bytes.copyOfRange(offset, offset + length), Sign.POSITIVE)

    /** Big-endian, unsigned, fixed-width ([length], default 32) encoding of a scalar. */
    fun scalarToBytes(value: BigInteger, length: Int = FIELD_SIZE): ByteArray {
        val raw = value.toByteArray() // big-endian magnitude for non-negative values
        return when {
            raw.size == length -> raw
            raw.size > length -> raw.copyOfRange(raw.size - length, raw.size)
            else -> ByteArray(length).also { raw.copyInto(it, length - raw.size) }
        }
    }

    /** Big-endian, unsigned decode of a scalar. */
    fun scalarFromBytes(bytes: ByteArray): BigInteger =
        BigInteger.fromByteArray(bytes, Sign.POSITIVE)
}
