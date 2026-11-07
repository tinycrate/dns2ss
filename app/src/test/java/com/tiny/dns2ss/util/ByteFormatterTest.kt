package com.tiny.dns2ss.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ByteFormatterTest {
    @Test
    fun bytesBelowOneKilobyteStayInBytes() {
        assertEquals("0 B", ByteFormatter.format(0))
        assertEquals("184 B", ByteFormatter.format(184))
        assertEquals("1023 B", ByteFormatter.format(1023))
    }

    @Test
    fun unitsStepThroughKbMbAndGb() {
        assertEquals("1.0 KB", ByteFormatter.format(1024))
        assertEquals("2.0 KB", ByteFormatter.format(2048))
        assertEquals("1.0 MB", ByteFormatter.format(1024L * 1024L))
        assertEquals("6.3 MB", ByteFormatter.format((6.3 * 1024 * 1024).toLong()))
        assertEquals("1.0 GB", ByteFormatter.format(1024L * 1024L * 1024L))
    }
}
