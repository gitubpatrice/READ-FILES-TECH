package com.readfilestech.read_files_tech.data.text

/**
 * A text the CSV scanner walks unit by unit: a decoded string, or the bytes of a file (the viewer's
 * `EncodedText`). Only the separator, the double quote and the line breaks are structural, and all are
 * ASCII: for a file, a unit is a byte (no byte of a multi-byte UTF-8 character is ASCII, and Windows-1252
 * has one byte per character) or a UTF-16 code unit. A field's text is decoded from its units only once
 * the scanner has found where it lies.
 */
internal abstract class CsvText {
    abstract val length: Int

    /** The unit at [index]: the character itself wherever it is ASCII. */
    abstract operator fun get(index: Int): Char

    /** Units [from] until [to], decoded. */
    abstract fun decode(from: Int, to: Int): String
}

/** A text already decoded. */
internal class CharsText(private val text: CharSequence) : CsvText() {
    override val length: Int get() = text.length

    override fun get(index: Int): Char = text[index]

    override fun decode(from: Int, to: Int): String = text.substring(from, to)
}

/**
 * The one CSV automaton of the app (RFC 4180, read as Excel reads it — see [CsvRows]): it walks ONE
 * record and gives each field as a range of RAW units, its quotes included; [field] turns a range into
 * its text. Nothing is allocated per character: the viewer indexes a 50 MiB file with it, and builds a
 * row's text only when it is shown.
 *
 * A double quote opens a quoted field only as its first character; inside, `""` is one quote and a lone
 * one closes it; whatever follows the closing quote, up to the separator, is kept as it is. `\r\n`, `\n`
 * and `\r` end a record outside quotes. Text after [limit] is not read: it ends the last record.
 */
internal class CsvScanner(private val text: CsvText, private val separator: Char, private val limit: Int = text.length) {
    /** Receives a field's raw range; returns false to stop the scan of the record there. */
    fun interface Fields {
        fun field(index: Int, start: Int, end: Int): Boolean
    }

    init {
        require(separator != QUOTE && separator != CR && separator != LF) { "not a separator: ${separator.code}" }
    }

    /** Where the last record scanned ends, its line break included — or where [Fields] stopped it. */
    var end: Int = 0
        private set

    /** The fields of the last record scanned, every one of them unless [Fields] stopped it. */
    var fieldCount: Int = 0
        private set

    /** Whether a field of the last record holds a character: one of empty fields (`;;;`, `""`) has none. */
    var hasContent: Boolean = false
        private set

    private var position = 0
    private var fieldStart = 0
    private var started = false

    /** Scans the record at [start], which must be before [limit]; false when [fields] stopped it before its end. */
    fun record(start: Int, fields: Fields? = null): Boolean {
        fieldCount = 0
        hasContent = false
        position = start
        fieldStart = start
        started = false
        var state = SCANNING
        while (state == SCANNING && position < limit) {
            val c = text[position]
            state = when {
                c == separator -> endField(fields)
                c == CR || c == LF -> endRecord(fields, c)
                c == QUOTE && !started -> skipQuoted()
                else -> {
                    started = true
                    hasContent = true
                    position++
                    SCANNING
                }
            }
        }
        if (state == SCANNING) {
            end = limit
            emit(fields, fieldStart, limit)
        }
        return state != STOPPED
    }

    private fun endField(fields: Fields?): Int {
        if (!emit(fields, fieldStart, position)) {
            end = position
            return STOPPED
        }
        position++
        fieldStart = position
        started = false
        return SCANNING
    }

    private fun endRecord(fields: Fields?, c: Char): Int {
        val fieldEnd = position
        position += if (c == CR && position + 1 < limit && text[position + 1] == LF) 2 else 1
        end = position
        emit(fields, fieldStart, fieldEnd)
        return ENDED
    }

    /** From an opening quote to just after its closing one — or to [limit], a quote never closed. */
    private fun skipQuoted(): Int {
        started = true
        position++
        var open = true
        while (open && position < limit) {
            when {
                text[position] != QUOTE -> position++
                position + 1 < limit && text[position + 1] == QUOTE -> position += 2
                else -> open = false
            }
            if (open) hasContent = true
        }
        if (!open) position++
        return SCANNING
    }

    private fun emit(fields: Fields?, start: Int, end: Int): Boolean {
        val index = fieldCount++
        return fields?.field(index, start, end) ?: true
    }

    companion object {
        const val QUOTE = '"'
        const val CR = '\r'
        const val LF = '\n'

        private const val SCANNING = 0
        private const val ENDED = 1
        private const val STOPPED = 2
    }
}

/**
 * The text of the field at units [start] until [end], as the scanner gave them: the quotes of a quoted
 * field removed and `""` read as one, at most [maxChars] characters — a field of a hostile file can be
 * the whole file, and only its first [maxChars] are decoded.
 */
internal fun CsvText.field(start: Int, end: Int, maxChars: Int = Int.MAX_VALUE): String {
    // A character of the text is at most 3 units (a UTF-8 BMP character; a 4-byte one is 2 characters;
    // `""` is 2 for one), plus the opening quote: the first maxChars characters lie whole before the cut,
    // and whatever the cut breaks — half a character, half of a `""` — comes after them, dropped below.
    val budget = maxChars.toLong() * MAX_UNITS_PER_CHAR + 2
    val cut = if (end - start > budget) start + budget.toInt() else end
    val text = unquote(decode(start, cut))
    if (text.length <= maxChars) return text
    // Never half of a surrogate pair at the end.
    val keep = if (text[maxChars - 1].isHighSurrogate()) maxChars - 1 else maxChars
    return text.substring(0, keep)
}

private const val MAX_UNITS_PER_CHAR = 3

/** A raw field's text: a quoted one without its quotes, `""` as one quote, what follows the closing quote kept. */
internal fun unquote(raw: String): String {
    if (raw.isEmpty() || raw[0] != CsvScanner.QUOTE) return raw
    val out = StringBuilder(raw.length)
    var i = 1
    while (i < raw.length) {
        val c = raw[i]
        if (c != CsvScanner.QUOTE) {
            out.append(c)
            i++
        } else if (i + 1 < raw.length && raw[i + 1] == CsvScanner.QUOTE) {
            out.append(c)
            i += 2
        } else {
            out.append(raw, i + 1, raw.length)
            break
        }
    }
    return out.toString()
}
