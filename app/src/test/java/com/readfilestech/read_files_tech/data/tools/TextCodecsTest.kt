package com.readfilestech.read_files_tech.data.tools

import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.Test

/**
 * The oracle is Python, not the code: `encode-oracle.json` holds what base64, urllib.parse.quote and
 * html.escape / unescape make of a corpus (tools/oracles/text_tools_oracle.py) — accents, emoji in
 * surrogate pairs, NUL, `&amp;amp;`, a `%` alone.
 */
class TextCodecsTest {
    private val oracle = JSONObject(resource("/tools/encode-oracle.json"))
    private val cases = oracle.getJSONArray("cases").objects()

    @Test
    fun `Base64 is Python's, both ways, in either alphabet`() {
        for (case in cases) {
            val text = case.getString("text")
            assertThat(TextCodecs.encode(TextCodec.BASE64, text)).isEqualTo(case.getString("base64"))
            assertThat(TextCodecs.decode(TextCodec.BASE64, case.getString("base64"))).isEqualTo(Decoded.Text(text))
            assertThat(TextCodecs.decode(TextCodec.BASE64, case.getString("base64url"))).isEqualTo(Decoded.Text(text))
        }
    }

    @Test
    fun `a Base64 text wrapped on lines decodes as one`() {
        val wrapped = TextCodecs.encode(TextCodec.BASE64, "x".repeat(200)).chunked(76).joinToString("\r\n")
        assertThat(TextCodecs.decode(TextCodec.BASE64, wrapped)).isEqualTo(Decoded.Text("x".repeat(200)))
    }

    @Test
    fun `what Python refuses as Base64 is refused, and bytes that are not UTF-8 are said so`() {
        for (invalid in oracle.getJSONArray("base64Invalid").strings()) {
            assertThat(TextCodecs.decode(TextCodec.BASE64, invalid)).isEqualTo(Decoded.Refused(DecodeRefusal.BASE64_INVALID))
        }
        // FF FE: a UTF-16 BOM, never UTF-8.
        assertThat(TextCodecs.decode(TextCodec.BASE64, "//4=")).isEqualTo(Decoded.Refused(DecodeRefusal.NOT_UTF8))
    }

    @Test
    fun `URL encoding is a parameter's percent-encoding, Python's quote with nothing safe`() {
        for (case in cases) {
            val text = case.getString("text")
            assertThat(TextCodecs.encode(TextCodec.URL, text)).isEqualTo(case.getString("url"))
            assertThat(TextCodecs.decode(TextCodec.URL, case.getString("url"))).isEqualTo(Decoded.Text(text))
        }
        // The 2.15.3's Uri.encodeFull left these as they were (inventory F2).
        assertThat(TextCodecs.encode(TextCodec.URL, "a&b=c?d/e#f")).isEqualTo("a%26b%3Dc%3Fd%2Fe%23f")
    }

    @Test
    fun `a percent sign without two hexadecimal digits, or bytes that are not UTF-8, are refused`() {
        for (invalid in listOf("%", "100%", "%G0", "abc%2", "%%41", "%٣٣")) {
            assertThat(TextCodecs.decode(TextCodec.URL, invalid)).isEqualTo(Decoded.Refused(DecodeRefusal.PERCENT_INVALID))
        }
        assertThat(TextCodecs.decode(TextCodec.URL, "caf%E9")).isEqualTo(Decoded.Refused(DecodeRefusal.NOT_UTF8))
        assertThat(TextCodecs.decode(TextCodec.URL, "caf%c3%a9+x")).isEqualTo(Decoded.Text("café+x"))
    }

    @Test
    fun `HTML escaping goes back to the text, and Python's escaping is read too`() {
        for (case in cases) {
            val text = case.getString("text")
            assertThat(TextCodecs.decode(TextCodec.HTML, TextCodecs.encode(TextCodec.HTML, text))).isEqualTo(Decoded.Text(text))
            assertThat(TextCodecs.decode(TextCodec.HTML, case.getString("htmlEscaped"))).isEqualTo(Decoded.Text(text))
        }
        assertThat(TextCodecs.encode(TextCodec.HTML, "<a href=\"x\">it's & more</a>"))
            .isEqualTo("&lt;a href=&quot;x&quot;&gt;it&#39;s &amp; more&lt;/a&gt;")
    }

    @Test
    fun `HTML references are decoded once, as Python's html unescape reads them`() {
        for (sample in oracle.getJSONArray("html").objects()) {
            assertThat(TextCodecs.decode(TextCodec.HTML, sample.getString("html"))).isEqualTo(Decoded.Text(sample.getString("text")))
        }
        // The 2.15.3 decoded &amp; first, then the result again: "&amp;lt;" became "<" (inventory F2).
        assertThat(TextCodecs.decode(TextCodec.HTML, "&amp;lt;")).isEqualTo(Decoded.Text("&lt;"))
    }

    @Test
    fun `an empty text is an empty result every way`() {
        for (codec in TextCodec.entries) {
            assertThat(TextCodecs.encode(codec, "")).isEmpty()
            assertThat(TextCodecs.decode(codec, "")).isEqualTo(Decoded.Text(""))
        }
    }
}

internal fun resource(path: String): String {
    val stream = requireNotNull(TextCodecsTest::class.java.getResourceAsStream(path)) { "no resource $path" }
    return stream.use { it.readBytes().decodeToString() }
}

internal fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)

internal fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)
