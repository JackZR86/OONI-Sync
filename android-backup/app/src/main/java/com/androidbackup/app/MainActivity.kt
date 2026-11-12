package com.androidbackup.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.androidbackup.app.ui.navigation.MainAppNavigation
import com.androidbackup.app.ui.theme.AndroidBackupTheme

/**
 * Single Activity per Android Backup.
 * Architettura MVVM leggera con Navigation Compose e Material 3.
 * Conforme alla Specifica Fase 0: nessun accesso non autorizzato a file locali o servizi cloud.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidBackupTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainAppNavigation()
                }
            }
        }
    }
}
