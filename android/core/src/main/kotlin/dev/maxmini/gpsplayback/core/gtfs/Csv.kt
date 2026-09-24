package dev.maxmini.gpsplayback.core.gtfs

import java.io.Reader

/**
 * Minimal streaming RFC 4180 CSV reader: quoted fields, escaped quotes (""),
 * embedded newlines, CRLF/LF line endings and a leading UTF-8 BOM.
 *
 * Streaming matters because stop_times.txt in real feeds can be hundreds of MB.
 */
class CsvReader(private val reader: Reader) {
    private val buf = CharArray(64 * 1024)
    private var len = 0
    private var pos = 0
    private var first = true

    private fun read(): Int {
        if (pos >= len) {
            len = reader.read(buf)
            pos = 0
            if (len <= 0) return -1
        }
        val c = buf[pos++].code
        if (first) {
            first = false
            if (c == 0xFEFF) return read()
        }
        return c
    }

    private fun unread() {
        pos--
    }

    /** Next record, or null at end of input. Blank lines are skipped. */
    fun readRow(): List<String>? {
        while (true) {
            val row = readRawRow() ?: return null
            if (row.size == 1 && row[0].isEmpty()) continue
            return row
        }
    }

    private fun readRawRow(): List<String>? {
        var c = read()
        if (c == -1) return null
        val fields = ArrayList<String>()
        val field = StringBuilder()
        var inQuotes = false
        while (true) {
            if (inQuotes) {
                when (c) {
                    -1 -> { fields.add(field.toString()); return fields }
                    '"'.code -> {
                        val next = read()
                        if (next == '"'.code) field.append('"')
                        else { inQuotes = false; c = next; continue }
                    }
                    else -> field.append(c.toChar())
                }
            } else {
                when (c) {
                    -1 -> { fields.add(field.toString()); return fields }
                    '"'.code -> if (field.isEmpty()) inQuotes = true else field.append('"')
                    ','.code -> { fields.add(field.toString()); field.setLength(0) }
                    '\r'.code -> {
                        val next = read()
                        if (next != '\n'.code && next != -1) unread()
                        fields.add(field.toString()); return fields
                    }
                    '\n'.code -> { fields.add(field.toString()); return fields }
                    else -> field.append(c.toChar())
                }
            }
            c = read()
        }
    }
}

/**
 * Iterate the rows of a CSV with a header line, calling [block] with a column
 * lookup for each record. Columns missing from a row read as "".
 */
inline fun forEachCsvRecord(reader: Reader, block: (get: (String) -> String) -> Unit) {
    val csv = CsvReader(reader)
    val header = csv.readRow() ?: return
    val index = header.withIndex().associate { (i, name) -> name.trim() to i }
    while (true) {
        val row = csv.readRow() ?: break
        block { name -> index[name]?.let { row.getOrNull(it)?.trim() } ?: "" }
    }
}
