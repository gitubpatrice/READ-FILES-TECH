package com.readfilestech.read_files_tech.ui.tools.color

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import com.readfilestech.read_files_tech.R
import com.readfilestech.read_files_tech.data.tools.Hsv
import com.readfilestech.read_files_tech.data.tools.Rgb
import com.readfilestech.read_files_tech.ui.common.UserMessages
import com.readfilestech.read_files_tech.ui.common.uiText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject

/**
 * `ColorPickerScreen` of the 2.15.3: the colour chosen, as the picker's hue, saturation and value — kept
 * in the saved state, the 2.15.3 lost it on a rotation — from `#1565C0`, the app's blue, as there.
 */
@HiltViewModel
class ColorPickerViewModel @Inject constructor(private val handle: SavedStateHandle, private val messages: UserMessages) : ViewModel() {
    private val mutableColor = MutableStateFlow(
        handle.get<DoubleArray>(KEY_HSV)?.takeIf { it.size == HSV_SIZE }?.let { Hsv(it[0], it[1], it[2]) } ?: INITIAL.toHsv(),
    )
    val color: StateFlow<Hsv> = mutableColor.asStateFlow()

    fun pick(hsv: Hsv) {
        mutableColor.value = hsv
        handle[KEY_HSV] = doubleArrayOf(hsv.hue, hsv.saturation, hsv.value)
    }

    fun pick(rgb: Rgb) = pick(rgb.toHsv())

    fun copied(value: String) = messages.info(uiText(R.string.value_copied, value))

    companion object {
        /** The 12 colours of the palette, as the 2.15.3 (`color_picker_screen.dart:86-98`). */
        val PALETTE: List<Rgb> = listOf(
            "#FF0000", "#FF6600", "#FFCC00", "#33CC33", "#0066FF", "#9900CC",
            "#FF0099", "#000000", "#FFFFFF", "#666666", "#1565C0", "#C62828",
        ).map { requireNotNull(Rgb.parseHex(it)) }

        private val INITIAL = requireNotNull(Rgb.parseHex("#1565C0"))
        private const val KEY_HSV = "color_hsv"
        private const val HSV_SIZE = 3
    }
}
