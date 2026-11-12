# PROMPT 01A — Room e persistenza delle impostazioni

## Contesto

Stiamo sviluppando un'app Android nativa chiamata **Android Backup**, in Kotlin e Jetpack Compose, che in una fase successiva eseguirà backup unidirezionali verso Google Drive.

La Fase 0 è stata completata e verificata manualmente su Android: l'app si compila, si installa e le cinque schermate principali funzionano.

Il progetto di partenza è quello contenuto nello ZIP allegato. Mantieni il progetto esistente e lavora direttamente su di esso.

## Obiettivo di questa fase

Integrare **Room** per salvare sul dispositivo le impostazioni effettivamente scelte dall'utente, in modo che rimangano disponibili dopo la chiusura e la riapertura dell'app.

Questa è la fase **01A**: implementa soltanto database e persistenza delle impostazioni. La selezione reale delle cartelle tramite Storage Access Framework (SAF), la scansione del filesystem, gli hash e Google Drive saranno affrontati in fasi successive.

## Vincoli inderogabili

1. Non ricreare il progetto da zero.
2. Non eliminare o riscrivere componenti funzionanti senza una ragione tecnica documentata.
3. Mantieni Kotlin 2.0.0, Android Gradle Plugin 8.3.1, Gradle 8.5, Java 17, compileSdk 34 e minSdk 26.
4. Mantieni Jetpack Compose, Material 3, Navigation Compose e l'architettura MVVM esistente.
5. Non aggiungere ancora Google OAuth, Google Drive API, Firebase, WorkManager o servizi in background.
6. Non aggiungere permessi per leggere o scrivere file, né permessi di rete.
7. Non implementare cancellazioni di file, locali o remoti.
8. Non salvare dati dimostrativi o mock nel database come se fossero dati reali.
9. Non usare `allowMainThreadQueries()`.
10. Non usare `fallbackToDestructiveMigration()` e non introdurre strategie che cancellino il database per risolvere errori di migrazione.

## 1. Dipendenze Room

Integra Room in modo compatibile con le versioni già presenti nel progetto.

Usa inizialmente Room **2.6.1**, con annotation processing tramite Kotlin KAPT, mantenendo Kotlin 2.0.0. Aggiorna il Version Catalog `gradle/libs.versions.toml` e i file Gradle necessari senza modificare le versioni esistenti di AGP, Kotlin o Compose.

Aggiungi almeno:
- `androidx.room:room-runtime`
- `androidx.room:room-ktx`
- `androidx.room:room-compiler` tramite KAPT

Configura correttamente il plugin KAPT e, se abiliti `exportSchema`, configura anche la directory degli schemi Room e includi lo schema generato nel progetto.

Non aggiungere dipendenze non necessarie.

## 2. Modello dati persistente

In questa fase persisti soltanto le impostazioni dell'app e i pattern di esclusione. Non persistere le liste dimostrative di cartelle, cronologia, log o quota.

Crea una struttura ordinata, ad esempio sotto:

`app/src/main/java/com/androidbackup/app/data/local/`

Implementa:

### AppSettingsEntity

Una singola riga di configurazione globale, identificata da una chiave fissa (ad esempio `id = 1`), contenente:
- frequenza backup;
- politica di rete;
- riserva di spazio in MB;
- rotazione FIFO abilitata/disabilitata;
- autorizzazione a eliminare copie cloud-only;
- vincolo di ricarica;
- timestamp dell'ultima modifica, se utile.

Salva gli enum come stringhe stabili usando `enum.name`; gestisci in modo sicuro eventuali valori sconosciuti durante la lettura, ricadendo su valori predefiniti e registrando un errore comprensibile.

### ExclusionPatternEntity

Tabella separata per i pattern di esclusione, con:
- pattern come chiave primaria;
- posizione/ordine di visualizzazione.

Non serializzare la lista con concatenazioni che potrebbero creare ambiguità. La tabella separata permette di mantenere l'ordine e impedire duplicati.

### DAO

Implementa DAO con:
- osservazione delle impostazioni tramite `Flow`;
- osservazione dei pattern tramite `Flow`, ordinati per posizione;
- inserimento/aggiornamento delle impostazioni;
- sostituzione atomica della lista dei pattern;
- operazioni necessarie per inizializzare i valori predefiniti soltanto quando il database è vuoto.

### Database

Crea `BackupDatabase`, con nome file stabile `android_backup.db` e versione iniziale `1`.

Prevedi un punto chiaro per aggiungere future migrazioni. Non usare fallback distruttivi. Non inserire nel database i dati di `MockDataProvider`.

## 3. Repository

