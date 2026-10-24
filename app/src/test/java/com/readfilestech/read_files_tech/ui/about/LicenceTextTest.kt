package com.readfilestech.read_files_tech.ui.about

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.io.File

class LicenceTextTest {
    @Test
    fun `lines the file wrapped are joined, the paragraphs kept apart`() {
        val text = """
            Redistribution and use in source and binary forms, with or without
            modification, are permitted provided that the following conditions
            are met.

            THIS SOFTWARE IS PROVIDED "AS IS".
        """.trimIndent()
        assertThat(LicenceText.paragraphs(text)).containsExactly(
            "Redistribution and use in source and binary forms, with or without modification, are permitted " +
                "provided that the following conditions are met.",
            "THIS SOFTWARE IS PROVIDED \"AS IS\".",
        ).inOrder()
    }

    /** Copyright lines, a list, a new sentence after a finished one: each stays a line of its own. */
    @Test
    fun `lines their author ended stay lines`() {
        val text = """
            Copyright (c) 1995-2025 The PNG Reference Library Authors.
            Copyright (c) 2018-2025 Cosmin Truta.
              - AndroidX and Jetpack Compose (activity, annotation, collection, core,
                lifecycle, navigation) — The Android Open Source Project, Google LLC.
              - Kotlin standard library — JetBrains s.r.o. and Kotlin Programming Language
                contributors.
            Part 1  Code and data ported into the app's own source, and the OCR stack.
            Part 2  Libraries under the Apache License 2.0, and the NOTICE files of those
                    that have one.
        """.trimIndent()
        assertThat(LicenceText.paragraphs(text).single().lines()).containsExactly(
            "Copyright (c) 1995-2025 The PNG Reference Library Authors.",
            "Copyright (c) 2018-2025 Cosmin Truta.",
            "  - AndroidX and Jetpack Compose (activity, annotation, collection, core, lifecycle, navigation) — " +
                "The Android Open Source Project, Google LLC.",
            "  - Kotlin standard library — JetBrains s.r.o. and Kotlin Programming Language contributors.",
            "Part 1  Code and data ported into the app's own source, and the OCR stack.",
            "Part 2  Libraries under the Apache License 2.0, and the NOTICE files of those that have one.",
        ).inOrder()
    }

    /**
     * A short line was ended by its author, inside a sentence too — a name above its address, a list of holders;
     * what decides is the line in the FILE, not the line already joined above it.
     */
    @Test
    fun `a short line ends its line, even inside a sentence`() {
        val text = """
            Tesseract4Android 4.9.0
            https://github.com/adaptech-cz/Tesseract4Android
            This product includes software developed at the Apache Software Foundation, by
            its contributors,
            listed in its repository.
        """.trimIndent()
        assertThat(LicenceText.paragraphs(text).single().lines()).containsExactly(
            "Tesseract4Android 4.9.0",
            "https://github.com/adaptech-cz/Tesseract4Android",
            "This product includes software developed at the Apache Software Foundation, by its contributors,",
            "listed in its repository.",
        ).inOrder()
    }

    @Test
    fun `a rule is a paragraph of its own, drawn apart`() {
        val text = "Read Files Tech\n\n" + "-".repeat(80) + "\nPart 1 — Code and data\n"
        assertThat(LicenceText.paragraphs(text)).containsExactly("Read Files Tech", LicenceText.RULE, "Part 1 — Code and data").inOrder()
    }

    /** The two files the app shows: nothing of them lost — every word still there, in order. */
    @Test
    fun `the notices and the licence keep every word`() {
        for (name in listOf("NOTICE", "LICENSE")) {
            val file = generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, name) }.first(File::isFile)
            val text = file.readText()
            val shown = LicenceText.paragraphs(text).filter { it != LicenceText.RULE }.joinToString(" ")
            val words = { s: String -> s.split(Regex("\\s+")).filter { it.isNotEmpty() && !it.all { c -> c == '-' } } }
            assertThat(words(shown)).containsExactlyElementsIn(words(text)).inOrder()
        }
    }
}
