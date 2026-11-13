# AI_HANDOFF.md — Coordinamento ChatGPT / Gemini

Questo file è il punto di coordinamento condiviso per lo sviluppo di OONI-Sync. Va letto prima di iniziare ogni nuova attività che modifica il progetto.

## 1. Repository e fonte ufficiale

- Repository: `JackZR86/OONI-Sync`
- Branch di lavoro attuale: `main`
- Progetto Android: `android-backup/`
- Stack: Kotlin, Jetpack Compose, Material 3, MVVM, Room e Android SAF.
- La versione ufficiale del progetto è quella pubblicata nel branch GitHub `main`.
- Il workspace di Google AI Studio e gli ZIP esportati sono copie di lavoro/consegna: non sono aggiornamenti ufficiali finché le modifiche non sono state sincronizzate su GitHub.
- Non sovrascrivere, eliminare o riscrivere la cronologia Git. Non eseguire force push.

## 2. Ruoli

### Gemini / Google AI Studio — implementazione

- È il principale agente di implementazione: modifica il codice Android secondo le richieste approvate.
- Prima di modificare il progetto, sincronizza con GitHub (Pull) e verifica di lavorare sul commit più recente di `main`.
- Legge questo file e le specifiche/documenti pertinenti prima di intervenire.
- Mantiene il perimetro della fase concordata. Non introduce funzionalità ulteriori, migrazioni, modifiche di schema o refactoring non richiesti.
- Dopo le modifiche, esegue tutti i controlli realmente disponibili. Se mancano JDK, Android SDK, rete o credenziali, lo dichiara senza simulare esiti.
- Fornisce un riepilogo dei file modificati, motivazioni, test eseguiti e non eseguiti, problemi residui e SHA-256 dello ZIP se viene prodotto.
- Non afferma che test o build siano superati senza averli eseguiti.
- Quando il workspace è pronto, comunica che è necessario effettuare Push se la sincronizzazione non è automatica.

### ChatGPT — revisione indipendente e coordinamento

- Legge il codice effettivamente pubblicato su GitHub; non considera i soli report di Gemini o gli ZIP come prova dell'implementazione.
- Controlla i diff rispetto al commit di partenza, i punti critici, la coerenza con le specifiche e i risultati CI.
- Può pubblicare in questo file istruzioni, richieste di correzione e note di revisione per Gemini, evitando di trasferire lunghi messaggi tramite l'utente.
- Può proporre o applicare correzioni circoscritte quando appropriate e autorizzate; per modifiche architetturali, ampliamenti di perimetro o decisioni con impatto funzionale rilevante, sottopone prima la decisione all'utente.
- Non dichiara una fase verificata se i controlli richiesti non sono realmente riusciti.

### Utente — responsabile delle decisioni di prodotto

- Definisce requisiti e priorità e approva le decisioni funzionali rilevanti.
- Quando Google AI Studio richiede sincronizzazione manuale, esegue Pull prima del lavoro di Gemini e Push dopo aver esaminato/concluso il lavoro.
- Non deve modificare manualmente il codice né eseguire comandi Git/Gradle salvo richiesta esplicita e motivata.

## 3. Protocollo per ogni attività

1. **Prima di lavorare:** controllare il commit corrente di `main`, leggere questo file e individuare la baseline e le specifiche della fase.
2. **Allineamento:** in AI Studio eseguire Pull prima di iniziare, così il workspace non parte da una copia obsoleta. Non sovrascrivere modifiche locali non sincronizzate.
3. **Implementazione:** intervenire solo sui file necessari e mantenere la compatibilità con le fasi già approvate. Non cambiare dipendenze, Gradle, Kotlin, AGP, schema Room o configurazioni CI per comodità.
4. **Controlli:** eseguire test e build disponibili. Riportare i comandi effettivi e l'esito reale; distinguere errori di ambiente, errori di compilazione e test falliti.
5. **Consegna a GitHub:** quando le modifiche sono pronte, effettuare Push su `main` attraverso la sincronizzazione di AI Studio, salvo diversa istruzione. Comunicare all'utente che il Push è necessario quando non può essere eseguito automaticamente.
6. **Revisione:** ChatGPT verifica il nuovo commit e il diff su GitHub. Se rileva problemi, pubblica qui richieste di correzione precise oppure propone una correzione circoscritta.
7. **CI:** ogni Push su `main` avvia GitHub Actions. La verifica completa della build Android richiede il successo di entrambi:
   - `:app:testDebugUnitTest`
   - `:app:assembleDebug`
