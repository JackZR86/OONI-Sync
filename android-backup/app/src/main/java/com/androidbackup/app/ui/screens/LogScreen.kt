package com.androidbackup.app.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.androidbackup.app.data.model.BackupLog
import com.androidbackup.app.data.model.LogLevel
import com.androidbackup.app.ui.components.*
import com.androidbackup.app.ui.viewmodel.BackupUiState
import com.androidbackup.app.ui.viewmodel.BackupViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(
    uiState: BackupUiState,
    viewModel: BackupViewModel,
    modifier: Modifier = Modifier
) {
    val filteredLogs = remember(uiState.logs, uiState.selectedLogLevel, uiState.logSearchQuery) {
        uiState.logs.filter { log ->
            val matchLevel = uiState.selectedLogLevel == null || log.level == uiState.selectedLogLevel
            val matchQuery = uiState.logSearchQuery.isBlank() ||
                    log.message.contains(uiState.logSearchQuery, ignoreCase = true) ||
                    (log.fileName?.contains(uiState.logSearchQuery, ignoreCase = true) == true) ||
                    (log.technicalCode?.contains(uiState.logSearchQuery, ignoreCase = true) == true)
            matchLevel && matchQuery
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Registro Attività (Log)",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${filteredLogs.size} eventi registrati",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Avviso DEMO / Dati dimostrativi
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
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
                        text = "DEMO / DATI DIMOSTRATIVI: Registro eventi simulato. Nessuna operazione su file locali o chiamate di rete a Google Drive.",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }

            // Ricerca testuale
            OutlinedTextField(
                value = uiState.logSearchQuery,
                onValueChange = { viewModel.setLogSearchQuery(it) },
                placeholder = { Text("Cerca per nome file, messaggio o codice...") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (uiState.logSearchQuery.isNotBlank()) {
                        IconButton(onClick = { viewModel.setLogSearchQuery("") }) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancella")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Filtri: Tutti, Errori, Info, Debug
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = uiState.selectedLogLevel == null,
                    onClick = { viewModel.setLogLevelFilter(null) },
                    label = { Text("Tutti") }
                )
                FilterChip(
                    selected = uiState.selectedLogLevel == LogLevel.ERRORE,
                    onClick = { viewModel.setLogLevelFilter(LogLevel.ERRORE) },
                    label = { Text("Errori") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.errorContainer
                    )
                )
                FilterChip(
                    selected = uiState.selectedLogLevel == LogLevel.INFO,
                    onClick = { viewModel.setLogLevelFilter(LogLevel.INFO) },
                    label = { Text("Info") }
                )
                FilterChip(
                    selected = uiState.selectedLogLevel == LogLevel.DEBUG,
                    onClick = { viewModel.setLogLevelFilter(LogLevel.DEBUG) },
                    label = { Text("Debug") }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (filteredLogs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Nessun evento corrisponde ai filtri selezionati.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredLogs, key = { it.id }) { log ->
                        LogItemCard(log = log)
                    }
                }
            }
        }
    }
}
