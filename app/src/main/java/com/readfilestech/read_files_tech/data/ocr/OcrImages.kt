package com.readfilestech.read_files_tech.data.ocr

import android.graphics.Bitmap
import androidx.core.graphics.scale
import com.readfilestech.read_files_tech.data.images.ImageDecoding
import com.readfilestech.read_files_tech.data.images.ImageExport
import com.readfilestech.read_files_tech.data.viewer.UnreadableFileException
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The picture the OCR reads, made by the image viewer's decoder within its bounds ([ImageDecoding]): the
 * file's size, then the dimensions its header announces, checked before a pixel is allocated — a picture
 * of 100 megapixels is refused there, in words —, the EXIF orientation applied, so that the picture is
 * upright before Tesseract sees it; subsampled to [MAX_PIXELS] at most; on white where it is transparent,
 * in ARGB_8888 (what Tesseract's binding takes). The 2.15.3 handed the path to ML Kit
 * (`InputImage.fromFilePath`, `ocr_screen.dart:44`): no cap, the whole image decoded whatever its size.
 */
object OcrImages {
    /**
     * Pixels decoded at most: 16 million, 64 MB of ARGB — and no other resizing for Tesseract. A page
     * photographed whole by a 12 Mpx sensor (the S9's 4032 × 3024) gives its 11-point body text capitals of
     * about 25 to 35 pixels, so such a photo is read WHOLE, and one of 48 to 50 Mpx (the full-resolution mode of newer
     * sensors) is halved to 12, its characters as large as on the first. Measured on the oracle
     * (tools/oracles/ocr_oracle.py, emulator, 2026-10-23): Tesseract read capitals from about 9 pixels (Times
     * 26 px at half size) to 54 (the 48 Mpx page halved) at 98.4 to 100 % of the words; doubled to 108, 95 %.
     * Halving the 12 Mpx photo kept its words and took 0.25 s instead of 0.6, but would bring a page's small
     * print below those 9 pixels: not done. A budget of pixels, not of side: a 1080 × 20 000 screenshot halves
     * to 540 × 10 000, where a limit of 4096 pixels a side would divide its text by eight.
     */
    const val MAX_PIXELS = 16_000_000L

    /** The picture shown above the text: this many pixels on its longest side at most. */
    const val PREVIEW_SIDE = 720

    /**
     * The smallest power of two that brings a [width] × [height] picture to [MAX_PIXELS] at most — the one
     * rule of the platform's decoders, which produce `ceil(side / sample)` pixels a side.
     */
    fun sampleFor(width: Int, height: Int): Int {
        var sample = 1
        while (ceilDiv(width, sample) * ceilDiv(height, sample) > MAX_PIXELS) sample *= 2
        return sample
    }

    /**
     * The picture of [file], upright, ready for recognition.
     *
     * @throws java.io.IOException refused or unreadable, as the image viewer refuses it.
     */
    fun decode(file: File): Bitmap {
        val flat = ImageExport.opaque(ImageDecoding.still(file, ::sampleFor))
        if (flat.config == Bitmap.Config.ARGB_8888) return flat
        // A 16-bit PNG or a 10-bit HEIF decodes to RGBA_F16 (or 1010102): Tesseract's binding takes 8 bits a channel.
        return ImageExport.replaced(flat) {
            it.copy(Bitmap.Config.ARGB_8888, false) ?: throw UnreadableFileException("not converted")
        }
    }

    /** [picture] at [PREVIEW_SIDE] pixels on its longest side at most, [picture] itself when smaller. */
    fun preview(picture: Bitmap): Bitmap {
        val ratio = PREVIEW_SIDE.toDouble() / max(picture.width, picture.height)
        if (ratio >= 1) return picture
        return picture.scale(max(1, (picture.width * ratio).roundToInt()), max(1, (picture.height * ratio).roundToInt()))
    }

    private fun ceilDiv(value: Int, divisor: Int): Long = (value.toLong() + divisor - 1) / divisor
}
