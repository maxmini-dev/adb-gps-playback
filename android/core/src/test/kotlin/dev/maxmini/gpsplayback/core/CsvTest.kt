package dev.maxmini.gpsplayback.core

import dev.maxmini.gpsplayback.core.gtfs.CsvReader
import dev.maxmini.gpsplayback.core.gtfs.forEachCsvRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.StringReader

class CsvTest {
    private fun rows(text: String): List<List<String>> {
        val r = CsvReader(StringReader(text))
        return generateSequence { r.readRow() }.toList()
    }

    @Test fun plainAndQuoted() {
        assertEquals(
            listOf(listOf("a", "b", "c"), listOf("1", "x, y", "say \"hi\"")),
            rows("a,b,c\n1,\"x, y\",\"say \"\"hi\"\"\"\n"),
        )
    }

    @Test fun crlfBomBlankLinesAndEmbeddedNewline() {
        assertEquals(
            listOf(listOf("id", "name"), listOf("1", "two\nlines"), listOf("2", "")),
            rows("﻿id,name\r\n1,\"two\nlines\"\r\n\r\n2,\r\n"),
        )
    }

    @Test fun noTrailingNewline() {
        assertEquals(listOf(listOf("a"), listOf("b")), rows("a\nb"))
        assertNull(CsvReader(StringReader("")).readRow())
    }

    @Test fun recordsByHeaderName() {
        val seen = mutableListOf<Pair<String, String>>()
        forEachCsvRecord(StringReader("stop_id, stop_name\nS1,Main St\nS2\n")) { get ->
            seen += get("stop_id") to get("stop_name")
        }
        assertEquals(listOf("S1" to "Main St", "S2" to ""), seen)
    }
}
