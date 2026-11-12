package com.androidbackup.app.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Entità Room per i pattern di esclusione file e directory.
 * Conforme alla Specifica Fase 01A:
 * - Pattern univoco come chiave primaria (previene duplicati)
 * - Posizione numerica per preservare rigorosamente l'ordine
 * - Non serializza stringhe concatenate per evitare ambiguità
 */
@Entity(tableName = "exclusion_patterns")
data class ExclusionPatternEntity(
    @PrimaryKey
    @ColumnInfo(name = "pattern")
    val pattern: String,

    @ColumnInfo(name = "position")
    val position: Int
) {
    init {
        require(pattern.isNotBlank()) { "Il pattern di esclusione non può essere vuoto o composto solo da spazi." }
    }

    companion object {
        /**
         * Normalizza una lista grezza di stringhe:
         * 1. Applica trim()
         * 2. Rimuove elementi vuoti o blank
         * 3. Rimuove duplicati preservando il primo ordine di apparizione
         */
        fun normalizeList(rawPatterns: List<String>): List<ExclusionPatternEntity> {
            val seen = mutableSetOf<String>()
            val result = mutableListOf<ExclusionPatternEntity>()
            var position = 0

            for (raw in rawPatterns) {
                val cleaned = raw.trim()
                if (cleaned.isNotEmpty() && seen.add(cleaned)) {
                    result.add(ExclusionPatternEntity(pattern = cleaned, position = position++))
                }
            }
            return result
        }
    }
}
