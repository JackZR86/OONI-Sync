package com.androidbackup.app.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidbackup.app.data.model.BackupStatus
import com.androidbackup.app.ui.components.*
import com.androidbackup.app.ui.theme.*
import com.androidbackup.app.ui.viewmodel.BackupUiState
import com.androidbackup.app.ui.viewmodel.BackupViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: BackupUiState,
    viewModel: BackupViewModel,
    onNavigateToFolders: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Android Backup",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Unidirezionale verso Google Drive",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                actions = {
                    // Indicatore o switch rapido modalità Demo
                    FilterChip(
                        selected = uiState.isDemoMode,
                        onClick = { viewModel.toggleDemoMode(!uiState.isDemoMode) },
                        label = {
                            Text(if (uiState.isDemoMode) "Demo Mock ON" else "Scheletro Vuoto")
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = if (uiState.isDemoMode) Icons.Filled.CheckCircle else Icons.Outlined.Layers,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        },
                        modifier = Modifier.padding(end = 8.dp)
                    )
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Avviso trasparente Mock UI
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp)
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
                        text = "Fase 0: Scheletro e UI attiva. Nessun accesso reale ai file locali o chiamate a Google Drive.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }

            // Scheda Principale: Stato del Backup
            BackupStatusCard(
                status = uiState.status,
                progress = uiState.backupProgress,
                operationMessage = uiState.currentOperationMessage,
                lastBackup = uiState.lastBackupTimestamp,
                nextBackup = uiState.nextEstimatedBackupTime,
                onStatusSelect = { viewModel.setBackupStatus(it) }
            )

            // Pulsante Principale "Esegui Backup Ora"
            val isRunning = uiState.status == BackupStatus.IN_CORSO
            Button(
                onClick = {
                    if (isRunning) {
                        viewModel.cancelBackupSimulation()
                    } else {
                        viewModel.startBackupSimulation()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRunning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = if (isRunning) Icons.Filled.Stop else Icons.Filled.CloudUpload,
                    contentDescription = null
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = if (isRunning) "Annulla Simulazione" else "Avvia Simulazione Backup",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // Scheda Quota Google Drive
            GoogleStorageCard(
                quota = uiState.quota,
                isMock = uiState.quota.isMock
            )

            // Riepilogo Sorgenti configurate
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Folder,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Cartelle Sorgente",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        TextButton(onClick = onNavigateToFolders) {
                            Text("Gestisci")
                        }
                    }

                    if (uiState.folders.isEmpty()) {
                        Text(
                            text = "Nessuna cartella configurata. Aggiungi le cartelle dal pulsante 'Gestisci' per avviare il backup.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    } else {
                        Text(
                            text = "${uiState.folders.size} cartelle monitorate (${uiState.folders.count { it.priority == com.androidbackup.app.data.model.Priority.ALTA }} con priorità Alta).",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )

                        val completedScan = uiState.lastCompletedScanSession
                        if (!uiState.isDemoMode && completedScan != null) {
                            val kb = completedScan.knownTotalBytes / 1024
                            val mb = kb / 1024f
                            val sizeStr = if (mb >= 1f) "%.1f MB".format(mb) else "$kb KB"
                            Text(
                                text = "Inventario completato: ${completedScan.totalFiles} file individuati ($sizeStr stimati).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
