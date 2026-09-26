package com.ds.localapi.core

import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * DeepSeekHashV1 Proof-of-Work solver.
 *
 * Ported from the public reverse-engineering reference (Go) implementation:
 * DeepSeekHashV1 = SHA3-256 with Keccak-f[1600] running rounds 1..23 (round 0 skipped),
 * rate = 136 bytes, output 32 bytes.
 */
object Pow {

    /**
     * Kotlin 不允许直接写高 bit 置位的 64 位十六进制 Long 字面量（会报 out of range），
     * 因此 Keccak 轮常数用两个 32 位半字拼出。
     */
    private fun u64(hi: Long, lo: Long): Long =
        (hi shl 32) or (lo and 0xFFFFFFFFL)

    private val RC = longArrayOf(
        u64(0x00000000L, 0x00000001L), u64(0x00000000L, 0x00008082L),
        u64(0x80000000L, 0x0000808AL), u64(0x80000000L, 0x80008000L),
        u64(0x00000000L, 0x0000808BL), u64(0x00000000L, 0x80000001L),
        u64(0x80000000L, 0x80008081L), u64(0x80000000L, 0x00008009L),
        u64(0x00000000L, 0x0000008AL), u64(0x00000000L, 0x00000088L),
        u64(0x00000000L, 0x80008009L), u64(0x00000000L, 0x8000000AL),
        u64(0x00000000L, 0x8000808BL), u64(0x80000000L, 0x0000008BL),
        u64(0x80000000L, 0x00008089L), u64(0x80000000L, 0x00008003L),
        u64(0x80000000L, 0x00008002L), u64(0x80000000L, 0x00000080L),
        u64(0x00000000L, 0x0000800AL), u64(0x80000000L, 0x8000000AL),
        u64(0x80000000L, 0x80008081L), u64(0x80000000L, 0x00008080L),
        u64(0x00000000L, 0x80000001L), u64(0x80000000L, 0x80008008L)
    )

    private fun rotl(v: Long, k: Int): Long = (v shl k) or (v ushr (64 - k))

