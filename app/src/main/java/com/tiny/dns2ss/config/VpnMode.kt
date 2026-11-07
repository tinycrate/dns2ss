package com.tiny.dns2ss.config

enum class VpnMode {
    FAST,
    STRICT,
    FULL;

    companion object {
        val default: VpnMode = FAST

        fun fromStorage(value: String?): VpnMode =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: default
    }
}
