package com.readfilestech.read_files_tech.ui.explorer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.browse.BulkRename
import com.readfilestech.read_files_tech.data.browse.FileEntry
import com.readfilestech.read_files_tech.data.browse.NameProblem
import com.readfilestech.read_files_tech.data.browse.RenameProposal
import com.readfilestech.read_files_tech.data.browse.RenameRule
import com.readfilestech.read_files_tech.data.search.InvalidPatternException
import com.readfilestech.read_files_tech.data.search.SearchPattern
import com.readfilestech.read_files_tech.ui.common.UiText
import com.readfilestech.read_files_tech.ui.common.resolve
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.tools.files.patternProblem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** The bulk rename's three modes, in the 2.15.3's order. */
private enum class RenameMode(val label: Int) {
    NUMBERING(R.string.bulk_rename_numbering),
    AFFIXES(R.string.bulk_rename_affixes),
    REPLACE(R.string.bulk_rename_replace),
}

/** The preview: every new name, or why there is none. */
private sealed interface Preview {
    data object Working : Preview

    data class Ready(val proposals: List<RenameProposal>) : Preview

    data class Refused(val reason: UiText) : Preview
}

/** The preview took longer than [PREVIEW_DEADLINE_MS]: a pattern too slow on these names. */
private class PreviewTooSlowException : RuntimeException("preview too slow")

private const val PREVIEW_DEADLINE_MS = 2_000L
private const val TYPING_PAUSE_MS = 150L
private const val NANOS_PER_MILLI = 1_000_000L
private const val MAX_DIGITS = 6

/** The 2.15.3's: `Photo_001`. */
private const val DEFAULT_DIGITS = 3

/**
 * The bulk rename of the explorer's selection (`BulkRenameScreen` of the 2.15.3, opened from `FES:728` only):
 * number, prefix and suffix, or a regular expression with `$1`; the extension kept unless unticked; every new
 * name previewed as it is typed — worked out off the main thread, within [PREVIEW_DEADLINE_MS] — and the whole
 * batch refused while one name is ([BulkRename.preview]). [onRename] hands the batch to the explorer's own
 * operations, which finish it whatever screen is on top.
 */
