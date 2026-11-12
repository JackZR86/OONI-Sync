package com.androidbackup.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidbackup.app.data.model.BackupFrequency
import com.androidbackup.app.data.model.NetworkPolicy
import com.androidbackup.app.ui.viewmodel.BackupUiState
import com.androidbackup.app.ui.viewmodel.BackupViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RulesScreen(
    uiState: BackupUiState,
    viewModel: BackupViewModel,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    var newExclusionText by remember { mutableStateOf("") }
    var showFifoConfirmationDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "Regole di Backup",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Criteri di automazione e sicurezza quota",
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
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Sezione 1: Frequenza automatica
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Frequenza Automatica",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "Gestito da WorkManager. L'esecuzione è approssimata per rispettare la batteria.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )

                    BackupFrequency.values().forEach { freq ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = uiState.rules.frequency == freq,
                                onClick = { viewModel.updateFrequency(freq) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = freq.label,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            // Sezione 2: Politica di Rete
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Wifi,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Vincoli di Rete",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "Imposta su quale connessione è consentito effettuare il trasferimento verso Drive.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )

                    NetworkPolicy.values().forEach { policy ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = uiState.rules.networkPolicy == policy,
                                onClick = { viewModel.updateNetworkPolicy(policy) }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = policy.label,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            // Sezione: Vincolo di Ricarica Batteria
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.BatteryChargingFull,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "Esegui solo durante la ricarica",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "Avvia il backup automatico solo se il dispositivo è collegato alla corrente.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = uiState.rules.requireCharging,
                            onCheckedChange = { viewModel.setRequireCharging(it) }
                        )
                    }
                }
            }

            // Sezione 3: Esclusioni di file e cartelle
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.FilterAlt,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Esclusioni Automatiche",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "I file e le sottocartelle corrispondenti non vengono caricati su Drive.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        uiState.rules.exclusions.forEach { pattern ->
                            InputChip(
                                selected = true,
                                onClick = { viewModel.removeExclusionPattern(pattern) },
                                label = { Text(pattern) },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = "Rimuovi esclusione",
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = newExclusionText,
                            onValueChange = { newExclusionText = it },
                            placeholder = { Text("es. *.log o cache") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                viewModel.addExclusionPattern(newExclusionText)
                                newExclusionText = ""
                            },
                            enabled = newExclusionText.isNotBlank()
                        ) {
                            Text("Aggiungi")
                        }
                    }
                }
            }

            // Sezione 4: Riserva di Spazio
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.SdStorage,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Riserva di Spazio",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        text = "Margine minimo di sicurezza mantenuto su Google Drive prima di consentire nuovi caricamenti.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Soglia minima riservata:",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Text(
                            text = "${uiState.rules.reserveSpaceMb} MB",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(50, 100, 250, 500).forEach { mb ->
                            FilterChip(
                                selected = uiState.rules.reserveSpaceMb == mb,
                                onClick = { viewModel.setReserveSpace(mb) },
                                label = { Text("$mb MB") },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }

            // Sezione 5: Rotazione FIFO (Disattivata per impostazione predefinita)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (uiState.rules.fifoRotationEnabled)
                        MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    else
                        MaterialTheme.colorScheme.surfaceVariant
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
                                imageVector = Icons.Outlined.AutoDelete,
                                contentDescription = null,
                                tint = if (uiState.rules.fifoRotationEnabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Rotazione FIFO remota",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Switch(
                            checked = uiState.rules.fifoRotationEnabled,
                            onCheckedChange = { willEnable ->
                                if (willEnable) {
                                    showFifoConfirmationDialog = true
                                } else {
                                    viewModel.setFifoEnabled(false)
                                }
                            }
                        )
                    }

                    Surface(
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        Text(
                            text = if (uiState.rules.fifoRotationEnabled)
                                "ATTENZIONE: La rotazione remota è ATTIVA. Se lo spazio su Drive si esaurisce, l'app cancellerà in modo PERMANENTE i file remoti più vecchi delle cartelle non protette (senza passare dal Cestino)."
                            else
                                "DISATTIVATA (Predefinita di sicurezza): Nessun file su Google Drive verrà mai cancellato automaticamente. Se lo spazio si esaurisce, il backup si sospenderà in sicurezza.",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (uiState.rules.fifoRotationEnabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(10.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Opzione copie cloud-only protette
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Elimina copie cloud-only se necessario",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Se disattivato (default), i file rimossi dal telefono rimangono protetti per sempre su Drive.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = uiState.rules.allowCloudOnlyEviction,
                            onCheckedChange = { viewModel.setAllowCloudOnlyEviction(it) },
                            enabled = uiState.rules.fifoRotationEnabled
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        if (showFifoConfirmationDialog) {
            AlertDialog(
                onDismissRequest = { showFifoConfirmationDialog = false },
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(32.dp)
                    )
                },
                title = {
                    Text("Attivazione Rotazione FIFO")
                },
                text = {
                    Text(
                        "L'attivazione della rotazione FIFO autorizza l'applicazione ad eliminare file remoti tramite 'files.delete' quando lo spazio Google Drive è insufficiente.\n\n" +
                        "• L'eliminazione su Drive è DEFINITIVA e NON passa dal Cestino.\n" +
                        "• I file locali sul telefono NON saranno MAI toccati o eliminati.\n" +
                        "• Le cartelle con 'Proteggi dalle eliminazioni automatiche' rimangono protette.\n\n" +
                        "Vuoi procedere con l'attivazione?"
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            viewModel.setFifoEnabled(true)
                            showFifoConfirmationDialog = false
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Attiva Rotazione")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showFifoConfirmationDialog = false }) {
                        Text("Annulla (Mantieni protetto)")
                    }
                }
            )
        }
    }
}
