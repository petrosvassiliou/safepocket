package com.safepocket.shared.crypto

/**
 * Self-contained Pure-Kotlin Multiplatform RFC 8032 Ed25519 + FIPS 180-4 SHA-512 Verifier.
 *
 * Works identically on Android API 29–36+, Googlebook OS, macOS, and JS without relying on
 * JCE provider availability (`NoSuchAlgorithmException` on older Android BouncyCastle runtimes).
 */
object Ed25519VerifierPure {

    const val PINNED_RELEASE_ED25519_PUBKEY_HEX =
        "b793fdd180549d2d3f226fb13798fa85e2f85901d44c20fa85c2a6bde25420be"

    private val D = longArrayOf(
        0x78a3, 0x1359, 0x4dca, 0x75eb, 0xd8ab, 0x4141, 0x0a4d, 0x0070,
        0xe898, 0x7779, 0x4079, 0x8cc7, 0xfe73, 0x2b6f, 0x6cee, 0x5203
    )
    private val I_C = longArrayOf(
        0xa0b0, 0x4a0e, 0x1b27, 0xc4ee, 0xe478, 0xad2f, 0x1806, 0x2f43,
        0xd7a7, 0x3dfb, 0x0099, 0x2b4d, 0xdf0b, 0x4fc1, 0x2480, 0x2b83
    )
    private val X_B = longArrayOf(
        0xd51a, 0x8f25, 0x2d60, 0xc956, 0xa7b2, 0x9525, 0xc760, 0x692c,
        0xdc5c, 0xfdd6, 0xe231, 0xc0a4, 0x53fe, 0xcd6e, 0x36d3, 0x2169
    )
    private val Y_B = longArrayOf(
        0x6658, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666,
        0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666, 0x6666
    )
    private val L_ORDER = longArrayOf(
        0xed, 0xd3, 0xf5, 0x5c, 0x1a, 0x63, 0x12, 0x58,
        0xd6, 0x9c, 0xf7, 0xa2, 0xde, 0xf9, 0xde, 0x14,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x10
    )

    fun verifyHexSignature(
        publicKeyHex: String,
        messageUtf8: String,
        signatureHex: String
    ): Boolean = runCatching {
        val pk = ZeroKnowledgeEncryptionService.hexToBytes(publicKeyHex.trim())
        val sig = ZeroKnowledgeEncryptionService.hexToBytes(signatureHex.trim())
        verify(pk, messageUtf8.encodeToByteArray(), sig)
    }.getOrDefault(false)

    fun verify(publicKey32: ByteArray, message: ByteArray, signature64: ByteArray): Boolean {
        if (publicKey32.size != 32 || signature64.size != 64) return false
        val q = Array(4) { LongArray(16) }
        if (!unpackNeg(q, publicKey32)) return false

        val hashInput = ByteArray(64 + message.size)
        signature64.copyInto(hashInput, destinationOffset = 0, startIndex = 0, endIndex = 32)
        publicKey32.copyInto(hashInput, destinationOffset = 32, startIndex = 0, endIndex = 32)
        message.copyInto(hashInput, destinationOffset = 64)

        val hDigest = sha512(hashInput)
        val x = LongArray(64) { (hDigest[it].toInt() and 0xff).toLong() }
        val hReduced = IntArray(32)
        modL(hReduced, x)

        val p = Array(4) { LongArray(16) }
        val hBytes = ByteArray(32) { hReduced[it].toByte() }
        scalarMult(p, q, hBytes)

        var xb = X_B.copyOf()
        if ((pack25519(xb)[0].toInt() and 1) != 0) {
            xb = gfSub(LongArray(16), xb)
        }
        val gf1 = LongArray(16).also { it[0] = 1L }
        val qBase = arrayOf(xb, Y_B.copyOf(), gf1, gfMul(xb, Y_B))
        val p2 = Array(4) { LongArray(16) }
        val sBytes = signature64.copyOfRange(32, 64)
        scalarMult(p2, qBase, sBytes)

        pointAdd(p, p2)
        val packed = packPoint(p)
        var diff = 0
        for (i in 0 until 32) {
            diff = diff or ((packed[i].toInt() and 0xff) xor (signature64[i].toInt() and 0xff))
        }
        return diff == 0
    }

