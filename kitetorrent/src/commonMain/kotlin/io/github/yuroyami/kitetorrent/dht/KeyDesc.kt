package io.github.yuroyami.kitetorrent.dht

import io.github.yuroyami.kitetorrent.bencode.BdecodeNode

/**
 * A single required or optional key in a DHT message schema. This is the port of
 * `struct key_desc_t` (include/libtorrent/kademlia/msg.hpp). Together with
 * [verifyMessage] this reproduces libtorrent's `verify_message_impl`
 * (src/kademlia/msg.cpp): a table of these describes the keys a query/response must
 * contain, their bencode [type], optional exact [size] constraints, and nesting
 * [flags].
 */
class KeyDesc(
    /** The dictionary key to look up. */
    val name: String,
    /** Required bencode type, or [BdecodeNode.Type.NONE] to accept any type. */
    val type: BdecodeNode.Type,
    /**
     * Required string length (only checked for [BdecodeNode.Type.STRING]); 0 means
     * "no length constraint". With [SIZE_DIVISIBLE] the length must instead be a
     * multiple of this.
     */
    val size: Int = 0,
    /** Bit-mask of [OPTIONAL] / [PARSE_CHILDREN] / [LAST_CHILD] / [SIZE_DIVISIBLE]. */
    val flags: Int = 0,
) {
    companion object {
        /** The key is optional; parsing does not fail if it is absent. */
        const val OPTIONAL = 1

        /**
         * For a dictionary: the following descriptors describe child keys of this
         * node, up to and including the next descriptor with [LAST_CHILD]. Nestable.
         */
        const val PARSE_CHILDREN = 2

        /** Marks the last descriptor inside a [PARSE_CHILDREN] dictionary. */
        const val LAST_CHILD = 4

        /** [size] means "length must be divisible by N" rather than "length == N". */
        const val SIZE_DIVISIBLE = 8
    }
}

/** Result of [verifyMessage]: success plus the extracted nodes, or a failure reason. */
class VerifyResult internal constructor(
    /** True if every required key was present and well-typed/-sized. */
    val ok: Boolean,
    /**
     * Parallel to the descriptor array passed in: the matched node for each
     * descriptor, or [BdecodeNode.none] if absent (only possible for [KeyDesc.OPTIONAL]
     * keys when [ok]). Index with the same position as the descriptor.
     */
    val values: List<BdecodeNode>,
    /** Human-readable failure reason, matching libtorrent's `error_string`; "" on success. */
    val error: String,
)

/**
 * Verify a DHT message against a schema and extract its keys. This is a faithful port
 * of `verify_message_impl` (src/kademlia/msg.cpp).
 *
 * Walks [desc] in order against [message] (which must be a dict). For each
 * descriptor it does a `dict_find`, drops the result on a type mismatch (unless the
 * descriptor type is [BdecodeNode.Type.NONE], the wildcard), fails on a missing
 * non-optional key, enforces the string-length / divisibility constraint, and
 * descends into / pops out of child dictionaries via the
 * [KeyDesc.PARSE_CHILDREN]/[KeyDesc.LAST_CHILD] flags (libtorrent keeps an explicit
 * stack of up to 5 frames; we do the same).
 *
 * @return a [VerifyResult] whose [VerifyResult.values] line up index-for-index with
 *   [desc]. This is the `ret[]` array libtorrent fills in.
 */
fun verifyMessage(message: BdecodeNode, desc: List<KeyDesc>): VerifyResult {
    val size = desc.size
    val ret = arrayOfNulls<BdecodeNode>(size)

    if (message.type != BdecodeNode.Type.DICT) {
        return VerifyResult(false, fill(ret, size), "not a dictionary")
    }

    // Stack of dict nodes to return to when leaving a parse_children block.
    val stack = arrayOfNulls<BdecodeNode>(5)
    var stackPtr = -1
    var msg = message
    stackPtr++
    stack[stackPtr] = msg

    var i = 0
    while (i < size) {
        val k = desc[i]

        var found = msg.dictFind(k.name)
        // none_t (our NONE) means "any type"; otherwise a type mismatch clears it.
        if (found.isValid && found.type != k.type && k.type != BdecodeNode.Type.NONE) {
            found = BdecodeNode.none
        }
        if (!found.isValid && (k.flags and KeyDesc.OPTIONAL) == 0) {
            ret[i] = BdecodeNode.none
            return VerifyResult(false, fill(ret, size), "missing '${k.name}' key")
        }

        if (k.size > 0 && found.isValid && k.type == BdecodeNode.Type.STRING) {
            val len = found.stringLength()
            val invalid = if ((k.flags and KeyDesc.SIZE_DIVISIBLE) != 0) {
                (len % k.size) != 0
            } else {
                len != k.size
            }
            if (invalid) {
                found = BdecodeNode.none
                if ((k.flags and KeyDesc.OPTIONAL) == 0) {
                    ret[i] = BdecodeNode.none
                    return VerifyResult(false, fill(ret, size), "invalid value for '${k.name}'")
                }
            }
        }

        ret[i] = found

        if ((k.flags and KeyDesc.PARSE_CHILDREN) != 0) {
            if (found.isValid) {
                stackPtr++
                msg = found
                stack[stackPtr] = msg
            } else {
                // skip all children up to and including the matching last_child
                while (i < size && (desc[i].flags and KeyDesc.LAST_CHILD) == 0) i++
            }
        } else if ((k.flags and KeyDesc.LAST_CHILD) != 0) {
            // unbalanced spec guard, mirroring libtorrent's `stack_ptr == 0` check
            if (stackPtr == 0) return VerifyResult(false, fill(ret, size), "unbalanced message spec")
            stackPtr--
            msg = stack[stackPtr]!!
        }
        i++
    }
    return VerifyResult(true, fill(ret, size), "")
}

private fun fill(ret: Array<BdecodeNode?>, size: Int): List<BdecodeNode> =
    List(size) { ret[it] ?: BdecodeNode.none }
