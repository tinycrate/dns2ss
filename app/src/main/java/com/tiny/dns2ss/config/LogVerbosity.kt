package com.tiny.dns2ss.config

enum class LogVerbosity(
    val label: String,
    val sslocalLevel: Int,
) {
    OFF("Off", 5),
    ERROR("Errors", 4),
    WARN("Warnings", 3),
    INFO("Info", 2),
    DEBUG("Debug", 1);

    companion object {
        val default: LogVerbosity = WARN

        fun fromStorage(value: String?): LogVerbosity =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: default
    }
}
