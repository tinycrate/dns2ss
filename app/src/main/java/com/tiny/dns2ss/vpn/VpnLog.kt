package com.tiny.dns2ss.vpn

object VpnLog {
    private const val MAX_LINES = 250
    private val lines = ArrayDeque<String>(MAX_LINES)

    @Volatile
    var enabled: Boolean = true

    @Synchronized
    fun add(tag: String, message: String) {
        if (!enabled) return
        val clean = message.replace('\n', ' ').replace('\r', ' ').trim()
        if (clean.isEmpty()) return
        lines.addLast("$tag: $clean")
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized
    fun snapshot(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()
}
