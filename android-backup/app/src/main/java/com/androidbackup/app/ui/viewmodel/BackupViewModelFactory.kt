package com.androidbackup.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.androidbackup.app.BackupApplication
import com.androidbackup.app.data.repository.FolderRepository
import com.androidbackup.app.data.repository.ScanRepository
import com.androidbackup.app.data.repository.SettingsRepository

/**
 * Factory per creare l'istanza di [BackupViewModel] iniettando [SettingsRepository], [FolderRepository] e [ScanRepository].
 *
 * Conforme alla Specifica Fase 1B.2:
 * - Evita framework complessi di DI
 * - Garantisce che il ViewModel acceda a Room e alle API SAF esclusivamente tramite i rispettivi Repository
 */
class BackupViewModelFactory(
    private val settingsRepository: SettingsRepository,
    private val folderRepository: FolderRepository? = null,
    private val scanRepository: ScanRepository? = null
) : ViewModelProvider.Factory {

    constructor(
        settingsRepository: SettingsRepository,
        folderRepository: FolderRepository
    ) : this(settingsRepository, folderRepository, null)

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BackupViewModel::class.java)) {
            return BackupViewModel(settingsRepository, folderRepository, scanRepository) as T
        }
        throw IllegalArgumentException("Classe ViewModel non supportata: ${modelClass.name}")
    }

    companion object {
        /**
         * Crea la factory a partire dall'application context
         */
        fun provideFactory(context: Context): BackupViewModelFactory {
            val app = context.applicationContext as? BackupApplication
                ?: throw IllegalStateException("L'Application context deve essere un'istanza di BackupApplication")
            return BackupViewModelFactory(
                settingsRepository = app.settingsRepository,
                folderRepository = app.folderRepository,
                scanRepository = app.scanRepository
            )
        }
    }
}
