package com.safepocket.shared.crypto

/**
 * Pure-Kotlin Multiplatform RFC 9106 Argon2id (v1.3) + RFC 7693 Blake2b + RFC 5869 HKDF-SHA256.
 *
 * Ensures 100% byte-for-byte identical key derivation across Android (`arm64-v8a` + `x86_64`),
 * ChromeOS/Web PWA (`hash-wasm` Argon2id + WebCrypto HKDF), and macOS without native `.so` JNI hazards.
 */
object Argon2idPure {

    private val BLAKE2B_IV = ulongArrayOf(
        0x6a09e667f3bcc908UL, 0xbb67ae8584caa73bUL,
        0x3c6ef372fe94f82bUL, 0xa54ff53a5f1d36f1UL,
        0x510e527fade682d1UL, 0x9b05688c2b3e6c1fUL,
        0x1f83d9abfb41bd6bUL, 0x5be0cd19137e2179UL
    )

    private val BLAKE2B_SIGMA = arrayOf(
        intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
        intArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
        intArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
        intArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
        intArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
        intArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
        intArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
        intArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
        intArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
        intArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0)
    )

    fun hash(
        password: ByteArray,
        salt: ByteArray,
        memoryKiB: Int,
        iterations: Int,
        parallelism: Int,
        hashLength: Int = 32
    ): ByteArray {
        require(parallelism >= 1) { "parallelism must be >= 1" }
        require(iterations >= 1) { "iterations must be >= 1" }
        require(hashLength >= 4) { "hashLength must be >= 4" }

        val minBlocks = 8 * parallelism
        val rawBlocks = memoryKiB.coerceAtLeast(minBlocks)
        val segmentLength = rawBlocks / (4 * parallelism)
        val laneLength = segmentLength * 4
        val totalBlocks = laneLength * parallelism

        // H_0 = Blake2b-512(p || T || m || t || v=0x13 || y=2 || |P| || P || |S| || S || 0 || 0)
        val h0Input = ByteArray(40 + password.size + salt.size)
        var pos = 0
        pos = putLe32(h0Input, pos, parallelism)
        pos = putLe32(h0Input, pos, hashLength)
        pos = putLe32(h0Input, pos, memoryKiB)
        pos = putLe32(h0Input, pos, iterations)
        pos = putLe32(h0Input, pos, 0x13) // Version 1.3
        pos = putLe32(h0Input, pos, 2)    // Type 2 = Argon2id
        pos = putLe32(h0Input, pos, password.size)
        password.copyInto(h0Input, pos)
        pos += password.size
        pos = putLe32(h0Input, pos, salt.size)
        salt.copyInto(h0Input, pos)
        pos += salt.size
        pos = putLe32(h0Input, pos, 0) // Secret length = 0
        putLe32(h0Input, pos, 0)       // Associated data length = 0

        val h0 = blake2b(64, h0Input)

        // Memory blocks: [lane][column] -> ULongArray(128) (1024 bytes per block)
        val memory = Array(parallelism) { Array(laneLength) { ULongArray(128) } }

        val seedBuf = ByteArray(72)
        h0.copyInto(seedBuf, 0)
        for (lane in 0 until parallelism) {
            putLe32(seedBuf, 64, 0)
            putLe32(seedBuf, 68, lane)
            bytesToWords1024(blake2bLong(1024, seedBuf), memory[lane][0])

            putLe32(seedBuf, 64, 1)
            putLe32(seedBuf, 68, lane)
            bytesToWords1024(blake2bLong(1024, seedBuf), memory[lane][1])
        }

        val zeroBlock = ULongArray(128)
        val inputBlock = ULongArray(128)
        val addressBlock = ULongArray(128)
        val tmpBlock = ULongArray(128)

        for (pass in 0 until iterations) {
            for (slice in 0 until 4) {
                for (lane in 0 until parallelism) {
                    val dataIndependent = (pass == 0 && slice < 2)
                    if (dataIndependent) {
                        inputBlock.fill(0UL)
                        inputBlock[0] = pass.toULong()
                        inputBlock[1] = lane.toULong()
                        inputBlock[2] = slice.toULong()
                        inputBlock[3] = totalBlocks.toULong()
                        inputBlock[4] = iterations.toULong()
                        inputBlock[5] = 2UL // Argon2id
                    }

                    val startIndex = if (pass == 0 && slice == 0) 2 else 0
                    if (dataIndependent && startIndex > 0) {
                        inputBlock[6] = 1UL
                        generateAddresses(zeroBlock, inputBlock, tmpBlock, addressBlock)
                    }

                    for (index in startIndex until segmentLength) {
                        val currCol = slice * segmentLength + index
                        val prevCol = if (currCol == 0) laneLength - 1 else currCol - 1

                        val pseudoRand: ULong = if (dataIndependent) {
                            if (index % 128 == 0) {
                                inputBlock[6] = inputBlock[6] + 1UL
                                generateAddresses(zeroBlock, inputBlock, tmpBlock, addressBlock)
                            }
                            addressBlock[index % 128]
                        } else {
                            memory[lane][prevCol][0]
                        }

                        val j1 = pseudoRand and 0xFFFFFFFFUL
                        val j2 = (pseudoRand shr 32) and 0xFFFFFFFFUL
                        val refLane = if (pass == 0 && slice == 0) {
                            lane
                        } else {
                            (j2 % parallelism.toULong()).toInt()
                        }

                        val refAreaSize = when {
                            pass == 0 && slice == 0 -> index - 1
                            pass == 0 && refLane == lane -> slice * segmentLength + index - 1
                            pass == 0 -> slice * segmentLength - (if (index == 0) 1 else 0)
                            refLane == lane -> laneLength - segmentLength + index - 1
                            else -> laneLength - segmentLength - (if (index == 0) 1 else 0)
                        }

                        val x = (j1 * j1) shr 32
                        val y = (refAreaSize.toULong() * x) shr 32
                        val relPos = refAreaSize - 1 - y.toInt()
                        val startPos = if (pass == 0 || slice == 3) 0 else (slice + 1) * segmentLength
                        val refCol = (startPos + relPos) % laneLength

                        compressBlockG(
                            x = memory[lane][prevCol],
                            y = memory[refLane][refCol],
                            dest = memory[lane][currCol],
                            xorIntoDest = pass > 0,
                            rBuf = tmpBlock
                        )
                    }
                }
            }
        }

        val finalBlock = memory[0][laneLength - 1].copyOf()
        for (lane in 1 until parallelism) {
            val last = memory[lane][laneLength - 1]
            for (w in 0 until 128) {
                finalBlock[w] = finalBlock[w] xor last[w]
            }
        }

        val finalBytes = ByteArray(1024)
        words1024ToBytes(finalBlock, finalBytes)
        return blake2bLong(hashLength, finalBytes)
    }

    private fun generateAddresses(
        zeroBlock: ULongArray,
        inputBlock: ULongArray,
        tmpBlock: ULongArray,
        addressBlock: ULongArray
    ) {
        val mid = ULongArray(128)
        compressBlockG(zeroBlock, inputBlock, mid, xorIntoDest = false, rBuf = tmpBlock)
        compressBlockG(zeroBlock, mid, addressBlock, xorIntoDest = false, rBuf = tmpBlock)
    }

    private fun compressBlockG(
        x: ULongArray,
        y: ULongArray,
        dest: ULongArray,
        xorIntoDest: Boolean,
        rBuf: ULongArray
    ) {
        for (i in 0 until 128) {
            rBuf[i] = x[i] xor y[i]
        }
        val z = rBuf.copyOf()

        // Row-wise P on 8 rows of 16 ULongs
        for (row in 0 until 8) {
            val base = row * 16
            permute16(
                z,
                base + 0, base + 1, base + 2, base + 3,
                base + 4, base + 5, base + 6, base + 7,
                base + 8, base + 9, base + 10, base + 11,
                base + 12, base + 13, base + 14, base + 15
            )
        }

        // Column-wise P on 8 columns of 2 ULongs across the 8 rows
        for (col in 0 until 8) {
            val c = col * 2
            permute16(
                z,
                0 * 16 + c, 0 * 16 + c + 1,
                1 * 16 + c, 1 * 16 + c + 1,
                2 * 16 + c, 2 * 16 + c + 1,
                3 * 16 + c, 3 * 16 + c + 1,
                4 * 16 + c, 4 * 16 + c + 1,
                5 * 16 + c, 5 * 16 + c + 1,
                6 * 16 + c, 6 * 16 + c + 1,
                7 * 16 + c, 7 * 16 + c + 1
            )
        }

        if (xorIntoDest) {
            for (i in 0 until 128) {
                dest[i] = dest[i] xor rBuf[i] xor z[i]
            }
        } else {
            for (i in 0 until 128) {
                dest[i] = rBuf[i] xor z[i]
            }
        }
    }

    private fun permute16(
        v: ULongArray,
        i0: Int, i1: Int, i2: Int, i3: Int,
        i4: Int, i5: Int, i6: Int, i7: Int,
        i8: Int, i9: Int, i10: Int, i11: Int,
        i12: Int, i13: Int, i14: Int, i15: Int
    ) {
        gb(v, i0, i4, i8, i12)
        gb(v, i1, i5, i9, i13)
        gb(v, i2, i6, i10, i14)
        gb(v, i3, i7, i11, i15)

        gb(v, i0, i5, i10, i15)
        gb(v, i1, i6, i11, i12)
        gb(v, i2, i7, i8, i13)
        gb(v, i3, i4, i9, i14)
    }

    private fun gb(v: ULongArray, a: Int, b: Int, c: Int, d: Int) {
        v[a] = fBlaMka(v[a], v[b])
        v[d] = (v[d] xor v[a]).rotateRight64(32)
        v[c] = fBlaMka(v[c], v[d])
        v[b] = (v[b] xor v[c]).rotateRight64(24)

        v[a] = fBlaMka(v[a], v[b])
        v[d] = (v[d] xor v[a]).rotateRight64(16)
        v[c] = fBlaMka(v[c], v[d])
        v[b] = (v[b] xor v[c]).rotateRight64(63)
    }

    private inline fun fBlaMka(x: ULong, y: ULong): ULong {
        val m = (x and 0xFFFFFFFFUL) * (y and 0xFFFFFFFFUL)
        return x + y + (m shl 1)
    }

    /**
     * RFC 9106 Section 3.2 Variable-Length Hash Function H'(X).
     */
    fun blake2bLong(outLen: Int, input: ByteArray): ByteArray {
        val prefixed = ByteArray(4 + input.size)
        putLe32(prefixed, 0, outLen)
        input.copyInto(prefixed, 4)

        if (outLen <= 64) {
            return blake2b(outLen, prefixed)
        }

        val out = ByteArray(outLen)
        val r = (outLen + 31) / 32 - 2
        var prev = blake2b(64, prefixed)
        prev.copyInto(out, destinationOffset = 0, startIndex = 0, endIndex = 32)

        for (i in 1 until r) {
            prev = blake2b(64, prev)
            prev.copyInto(out, destinationOffset = i * 32, startIndex = 0, endIndex = 32)
        }

        val remaining = outLen - 32 * r
        val tail = blake2b(remaining, prev)
        tail.copyInto(out, destinationOffset = r * 32, startIndex = 0, endIndex = remaining)
        return out
    }

    /**
     * RFC 7693 Unkeyed Blake2b(outLen in 1..64, input).
     */
    fun blake2b(outLen: Int, input: ByteArray): ByteArray {
        require(outLen in 1..64)
        val h = BLAKE2B_IV.copyOf()
        h[0] = h[0] xor (0x01010000UL or outLen.toULong())

        var tLow = 0UL
        var offset = 0
        val block = ByteArray(128)
        val m = ULongArray(16)
        val v = ULongArray(16)

        while (input.size - offset > 128) {
            tLow += 128UL
            bytesToWords128(input, offset, m)
            blake2bCompress(h, m, v, tLow, isLast = false)
            offset += 128
        }

        val rem = input.size - offset
        tLow += rem.toULong()
        block.fill(0)
        if (rem > 0) {
            input.copyInto(block, 0, offset, offset + rem)
        }
        bytesToWords128(block, 0, m)
        blake2bCompress(h, m, v, tLow, isLast = true)

        val fullOut = ByteArray(64)
        for (i in 0 until 8) {
            putLe64(fullOut, i * 8, h[i])
        }
        return fullOut.copyOf(outLen)
    }

    private fun blake2bCompress(
        h: ULongArray,
        m: ULongArray,
        v: ULongArray,
        tLow: ULong,
        isLast: Boolean
    ) {
        for (i in 0 until 8) {
            v[i] = h[i]
            v[i + 8] = BLAKE2B_IV[i]
        }
        v[12] = v[12] xor tLow
        if (isLast) {
            v[14] = v[14] xor 0xFFFFFFFFFFFFFFFFUL
        }

        for (round in 0 until 12) {
            val s = BLAKE2B_SIGMA[round % 10]
            b2g(v, 0, 4, 8, 12, m[s[0]], m[s[1]])
            b2g(v, 1, 5, 9, 13, m[s[2]], m[s[3]])
            b2g(v, 2, 6, 10, 14, m[s[4]], m[s[5]])
            b2g(v, 3, 7, 11, 15, m[s[6]], m[s[7]])

            b2g(v, 0, 5, 10, 15, m[s[8]], m[s[9]])
            b2g(v, 1, 6, 11, 12, m[s[10]], m[s[11]])
            b2g(v, 2, 7, 8, 13, m[s[12]], m[s[13]])
            b2g(v, 3, 4, 9, 14, m[s[14]], m[s[15]])
        }

        for (i in 0 until 8) {
            h[i] = h[i] xor v[i] xor v[i + 8]
        }
    }

    private fun b2g(v: ULongArray, a: Int, b: Int, c: Int, d: Int, x: ULong, y: ULong) {
        v[a] = v[a] + v[b] + x
        v[d] = (v[d] xor v[a]).rotateRight64(32)
        v[c] = v[c] + v[d]
        v[b] = (v[b] xor v[c]).rotateRight64(24)
        v[a] = v[a] + v[b] + y
        v[d] = (v[d] xor v[a]).rotateRight64(16)
        v[c] = v[c] + v[d]
        v[b] = (v[b] xor v[c]).rotateRight64(63)
    }

    private inline fun ULong.rotateRight64(bits: Int): ULong =
        (this shr bits) or (this shl (64 - bits))

    private fun putLe32(buf: ByteArray, offset: Int, value: Int): Int {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value ushr 24) and 0xFF).toByte()
        return offset + 4
    }

    private fun putLe64(buf: ByteArray, offset: Int, value: ULong) {
        for (i in 0 until 8) {
            buf[offset + i] = ((value shr (i * 8)) and 0xFFUL).toByte()
        }
    }

    private fun bytesToWords128(src: ByteArray, offset: Int, out: ULongArray) {
        for (i in 0 until 16) {
            var w = 0UL
            val base = offset + i * 8
            for (b in 0 until 8) {
                w = w or ((src[base + b].toULong() and 0xFFUL) shl (b * 8))
            }
            out[i] = w
        }
    }

    private fun bytesToWords1024(src: ByteArray, out: ULongArray) {
        for (i in 0 until 128) {
            var w = 0UL
            val base = i * 8
            for (b in 0 until 8) {
                w = w or ((src[base + b].toULong() and 0xFFUL) shl (b * 8))
            }
            out[i] = w
        }
    }

    private fun words1024ToBytes(src: ULongArray, out: ByteArray) {
        for (i in 0 until 128) {
            putLe64(out, i * 8, src[i])
        }
    }
}

