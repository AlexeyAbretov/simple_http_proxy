package dev.httpvpn.net

/** 32-bit TCP sequence arithmetic (RFC 1982). */
object Seq {
    private const val MASK = 0xFFFFFFFFL
    private const val HALF = 0x80000000L

    fun lt(a: Long, b: Long): Boolean = ((a - b) and MASK) > HALF

    fun lte(a: Long, b: Long): Boolean = !lt(b, a)

    fun add(seq: Long, count: Int): Long = (seq + count) and MASK

    /** How far [to] is ahead of [from], as an unsigned 32-bit distance. */
    fun ahead(to: Long, from: Long): Long = (to - from) and MASK
}
