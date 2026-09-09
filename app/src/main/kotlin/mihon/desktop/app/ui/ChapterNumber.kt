package mihon.desktop.app.ui

import eu.kanade.tachiyomi.source.model.SChapter

/** Extracts the chapter number from a chapter name (e.g. "Chapter 12.5" -> 12.5, "Ch 1" -> 1). */
fun SChapter.parseChapterNameNumber(): Double? {
    val name = this.name
    // Match "Chapter 12.5", "Ch 12.5", "Ch. 12.5", etc.
    val match = Regex("""[Cc]h(?:apter|\.)?\s*([\d.]+)""").find(name)
        ?: Regex("""\b([\d]+(?:\.[\d]+)?)\b""").find(name)
    return match?.groupValues?.get(1)?.toDoubleOrNull()
}

/**
 * Best chapter number for reading-progress pushes (tracker sync). Uses the
 * source-provided [SChapter.chapter_number] when it is valid (>= 0); sources
 * that leave it unset fall back to parsing the chapter name. Keeps the
 * reader's push and MangaDetail's mark-as-read push consistent.
 */
fun SChapter.chapterNumberOrFallback(): Double? =
    chapter_number.takeIf { it >= 0 }?.toDouble() ?: parseChapterNameNumber()