8. **Chiusura:** una fase è verificata soltanto quando codice, revisione e controlli richiesti sono coerenti. Un report di Gemini o uno ZIP valido non sostituiscono la CI.

## 4. Regole per leggere e gestire i fallimenti

- Conservare i log e i report dei test CI; identificare il test fallito e l'asserzione/causa precisa.
- Non rendere verdi i test eliminando asserzioni, abbassando la copertura o modificando le aspettative senza dimostrare che siano errate rispetto alle specifiche.
- Correggere il codice applicativo quando esiste un difetto funzionale; correggere il test quando il test non rappresenta il comportamento specificato.
- Dopo una correzione, rieseguire l'intera suite, non soltanto il singolo test, e poi la build APK.
- Non confondere "compilazione dei test riuscita" con "test superati".
- Non considerare una run di un commit precedente come prova del commit corrente.

## 5. Stato corrente e baseline ufficiale

- **Fase completata e verificata:** **Fase 1B.3C — Integrazione del fingerprinting nella pipeline di scansione**.
- **Commit ufficiale più recente (HEAD corrente di `main`):** `bd7a6f97bbafe5afb9edfdbe0f0cdcfe9b1c6534` (commit esclusivamente documentale su `AI_HANDOFF.md`).
- **Baseline del codice Android verificata (non coincide con l'HEAD corrente):** `73b7c16537920dee3395c708360bafebef5e4431`. Non sono presenti modifiche applicative successive a questa baseline.
- **Database:** Room schema v4, senza migrazioni pendenti.
- **Stato CI (GitHub Actions):**
  - **Run #12** su baseline codice Android (`73b7c16537920dee3395c708360bafebef5e4431`): successo (`:app:testDebugUnitTest`: 134 test superati; `:app:assembleDebug`: `BUILD SUCCESSFUL`; artifact dei report e APK debug generati e caricati).
  - **Run #13** sul commit documentale (`bd7a6f97bbafe5afb9edfdbe0f0cdcfe9b1c6534`): successo (test e build APK completati con successo).
- **Avanzamento:** nessuna nuova fase di sviluppo è ancora iniziata.
- **Prossima attività:** qualsiasi nuova attività dovrà essere preceduta da una specifica progettuale approvata.

### Riferimento storico (stato iniziale alla creazione del file — superato)

- La precedente descrizione iniziale si riferiva alla **Fase 1B.3A — Database v4 e motore SHA-256 di base** (revisione rev3 sul commit `0958ed642a643fda10574e378a263dde0969fd7e`, ZIP con SHA-256 `7808fb1ed9b23a01b27e10d43b906d2cd4e7b7e57338b33c76f7542fa0504314`, interventi su `ScannedDocumentDao.kt`, `LocalFolderScanner.kt` e `LocalFolderScannerTest.kt`). Tali informazioni hanno valore esclusivamente storico e non rappresentano più lo stato corrente del repository.

## 6. Sicurezza e integrità

- Non inserire nel repository password, token, chiavi API, file di configurazione personali o credenziali.
- Non alterare file locali dell'utente, impostazioni Android Studio, file `.local` o segreti.
- Non modificare il workflow GitHub Actions per mascherare fallimenti.
- In caso di conflitto tra workspace AI Studio e GitHub, fermarsi e segnalare il conflitto: non scegliere automaticamente quale versione sovrascrivere.
- In caso di dubbio sulla specifica o sulla sicurezza dei dati, preservare i dati e chiedere una decisione prima di procedere.