    private fun car25519(o: LongArray) {
        for (i in 0 until 16) {
            o[i] += 65536L
            val c = o[i] shr 16
            if (i < 15) {
                o[i + 1] += c - 1L
            } else {
                o[0] += 38L * (c - 1L)
            }
            o[i] -= c shl 16
        }
    }

    private fun sel25519(p: LongArray, q: LongArray, b: Int) {
        val mask = (b.toLong() - 1L).inv()
        for (i in 0 until 16) {
            val t = mask and (p[i] xor q[i])
            p[i] = p[i] xor t
            q[i] = q[i] xor t
        }
    }

    private fun pack25519(n: LongArray): ByteArray {
        val t = n.copyOf()
        car25519(t); car25519(t); car25519(t)
        val m = LongArray(16)
        repeat(2) {
            m[0] = t[0] - 0xffedL
            for (i in 1..14) {
                m[i] = t[i] - 0xffffL - ((m[i - 1] shr 16) and 1L)
                m[i - 1] = m[i - 1] and 0xffffL
            }
            m[15] = t[15] - 0x7fffL - ((m[14] shr 16) and 1L)
            val b = ((m[15] shr 16) and 1L).toInt()
            m[14] = m[14] and 0xffffL
            sel25519(t, m, 1 - b)
        }
        val out = ByteArray(32)
        for (i in 0 until 16) {
            out[2 * i] = (t[i] and 0xffL).toByte()
            out[2 * i + 1] = ((t[i] shr 8) and 0xffL).toByte()
        }
        return out
    }

    private fun unpack25519(n: ByteArray): LongArray {
        val o = LongArray(16) { i ->
            ((n[2 * i].toInt() and 0xff) + ((n[2 * i + 1].toInt() and 0xff) shl 8)).toLong()
        }
        o[15] = o[15] and 0x7fffL
        return o
    }

    private fun gfAdd(a: LongArray, b: LongArray) = LongArray(16) { a[it] + b[it] }
    private fun gfSub(a: LongArray, b: LongArray) = LongArray(16) { a[it] - b[it] }

    private fun gfMul(a: LongArray, b: LongArray): LongArray {
        val t = LongArray(31)
        for (i in 0 until 16) {
            for (j in 0 until 16) {
                t[i + j] += a[i] * b[j]
            }
        }
        for (i in 0 until 15) {
            t[i] += 38L * t[i + 16]
        }
        val o = t.copyOfRange(0, 16)
        car25519(o); car25519(o)
        return o
    }

    private fun gfSq(a: LongArray) = gfMul(a, a)

    private fun inv25519(i: LongArray): LongArray {
        var c = i.copyOf()
        for (a in 253 downTo 0) {
            c = gfSq(c)
            if (a != 2 && a != 4) c = gfMul(c, i)
        }
        return c
    }

    private fun pow2523(i: LongArray): LongArray {
        var c = i.copyOf()
        for (a in 250 downTo 0) {
            c = gfSq(c)
            if (a != 1) c = gfMul(c, i)
        }
        return c
    }

    private fun pointAdd(p: Array<LongArray>, q: Array<LongArray>) {
        val a = gfMul(gfSub(p[1], p[0]), gfSub(q[1], q[0]))
        val b = gfMul(gfAdd(p[0], p[1]), gfAdd(q[0], q[1]))
        var c = gfMul(gfMul(p[3], q[3]), D)
        c = gfAdd(c, c)
        var d = gfMul(p[2], q[2])
        d = gfAdd(d, d)
        val e = gfSub(b, a)
        val f = gfSub(d, c)
        val g = gfAdd(d, c)
        val h = gfAdd(b, a)
        p[0] = gfMul(e, f)
        p[1] = gfMul(h, g)
        p[2] = gfMul(g, f)
        p[3] = gfMul(e, h)
    }

    private fun cswap(p: Array<LongArray>, q: Array<LongArray>, b: Int) {
        for (i in 0 until 4) sel25519(p[i], q[i], b)
    }

    private fun packPoint(p: Array<LongArray>): ByteArray {
        val zi = inv25519(p[2])
        val tx = gfMul(p[0], zi)
        val ty = gfMul(p[1], zi)
        val r = pack25519(ty)
        r[31] = (r[31].toInt() xor (((pack25519(tx)[0].toInt() and 1) shl 7))).toByte()
        return r
    }

