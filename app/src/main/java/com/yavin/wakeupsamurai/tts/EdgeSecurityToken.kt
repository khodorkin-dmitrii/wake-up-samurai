// SPDX-License-Identifier: LGPL-3.0-only
// Adapted from rany2/edge-tts 7.3.0, commit 6d3fff402a77dd4d43cccf074d042252770e41c2.
// Wake Up, Samurai changes: Kotlin representation and offline regression-test boundary.
package com.yavin.wakeupsamurai.tts

/**
 * Kotlin port of rany2/edge-tts 7.3.0 (commit 6d3fff402a77dd4d43cccf074d042252770e41c2)
 * Sec-MS-GEC v2 token primitive, adapted from src/edge_tts/drm.py.
 *
 * This is deliberately separate from the WebSocket client so its fixed upstream vectors can be
 * tested without network access. See THIRD_PARTY_NOTICES.md for the LGPL-3.0 attribution.
 */
@OptIn(ExperimentalUnsignedTypes::class)
internal object EdgeSecurityToken {
    internal const val SEC_MS_GEC_VERSION = "2-143.0.3650.75"
    private const val trustedClientToken = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val windowsEpochSeconds = 11_644_473_600L
    private const val windowSeconds = 300L

    private val initial = words("6A09E667 BB67AE85 3C6EF372 A54FF53A 510E527F 9B05688C 1F83D9AB 5BE0CD19")
    private val seededState = words("FD9FBEA2 91243CE7 BE499094 CD2E1E0A 6C006F49 04BD35AB FEB0A7E4 03EFFBD2")
    private val seededSchedule = words("EB24BF9E 1558691F A0C09EE2 0CFA32BA A622EE8C D1DD9491 C0DD25A0 F9E70AB6 F0C31573 7058CE4E 7188E606 00E5AB33 D258B060 72766564")
    private val roundConstants = words("428A2F98 71374491 B5C0FBCF E9B5DBA5 3956C25B 59F111F1 923F82A4 AB1C5ED5 D807AA98 12835B01 243185BE 550C7DC3 72BE5D74 80DEB1FE 9BDC06A7 C19BF174 E49B69C1 EFBE4786 0FC19DC6 240CA1CC 2DE92C6F 4A7484AA 5CB0A9DC 76F988DA 983E5152 A831C66D B00327C8 BF597FC7 C6E00BF3 D5A79147 06CA6351 14292967 27B70A85 2E1B2138 4D2C6DFC 53380D13 650A7354 766A0ABB 81C2C92E 92722C85 A2BFE8A1 A81A664B C24B8B70 C76C51A3 D192E819 D6990624 F40E3585 106AA070 19A4C116 1E376C08 2748774C 34B0BCB5 391C0CB3 4ED8AA4A 5B9CCA4F 682E6FF3 748F82EE 78A5636F 84C87814 8CC70208 90BEFFFA A4506CEB BEF9A3F7 C67178F2")
    private val coefficientTerms = words("E82E91B1 44DE24C6 3BEB65BC C89C5933 8D1EC211 314EF5D5 E92491B1 F0A5B9D5 06064CF3 DA79FA60 BED20560 89F9C9F8 35FA38C6 CB49E3C6")
    private val coefficientOffsets = words("B32700D0 88DFF51D 29514038 B328CBDF D405B4D5 26E55F3C 607801C6 82B2C1FB FA51A3DA 207CC464 3C3C74F3 D2D14B61 BB04A2C1 C5626167")

    fun generate(nowSeconds: Long = System.currentTimeMillis() / 1_000L): String {
        val roundedSeconds = (nowSeconds + windowsEpochSeconds).let { it - it % windowSeconds }
        val data = "${roundedSeconds * 10_000_000L}$trustedClientToken".encodeToByteArray()
        val digest = sha256Digest(data)
        val (first, second) = cs64Digest(data, digest)
        return littleEndianHex(first) + littleEndianHex(second) + "-" + digest.toHex()
    }

