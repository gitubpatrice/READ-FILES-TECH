package com.readfilestech.read_files_tech.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.readfilestech.read_files_tech.data.viewer.text.CodeStyle

/**
 * The colours of code in the 2.15.3 (flutter_highlight 0.7.0): highlight.js's "github" theme in light,
 * "atom-one-dark" in dark, class by class. Two take the CSS of highlight.js where the Dart port lost it: an
 * XML attribute (`.hljs-tag .hljs-attr`, teal in github), and a CSS selector (github's red title, which its
 * `selector-id` has).
 */
object CodeColors {
    fun style(style: CodeStyle, dark: Boolean): SpanStyle = if (dark) dark(style) else light(style)

    private fun light(style: CodeStyle): SpanStyle = when (style) {
        CodeStyle.COMMENT -> SpanStyle(color = Color(0xFF999988), fontStyle = FontStyle.Italic)
        CodeStyle.KEYWORD -> SpanStyle(color = Color(0xFF333333), fontWeight = FontWeight.Bold)
        CodeStyle.BUILT_IN -> SpanStyle(color = Color(0xFF0086B3))
        CodeStyle.LITERAL, CodeStyle.NUMBER, CodeStyle.VARIABLE, CodeStyle.ATTRIBUTE -> SpanStyle(color = Color(0xFF008080))
        CodeStyle.STRING -> SpanStyle(color = Color(0xFFDD1144))
        CodeStyle.REGEXP, CodeStyle.LINK -> SpanStyle(color = Color(0xFF009926))
        CodeStyle.META -> SpanStyle(color = Color(0xFF999999), fontWeight = FontWeight.Bold)
        CodeStyle.TAG, CodeStyle.PROPERTY -> SpanStyle(color = Color(0xFF000080))
        CodeStyle.SELECTOR, CodeStyle.SECTION -> SpanStyle(color = Color(0xFF990000), fontWeight = FontWeight.Bold)
        CodeStyle.BULLET -> SpanStyle(color = Color(0xFF990073))
        CodeStyle.EMPHASIS -> SpanStyle(fontStyle = FontStyle.Italic)
        CodeStyle.STRONG -> SpanStyle(fontWeight = FontWeight.Bold)
    }

    private fun dark(style: CodeStyle): SpanStyle = when (style) {
        CodeStyle.COMMENT -> SpanStyle(color = Color(0xFF5C6370), fontStyle = FontStyle.Italic)
        CodeStyle.KEYWORD -> SpanStyle(color = Color(0xFFC678DD))
        CodeStyle.BUILT_IN -> SpanStyle(color = Color(0xFFE6C07B))
        CodeStyle.LITERAL -> SpanStyle(color = Color(0xFF56B6C2))
        CodeStyle.NUMBER, CodeStyle.VARIABLE, CodeStyle.ATTRIBUTE -> SpanStyle(color = Color(0xFFD19A66))
        CodeStyle.STRING, CodeStyle.REGEXP, CodeStyle.PROPERTY -> SpanStyle(color = Color(0xFF98C379))
        CodeStyle.META, CodeStyle.BULLET, CodeStyle.LINK -> SpanStyle(color = Color(0xFF61AEEE))
        CodeStyle.TAG, CodeStyle.SELECTOR, CodeStyle.SECTION -> SpanStyle(color = Color(0xFFE06C75))
        CodeStyle.EMPHASIS -> SpanStyle(fontStyle = FontStyle.Italic)
        CodeStyle.STRONG -> SpanStyle(fontWeight = FontWeight.Bold)
    }
}