    private fun keccakF23(s: LongArray) {
        var a0 = s[0]; var a1 = s[1]; var a2 = s[2]; var a3 = s[3]; var a4 = s[4]
        var a5 = s[5]; var a6 = s[6]; var a7 = s[7]; var a8 = s[8]; var a9 = s[9]
        var a10 = s[10]; var a11 = s[11]; var a12 = s[12]; var a13 = s[13]; var a14 = s[14]
        var a15 = s[15]; var a16 = s[16]; var a17 = s[17]; var a18 = s[18]; var a19 = s[19]
        var a20 = s[20]; var a21 = s[21]; var a22 = s[22]; var a23 = s[23]; var a24 = s[24]

        for (r in 1 until 24) {
            val c0 = a0 xor a5 xor a10 xor a15 xor a20
            val c1 = a1 xor a6 xor a11 xor a16 xor a21
            val c2 = a2 xor a7 xor a12 xor a17 xor a22
            val c3 = a3 xor a8 xor a13 xor a18 xor a23
            val c4 = a4 xor a9 xor a14 xor a19 xor a24

            val d0 = c4 xor rotl(c1, 1)
            val d1 = c0 xor rotl(c2, 1)
            val d2 = c1 xor rotl(c3, 1)
            val d3 = c2 xor rotl(c4, 1)
            val d4 = c3 xor rotl(c0, 1)

            a0 = a0 xor d0; a5 = a5 xor d0; a10 = a10 xor d0; a15 = a15 xor d0; a20 = a20 xor d0
            a1 = a1 xor d1; a6 = a6 xor d1; a11 = a11 xor d1; a16 = a16 xor d1; a21 = a21 xor d1
            a2 = a2 xor d2; a7 = a7 xor d2; a12 = a12 xor d2; a17 = a17 xor d2; a22 = a22 xor d2
            a3 = a3 xor d3; a8 = a8 xor d3; a13 = a13 xor d3; a18 = a18 xor d3; a23 = a23 xor d3
            a4 = a4 xor d4; a9 = a9 xor d4; a14 = a14 xor d4; a19 = a19 xor d4; a24 = a24 xor d4

            val b0 = a0
            val b10 = rotl(a1, 1)
            val b20 = rotl(a2, 62)
            val b5 = rotl(a3, 28)
            val b15 = rotl(a4, 27)
            val b16 = rotl(a5, 36)
            val b1 = rotl(a6, 44)
            val b11 = rotl(a7, 6)
            val b21 = rotl(a8, 55)
            val b6 = rotl(a9, 20)
            val b7 = rotl(a10, 3)
            val b17 = rotl(a11, 10)
            val b2 = rotl(a12, 43)
            val b12 = rotl(a13, 25)
            val b22 = rotl(a14, 39)
            val b23 = rotl(a15, 41)
            val b8 = rotl(a16, 45)
            val b18 = rotl(a17, 15)
            val b3 = rotl(a18, 21)
            val b13 = rotl(a19, 8)
            val b14 = rotl(a20, 18)
            val b24 = rotl(a21, 2)
            val b9 = rotl(a22, 61)
            val b19 = rotl(a23, 56)
            val b4 = rotl(a24, 14)

            a0 = b0 xor (b1.inv() and b2)
            a1 = b1 xor (b2.inv() and b3)
            a2 = b2 xor (b3.inv() and b4)
            a3 = b3 xor (b4.inv() and b0)
            a4 = b4 xor (b0.inv() and b1)
            a5 = b5 xor (b6.inv() and b7)
            a6 = b6 xor (b7.inv() and b8)
            a7 = b7 xor (b8.inv() and b9)
            a8 = b8 xor (b9.inv() and b5)
            a9 = b9 xor (b5.inv() and b6)
            a10 = b10 xor (b11.inv() and b12)
            a11 = b11 xor (b12.inv() and b13)
            a12 = b12 xor (b13.inv() and b14)
            a13 = b13 xor (b14.inv() and b10)
            a14 = b14 xor (b10.inv() and b11)
            a15 = b15 xor (b16.inv() and b17)
            a16 = b16 xor (b17.inv() and b18)
            a17 = b17 xor (b18.inv() and b19)
            a18 = b18 xor (b19.inv() and b15)
            a19 = b19 xor (b15.inv() and b16)
            a20 = b20 xor (b21.inv() and b22)
            a21 = b21 xor (b22.inv() and b23)
            a22 = b22 xor (b23.inv() and b24)
            a23 = b23 xor (b24.inv() and b20)
            a24 = b24 xor (b20.inv() and b21)

            a0 = a0 xor RC[r]
        }

        s[0] = a0; s[1] = a1; s[2] = a2; s[3] = a3; s[4] = a4
        s[5] = a5; s[6] = a6; s[7] = a7; s[8] = a8; s[9] = a9
        s[10] = a10; s[11] = a11; s[12] = a12; s[13] = a13; s[14] = a14
        s[15] = a15; s[16] = a16; s[17] = a17; s[18] = a18; s[19] = a19
        s[20] = a20; s[21] = a21; s[22] = a22; s[23] = a23; s[24] = a24
    }

    private fun leU64(bytes: ByteArray, off: Int): Long {
        var v = 0L
        for (i in 0 until 8) {
            v = v or ((bytes[off + i].toLong() and 0xFF) shl (8 * i))
        }
        return v
    }

    private fun putLE64(out: ByteArray, off: Int, value: Long) {
        for (i in 0 until 8) {
            out[off + i] = ((value ushr (8 * i)) and 0xFF).toByte()
        }
    }

