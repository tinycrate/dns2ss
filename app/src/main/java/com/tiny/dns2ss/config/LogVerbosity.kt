package com.tiny.dns2ss.config

enum class LogVerbosity(
    val label: String,
    val rustLog: String,
) {
    OFF("Off", "off"),
    ERROR("Errors", "error"),
    WARN("Warnings", "warn"),
    INFO("Info", "info"),
    DEBUG("Debug", "debug");

    companion object {
        val default: LogVerbosity = WARN

        fun fromStorage(value: String?): LogVerbosity =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: default
    }
}
