package dev.httpvpn.net

/** Ordered byte chunks with a single consumer. Not thread-safe. */
class ByteQueue {
    private val chunks = ArrayDeque<ByteArray>()
    private var offset = 0
    var size: Int = 0
        private set

    fun append(data: ByteArray) {
        if (data.isEmpty()) return
        chunks.addLast(data)
        size += data.size
    }

    fun take(n: Int): ByteArray {
        require(n in 1..size)
        val out = ByteArray(n)
        var filled = 0
        while (filled < n) {
            val chunk = chunks.first()
            val count = minOf(chunk.size - offset, n - filled)
            chunk.copyInto(out, filled, offset, offset + count)
            filled += count
            offset += count
            size -= count
            if (offset == chunk.size) {
                chunks.removeFirst()
                offset = 0
            }
        }
        return out
    }
}
