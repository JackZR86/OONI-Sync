package com.androidbackup.app.data.local

import com.androidbackup.app.data.local.entity.ExclusionPatternEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Test unitari di normalizzazione e validazione per i pattern di esclusione (Fase 01A).
 * Verifica i punti 3, 4, 5, 6 della specifica dei test.
 */
class ExclusionPatternNormalizationTest {

    @Test
    fun test3_exclusionPatternsPreserveDisplayOrder() {
        val raw = listOf("cache", "tmp", ".thumbnails", "download_cache")
        val entities = ExclusionPatternEntity.normalizeList(raw)

        assertEquals("Il numero di elementi deve corrispondere", 4, entities.size)
        assertEquals("Primo elemento 'cache' a posizione 0", 0, entities[0].position)
        assertEquals("cache", entities[0].pattern)
        assertEquals("tmp", entities[1].pattern)
        assertEquals(1, entities[1].position)
        assertEquals(".thumbnails", entities[2].pattern)
        assertEquals(2, entities[2].position)
        assertEquals("download_cache", entities[3].pattern)
        assertEquals(3, entities[3].position)
    }

    @Test
    fun test4_duplicatePatternsAreNotInserted() {
        val rawWithDuplicates = listOf("cache", "tmp", "cache", "tmp", "logs", "cache")
        val entities = ExclusionPatternEntity.normalizeList(rawWithDuplicates)

        assertEquals("I duplicati devono essere rimossi", 3, entities.size)
        val patterns = entities.map { it.pattern }
        assertEquals(listOf("cache", "tmp", "logs"), patterns)
        assertEquals(listOf(0, 1, 2), entities.map { it.position })
    }

    @Test
    fun test5_patternWithLeadingAndTrailingWhitespacesIsTrimmed() {
        val rawWithSpaces = listOf("   cache   ", "  *.tmp ", "\t.thumbnails\n")
        val entities = ExclusionPatternEntity.normalizeList(rawWithSpaces)

        assertEquals(3, entities.size)
        assertEquals("cache", entities[0].pattern)
        assertEquals("*.tmp", entities[1].pattern)
        assertEquals(".thumbnails", entities[2].pattern)
    }

    @Test
    fun test6_emptyOrWhitespaceOnlyStringsAreIgnored() {
        val rawWithBlanks = listOf("", "   ", "\t", "\n", "valid_folder", "  ", "another_valid")
        val entities = ExclusionPatternEntity.normalizeList(rawWithBlanks)

        assertEquals("Le stringhe vuote o di soli spazi devono essere filtrate", 2, entities.size)
        assertEquals("valid_folder", entities[0].pattern)
        assertEquals("another_valid", entities[1].pattern)
    }

    @Test
    fun testCombinedEdgeCases() {
        val combined = listOf("  cache  ", "", "cache", "   ", "  tmp", "tmp  ", "  ")
        val entities = ExclusionPatternEntity.normalizeList(combined)

        assertEquals(2, entities.size)
        assertEquals("cache", entities[0].pattern)
        assertEquals(0, entities[0].position)
        assertEquals("tmp", entities[1].pattern)
        assertEquals(1, entities[1].position)
    }
}
