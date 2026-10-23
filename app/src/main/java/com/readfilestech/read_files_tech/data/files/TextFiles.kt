package com.readfilestech.read_files_tech.data.files

/**
 * The text files, by their extension: the ONE list the explorer's preview, the code editor (its menu entry,
 * its picker, the picker's "Edit"), the global search (which reads their content) and the diff's picker, the
 * content search and the TXT tools' picker read. They were four copies of four different 2.15.3 lists, each
 * missing some of the others': `previewExts` / `editableExts` (16, `file_viewer_router.dart:23-40`; the code
 * editor's own picker offered ten, `code_editor_screen.dart:50-61`), the global search's `_textExts` (23), the
 * content search's (19, `content_search_screen.dart:30-50`), the TXT tools' picker (5,
 * `txt_tools_screen.dart:42`). This is their union, but for `ts`: TypeScript to the content search,
 * an MPEG transport-stream VIDEO as often (a TV recording, a stream's segment), which the text viewer would
 * then take from the video player.
 *
 * Each opens in a viewer that shows it — its own (CSV, Markdown, JSON, HTML) or the text viewer (`ViewerKind`;
 * TextFilesTest). What the editor opens is still decided by the content (`EditableText.of`), never by the name.
 */
object TextFiles {
    val EXTENSIONS: Set<String> = setOf(
        "txt", "md", "csv", "tsv", "json", "xml", "html", "htm", "css", "js", "php", "dart", "py", "java", "kt", "sh", "bat",
        "yaml", "yml", "ini", "conf", "log", "rst", "tex",
    )
}