    /** Returns the 32-byte DeepSeekHashV1 digest of [data]. */
    fun deepSeekHashV1(data: ByteArray): ByteArray {
        val rate = 136
        val s = LongArray(25)
        var off = 0
        while (off + rate <= data.size) {
            for (i in 0 until rate / 8) s[i] = s[i] xor leU64(data, off + i * 8)
            keccakF23(s)
            off += rate
        }

        val final = ByteArray(rate)
        val rem = data.size - off
        System.arraycopy(data, off, final, 0, rem)
        final[rem] = 0x06
        final[rate - 1] = (final[rate - 1].toInt() or 0x80).toByte()
        for (i in 0 until rate / 8) s[i] = s[i] xor leU64(final, i * 8)
        keccakF23(s)

        val out = ByteArray(32)
        putLE64(out, 0, s[0])
        putLE64(out, 8, s[1])
        putLE64(out, 16, s[2])
        putLE64(out, 24, s[3])
        return out
    }

    /**
     * 求解 PoW：在 [0, max) 内搜索 nonce，使得
     *   DeepSeekHashV1( challenge_bytes + utf8("{salt}_{expire_at}_{nonce}") )
     * 的前 4 字节按小端 u32 解读后 < (2^32 / difficulty)。
     *
     * 对齐参考实现（pow_native.py / 官方 WASM DeepSeekHashV1）：
     *  - challenge（hex 解码后的 32 字节）参与哈希前缀；
     *  - 判定条件是「前 4 字节小端值 < 阈值」，不是与 challenge 相等；
     *  - difficulty 默认 144000 → 阈值 ≈ 29826，期望 ~3 万次命中。
     */
    fun solvePow(challengeHex: String, salt: String, expireAt: Long, difficultyParam: Long): Long {
        require(challengeHex.length == 64) { "pow: challenge must be 64 hex chars" }
        val difficulty = if (difficultyParam <= 0L) 144000L else difficultyParam
        val threshold = 4_294_967_296L / difficulty // 2^32 / difficulty

        val prefix = hexDecode(challengeHex) + (salt + "_" + expireAt + "_").toByteArray(StandardCharsets.UTF_8)
        // 复用缓冲区：prefix + 最长 10 位十进制 nonce
        val buf = ByteArray(prefix.size + 10)
        System.arraycopy(prefix, 0, buf, 0, prefix.size)
        val max = 10_000_000L
        var n = 0L
        while (n < max) {
            // 写入十进制 nonce
            var v = n
            var pos = buf.size
            if (v == 0L) {
                pos--
                buf[pos] = '0'.code.toByte()
            } else {
                while (v > 0) {
                    pos--
                    buf[pos] = ('0'.code + (v % 10)).toByte()
                    v /= 10
                }
            }
            val h = deepSeekHashV1(if (pos == 0) buf else buf.copyOfRange(pos, buf.size))
            // 前 4 字节小端 u32
            val value = (h[0].toLong() and 0xFF) or
                    ((h[1].toLong() and 0xFF) shl 8) or
                    ((h[2].toLong() and 0xFF) shl 16) or
                    ((h[3].toLong() and 0xFF) shl 24)
            if (value < threshold) return n
            n++
        }
        throw IllegalStateException("pow: no solution within $max iterations")
    }

    /** Builds and returns the `x-ds-pow-response` header value = base64(JSON). */
    fun buildPowHeader(c: PowChallenge, answer: Long): String {
        val json = JSONObject()
        json.put("algorithm", c.algorithm)
        json.put("challenge", c.challenge)
        json.put("salt", c.salt)
        json.put("answer", answer)
        json.put("signature", c.signature)
        json.put("target_path", c.targetPath)
        return Base64.getEncoder().encodeToString(json.toString().toByteArray(StandardCharsets.UTF_8))
    }

    /** End-to-end: given a challenge, returns the header value. */
    fun solveAndBuildHeader(c: PowChallenge): String {
        require(c.algorithm == "DeepSeekHashV1") { "pow: unsupported algorithm: ${c.algorithm}" }
        val answer = solvePow(c.challenge, c.salt, c.expireAt, c.difficulty)
        return buildPowHeader(c, answer)
    }

    private fun hexDecode(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }
}