package com.readfilestech.read_files_tech.ui.tools.encode

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.tools.DecodeRefusal
import com.readfilestech.read_files_tech.data.tools.Decoded
import com.readfilestech.read_files_tech.data.tools.TextCodec
import com.readfilestech.read_files_tech.data.tools.TextCodecs
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import com.readfilestech.read_files_tech.ui.tools.copyableResult
import com.readfilestech.read_files_tech.ui.tools.resultCopied
import com.readfilestech.read_files_tech.ui.viewer.loadOrFailure
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** The encoding tool's screen: the text typed, the coding and its way, and the result or why there is none. */
data class EncodeState(
    val input: String = "",
    val codec: TextCodec = TextCodec.BASE64,
    val encoding: Boolean = true,
    val output: String = "",
    val refusal: DecodeRefusal? = null,
)

/**
 * `EncodeScreen` of the 2.15.3, whose result did not follow the text typed (inventory F1: `_process()`
 * changed the result's controller without `setState`, `encode_screen.dart:25-48`): here the result is
 * made again, off the main thread, at every change of the text, the coding or the way — the last change
 * only. The text is not kept across a process death, as the 2.15.3's was not: a pasted text of a megabyte
 * would not fit in the saved state.
 */
@HiltViewModel
class EncodeViewModel @Inject constructor(private val messages: UserMessages) : ViewModel() {
    private val mutableState = MutableStateFlow(EncodeState())
    val state: StateFlow<EncodeState> = mutableState.asStateFlow()
    private var job: Job? = null

    fun input(text: String) = change { it.copy(input = text) }

    fun codec(codec: TextCodec) = change { it.copy(codec = codec) }

    fun encoding(encoding: Boolean) = change { it.copy(encoding = encoding) }

    fun clear() = change { it.copy(input = "") }

    /** The result becomes the text, and the way turns (the 2.15.3's "Inverser"); a refusal does not. */
    fun swap() = change { if (it.refusal != null) it else it.copy(input = it.output, encoding = !it.encoding) }

    fun copyText(): String? = state.value.output.let { copyableResult(it, it.length.toLong(), messages) }

    fun copied() = resultCopied(messages)

    private fun change(edit: (EncodeState) -> EncodeState) {
        mutableState.update(edit)
        val asked = mutableState.value
        // Cancelled by the next change before it shows: a result is never older than the text.
        job?.cancel()
        job = viewModelScope.launch {
            // A pasted text of many megabytes may not fit in memory: said in words, never the end of the app
            // (Claude review R17 — the other tools' results already went through loadOrFailure).
            loadOrFailure { withContext(Dispatchers.Default) { resultOf(asked) } }
                .onSuccess { result -> mutableState.update { it.copy(output = result.first, refusal = result.second) } }
                .onFailure {
                    mutableState.update { it.copy(output = "", refusal = null) }
                    messages.error(uiText(R.string.error_unexpected))
                }
        }
    }

    private fun resultOf(state: EncodeState): Pair<String, DecodeRefusal?> = when {
        state.input.isEmpty() -> "" to null
        state.encoding -> TextCodecs.encode(state.codec, state.input) to null
        else -> when (val decoded = TextCodecs.decode(state.codec, state.input)) {
            is Decoded.Text -> decoded.text to null
            is Decoded.Refused -> "" to decoded.reason
        }
    }
}
