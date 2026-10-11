package com.readfilestech.read_files_tech.data

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.util.Base64

/** Objects and numbers as `jsonDecode` of Dart 3.13.2 read them (tools/dart-vectors/json_object_date_oracle.dart). */
class DartJsonTest {

    @ParameterizedTest
    @MethodSource("objectOracle")
    fun `an object is read exactly as Dart read it`(case: ObjectCase) {
        when (val verdict = case.verdict) {
            "REFUSE" -> assertThrows<DartJson.Malformed> { DartJson.decode(case.json) }
            else -> {
                val value = DartJson.decode(case.json)
                if (verdict.startsWith("NOTMAP ")) {
                    assertThat(value).isNotInstanceOf(Map::class.java)
                } else {
                    val expected = verdict.removePrefix("ACCEPT").trim()
                    assertThat(describe(value as Map<*, *>)).isEqualTo(expected)
                }
            }
        }
    }

    @Test
    fun `the oracle covers ints, doubles, overflow, repeated keys and refusals`() {
        val verdicts = objectOracle().map { it.verdict }
        assertThat(verdicts.count { it == "REFUSE" }).isAtLeast(8)
        assertThat(verdicts.any { "double:9223372036854776000.0" in it }).isTrue()
        assertThat(verdicts.any { it == "ACCEPT 97=string:121" }).isTrue() // {"a":"x","a":"y"}
    }

    /** The oracle's own notation: `key codes=type:value`, `;`-separated, in key order. */
    private fun describe(map: Map<*, *>): String = map.entries.joinToString(";") { (k, v) ->
        "${(k as String).map { it.code }.joinToString(",")}=${describeValue(v)}"
    }

    private fun describeValue(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> "bool:$v"
        is Long -> "int:$v"
        is Double -> "double:" + DOUBLE_SPELLINGS.getOrElse(v) { v.toString() }
        is String -> "string:" + v.map { it.code }.joinToString(",")
        is List<*> -> "list:${v.size}"
        is Map<*, *> -> "map:${v.size}"
        else -> "other"
    }

    data class ObjectCase(val json: String, val verdict: String) {
        override fun toString() = json
    }

    companion object {
        /** How Dart spells the doubles of the oracle, which Kotlin spells otherwise (`1.0E3`). */
        private val DOUBLE_SPELLINGS = mapOf(
            1.0 to "1.0",
            1000.0 to "1000.0",
            -0.0015 to "-0.0015",
            9.223372036854776E18 to "9223372036854776000.0",
        )

        @JvmStatic
        fun objectOracle(): List<ObjectCase> {
            val resource = checkNotNull(DartJsonTest::class.java.getResource("/prefs/json-object-date-oracle-dart-3.13.2.txt"))
            val lines = resource.readText(Charsets.UTF_8).lines()
            return lines.subList(lines.indexOf("OBJECTS") + 1, lines.indexOf("DATES")).filter { it.isNotBlank() }.map { line ->
                val (encoded, verdict) = line.split(" ", limit = 2)
                ObjectCase(String(Base64.getDecoder().decode(encoded), Charsets.UTF_8), verdict.trimEnd())
            }
        }
    }
}
