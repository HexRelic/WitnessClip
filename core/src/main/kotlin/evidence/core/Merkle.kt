// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 HexRelic

package evidence.core

/**
 * Append-only Merkle log with RFC 6962 hashing.
 *   leaf = SHA-256(0x00 ‖ entry bytes)      node = SHA-256(0x01 ‖ left ‖ right)
 * Kept as a stack of perfect subtrees so appending and reading the root are O(log n).
 */
class MerkleLog {
    private val hashes = ArrayList<ByteArray>()
    private val heights = ArrayList<Int>()
    var size = 0
        private set

    fun append(leafHash: ByteArray) {
        var h = leafHash
        var height = 0
        while (heights.isNotEmpty() && heights.last() == height) {
            h = node(hashes.removeAt(hashes.size - 1), h)
            heights.removeAt(heights.size - 1)
            height++
        }
        hashes.add(h)
        heights.add(height)
        size++
    }

    fun root(): ByteArray {
        if (hashes.isEmpty()) return sha256()
        var acc = hashes.last()
        for (i in hashes.size - 2 downTo 0) acc = node(hashes[i], acc)
        return acc
    }

    companion object {
        private val LEAF = byteArrayOf(0)
        private val NODE = byteArrayOf(1)
        fun leaf(entry: ByteArray): ByteArray = sha256(LEAF, entry)
        fun node(l: ByteArray, r: ByteArray): ByteArray = sha256(NODE, l, r)
    }
}
