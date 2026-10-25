package com.readfilestech.read_files_tech.ui.common

import android.content.res.Resources
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource

/**
 * A text decided away from the screen — in a ViewModel, in an operation that outlives its screen — and
 * resolved in the screen's language when shown. Every text goes through the resources (strings.xml), so
 * nothing written in code escapes the translations.
 */
sealed interface UiText {
    data class Res(@param:StringRes val id: Int, val args: List<Any> = emptyList()) : UiText

    data class Plural(@param:PluralsRes val id: Int, val count: Int, val args: List<Any> = listOf(count)) : UiText

    /** Two texts side by side (a result and its reason), joined by the resource [separator]. */
    data class Joined(val first: UiText, val second: UiText, @param:StringRes val separator: Int) : UiText
}

fun uiText(@StringRes id: Int, vararg args: Any): UiText = UiText.Res(id, args.toList())

// SpreadOperator: getString takes its arguments as a vararg; these arrays hold one or two of them.
@Suppress("SpreadOperator")
@Composable
fun UiText.resolve(): String = when (this) {
    is UiText.Res -> stringResource(id, *args.map { it.resolveArg() }.toTypedArray())
    is UiText.Plural -> pluralStringResource(id, count, *args.map { it.resolveArg() }.toTypedArray())
    is UiText.Joined -> stringResource(separator, first.resolve(), second.resolve())
}

/** An argument may itself be a [UiText] (a reason inside a sentence). */
@Composable
private fun Any.resolveArg(): Any = if (this is UiText) resolve() else this

/** The same, outside composition (a message collected in a coroutine). */
@Suppress("SpreadOperator") // as above
fun UiText.resolve(resources: Resources): String = when (this) {
    is UiText.Res -> resources.getString(id, *args.map { it.resolveArg(resources) }.toTypedArray())
    is UiText.Plural -> resources.getQuantityString(id, count, *args.map { it.resolveArg(resources) }.toTypedArray())
    is UiText.Joined -> resources.getString(separator, first.resolve(resources), second.resolve(resources))
}

private fun Any.resolveArg(resources: Resources): Any = if (this is UiText) resolve(resources) else this