    private fun scalarMult(p: Array<LongArray>, q: Array<LongArray>, s: ByteArray) {
        p[0] = LongArray(16)
        p[1] = LongArray(16).also { it[0] = 1L }
        p[2] = LongArray(16).also { it[0] = 1L }
        p[3] = LongArray(16)
        for (i in 255 downTo 0) {
            val b = ((s[i ushr 3].toInt() and 0xff) ushr (i and 7)) and 1
            cswap(p, q, b)
            pointAdd(q, p)
            pointAdd(p, p)
            cswap(p, q, b)
        }
    }

    private fun unpackNeg(r: Array<LongArray>, pBytes: ByteArray): Boolean {
        val gf0 = LongArray(16)
        val gf1 = LongArray(16).also { it[0] = 1L }
        r[1] = unpack25519(pBytes)
        r[2] = gf1
        var u = gfSq(r[1])
        var v = gfMul(u, D)
        u = gfSub(u, r[2])
        v = gfAdd(r[2], v)
        val v3 = gfMul(gfSq(v), v)
        val uv7 = gfMul(gfMul(gfSq(v3), v), u)
        r[0] = gfMul(gfMul(v3, u), pow2523(uv7))
        val chk = gfMul(gfSq(r[0]), v)
        if (!pack25519(gfSub(chk, u)).all { it == 0.toByte() }) {
            if (!pack25519(gfAdd(chk, u)).all { it == 0.toByte() }) return false
            r[0] = gfMul(r[0], I_C)
        }
        val signBit = (pBytes[31].toInt() and 0xff) ushr 7
        if ((pack25519(r[0])[0].toInt() and 1) == signBit) {
            r[0] = gfSub(gf0, r[0])
        }
        r[3] = gfMul(r[0], r[1])
        return true
    }

    private fun modL(r: IntArray, x: LongArray) {
        for (i in 63 downTo 32) {
            var carry = 0L
            for (j in (i - 32) until (i - 12)) {
                x[j] += carry - 16L * x[i] * L_ORDER[j - (i - 32)]
                carry = (x[j] + 128L) shr 8
                x[j] -= carry shl 8
            }
            x[i - 12] += carry
            x[i] = 0L
        }
        var carry = 0L
        for (j in 0 until 32) {
            x[j] += carry - (x[31] shr 4) * L_ORDER[j]
            carry = x[j] shr 8
            x[j] = x[j] and 255L
        }
        for (j in 0 until 32) {
            x[j] -= carry * L_ORDER[j]
        }
        for (i in 0 until 32) {
            x[i + 1] += x[i] shr 8
            r[i] = (x[i] and 255L).toInt()
        }
    }

    // Pure-Kotlin FIPS 180-4 SHA-512
    private val K512 = ulongArrayOf(
        0x428a2f98d728ae22uL, 0x7137449123ef65cduL, 0xb5c0fbcfec4d3b2fuL, 0xe9b5dba58189dbbcuL,
        0x3956c25bf348b538uL, 0x59f111f1b605d019uL, 0x923f82a4af194f9buL, 0xab1c5ed5da6d8118uL,
        0xd807aa98a3030242uL, 0x12835b0145706fbeuL, 0x243185be4ee4b28cuL, 0x550c7dc3d5ffb4e2uL,
        0x72be5d74f27b896fuL, 0x80deb1fe3b1696b1uL, 0x9bdc06a725c71235uL, 0xc19bf174cf692694uL,
        0xe49b69c19ef14ad2uL, 0xefbe4786384f25e3uL, 0x0fc19dc68b8cd5b5uL, 0x240ca1cc77ac9c65uL,
        0x2de92c6f592b0275uL, 0x4a7484aa6ea6e483uL, 0x5cb0a9dcbd41fbd4uL, 0x76f988da831153b5uL,
        0x983e5152ee66dfabuL, 0xa831c66d2db43210uL, 0xb00327c898fb213fuL, 0xbf597fc7beef0ee4uL,
        0xc6e00bf33da88fc2uL, 0xd5a79147930aa725uL, 0x06ca6351e003826fuL, 0x142929670a0e6e70uL,
        0x27b70a8546d22ffcuL, 0x2e1b21385c26c926uL, 0x4d2c6dfc5ac42aeduL, 0x53380d139d95b3dfuL,
        0x650a73548baf63deuL, 0x766a0abb3c77b2a8uL, 0x81c2c92e47edaee6uL, 0x92722c851482353buL,
        0xa2bfe8a14cf10364uL, 0xa81a664bbc423001uL, 0xc24b8b70d0f89791uL, 0xc76c51a30654be30uL,
        0xd192e819d6ef5218uL, 0xd69906245565a910uL, 0xf40e35855771202auL, 0x106aa07032bbd1b8uL,
        0x19a4c116b8d2d0c8uL, 0x1e376c085141ab53uL, 0x2748774cdf8eeb99uL, 0x34b0bcb5e19b48a8uL,
        0x391c0cb3c5c95a63uL, 0x4ed8aa4ae3418acbuL, 0x5b9cca4f7763e373uL, 0x682e6ff3d6b2b8a3uL,
        0x748f82ee5defb2fcuL, 0x78a5636f43172f60uL, 0x84c87814a1f0ab72uL, 0x8cc702081a6439ecuL,
        0x90befffa23631e28uL, 0xa4506cebde82bde9uL, 0xbef9a3f7b2c67915uL, 0xc67178f2e372532buL,
        0xca273eceea26619cuL, 0xd186b8c721c0c207uL, 0xeada7dd6cde0eb1euL, 0xf57d4f7fee6ed178uL,
        0x06f067aa72176fbauL, 0x0a637dc5a2c898a6uL, 0x113f9804bef90daeuL, 0x1b710b35131c471buL,
        0x28db77f523047d84uL, 0x32caab7b40c72493uL, 0x3c9ebe0a15c9bebcuL, 0x431d67c49c100d4cuL,
        0x4cc5d4becb3e42b6uL, 0x597f299cfc657e2auL, 0x5fcb6fab3ad6faecuL, 0x6c44198c4a475817uL
    )

