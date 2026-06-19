package messina.cryptography

expect class EcdhKeyPair {
    val publicKey: ByteArray

    fun sharedKey(peerPublicKey: ByteArray): ByteArray

    companion object {
        fun generate(): EcdhKeyPair
    }
}
