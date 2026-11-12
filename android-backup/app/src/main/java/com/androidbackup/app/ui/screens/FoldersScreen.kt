package com.androidbackup.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.androidbackup.app.data.local.entity.ScanSessionStatus
import com.androidbackup.app.data.model.BackupSource
import com.androidbackup.app.data.model.Priority
import com.androidbackup.app.ui.components.*
import com.androidbackup.app.ui.viewmodel.BackupUiState
import com.androidbackup.app.ui.viewmodel.BackupViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FoldersScreen(
    uiState: BackupUiState,
    viewModel: BackupViewModel,
    modifier: Modifier = Modifier
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var reauthorizingFolderId by remember { mutableStateOf<String?>(null) }

    // Rileva ritorni in primo piano dell'app per verificare revoche esterne
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshFolderPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshFolderPermissions()
    }

    // Launcher SAF di sistema per aggiunta nuova cartella
    val openDocumentTreeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.addFolderFromSaf(uri)
        }
    }

    // Launcher SAF distinto per ripristino autorizzazione cartella esistente
    val reauthorizeLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        val folderId = reauthorizingFolderId
        if (uri != null && folderId != null) {
            viewModel.reauthorizeFolder(folderId, uri)
        }
        reauthorizingFolderId = null
    }

    val onAddFolderClicked = {
        if (uiState.isDemoMode) {
            showAddDialog = true
        } else {
            openDocumentTreeLauncher.launch(null)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Cartelle di Backup",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (uiState.isDemoMode) {
                                "${uiState.folders.size} cartelle monitorate (Modalità Dimostrativa)"
                            } else {
                                "${uiState.folders.size} cartelle configurate (Sola lettura)"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddFolderClicked,
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("Aggiungi cartella") },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            )
        }
    ) { innerPadding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Banner errore se presente
                if (uiState.lastErrorMessage != null) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = uiState.lastErrorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { viewModel.clearErrorMessage() }) {
                                Icon(
                                    imageVector = Icons.Filled.Close,
                                    contentDescription = "Chiudi",
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                // Avvisi e Retry per autorizzazioni orfane SAF
                if (!uiState.isDemoMode && uiState.orphanPermissions.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        uiState.orphanPermissions.forEach { orphanUri ->
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.85f),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Outlined.WarningAmber,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                                            modifier = Modifier.size(20.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "Permesso SAF di sistema non rilasciato",
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "La cartella è stata rimossa dalla configurazione, ma il permesso Android in lettura è ancora attivo nel sistema:\n${orphanUri.path ?: orphanUri.toString()}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        Button(
                                            onClick = { viewModel.retryReleasePermission(orphanUri) },
                                            enabled = !uiState.orphanActionInProgress,
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = MaterialTheme.colorScheme.tertiary,
                                                contentColor = MaterialTheme.colorScheme.onTertiary
                                            ),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Refresh,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Riprova rilascio", fontSize = 13.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                if (uiState.folders.isEmpty()) {
                    EmptyFoldersView(onAddClick = onAddFolderClicked)
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 88.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        item {
                            if (uiState.isDemoMode) {
                                Surface(
                                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier.padding(bottom = 4.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = Icons.Outlined.Info,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                        Spacer(modifier = Modifier.width(12.dp))
                                        Text(
                                            text = "DATI DIMOSTRATIVI ATTIVI: Elenco cartelle e volumi simulati. Disattiva la modalità demo nelle impostazioni per usare lo storage locale reale.",
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer
                                        )
                                    }
                                }
                            } else {
                                // Sezione Motore di Scansione Locale e Inventario File (Fase 1B.2)
                                ScanEngineControlCard(
                                    uiState = uiState,
                                    onStartScan = { viewModel.startScan() },
                                    onPauseScan = { viewModel.pauseScan() },
                                    onResumeScan = { viewModel.resumeScan() },
                                    onCancelScan = { viewModel.cancelScan() }
                                )
                            }
                        }

                        items(uiState.folders, key = { it.id }) { folder ->
                            FolderCard(
                                folder = folder,
                                isDemo = uiState.isDemoMode,
                                onPriorityChange = { newPriority ->
                                    viewModel.updateFolderPriority(folder.id, newPriority)
                                },
                                onProtectionToggle = {
                                    viewModel.toggleFolderProtection(folder.id)
                                },
                                onDelete = {
                                    viewModel.removeFolder(folder.id)
                                },
                                onReselect = {
                                    reauthorizingFolderId = folder.id
                                    reauthorizeLauncher.launch(null)
                                },
                                inventoryInfo = uiState.folderInventoryMap[folder.id]
                            )
                        }
                    }
                }
            }

            if (showAddDialog) {
                AddFolderDialog(
                    onDismiss = { showAddDialog = false },
                    onConfirm = { name, path, priority, protect ->
                        viewModel.addFolder(name, path, priority, protect)
                        showAddDialog = false
                    }
                )
            }
        }
    }
}

/**
 * Scheda di controllo del motore di scansione locale e riepilogo inventario (Fase 1B.2).
 */
