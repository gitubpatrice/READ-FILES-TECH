package com.readfilestech.read_files_tech.data.viewer.pdf

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** The rendered pages put aside: never recycled while drawn, the oldest recycled past the bound. */
class RecyclingCacheTest {
    /** A page's render: its bytes, and whether it was recycled (a Bitmap's recycle()). */
    private class Render(val bytes: Long) {
        var recycled = 0
    }

    private val cache = RecyclingCache<Int, Render>(maxBytes = 100, sizeOf = { it.bytes }, recycle = { it.recycled++ })

    @Test
    fun `a render given back is taken again, and is no longer in the cache once taken`() {
        val page = Render(10)
        cache.give(1, page)

        assertThat(cache.take(1)).isSameInstanceAs(page)
        assertThat(cache.take(1)).isNull()
        assertThat(page.recycled).isEqualTo(0)
    }

    @Test
    fun `past the bound the oldest given back are recycled, the newest kept`() {
        val renders = (1..4).map { Render(40) }
        renders.forEachIndexed { index, render -> cache.give(index, render) }

        assertThat(renders.map { it.recycled }).containsExactly(1, 1, 0, 0).inOrder()
        assertThat(cache.take(0)).isNull()
        assertThat(cache.take(3)).isSameInstanceAs(renders[3])
    }

    @Test
    fun `a render taken is never recycled, whatever comes in after`() {
        val shown = Render(60)
        cache.give(1, shown)
        cache.take(1)
        repeat(5) { cache.give(10 + it, Render(60)) }

        assertThat(shown.recycled).isEqualTo(0)
    }

    @Test
    fun `a render alone above the bound stays until the next one comes`() {
        val big = Render(500)
        cache.give(1, big)
        assertThat(big.recycled).isEqualTo(0)

        cache.give(2, Render(1))
        assertThat(big.recycled).isEqualTo(1)
    }

    @Test
    fun `a render given back under a key already kept replaces it, recycled`() {
        val old = Render(10)
        cache.give(1, old)
        cache.give(1, Render(10))

        assertThat(old.recycled).isEqualTo(1)
    }

    @Test
    fun `clear recycles what is kept, and what comes back after`() {
        val kept = Render(10)
        val late = Render(10)
        cache.give(1, kept)
        cache.clear()
        cache.give(2, late)

        assertThat(kept.recycled).isEqualTo(1)
        assertThat(late.recycled).isEqualTo(1)
        assertThat(cache.take(2)).isNull()
    }
}