    fun sha512(input: ByteArray): ByteArray {
        var h0 = 0x6a09e667f3bcc908uL
        var h1 = 0xbb67ae8584caa73buL
        var h2 = 0x3c6ef372fe94f82buL
        var h3 = 0xa54ff53a5f1d36f1uL
        var h4 = 0x510e527fade682d1uL
        var h5 = 0x9b05688c2b3e6c1fuL
        var h6 = 0x1f83d9abfb41bd6buL
        var h7 = 0x5be0cd19137e2179uL

        val bitLen = input.size.toULong() * 8uL
        val padZeroes = (112 - ((input.size + 1) % 128) + 128) % 128
        val padded = ByteArray(input.size + 1 + padZeroes + 16)
        input.copyInto(padded)
        padded[input.size] = 0x80.toByte()
        for (i in 0 until 8) {
            padded[padded.size - 8 + i] = ((bitLen shr ((7 - i) * 8)) and 0xffuL).toByte()
        }

        val w = ULongArray(80)
        var offset = 0
        while (offset < padded.size) {
            for (i in 0 until 16) {
                var v = 0uL
                for (b in 0 until 8) {
                    v = (v shl 8) or (padded[offset + i * 8 + b].toUByte().toULong())
                }
                w[i] = v
            }
            for (i in 16 until 80) {
                val s0 = w[i - 15].rotateRight(1) xor w[i - 15].rotateRight(8) xor (w[i - 15] shr 7)
                val s1 = w[i - 2].rotateRight(19) xor w[i - 2].rotateRight(61) xor (w[i - 2] shr 6)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }

            var a = h0; var b = h1; var c = h2; var d = h3
            var e = h4; var f = h5; var g = h6; var h = h7

            for (i in 0 until 80) {
                val sum1 = e.rotateRight(14) xor e.rotateRight(18) xor e.rotateRight(41)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = h + sum1 + ch + K512[i] + w[i]
                val sum0 = a.rotateRight(28) xor a.rotateRight(34) xor a.rotateRight(39)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = sum0 + maj

                h = g; g = f; f = e; e = d + temp1
                d = c; c = b; b = a; a = temp1 + temp2
            }

            h0 += a; h1 += b; h2 += c; h3 += d
            h4 += e; h5 += f; h6 += g; h7 += h
            offset += 128
        }

        val out = ByteArray(64)
        val hs = ulongArrayOf(h0, h1, h2, h3, h4, h5, h6, h7)
        for (i in 0 until 8) {
            for (b in 0 until 8) {
                out[i * 8 + b] = ((hs[i] shr ((7 - b) * 8)) and 0xffuL).toByte()
            }
        }
        return out
    }
}
