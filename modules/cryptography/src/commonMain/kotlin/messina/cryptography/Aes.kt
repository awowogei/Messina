package messina.cryptography

/**
 * AES in ECB mode with no padding. [data] length must be a non-zero multiple of
 * the 16-byte block size; [key] must be 16, 24, or 32 bytes.
 *
 * ECB is a low-level building block with no diffusion across blocks; callers are
 * responsible for using it safely (e.g. as a single-block primitive).
 */
expect fun aesEcbEncrypt(key: ByteArray, data: ByteArray): ByteArray