Crea un repository, ad esempio `SettingsRepository`, che:
- esponga un `Flow<AppSettings>` combinando riga impostazioni e pattern;
- restituisca i valori predefiniti se il database non contiene ancora configurazione;
- salvi impostazioni e pattern in una transazione Room;
- esegua le scritture con API `suspend` e senza bloccare il thread principale;
- gestisca e propaghi gli errori in modo esplicito.

Il repository deve essere l'unico punto usato dal ViewModel per leggere e scrivere le impostazioni persistenti. Il ViewModel non deve conoscere direttamente DAO o Entity.

## 4. Integrazione nell'applicazione

Crea un contenitore semplice dell'applicazione, ad esempio `BackupApplication`, che inizializzi una sola istanza di `BackupDatabase` e `SettingsRepository`.

Registralo nel Manifest tramite `android:name`.

Collega `BackupViewModel` al repository usando una soluzione semplice e coerente con il progetto attuale (factory o `AndroidViewModel`); non introdurre Hilt o un framework di dependency injection in questa fase.

All'avvio:
- carica le impostazioni persistenti dal repository;
- aggiorna `BackupUiState.rules` quando il `Flow` emette nuovi valori;
- evita che i valori dimostrativi sovrascrivano le preferenze salvate;
- mantieni la Home e le altre schermate funzionanti.

Quando l'utente modifica una regola, aggiorna subito la UI e salva il nuovo valore nel database. Gestisci gli errori di salvataggio senza bloccare l'app e rendendoli visibili nel log o con un messaggio comprensibile.

Le funzioni già presenti per frequenza, rete, riserva, FIFO, copie cloud-only, ricarica e pattern di esclusione devono continuare a funzionare e ora devono essere persistenti.

## 5. Separazione rigorosa tra dati demo e dati reali

Mantieni `MockDataProvider` separato dal database.

- Le cartelle dimostrative non devono essere inserite in Room.
- La cronologia e i log dimostrativi non devono essere inseriti in Room.
- La quota Google dimostrativa non deve essere salvata come quota reale.
- La simulazione del backup deve continuare a essere chiaramente identificata come simulazione.
- Le sessioni simulate non devono essere presentate come backup reali o come prova di upload su Drive.

Le impostazioni modificate dall'utente, invece, sono preferenze reali dell'app e devono essere persistite anche se l'interfaccia mostra ancora dati demo.

Non modificare inutilmente la logica grafica e il flusso delle cinque schermate.

## 6. Test richiesti

Aggiungi test adeguati per verificare almeno:

1. In un database vuoto vengono restituite le impostazioni predefinite.
2. Le impostazioni salvate vengono rilette correttamente.
3. I pattern di esclusione mantengono l'ordine.
4. I pattern duplicati non vengono inseriti.
5. Un pattern con spazi iniziali/finali viene normalizzato con `trim()`.
6. Una stringa vuota o composta soltanto da spazi viene ignorata.
7. Il salvataggio delle impostazioni e dei pattern è atomico.
8. I dati di `MockDataProvider` non vengono inseriti nel database.

Usa test Room in-memory dove appropriato. Se alcuni test richiedono un dispositivo/emulatore e non possono essere eseguiti nell'ambiente corrente, dichiaralo chiaramente. Non sostituire test Android con test Node/TypeScript e non presentarli come prova della persistenza Room.

## 7. Criteri di accettazione manuale

La fase sarà considerata completata quando, installando l'app:

1. Le cinque schermate continuano ad aprirsi senza errori.
2. L'utente modifica la frequenza del backup e una o più altre regole.
3. L'utente chiude completamente l'app e la riapre.
4. Le impostazioni scelte sono ancora presenti.
5. Aggiungendo/rimuovendo pattern, l'elenco aggiornato sopravvive al riavvio.
6. I dati dimostrativi restano chiaramente distinti dai dati persistenti reali.
7. Nessun permesso di accesso ai file o alla rete è stato introdotto.
8. Nessun file locale o remoto viene modificato o cancellato.

## 8. Verifica e consegna

Prima di terminare:
- controlla package, import, DAO, Entity, repository e ViewModel;
- verifica la configurazione KAPT e Room;
- esegui Gradle Sync;
- esegui `:app:assembleDebug` se l'ambiente lo consente;
- esegui i test disponibili;
- non dichiarare superata una verifica che non è stata effettivamente eseguita.

Consegna:
- ZIP completo del progetto Android aggiornato;
- elenco dei file creati e modificati;
- versione Room effettivamente utilizzata;
- esito dei test e della compilazione, distinguendo test statici, unit test, test strumentali e build APK;
- eventuali problemi residui.

Non iniziare la fase SAF in questa consegna. Attendi una revisione separata prima di aggiungere il selettore reale delle cartelle.