/**
 * Pure-Kotlin Multiplatform SHA-256, HMAC-SHA-256, and RFC 5869 HKDF-SHA-256.
 * Matches WebCrypto `crypto.subtle.deriveKey({ name: 'HKDF', hash: 'SHA-256', salt, info })` byte-for-byte.
 */
object HkdfSha256Pure {

    private val K = intArrayOf(
        0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
        -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
        -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
        -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, -0x0bf1ca7b, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8, -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e
    )

    fun sha256(data: ByteArray): ByteArray {
        var h0 = 0x6a09e667
        var h1 = -0x4498517b
        var h2 = 0x3c6ef372
        var h3 = -0x5ab00ac6
        var h4 = 0x510e527f
        var h5 = -0x64fa9774
        var h6 = 0x1f83d9ab
        var h7 = 0x5be0cd19

        val bitLen = data.size.toLong() * 8L
        val padLen = ((56 - (data.size + 1) % 64) + 64) % 64
        val padded = ByteArray(data.size + 1 + padLen + 8)
        data.copyInto(padded, 0)
        padded[data.size] = 0x80.toByte()
        for (i in 0 until 8) {
            padded[padded.size - 8 + i] = ((bitLen ushr ((7 - i) * 8)) and 0xFF).toByte()
        }

        val w = IntArray(64)
        var chunk = 0
        while (chunk < padded.size) {
            for (i in 0 until 16) {
                val j = chunk + i * 4
                w[i] = ((padded[j].toInt() and 0xFF) shl 24) or
                    ((padded[j + 1].toInt() and 0xFF) shl 16) or
                    ((padded[j + 2].toInt() and 0xFF) shl 8) or
                    (padded[j + 3].toInt() and 0xFF)
            }
            for (i in 16 until 64) {
                val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
                val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
                w[i] = w[i - 16] + s0 + w[i - 7] + s1
            }

            var a = h0; var b = h1; var c = h2; var d = h3
            var e = h4; var f = h5; var g = h6; var h = h7

            for (i in 0 until 64) {
                val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
                val ch = (e and f) xor (e.inv() and g)
                val temp1 = h + s1 + ch + K[i] + w[i]
                val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
                val maj = (a and b) xor (a and c) xor (b and c)
                val temp2 = s0 + maj

                h = g; g = f; f = e; e = d + temp1
                d = c; c = b; b = a; a = temp1 + temp2
            }

            h0 += a; h1 += b; h2 += c; h3 += d
            h4 += e; h5 += f; h6 += g; h7 += h
            chunk += 64
        }

        val out = ByteArray(32)
        val hs = intArrayOf(h0, h1, h2, h3, h4, h5, h6, h7)
        for (i in 0 until 8) {
            out[i * 4] = (hs[i] ushr 24).toByte()
            out[i * 4 + 1] = (hs[i] ushr 16).toByte()
            out[i * 4 + 2] = (hs[i] ushr 8).toByte()
            out[i * 4 + 3] = hs[i].toByte()
        }
        return out
    }

    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val blockKey = if (key.size > 64) sha256(key) else key
        val kPad = ByteArray(64)
        blockKey.copyInto(kPad, 0)

