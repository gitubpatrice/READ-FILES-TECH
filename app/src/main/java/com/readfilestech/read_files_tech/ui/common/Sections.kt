package com.readfilestech.read_files_tech.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A card of the portfolio (Notes Tech's `CarteFilesTech`): the surface, a 1 dp border, radius 12, no shadow — one
 * per section of the settings and of About, its lines parted by dividers, and each tool of the home. The border is
 * not decoration: the card is the page's own colour in both themes, and without it the grouping disappears.
 *
 * @param border the outline; the panic's card tints it red.
 * @param onClick the card as one button (a tool); null for a card whose lines are the targets.
 */
@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    border: Color = MaterialTheme.colorScheme.outlineVariant,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val stroke = BorderStroke(1.dp, border)
    val color = MaterialTheme.colorScheme.surface
    if (onClick == null) {
        Surface(modifier = modifier.fillMaxWidth(), shape = shape, color = color, border = stroke) { Column(content = content) }
    } else {
        Surface(onClick = onClick, modifier = modifier.fillMaxWidth(), shape = shape, color = color, border = stroke) {
            Column(content = content)
        }
    }
}

/**
 * The title of a section (the settings, About, the home): the primary colour, semi-bold, a heading for TalkBack,
 * which lets a user jump from one to the next. 16 dp above it, 8 below: the rhythm of a page of sections.
 */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.padding(start = 2.dp, top = 16.dp, bottom = 8.dp).semantics { heading() },
    )
}
