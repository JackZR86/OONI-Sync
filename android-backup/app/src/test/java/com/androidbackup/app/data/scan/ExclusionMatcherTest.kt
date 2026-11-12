package com.androidbackup.app.data.scan

import org.junit.Assert.*
import org.junit.Test

/**
 * Test unitari dedicati a [ExclusionMatcher] (Fase 1B.2).
 * Verifica le regole di matching, wildcard, percorsi relativi e preservazione dei file nascosti.
 */
class ExclusionMatcherTest {

    private val defaultExclusions = listOf("cache", "code_cache", ".thumbnails", "tmp", "*.tmp", "*.temp", "*.part", "*.bak")

    @Test
    fun testFileExtensionExclusions() {
        assertTrue(ExclusionMatcher.matches("documento.tmp", "docs/documento.tmp", defaultExclusions))
        assertTrue(ExclusionMatcher.matches("archivio.temp", "archivio.temp", defaultExclusions))
        assertTrue(ExclusionMatcher.matches("download.part", "downloads/download.part", defaultExclusions))
        assertTrue(ExclusionMatcher.matches("database.bak", "db/backup/database.bak", defaultExclusions))

        assertFalse(ExclusionMatcher.matches("documento.pdf", "docs/documento.pdf", defaultExclusions))
        assertFalse(ExclusionMatcher.matches("foto.jpg", "DCIM/foto.jpg", defaultExclusions))
    }

    @Test
    fun testDirectoryNameExclusions() {
        // Corrispondenza sul nome esatto della cartella
        assertTrue(ExclusionMatcher.matches("cache", "cache", defaultExclusions))
        assertTrue(ExclusionMatcher.matches(".thumbnails", ".thumbnails", defaultExclusions))
        assertTrue(ExclusionMatcher.matches("tmp", "tmp", defaultExclusions))

        // Cartelle lecite
        assertFalse(ExclusionMatcher.matches("Documents", "Documents", defaultExclusions))
        assertFalse(ExclusionMatcher.matches("Fotocamera", "DCIM/Fotocamera", defaultExclusions))
    }

    @Test
    fun testRelativePathSegmentExclusions() {
        // Un file all'interno di una cartella "cache" deve essere escluso anche se il nome del file è ordinario
        assertTrue(ExclusionMatcher.matches("immagine.png", "app_data/cache/immagine.png", defaultExclusions))
        assertTrue(ExclusionMatcher.matches("thumb_01.jpg", "media/.thumbnails/thumb_01.jpg", defaultExclusions))
        assertTrue(ExclusionMatcher.matches("session.dat", "temp/tmp/session.dat", defaultExclusions))

        // File normale in cartella normale
        assertFalse(ExclusionMatcher.matches("immagine.png", "app_data/photos/immagine.png", defaultExclusions))
    }

    @Test
    fun testHiddenFilesNotExcludedByDefault() {
        // I file o cartelle nascosti NON devono essere esclusi automaticamente,
        // a meno che non corrispondano specificamente a un pattern configurato (es. .thumbnails)
        assertFalse(ExclusionMatcher.matches(".nomedia", "WhatsApp/.nomedia", defaultExclusions))
        assertFalse(ExclusionMatcher.matches(".gitkeep", "project/.gitkeep", defaultExclusions))
        assertFalse(ExclusionMatcher.matches(".config", ".config", defaultExclusions))

        // .thumbnails è invece espressamente nei pattern
        assertTrue(ExclusionMatcher.matches(".thumbnails", ".thumbnails", defaultExclusions))
    }

    @Test
    fun testMultiSegmentRelativePathExclusions() {
        val multiSegmentPatterns = listOf("temp/cache", "data/logs", "*.tmp")

        // temp/cache/file.txt -> escluso
        assertTrue(ExclusionMatcher.matches("file.txt", "temp/cache/file.txt", multiSegmentPatterns))

        // temp/cache/archive/file.txt -> escluso (sottocartella del pattern multi-segmento)
        assertTrue(ExclusionMatcher.matches("file.txt", "temp/cache/archive/file.txt", multiSegmentPatterns))

        // temp/cache cartella stessa -> esclusa
        assertTrue(ExclusionMatcher.matches("cache", "temp/cache", multiSegmentPatterns))

        // temp/file.txt -> NON escluso (non è all'interno di cache)
        assertFalse(ExclusionMatcher.matches("file.txt", "temp/file.txt", multiSegmentPatterns))

        // other/cache/file.txt -> NON escluso (il prefisso "temp/" non corrisponde)
        assertFalse(ExclusionMatcher.matches("file.txt", "other/cache/file.txt", multiSegmentPatterns))

        // data/logs/system.log -> escluso
        assertTrue(ExclusionMatcher.matches("system.log", "data/logs/system.log", multiSegmentPatterns))

        // data/system.log -> NON escluso
        assertFalse(ExclusionMatcher.matches("system.log", "data/system.log", multiSegmentPatterns))
    }

    @Test
    fun testCaseInsensitiveMatching() {
        assertTrue(ExclusionMatcher.matches("FILE.TMP", "FILE.TMP", defaultExclusions))
        assertTrue(ExclusionMatcher.matches("CACHE", "CACHE", defaultExclusions))
        assertTrue(ExclusionMatcher.matches("data.Bak", "data.Bak", defaultExclusions))
        assertTrue(ExclusionMatcher.matches("File.Txt", "TEMP/CACHE/File.Txt", listOf("temp/cache")))
    }
}
