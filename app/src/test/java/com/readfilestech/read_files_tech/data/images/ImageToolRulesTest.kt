package com.readfilestech.read_files_tech.data.images

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ImageToolRulesTest {
    @Test
    fun `a wider picture is brought to the width asked, the height in proportion, a narrower one keeps its size`() {
        assertThat(ImageToolRules.fitWidth(4000, 3000, 1920)).isEqualTo(ImageSize(1920, 1440))
        assertThat(ImageToolRules.fitWidth(3000, 4000, 1920)).isEqualTo(ImageSize(1920, 2560))
        assertThat(ImageToolRules.fitWidth(1921, 1081, 1920)).isEqualTo(ImageSize(1920, 1080))
        assertThat(ImageToolRules.fitWidth(1920, 1080, 1920)).isEqualTo(ImageSize(1920, 1080))
        assertThat(ImageToolRules.fitWidth(640, 480, 1920)).isEqualTo(ImageSize(640, 480))
        // A panorama a pixel high stays a pixel high.
        assertThat(ImageToolRules.fitWidth(12_000, 1, 480)).isEqualTo(ImageSize(480, 1))
    }

    @Test
    fun `a PNG or a transparent picture stays lossless, everything else becomes a JPEG`() {
        assertThat(ImageToolRules.strippedFormat("png", hasAlpha = false)).isEqualTo(ImageFormat.PNG)
        assertThat(ImageToolRules.strippedFormat("PNG", hasAlpha = false)).isEqualTo(ImageFormat.PNG)
        assertThat(ImageToolRules.strippedFormat("webp", hasAlpha = true)).isEqualTo(ImageFormat.PNG)
        assertThat(ImageToolRules.strippedFormat("webp", hasAlpha = false)).isEqualTo(ImageFormat.JPEG)
        assertThat(ImageToolRules.strippedFormat("jpeg", hasAlpha = false)).isEqualTo(ImageFormat.JPEG)
        assertThat(ImageToolRules.strippedFormat("heic", hasAlpha = false)).isEqualTo(ImageFormat.JPEG)
        assertThat(ImageFormat.JPEG.extension).isEqualTo("jpg")
    }

    @Test
    fun `the output's name is the source's without its last extension, as the 2_15_3`() {
        assertThat(ImageToolRules.baseName("IMG_0001.JPG")).isEqualTo("IMG_0001")
        assertThat(ImageToolRules.baseName("holiday.2026.photo.jpeg")).isEqualTo("holiday.2026.photo")
        assertThat(ImageToolRules.baseName("no extension")).isEqualTo("no extension")
    }

    @Test
    fun `the reduction is said only when the copy is smaller`() {
        assertThat(ImageToolRules.reductionPercent(1000, 600)).isEqualTo(40)
        assertThat(ImageToolRules.reductionPercent(1000, 999)).isEqualTo(0)
        assertThat(ImageToolRules.reductionPercent(1000, 1000)).isNull()
        assertThat(ImageToolRules.reductionPercent(1000, 1500)).isNull()
        assertThat(ImageToolRules.reductionPercent(0, 10)).isNull()
    }

    @Test
    fun `the sliders are the 2_15_3's - quality 10 to 100 by 5, width 480 to 4096 in 18 steps, 80 and 1920 at first`() {
        val intervals = ImageToolRules.SLIDER_STEPS + 1
        assertThat(intervals).isEqualTo(18)
        assertThat((ImageToolRules.QUALITY_MAX - ImageToolRules.QUALITY_MIN) / intervals).isEqualTo(5)
        assertThat(ImageToolRules.QUALITY_DEFAULT).isEqualTo(80)
        assertThat(ImageToolRules.WIDTH_MIN to ImageToolRules.WIDTH_MAX).isEqualTo(480 to 4096)
        assertThat(ImageToolRules.WIDTH_DEFAULT).isEqualTo(1920)
        assertThat(ImageToolRules.STRIP_QUALITY).isEqualTo(95)
    }

    @Test
    fun `Remove EXIF takes the formats that carry EXIF, all of which Compress takes`() {
        assertThat(ImageToolRules.METADATA_EXTENSIONS).containsExactly("jpg", "jpeg", "png", "webp", "heic", "heif")
        assertThat(ImageToolRules.COMPRESS_EXTENSIONS).containsAtLeastElementsIn(ImageToolRules.METADATA_EXTENSIONS)
    }
}
