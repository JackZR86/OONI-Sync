package com.androidbackup.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.CreationExtras
import com.androidbackup.app.BackupApplication
import com.androidbackup.app.data.repository.DriveAccountRepository
import com.androidbackup.app.data.repository.FolderRepository
import com.androidbackup.app.data.repository.ScanRepository
import com.androidbackup.app.data.repository.SettingsRepository

/**
 * Factory per creare l'istanza di [BackupViewModel] iniettando [SettingsRepository],
 * [FolderRepository], [ScanRepository], [DriveAccountRepository] e [SavedStateHandle].
 */
class BackupViewModelFactory(
    private val settingsRepository: SettingsRepository,
    private val folderRepository: FolderRepository? = null,
    private val scanRepository: ScanRepository? = null,
    private val driveAccountRepository: DriveAccountRepository? = null
) : ViewModelProvider.Factory {

    constructor(
        settingsRepository: SettingsRepository,
        folderRepository: FolderRepository
    ) : this(settingsRepository, folderRepository, null, null)

    constructor(
        settingsRepository: SettingsRepository,
        folderRepository: FolderRepository?,
        scanRepository: ScanRepository?
    ) : this(settingsRepository, folderRepository, scanRepository, null)

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T {
        if (modelClass.isAssignableFrom(BackupViewModel::class.java)) {
            val savedStateHandle = extras.createSavedStateHandle()
            return BackupViewModel(
                settingsRepository = settingsRepository,
                folderRepository = folderRepository,
                scanRepository = scanRepository,
                driveAccountRepository = driveAccountRepository,
                savedStateHandle = savedStateHandle
            ) as T
        }
        throw IllegalArgumentException("Classe ViewModel non supportata: ${modelClass.name}")
    }

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(BackupViewModel::class.java)) {
            return BackupViewModel(
                settingsRepository = settingsRepository,
                folderRepository = folderRepository,
                scanRepository = scanRepository,
                driveAccountRepository = driveAccountRepository,
                savedStateHandle = SavedStateHandle()
            ) as T
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
                scanRepository = app.scanRepository,
                driveAccountRepository = app.driveAccountRepository
            )
        }
    }
}
