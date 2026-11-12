# Android Backup – Progetto Nativo Android (Kotlin + Jetpack Compose)

Questo archivio contiene l'intero progetto Android nativo aggiornato alla **Specifica Fase 1B.2 (Motore di Scansione Locale e Inventario dei File con Revisione Correttiva Approvata)**.

## Requisiti di compilazione:
- **Android Studio**: Iguana (2023.2.1) o successivo (Ladybug, Koala, Meerkat, Ladybug Feature Drop)
- **JDK**: 17 o 21 (configurato in *Project Structure > SDK Location > Gradle JDK*)
- **Gradle Wrapper**: 8.5 (incluso: `gradle-wrapper.jar`, `gradle-wrapper.properties`, `gradlew`, `gradlew.bat`)
- **Android Gradle Plugin (AGP)**: 8.3.1
- **Kotlin**: 2.0.0 (con plugin Compose compiler integrato e KAPT)
- **Room Persistence**: 2.6.1 (`room-runtime`, `room-ktx`, `room-compiler` via KAPT)
- **Min SDK**: 26 (Android 8.0 Oreo)
- **Target / Compile SDK**: 34 (Android 14)

## Istruzioni di apertura ed esecuzione:
1. Estrai questo archivio in una cartella locale a tua scelta (es. `~/AndroidStudioProjects/AndroidBackup`).
2. Apri Android Studio e seleziona **File > Open...**.
3. Seleziona la cartella principale del progetto estratto (quella contenente `settings.gradle.kts` e `build.gradle.kts`).
4. Attendi che Gradle Sync completi il download delle dipendenze e l'indicizzazione.
5. Per eseguire i test unitari: tasto destro sulla cartella `app/src/test/java` > **Run 'Tests in 'java''**.
6. Seleziona un emulatore o dispositivo fisico ed esegui **Run 'app'** (`:app:assembleDebug`).

## Componenti e Novità Fase 1B.2 (Motore di Scansione SAF & Inventario Room v3 – Revisione Finale):
- **Motore di Scansione Ricorsiva SAF (`LocalFolderScanner`)**:
  - Esplorazione deterministica BFS guidata da coda persistente SQLite `pending_directories`.
  - Elaborazione progressiva in streaming a batch controllati (`batchSize = 100`) per contenere l'uso della memoria (`maxBatchSizeEmitted <= 100`).
  - Atomicità tra inventario e coda pendente tramite transazione Room `@Transaction` (`insertBatchAtomically`). Nessun record orfano in caso di fallimento SQLite.
  - Distinzione tra errori SAF ed errori Room tramite `ScanPersistenceException`: gli errori di scrittura Room non generano falsi `DIRECTORY_READ_ERROR`, provocano il rollback della transazione e lasciano la cartella genitore in `pending_directories`.
  - Transizione a `PAUSED` in `ScanRepository`: in presenza di `ScanPersistenceException`, la sessione passa a `PAUSED` con messaggio esplicativo, consentendo a `resumeScan()` di completare la scansione una volta risolto il problema di persistenza.
  - Coerenza delle sessioni in `startNewScan()`: se nel database è presente una sessione `PAUSED`, l'avvio di una nuova scansione viene rifiutato con un messaggio che richiede di riprendere o annullare esplicitamente la sessione precedente.
  - Propagazione rigorosa di `CancellationException` senza assorbimento in `catch (e: Exception)` generico: nessun falso errore `DIRECTORY_READ_ERROR`, directory pendente preservata per la ripresa e cursore SAF chiuso in modo affidabile (`cursor.use`).
  - Distinzione rigorosa tra directory vuota (0 elementi, operazione regolare) e cursore nullo (`NullCursorException`, registrata come `NULL_CURSOR` in `scan_errors`).
  - Prevenzione cicli e visite duplicate basata su `scanned_documents.isDocumentRecorded`.
  - Inizializzazione sessione atomica tramite `@Transaction` (`initializeSessionWithDirectories`).
  - Gestione cooperativa di pausa, ripresa e annullamento: un solo job attivo per istanza, sincronizzato via `Mutex`, con attesa della terminazione effettiva del job prima della marcatura dello stato finale e azzeramento dei flag solo a coroutine conclusa.
  - Pulizia sessioni con ritenzione delle ultime 2 sessioni completate oltre alla sessione corrente e attiva.
- **Valutazione Regole di Esclusione (`ExclusionMatcher`)**:
  - Wildcard estensione (`*.tmp`, `*.bak`, `*.part`).
  - Pattern multi-segmento per percorsi relativi (`temp/cache`, `data/logs`).
  - Corrispondenza case-insensitive su nomi e percorsi relativi.
  - Preservazione file nascosti (non esclusi automaticamente a meno di regole esplicite).
- **Database Room v3 e Migrazione (`MIGRATION_2_3`)**:
  - Quattro nuove tabelle: `scan_sessions`, `scanned_documents`, `pending_directories`, `scan_errors`.
  - Indice univoco `index_scanned_documents_session_folder_doc` su `(scan_session_id, backup_folder_id, document_id)`.
  - Migrazione non distruttiva dei dati v1 e v2.
- **Suite di Test Completa**:
  - 71 test unitari automatizzati passati con successo (100% pass rate).
  - 30 test dedicati in `LocalFolderScannerTest` su orchestrazione coroutine, cooperative cancel/pause, batch streaming con 250 file, chiusura cursore su `CancellationException`, rollback atomico Room SQLite, gestione `ScanPersistenceException`, rifiuto avvio su sessione in pausa, fallimenti su batch intermedi e recupero cross-process post-crash.