@Composable
fun BulkRenameDialog(entries: List<FileEntry>, onRename: (List<RenameProposal>) -> Unit, onDismiss: () -> Unit) {
    var mode by rememberSaveable { mutableStateOf(RenameMode.NUMBERING) }
    val defaultBase = stringResource(R.string.bulk_rename_base_default)
    var base by rememberSaveable { mutableStateOf(defaultBase) }
    var start by rememberSaveable { mutableStateOf("1") }
    var digits by rememberSaveable { mutableStateOf(DEFAULT_DIGITS.toString()) }
    var prefix by rememberSaveable { mutableStateOf("") }
    var suffix by rememberSaveable { mutableStateOf("") }
    var pattern by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    var keepExtension by rememberSaveable { mutableStateOf(true) }
    var preview by remember { mutableStateOf<Preview>(Preview.Working) }
    // The names in each folder of the selection, read once: what a new name must not take.
    var existing by remember { mutableStateOf<Map<File, Set<String>>?>(null) }
    LaunchedEffect(entries) {
        existing = withContext(Dispatchers.IO) {
            entries.mapNotNull { it.file.absoluteFile.parentFile }.distinct().associateWith { it.list().orEmpty().toSet() }
        }
    }
    val inputs = listOf(mode, base, start, digits, prefix, suffix, pattern, replacement, keepExtension, existing)
    LaunchedEffect(inputs) {
        val folders = existing ?: return@LaunchedEffect
        preview = Preview.Working
        delay(TYPING_PAUSE_MS)
        val rule = try {
            when (mode) {
                RenameMode.NUMBERING ->
                    RenameRule.Numbering(
                        base,
                        start.toIntOrNull()?.coerceAtLeast(0) ?: 1,
                        (digits.toIntOrNull() ?: DEFAULT_DIGITS).coerceIn(0, MAX_DIGITS)
                    )
                RenameMode.AFFIXES -> RenameRule.Affixes(prefix, suffix)
                // A pattern is a regular expression, case included, as the 2.15.3's RegExp.
                RenameMode.REPLACE -> RenameRule.Replace(
                    pattern.takeIf(String::isNotEmpty)?.let { SearchPattern.regex(it, matchCase = true) },
                    replacement
                )
            }
        } catch (e: InvalidPatternException) {
            preview = Preview.Refused(patternProblem(e.problem))
            return@LaunchedEffect
        }
        preview = withContext(Dispatchers.Default) {
            val deadline = System.nanoTime() + PREVIEW_DEADLINE_MS * NANOS_PER_MILLI
            val job = coroutineContext
            try {
                Preview.Ready(
                    BulkRename.preview(entries, rule, keepExtension, { folders[it].orEmpty() }) {
                        job.ensureActive()
                        if (System.nanoTime() > deadline) throw PreviewTooSlowException()
                    },
                )
            } catch (e: InvalidPatternException) {
                Preview.Refused(patternProblem(e.problem))
            } catch (expected: PreviewTooSlowException) {
                Preview.Refused(uiText(R.string.pattern_too_slow))
            }
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.systemBarsPadding().imePadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onDismiss
                    ) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.action_cancel)) }
                    Text(
                        pluralStringResource(R.plurals.bulk_rename_title, entries.size, entries.size),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    RenameMode.entries.forEachIndexed { index, choice ->
                        SegmentedButton(
                            selected = mode == choice,
                            onClick = { mode = choice },
                            shape = SegmentedButtonDefaults.itemShape(index, RenameMode.entries.size),
                        ) { Text(stringResource(choice.label)) }
                    }
                }
                when (mode) {
                    RenameMode.NUMBERING -> {
                        Field(base, { base = it }, R.string.bulk_rename_base, hint = R.string.bulk_rename_base_hint)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Field(start, { start = it }, R.string.bulk_rename_start, number = true, modifier = Modifier.weight(1f))
                            Field(digits, { digits = it }, R.string.bulk_rename_digits, number = true, modifier = Modifier.weight(1f))
                        }
                    }
                    RenameMode.AFFIXES -> {
                        Field(prefix, { prefix = it }, R.string.bulk_rename_prefix)
                        Field(suffix, { suffix = it }, R.string.bulk_rename_suffix)
                    }
                    RenameMode.REPLACE -> {
                        Field(pattern, { pattern = it }, R.string.bulk_rename_pattern, hint = R.string.bulk_rename_pattern_hint)
                        Field(
                            replacement,
                            { replacement = it },
                            R.string.bulk_rename_replacement,
                            hint = R.string.bulk_rename_replacement_hint
                        )
                    }
                }
                // The label ticks too, the box alone was the target; 48 dp high, as the box was (Claude review, phase 8).
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(value = keepExtension, role = Role.Checkbox, onValueChange = { keepExtension = it }),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = keepExtension, onCheckedChange = null)
                    Text(stringResource(R.string.bulk_rename_keep_extension))
                }
                HorizontalDivider()
                PreviewList(preview, Modifier.weight(1f))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
                    Spacer(Modifier.size(8.dp))
                    val ready = (preview as? Preview.Ready)?.proposals
                    val allowed = ready != null && ready.none { it.problem != null } && ready.any(RenameProposal::changes)
                    Button(onClick = { ready?.let(onRename) }, enabled = allowed) { Text(stringResource(R.string.action_rename)) }
                }
            }
        }
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: Int,
    modifier: Modifier = Modifier,
    hint: Int? = null,
    number: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        label = { Text(stringResource(label)) },
        placeholder = hint?.let { { Text(stringResource(it)) } },
        keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun PreviewList(preview: Preview, modifier: Modifier) {
    when (preview) {
        Preview.Working -> Spacer(modifier)
        is Preview.Refused -> Column(modifier) { Text(preview.reason.resolve(), color = MaterialTheme.colorScheme.error) }
        is Preview.Ready -> Column(modifier) {
            val refused = preview.proposals.count { it.problem != null }
            if (refused > 0) {
                Text(pluralStringResource(R.plurals.bulk_rename_refused, refused, refused), color = MaterialTheme.colorScheme.error)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(preview.proposals, key = { it.entry.path }) { ProposalRow(it) }
            }
        }
    }
}

@Composable
private fun ProposalRow(proposal: RenameProposal) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                proposal.entry.name,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(14.dp))
                Spacer(Modifier.size(4.dp))
                Text(
                    proposal.newName,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = if (proposal.problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val note = proposal.problem?.let {
                stringResource(problemText(it))
            } ?: if (!proposal.changes) stringResource(R.string.bulk_rename_unchanged) else null
            note?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (proposal.problem != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun problemText(problem: NameProblem): Int = when (problem) {
    NameProblem.INVALID -> R.string.error_invalid_name
    NameProblem.TOO_LONG -> R.string.error_name_too_long
    NameProblem.DUPLICATE -> R.string.bulk_rename_duplicate
    NameProblem.TAKEN -> R.string.error_already_exists
}
