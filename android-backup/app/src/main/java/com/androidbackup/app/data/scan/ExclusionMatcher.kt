package com.androidbackup.app.data.scan

/**
 * Valutatore delle regole di esclusione per file e directory durante la scansione ricorsiva SAF (Fase 1B.2).
 *
 * Conforme alla Specifica Fase 1B.2 e Revisione:
 * - Utilizza le esclusioni configurate in [SettingsRepository] / [ExclusionPatternEntity].
 * - Supporta pattern per estensione (es. "*.tmp", "*.bak").
 * - Supporta pattern multi-segmento (es. "temp/cache", "data/logs"):
 *   esclude percorsi che corrispondono al pattern o che si trovano all'interno della relativa sottocartella.
 * - Supporta pattern per nome directory/file esatto (es. "cache", ".thumbnails") sia sul nome sia sui segmenti del percorso relativo.
 * - Non esclude automaticamente file o directory nascosti a meno che non corrispondano ai pattern configurati.
 * - Non fa distinzione tra maiuscole e minuscole (case-insensitive).
 */
object ExclusionMatcher {

    /**
     * Verifica se un elemento (file o cartella) corrisponde a uno dei pattern di esclusione configurati.
     *
     * @param displayName Nome visualizzato del documento o cartella (es. "cache", "foto.tmp", "index.html")
     * @param relativePath Percorso relativo all'interno della cartella monitorata (es. "cache", "temp/cache/file.txt", "docs/test.bak")
     * @param patterns Elenco dei pattern di esclusione normalizzati (es. ["*.tmp", "temp/cache", ".thumbnails"])
     * @return `true` se l'elemento deve essere escluso, `false` altrimenti.
     */
    fun matches(displayName: String, relativePath: String, patterns: List<String>): Boolean {
        if (patterns.isEmpty()) return false
        val cleanName = displayName.trim()
        val cleanPath = relativePath.trim().replace('\\', '/').trim('/')
        val pathSegments = if (cleanPath.isEmpty()) emptyList() else cleanPath.split('/')

        for (rawPattern in patterns) {
            val pattern = rawPattern.trim()
            if (pattern.isBlank()) continue

            // 1. Pattern per estensione wildcard (es. "*.tmp", "*.bak", "*.log")
            if (pattern.startsWith("*.")) {
                val extensionWithDot = pattern.substring(1) // ".tmp"
                if (cleanName.endsWith(extensionWithDot, ignoreCase = true)) {
                    return true
                }
            }
            // 2. Pattern prefisso wildcard a segmento singolo (es. "temp*")
            else if (pattern.endsWith("*") && !pattern.startsWith("*") && !pattern.contains('/')) {
                val prefix = pattern.removeSuffix("*")
                if (cleanName.startsWith(prefix, ignoreCase = true)) {
                    return true
                }
            }
            // 3. Pattern multi-segmento con separatore di percorso (es. "temp/cache", "data/logs")
            else if (pattern.contains('/')) {
                val normPattern = pattern.replace('\\', '/').trim('/')
                // Corrispondenza se il percorso relativo è esattamente il pattern o una sua sottocartella/file
                if (cleanPath.equals(normPattern, ignoreCase = true) ||
                    cleanPath.startsWith("$normPattern/", ignoreCase = true)
                ) {
                    return true
                }
            }
            // 4. Pattern a segmento singolo per nome o directory antenata (es. "cache", ".thumbnails")
            else {
                // Match sul nome esatto dell'elemento corrente
                if (cleanName.equals(pattern, ignoreCase = true)) {
                    return true
                }
                // Match su qualsiasi segmento intermedio del percorso relativo
                if (pathSegments.any { it.equals(pattern, ignoreCase = true) }) {
                    return true
                }
            }
        }
        return false
    }
}