@Composable
fun ScanEngineControlCard(
    uiState: BackupUiState,
    onStartScan: () -> Unit,
    onPauseScan: () -> Unit,
    onResumeScan: () -> Unit,
    onCancelScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    val session = uiState.scanSession ?: uiState.lastCompletedScanSession
    val isRunning = uiState.isScanInProgress
    val isPaused = uiState.isScanPaused

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Intestazione con stato
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Outlined.Search,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Inventario Locale SAF",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    color = when {
                        isRunning -> MaterialTheme.colorScheme.primaryContainer
                        isPaused -> MaterialTheme.colorScheme.tertiaryContainer
                        session?.sessionStatus == ScanSessionStatus.COMPLETED -> MaterialTheme.colorScheme.secondaryContainer
                        session?.sessionStatus == ScanSessionStatus.COMPLETED_WITH_ERRORS -> MaterialTheme.colorScheme.errorContainer
                        else -> MaterialTheme.colorScheme.surface
                    },
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = when {
                            isRunning -> "In corso..."
                            isPaused -> "In pausa"
                            session?.sessionStatus == ScanSessionStatus.COMPLETED -> "Completato"
                            session?.sessionStatus == ScanSessionStatus.COMPLETED_WITH_ERRORS -> "Con avvisi"
                            session?.sessionStatus == ScanSessionStatus.FAILED -> "Fallito"
                            else -> "Non avviato"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        color = when {
                            isRunning -> MaterialTheme.colorScheme.onPrimaryContainer
                            isPaused -> MaterialTheme.colorScheme.onTertiaryContainer
                            session?.sessionStatus == ScanSessionStatus.COMPLETED -> MaterialTheme.colorScheme.onSecondaryContainer
                            session?.sessionStatus == ScanSessionStatus.COMPLETED_WITH_ERRORS -> MaterialTheme.colorScheme.onErrorContainer
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            if (isRunning) {
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Riepilogo Statistiche aggregate
            Row(modifier = Modifier.fillMaxWidth()) {
                val totalFiles = session?.totalFiles ?: 0
                val totalDirs = session?.totalDirectories ?: 0
                val knownBytes = session?.knownTotalBytes ?: 0L
                val unknownFiles = session?.unknownSizeFiles ?: 0
                val excluded = session?.excludedItems ?: 0

                val kb = knownBytes / 1024
                val mb = kb / 1024f
                val sizeText = if (mb >= 1f) "%.1f MB".format(mb) else "$kb KB"

                StatColumn(
                    label = "File trovati",
                    value = "$totalFiles file",
                    modifier = Modifier.weight(1f)
                )
                StatColumn(
                    label = "Directory visitate",
                    value = "$totalDirs cartelle",
                    modifier = Modifier.weight(1f)
                )
                StatColumn(
                    label = "Dimensione nota",
                    value = sizeText,
                    modifier = Modifier.weight(1f)
                )
            }

            if (session != null && (session.unknownSizeFiles > 0 || session.excludedItems > 0 || session.errorCount > 0)) {
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (session.unknownSizeFiles > 0) {
                        Text(
                            text = "• ${session.unknownSizeFiles} file senza dimensione",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    if (session.excludedItems > 0) {
                        Text(
                            text = "• ${session.excludedItems} esclusi dalle regole",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }
                    if (session.errorCount > 0) {
                        Text(
                            text = "• ${session.errorCount} errori di lettura",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Pulsanti di Azione
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                when {
                    isRunning -> {
                        OutlinedButton(
                            onClick = onCancelScan,
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Annulla")
                        }
                        Button(onClick = onPauseScan) {
                            Icon(Icons.Filled.Pause, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Pausa")
                        }
                    }
                    isPaused -> {
                        OutlinedButton(
                            onClick = onCancelScan,
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Annulla")
                        }
                        Button(onClick = onResumeScan) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Riprendi")
                        }
                    }
                    else -> {
                        Button(
                            onClick = onStartScan,
                            enabled = uiState.folders.isNotEmpty()
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(if (session != null) "Riesegui scansione" else "Avvia scansione")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyFoldersView(onAddClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.size(96.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = Icons.Outlined.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Nessuna cartella configurata",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Seleziona le cartelle del dispositivo che desideri copiare su Google Drive. L'accesso è in sola lettura e non modificherà mai i tuoi file locali.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onAddClick,
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Filled.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Aggiungi cartella")
        }
    }
}

@Composable
fun AddFolderDialog(
    onDismiss: () -> Unit,
    onConfirm: (name: String, path: String, priority: Priority, protect: Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var path by remember { mutableStateOf("/storage/emulated/0/") }
    var priority by remember { mutableStateOf(Priority.NORMALE) }
    var protect by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Aggiungi cartella da monitorare", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "Nota: In questa fase di scheletro UI, puoi inserire i parametri di prova. Nella versione integrata verrà aperto il selettore Storage Access Framework (SAF).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome visualizzato") },
                    placeholder = { Text("es. Fotocamera o Documenti") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = path,
                    onValueChange = { path = it },
                    label = { Text("Percorso locale / URI SAF") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Priorità iniziale:",
                    style = MaterialTheme.typography.labelMedium
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Priority.values().forEach { prio ->
                        FilterChip(
                            selected = priority == prio,
                            onClick = { priority = prio },
                            label = { Text(prio.label) }
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Proteggi dalle eliminazioni auto",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Switch(
                        checked = protect,
                        onCheckedChange = { protect = it }
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(name.ifBlank { "Nuova Cartella" }, path, priority, protect)
                }
            ) {
                Text("Aggiungi")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Annulla")
            }
        }
    )
}
