package com.readfilestech.read_files_tech.data.sharing

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.readfilestech.read_files_tech.vault.VaultFiles
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What the FileProvider may serve, exactly (`res/xml/file_paths.xml`): an entry added there is a way out of
 * the app, and must be added here too, read and argued. Above all, never the viewers' copies of vault files
 * (`cache/vault_decrypt/`, docs D-028) nor any private folder that holds them.
 */
class FileProviderPathsTest {
    private val paths: Set<Pair<String, String>> by lazy {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(PATHS))
        val children = document.documentElement.childNodes
        (0 until children.length).mapNotNull { children.item(it) as? Element }
            .map { it.tagName to it.getAttribute("path") }
            .toSet()
    }

    @Test
    fun `the provider serves shared storage and three private folders, nothing else`() {
        assertThat(paths).containsExactly(
            "external-path" to ".",
            "root-path" to "storage/",
            "cache-path" to "zip_shared/",
            "cache-path" to "scanner/capture/",
            "cache-path" to "${VaultFiles.SHARE_DIR}/",
        )
    }

    @Test
    fun `no entry covers the copies the viewers read`() {
        val decrypt = "${VaultFiles.DECRYPT_DIR}/"
        for ((tag, path) in paths.filter { it.first == "cache-path" }) {
            assertWithMessage("$tag $path").that(decrypt.startsWith(path.trimStart('.', '/')) || path in setOf("", ".", "/")).isFalse()
        }
        assertThat(paths.map { it.first }).containsNoneOf("files-path", "external-cache-path", "external-files-path")
    }

    private companion object {
        const val PATHS = "src/main/res/xml/file_paths.xml"
    }
}