    private fun sha256Digest(data: ByteArray): ByteArray {
        require(data.size >= 7) { "Token salt must contain at least seven bytes." }
        var state = sha256Init(data.copyOfRange(0, 7))
        val length = 57 + data.size
        val padded = ArrayList<Byte>(data.size + 72)
        for (index in 7 until data.size) padded += data[index]
        padded += 0x80.toByte()
        repeat(floorMod(55 - length, 64)) { padded += 0 }
        val bitLength = length.toLong() * 8
        for (shift in 56 downTo 0 step 8) padded += (bitLength shr shift).toByte()
        for (offset in padded.indices step 64) {
            val block = UIntArray(64)
            for (index in 0 until 16) block[index] = bigEndianWord(padded, offset + index * 4)
            expandSchedule(block, 16)
            state = addWords(state, sha256Rounds(state, block))
        }
        return ByteArray(32) { index -> (state[index / 4] shr (24 - (index % 4) * 8)).toByte() }
    }

    private fun sha256Init(data: ByteArray): UIntArray {
        val block = UIntArray(64)
        block[14] = ((data[0].toUInt() and 0xFFu) shl 16) or ((data[1].toUInt() and 0xFFu) shl 8) or (data[2].toUInt() and 0xFFu)
        block[15] = bigEndianWord(data, 3)
        for (index in 16 until 30) {
            var value = seededSchedule[index - 16]
            if (index - 2 >= 14) value += if (index == 16) sigma1(block[index - 2]) xor 0x000B92C0u else sigma1(block[index - 2])
            if (index - 7 >= 14) value += block[index - 7]
            if (index - 15 >= 14) value += if (index == 29) sigma0(block[index - 15]) xor 0x059C0B80u else sigma0(block[index - 15])
            block[index] = value
        }
        block[30] = sigma1(block[28]) + block[23] + sigma0(block[15]) + block[14] + 0xC9DC06A7u - roundConstants[14]
        expandSchedule(block, 31)
        return addWords(initial, sha256Rounds(seededState, block, firstRound = 14))
    }

