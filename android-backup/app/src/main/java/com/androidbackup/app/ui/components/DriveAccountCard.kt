package com.androidbackup.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.androidbackup.app.BuildConfig
import com.androidbackup.app.data.model.DriveAuthFlowPurpose
import com.androidbackup.app.data.model.DriveOAuthConstants
import com.androidbackup.app.data.model.DriveSessionAuthState
import com.androidbackup.app.data.model.LinkedDriveAccount
import com.androidbackup.app.data.model.PersistedDriveCheckOutcome
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Scheda dedicata all'identità e autorizzazione Google Drive nella schermata Regole (Fase 1C.1).
 *
 * Vincoli di sicurezza e isolamento:
 * - Quando `isDemoMode == true`, disattiva ogni azione reale e NON mostra alcun dato identificativo
 *   (email, nome o ID) di un eventuale account reale associato in Room.
 * - Non espone mai token o credenziali.
 * - Distingue sempre l'inizializzazione (`Initializing`), l'associazione persistita non verificata
 *   nella sessione (`LinkedUnverifiedInSession`) e la verifica attiva (`VerifiedInSession`).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DriveAccountCard(
    isDemoMode: Boolean,
    authState: DriveSessionAuthState,
    feedbackMessage: String?,
    errorMessage: String?,
    remoteRevokeFailedMessage: String?,
    onConnectAccount: () -> Unit,
    onVerifyConnection: () -> Unit,
    onSwitchAccount: () -> Unit,
    onDisconnectWithRemoteRevoke: () -> Unit,
    onConfirmLocalOnlyDisconnect: () -> Unit,
    onDebugSilentTokenCheck: () -> Unit,
    onDismissMessages: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showSwitchConfirmDialog by remember { mutableStateOf(false) }
    var showDisconnectConfirmDialog by remember { mutableStateOf(false) }
    var showLocalOnlySecondConfirmDialog by remember { mutableStateOf(false) }

    // In modalità Demo Mock la scheda è visibile ma disabilitata e priva di dati reali dell'account
    if (isDemoMode) {
        Card(
            modifier = modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Account Google Drive",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = "Demo Mock attiva",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.7f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Disattiva Demo Mock nella schermata Home per collegare un account reale",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Button(
                    onClick = {},
                    enabled = false,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Collega account Google Drive")
                }
            }
        }
        return
    }

    val currentAccount: LinkedDriveAccount? = when (authState) {
        DriveSessionAuthState.Initializing,
        DriveSessionAuthState.NotLinked -> null
        is DriveSessionAuthState.LinkedUnverifiedInSession -> authState.account
        is DriveSessionAuthState.InProgress -> authState.existingAccount
        is DriveSessionAuthState.Disconnecting -> authState.account
        is DriveSessionAuthState.VerifiedInSession -> authState.account
        is DriveSessionAuthState.ReauthRequired -> authState.account
        is DriveSessionAuthState.LastVerificationFailed -> authState.account
    }

    val isBusy = authState is DriveSessionAuthState.Initializing ||
        authState is DriveSessionAuthState.InProgress ||
        authState is DriveSessionAuthState.Disconnecting

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.AccountCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Account Google Drive",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                DriveStateBadge(authState = authState)
            }

            Spacer(modifier = Modifier.height(10.dp))

            when (authState) {
                DriveSessionAuthState.Initializing -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = "Controllo dell'associazione locale Google Drive in corso...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                DriveSessionAuthState.NotLinked -> {
                    Text(
                        text = "Nessun account collegato. Collega un singolo account Google per autorizzare esclusivamente lo scope applicativo minimo (drive.file).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                is DriveSessionAuthState.LinkedUnverifiedInSession -> {
                    DriveAccountDetailsSection(
                        account = authState.account,
                        sessionVerifiedAt = null
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = Color(0xFFFEF3C7),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Account salvato localmente, ma non ancora verificato nella sessione corrente. Verifica la connessione prima di future operazioni Drive.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF92400E),
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                    if (authState.account.lastPersistedOutcome != PersistedDriveCheckOutcome.LAST_CHECK_SUCCEEDED &&
                        !authState.account.lastErrorMessage.isNullOrBlank()
                    ) {
                        Text(
                            text = "Ultimo esito registrato (${authState.account.lastPersistedOutcome.name}): ${authState.account.lastErrorMessage}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }

                is DriveSessionAuthState.VerifiedInSession -> {
                    DriveAccountDetailsSection(
                        account = authState.account,
                        sessionVerifiedAt = authState.verifiedAt
                    )
                }

                is DriveSessionAuthState.InProgress -> {
                    if (authState.existingAccount != null) {
                        DriveAccountDetailsSection(
                            account = authState.existingAccount,
                            sessionVerifiedAt = null
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = when (authState.purpose) {
                                DriveAuthFlowPurpose.INITIAL_LINK -> "Collegamento account Google Drive in corso..."
                                DriveAuthFlowPurpose.REVERIFY -> "Verifica identità Google Drive in corso..."
                                DriveAuthFlowPurpose.SWITCH_ACCOUNT -> "Cambio account Google Drive in corso..."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                is DriveSessionAuthState.Disconnecting -> {
                    DriveAccountDetailsSection(
                        account = authState.account,
                        sessionVerifiedAt = null
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 4.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "Revoca autorizzazione e disconnessione in corso...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                is DriveSessionAuthState.ReauthRequired -> {
                    if (authState.account != null) {
                        DriveAccountDetailsSection(
                            account = authState.account,
                            sessionVerifiedAt = null
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Rinnovo autorizzazione richiesto: ${authState.reason}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }

                is DriveSessionAuthState.LastVerificationFailed -> {
                    if (authState.account != null) {
                        DriveAccountDetailsSection(
                            account = authState.account,
                            sessionVerifiedAt = null
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "Ultima verifica non riuscita: ${authState.reason}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(10.dp)
                        )
                    }
                }
            }

            // Messaggio di feedback positivo/informativo
            if (!feedbackMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = feedbackMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(
                            onClick = onDismissMessages,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ErrorOutline,
                                contentDescription = "Chiudi messaggio",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            // Messaggio di errore flusso autorizzazione/verifica
            if (!errorMessage.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onDismissMessages) {
                            Text("OK")
                        }
                    }
                }
            }

            // Gestione esplicita fallimento revoca remota: riga Room preservata + scelta utente
            if (!remoteRevokeFailedMessage.isNullOrBlank() && currentAccount != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Revoca remota non riuscita (account mantenuto)",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = remoteRevokeFailedMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = onDisconnectWithRemoteRevoke,
                                enabled = !isBusy
                            ) {
                                Text("Riprova disconnessione")
                            }
                            OutlinedButton(
                                onClick = { showLocalOnlySecondConfirmDialog = true },
                                enabled = !isBusy
                            ) {
                                Text("Scollega solo localmente")
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Azioni principali
            if (authState !is DriveSessionAuthState.Initializing) {
                if (currentAccount == null) {
                    Button(
                        onClick = onConnectAccount,
                        enabled = !isBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Collega account Google Drive")
                    }
                } else {
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onVerifyConnection,
                            enabled = !isBusy
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                if (authState is DriveSessionAuthState.ReauthRequired) {
                                    "Rinnova autorizzazione"
                                } else {
                                    "Verifica connessione"
                                }
                            )
                        }

                        OutlinedButton(
                            onClick = { showSwitchConfirmDialog = true },
                            enabled = !isBusy
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.SwapHoriz,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Cambia account")
                        }

                        OutlinedButton(
                            onClick = { showDisconnectConfirmDialog = true },
                            enabled = !isBusy,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.CloudOff,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Disconnetti")
                        }
                    }

                    // Pulsante diagnostico esclusivo delle build DEBUG (verifica silenziosa senza UI)
                    if (BuildConfig.DEBUG) {
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = onDebugSilentTokenCheck,
                            enabled = !isBusy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Debug: Verifica token silenzioso (no UI)")
                        }
                    }
                }
            }
        }
    }

    // Dialog conferma cambio account
    if (showSwitchConfirmDialog && currentAccount != null) {
        AlertDialog(
            onDismissRequest = { showSwitchConfirmDialog = false },
            title = { Text("Cambia account Google Drive") },
            text = {
                Text(
                    "OONI-Sync collega un solo account alla volta. Se scegli e autorizzi un altro account Google, sostituirà localmente ${currentAccount.primaryDisplayLabel()}.\n\n" +
                        "Se annulli o si verifica un errore prima del completamento, l'account attuale resterà invariato."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showSwitchConfirmDialog = false
                        onSwitchAccount()
                    }
                ) {
                    Text("Continua")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSwitchConfirmDialog = false }) {
                    Text("Annulla")
                }
            }
        )
    }

    // Dialog conferma disconnessione (distingue email disponibile vs email null)
    if (showDisconnectConfirmDialog && currentAccount != null) {
        val hasEmail = !currentAccount.emailAddress.isNullOrBlank()
        AlertDialog(
            onDismissRequest = { showDisconnectConfirmDialog = false },
            icon = {
                Icon(
                    imageVector = if (hasEmail) Icons.Outlined.CloudOff else Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Disconnetti account Google Drive") },
            text = {
                if (hasEmail) {
                    Text(
                        "Verrà richiesta la revoca remota dell'autorizzazione Google Drive (scope drive.file) per ${currentAccount.emailAddress} tramite Google Play Services, quindi l'associazione verrà rimossa dal dispositivo.\n\n" +
                            "Se la revoca remota non riesce, l'associazione locale verrà mantenuta finché non deciderai cosa fare."
                    )
                } else {
                    Text(
                        "Google non ha fornito un indirizzo email per questo account: non è possibile eseguire la revoca remota automatica.\n\n" +
                            "Puoi rimuovere l'associazione locale da OONI-Sync e revocare manualmente l'accesso dalle impostazioni del tuo Account Google (myaccount.google.com/permissions -> Accesso di terze parti)."
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showDisconnectConfirmDialog = false
                        if (hasEmail) {
                            onDisconnectWithRemoteRevoke()
                        } else {
                            onConfirmLocalOnlyDisconnect()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(if (hasEmail) "Revoca e disconnetti" else "Scollega solo localmente")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisconnectConfirmDialog = false }) {
                    Text("Annulla")
                }
            }
        )
    }

    // Seconda conferma esplicita dopo fallimento di revoca remota
    if (showLocalOnlySecondConfirmDialog && currentAccount != null) {
        AlertDialog(
            onDismissRequest = { showLocalOnlySecondConfirmDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Conferma scollegamento locale") },
            text = {
                Text(
                    "La revoca remota verso Google non è stata completata.\n\n" +
                        "Se confermi lo scollegamento solo locale, l'account verrà rimosso da OONI-Sync su questo dispositivo, ma l'autorizzazione Google Drive potrebbe restare attiva nel tuo Account Google finché non la revochi manualmente da myaccount.google.com/permissions."
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLocalOnlySecondConfirmDialog = false
                        onConfirmLocalOnlyDisconnect()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Conferma scollegamento locale")
                }
            },
            dismissButton = {
                TextButton(onClick = { showLocalOnlySecondConfirmDialog = false }) {
                    Text("Annulla")
                }
            }
        )
    }
}

@Composable
private fun DriveStateBadge(authState: DriveSessionAuthState) {
    val (bg, fg, label) = when (authState) {
        DriveSessionAuthState.Initializing ->
            Triple(MaterialTheme.colorScheme.surface, MaterialTheme.colorScheme.onSurfaceVariant, "Inizializzazione")
        DriveSessionAuthState.NotLinked ->
            Triple(Color(0xFFE4E4E7), Color(0xFF3F3F46), "Non collegato")
        is DriveSessionAuthState.LinkedUnverifiedInSession ->
            Triple(Color(0xFFFEF3C7), Color(0xFF92400E), "Non verificato ora")
        is DriveSessionAuthState.InProgress ->
            Triple(Color(0xFFDBEAFE), Color(0xFF1E40AF), "Operazione in corso")
        is DriveSessionAuthState.Disconnecting ->
            Triple(Color(0xFFDBEAFE), Color(0xFF1E40AF), "Disconnessione")
        is DriveSessionAuthState.VerifiedInSession ->
            Triple(Color(0xFFD1FAE5), Color(0xFF065F46), "Verificato")
        is DriveSessionAuthState.ReauthRequired ->
            Triple(Color(0xFFFEE2E2), Color(0xFF991B1B), "Rinnovo richiesto")
        is DriveSessionAuthState.LastVerificationFailed ->
            Triple(Color(0xFFFEE2E2), Color(0xFF991B1B), "Verifica fallita")
    }

    Surface(
        color = bg,
        shape = RoundedCornerShape(6.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        ) {
            if (authState is DriveSessionAuthState.VerifiedInSession) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = null,
                    tint = fg,
                    modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = fg
            )
        }
    }
}

@Composable
private fun DriveAccountDetailsSection(
    account: LinkedDriveAccount,
    sessionVerifiedAt: Long?
) {
    val permissionSuffix = account.permissionId.takeLast(6)

    Surface(
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.padding(end = 8.dp)) {
                    Icon(
                        imageVector = Icons.Outlined.VerifiedUser,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = account.primaryDisplayLabel(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (!account.displayName.isNullOrBlank() && !account.emailAddress.isNullOrBlank()) {
                        Text(
                            text = account.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (account.emailAddress.isNullOrBlank()) {
                        Text(
                            text = "Email non fornita da Google • Identificativo Drive: …$permissionSuffix",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "ID canonico Drive: …$permissionSuffix",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 8.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            Text(
                text = "Scope autorizzato: ${
                    if (account.grantedScope == DriveOAuthConstants.DRIVE_FILE_SCOPE) {
                        "drive.file (accesso ai soli file dell'app)"
                    } else {
                        account.grantedScope
                    }
                }",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            val formattedTimestamp = formatTimestamp(sessionVerifiedAt ?: account.lastVerifiedAt)
            Text(
                text = if (sessionVerifiedAt != null) {
                    "Verificato in questa sessione: $formattedTimestamp"
                } else {
                    "Ultima verifica persistita: $formattedTimestamp"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

private fun formatTimestamp(epochMillis: Long): String {
    if (epochMillis <= 0L) return "Non disponibile"
    return try {
        SimpleDateFormat("d MMM yyyy, HH:mm", Locale.ITALIAN).format(Date(epochMillis))
    } catch (_: Exception) {
        epochMillis.toString()
    }
}
