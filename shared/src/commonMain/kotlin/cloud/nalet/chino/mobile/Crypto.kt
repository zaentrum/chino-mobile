package cloud.nalet.chino.mobile

/**
 * MD5 hex digest for Gravatar URLs. Spec at
 * https://en.gravatar.com/site/implement/hash/ — lowercase trimmed email,
 * MD5, hex. Defined as expect/actual because there's no stable common
 * crypto API on KMP yet (kotlinx.io doesn't ship hashes; okio's
 * ByteString.md5() is available but pulling okio everywhere just for one
 * hash is overkill — platform native is fine).
 */
expect fun md5Hex(input: String): String

/**
 * SHA-256 hex digest (lowercase) — feeds the bug-report fingerprint so the
 * server can deduplicate auto-filed error/crash/player reports. Same
 * expect/actual rationale as [md5Hex]: platform-native hashing beats pulling
 * a crypto dependency into commonMain for one function.
 */
expect fun sha256Hex(input: String): String