    private fun sha256Rounds(input: UIntArray, block: UIntArray, firstRound: Int = 0): UIntArray {
        val constants = roundConstants.copyOf().also { if (firstRound == 14) it[14] = 0xC9DC06A7u }
        var a = input[0]; var b = input[1]; var c = input[2]; var d = input[3]
        var e = input[4]; var f = input[5]; var g = input[6]; var h = input[7]
        for (index in firstRound until 64) {
            val temporary1 = h + bigSigma1(e) + choose(e, f, g) + constants[index] + block[index]
            val temporary2 = bigSigma0(a) + majority(a, b, c)
            h = g; g = f; f = e; e = d + temporary1; d = c; c = b; b = a; a = temporary1 + temporary2
        }
        return uintArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun cs64Digest(data: ByteArray, digest: ByteArray): Pair<UInt, UInt> {
        val keyA = littleEndianWord(digest, 0) or 1u
        val keyB = littleEndianWord(digest, 4) or 1u
        val factors = uintArrayOf(keyA * 0x82893933u, keyB * 0x82893933u)
        val terms = MutableList(coefficientTerms.size) { index -> coefficientTerms[index] * factors[index % 2] }
        val offsets = coefficientOffsets.toMutableList()
        val prefixed = ByteArray(data.size + 1)
        data.copyInto(prefixed, destinationOffset = 1)
        littleEndianEvenWords(prefixed).forEachIndexed { index, word ->
            var term = word * if (index % 2 == 0) keyA else keyB
            var offset = word.rotateLeft(16) * if (index % 2 == 0) 0xEF0569FBu else 0xC31713DBu
            if (index == 0) { term += 0xF2F90B1Au * factors[0]; offset += 0x0B1A0000u }
            terms += term; offsets += offset
        }
        var swapAccumulator = 0u; var swapLast = 0u; var state = 0u; var accumulator = 0u
        for (index in terms.indices step 2) {
            var q = (offsets[index] + terms[index]).rotateLeft(16) * 0x76F50F34u
            swapAccumulator += q.rotateLeft(16) * 0xC3EFEA97u + q
            q = (offsets[index + 1] + terms[index + 1]).rotateLeft(16) * 0x1881CE3Cu
            swapLast = q.rotateLeft(16) * 0x35BD1EC9u + q; swapAccumulator += swapLast
            state = (state * keyA + terms[index]).rotateLeft(16) * 0xCF98B111u
            state = state.rotateLeft(16) * 0x87085B9Fu; state = state.rotateLeft(16) * 0x12CEB96Du; state = state.rotateLeft(16) * 0x257E1D83u
            accumulator += state
            q = (state * keyB + terms[index + 1]).rotateLeft(16) * 0xA27416F5u; q = q.rotateLeft(16) * 0xD38396FFu
            state = (q.rotateLeft(16) * 0x7C932B89u).rotateLeft(16) * 0xBFA49F69u; accumulator += state
        }
        return (swapLast xor state) to (swapAccumulator xor accumulator)
    }

    private fun expandSchedule(block: UIntArray, firstWord: Int) { for (index in firstWord until 64) block[index] = sigma1(block[index - 2]) + block[index - 7] + sigma0(block[index - 15]) + block[index - 16] }
    private fun sigma0(value: UInt): UInt = (value shr 7) xor (value shl 25) xor (value shr 18) xor (value shl 14) xor (value shr 3)
    private fun sigma1(value: UInt): UInt = (value shr 17) xor (value shl 15) xor (value shr 19) xor (value shl 13) xor (value shr 10)
    private fun bigSigma0(value: UInt): UInt = value.rotateRight(2) xor value.rotateRight(13) xor value.rotateRight(22)
    private fun bigSigma1(value: UInt): UInt = value.rotateRight(6) xor value.rotateRight(11) xor value.rotateRight(25)
    private fun choose(x: UInt, y: UInt, z: UInt): UInt = z xor (x and (y xor z))
    private fun majority(x: UInt, y: UInt, z: UInt): UInt = (x and y) or (z and (x or y))
    private fun addWords(first: UIntArray, second: UIntArray): UIntArray = UIntArray(8) { first[it] + second[it] }
    private fun bigEndianWord(bytes: List<Byte>, offset: Int): UInt = ((bytes[offset].toUInt() and 0xFFu) shl 24) or ((bytes[offset + 1].toUInt() and 0xFFu) shl 16) or ((bytes[offset + 2].toUInt() and 0xFFu) shl 8) or (bytes[offset + 3].toUInt() and 0xFFu)
    private fun bigEndianWord(bytes: ByteArray, offset: Int): UInt = bigEndianWord(bytes.toList(), offset)
    private fun littleEndianWord(bytes: ByteArray, offset: Int): UInt = (bytes[offset].toUInt() and 0xFFu) or ((bytes[offset + 1].toUInt() and 0xFFu) shl 8) or ((bytes[offset + 2].toUInt() and 0xFFu) shl 16) or ((bytes[offset + 3].toUInt() and 0xFFu) shl 24)
    private fun littleEndianEvenWords(bytes: ByteArray): List<UInt> { val padded = bytes.copyOf(bytes.size + floorMod(-bytes.size, 4)); val result = padded.indices.step(4).map { littleEndianWord(padded, it) }.toMutableList(); if (result.size % 2 == 1) result += 0u; return result }
    private fun words(value: String): UIntArray = value.split(' ').map { it.toUInt(16) }.toUIntArray()
    private fun floorMod(value: Int, divisor: Int): Int = ((value % divisor) + divisor) % divisor
    private fun littleEndianHex(value: UInt): String = listOf(0, 8, 16, 24).joinToString("") { shift -> hexByte((value shr shift) and 0xFFu) }
    private fun ByteArray.toHex(): String = joinToString("") { byte -> hexByte((byte.toInt() and 0xFF).toUInt()) }
    private fun hexByte(value: UInt): String = value.toString(radix = 16).uppercase().padStart(2, '0')
}