        val oKeyPad = ByteArray(64) { (kPad[it].toInt() xor 0x5c).toByte() }
        val iKeyPad = ByteArray(64) { (kPad[it].toInt() xor 0x36).toByte() }

        val inner = sha256(iKeyPad + message)
        return sha256(oKeyPad + inner)
    }

    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        val effectiveSalt = if (salt.isEmpty()) ByteArray(32) else salt
        val prk = hmacSha256(effectiveSalt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var generated = 0
        var counter = 1
        while (generated < length) {
            t = hmacSha256(prk, t + info + byteArrayOf(counter.toByte()))
            val take = minOf(t.size, length - generated)
            t.copyInto(out, generated, 0, take)
            generated += take
            counter++
        }
        return out
    }

    /**
     * Identical to PWA's `deriveTwoSecretAes256GcmKey(masterPassword, secretKeyFormatted, kdf, domainContext)`.
     */
    fun deriveTwoSecretKey(
        masterPassword: String,
        secretKeyFormatted: String,
        saltHex: String,
        memorySizeKiB: Int = 16_384,
        iterations: Int = 3,
        parallelism: Int = 2,
        domainContext: String = "safepocket-2skd-v1"
    ): ByteArray {
        val salt = ZeroKnowledgeEncryptionService.hexToBytes(saltHex)
        val pwBytes = masterPassword.encodeToByteArray()
        val secretBytes = secretKeyFormatted.trim().uppercase().encodeToByteArray()
        val argonBytes = Argon2idPure.hash(
            password = pwBytes,
            salt = salt,
            memoryKiB = memorySizeKiB,
            iterations = iterations,
            parallelism = parallelism,
            hashLength = 32
        )
        pwBytes.fill(0)
        val ikm = argonBytes + secretBytes
        return try {
            derive(
                ikm = ikm,
                salt = salt,
                info = domainContext.encodeToByteArray(),
                length = 32
            )
        } finally {
            argonBytes.fill(0)
            ikm.fill(0)
        }
    }

    private inline fun Int.rotateRight(bits: Int): Int =
        (this ushr bits) or (this shl (32 - bits))
}
