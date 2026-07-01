package messina.cryptography

/** Fill a new [size]-byte array with cryptographically secure random bytes. */
expect fun secureRandomBytes(size: Int): ByteArray
