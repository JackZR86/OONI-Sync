package com.androidbackup.app.data.mock

import com.androidbackup.app.data.model.*

/**
 * Fornitore di dati di esempio per il collaudo della UI in assenza di connessione reale.
 * Conforme alla Specifica:
 * "Tutti i valori visualizzati devono essere mock chiaramente separati dai dati reali;
 * non mostrare dati finti come se provenissero da Google Drive."
 */
object MockDataProvider {

    const val DISCLAIMER = "DATI MOCK DI SIMULAZIONE UI - Non collegati a Google Drive o al filesystem"

    val emptyFolders: List<BackupSource> = emptyList()

    val demoFolders: List<BackupSource> = listOf(
        BackupSource(
            id = "f-1",
            name = "Fotocamera (DCIM)",
            path = "/storage/emulated/0/DCIM/Camera",
            priority = Priority.ALTA,
            protectFromEviction = true,
            enabled = true,
            fileCount = 342,
            totalSizeMb = 1240,
            lastScanned = "Oggi, 09:15"
        ),
        BackupSource(
            id = "f-2",
            name = "Documenti",
            path = "/storage/emulated/0/Documents",
            priority = Priority.ALTA,
            protectFromEviction = true,
            enabled = true,
            fileCount = 48,
            totalSizeMb = 185,
            lastScanned = "Oggi, 09:15"
        ),
        BackupSource(
            id = "f-3",
            name = "Download",
            path = "/storage/emulated/0/Download",
            priority = Priority.NORMALE,
            protectFromEviction = false,
            enabled = true,
            fileCount = 89,
            totalSizeMb = 410,
            lastScanned = "Ieri, 18:30"
        ),
        BackupSource(
            id = "f-4",
            name = "Audio Registrazioni",
            path = "/storage/emulated/0/Recordings",
            priority = Priority.BASSA,
            protectFromEviction = false,
            enabled = true,
            fileCount = 15,
            totalSizeMb = 68,
            lastScanned = "3 giorni fa"
        )
    )

    val demoQuota = GoogleStorageQuota(
        totalBytes = 15L * 1024 * 1024 * 1024,
        usedBytes = (8.8 * 1024 * 1024 * 1024).toLong(),
        appUsedBytes = (1.45 * 1024 * 1024 * 1024).toLong(),
        trashBytes = 420L * 1024 * 1024,
        isMock = true
    )

    val demoHistory: List<BackupRun> = listOf(
        BackupRun(
            id = "h-101",
            timestamp = "5 Nov 2026, 09:15",
            durationSeconds = 142,
            status = BackupStatus.COMPLETATO,
            filesChecked = 494,
            filesUploaded = 14,
            filesSkipped = 480,
            bytesUploaded = 78L * 1024 * 1024,
            bytesFreed = 0L,
            triggerType = "PROGRAMMATO",
            notes = "Tutti i file sono stati verificati con SHA-256 e aggiornati su Drive."
        ),
        BackupRun(
            id = "h-100",
            timestamp = "4 Nov 2026, 09:14",
            durationSeconds = 98,
            status = BackupStatus.PARZIALE,
            filesChecked = 480,
            filesUploaded = 6,
            filesSkipped = 472,
            bytesUploaded = 32L * 1024 * 1024,
            bytesFreed = 0L,
            triggerType = "PROGRAMMATO",
            notes = "2 file in Download saltati per regole di esclusione (*.tmp)."
        ),
        BackupRun(
            id = "h-099",
            timestamp = "3 Nov 2026, 21:40",
            durationSeconds = 45,
            status = BackupStatus.COMPLETATO,
            filesChecked = 474,
            filesUploaded = 2,
            filesSkipped = 472,
            bytesUploaded = 12L * 1024 * 1024,
            bytesFreed = 0L,
            triggerType = "MANUALE",
            notes = "Backup manuale eseguito dall'utente."
        )
    )

    val demoLogs: List<BackupLog> = listOf(
        BackupLog(
            id = "log-1",
            timestamp = "09:17:44",
            level = LogLevel.INFO,
            category = "SISTEMA",
            message = "Sessione di backup #101 completata con successo.",
            technicalCode = "BACKUP_RUN_OK"
        ),
        BackupLog(
            id = "log-2",
            timestamp = "09:17:10",
            level = LogLevel.INFO,
            category = "UPLOAD",
            message = "Upload verificato su Drive: sha256Checksum coerente con fingerprint locale.",
            fileName = "IMG_20261105_091201.jpg",
            technicalCode = "CHECKSUM_VERIFIED"
        ),
        BackupLog(
            id = "log-3",
            timestamp = "09:16:35",
            level = LogLevel.DEBUG,
            category = "SCAN",
            message = "Metadati invariati (dimensione e timestamp coerenti), hash saltato.",
            fileName = "contratto_firmato.pdf",
            technicalCode = "FINGERPRINT_HIT"
        ),
        BackupLog(
            id = "log-4",
            timestamp = "09:15:20",
            level = LogLevel.DEBUG,
            category = "SCAN",
            message = "File escluso: corrisponde al pattern \"*.tmp\".",
            fileName = "sync_temp_cache.tmp",
            technicalCode = "EXCLUSION_MATCH"
        ),
        BackupLog(
            id = "log-5",
            timestamp = "09:15:02",
            level = LogLevel.INFO,
            category = "RETE",
            message = "Verifica vincolo rete: Wi-Fi attivo. Avvio scansione.",
            technicalCode = "NETWORK_WIFI_OK"
        ),
        BackupLog(
            id = "log-6",
            timestamp = "04 Nov 09:14:22",
            level = LogLevel.ERRORE,
            category = "SCAN",
            message = "File non leggibile dal provider locale (Media Provider EBUSY). File saltato.",
            fileName = "audio_draft.part",
            technicalCode = "FILE_READ_ERR_SKIPPED"
        )
    )
}
