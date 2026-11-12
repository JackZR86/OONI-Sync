package com.androidbackup.app.data.local.entity

import android.util.Log
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.androidbackup.app.data.model.AppSettings
import com.androidbackup.app.data.model.BackupFrequency
import com.androidbackup.app.data.model.NetworkPolicy

/**
 * Entità Room per la persistenza della singola riga di configurazione globale.
 * Conforme alla Specifica Fase 01A:
 * - Identificata da chiave fissa (id = 1)
 * - Salva gli enum come stringhe stabili tramite enum.name
 * - Gestisce valori sconosciuti con fallback sicuro a valori predefiniti
 */
@Entity(tableName = "app_settings")
data class AppSettingsEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: Int = DEFAULT_ID,

    @ColumnInfo(name = "frequency")
    val frequency: String,

    @ColumnInfo(name = "network_policy")
    val networkPolicy: String,

    @ColumnInfo(name = "reserve_space_mb")
    val reserveSpaceMb: Int,

    @ColumnInfo(name = "fifo_rotation_enabled")
    val fifoRotationEnabled: Boolean,

    @ColumnInfo(name = "allow_cloud_only_eviction")
    val allowCloudOnlyEviction: Boolean,

    @ColumnInfo(name = "require_charging")
    val requireCharging: Boolean,

    @ColumnInfo(name = "last_modified_timestamp")
    val lastModifiedTimestamp: Long = System.currentTimeMillis()
) {
    /**
     * Converte l'entità persistente nel modello di dominio [AppSettings].
     * Se una stringa enum è sconosciuta o corrotta, ricade sul valore predefinito
     * registrando un log esplicito.
     */
    fun toDomainModel(exclusions: List<String>): AppSettings {
        val parsedFrequency = try {
            BackupFrequency.valueOf(frequency)
        } catch (e: IllegalArgumentException) {
            safeLogWarn(
                TAG,
                "Frequenza '$frequency' non riconosciuta nel DB. Applicato fallback a ${BackupFrequency.TWENTY_FOUR_HOURS.name}."
            )
            BackupFrequency.TWENTY_FOUR_HOURS
        }

        val parsedNetworkPolicy = try {
            NetworkPolicy.valueOf(networkPolicy)
        } catch (e: IllegalArgumentException) {
            safeLogWarn(
                TAG,
                "Policy di rete '$networkPolicy' non riconosciuta nel DB. Applicato fallback a ${NetworkPolicy.ONLY_WIFI.name}."
            )
            NetworkPolicy.ONLY_WIFI
        }

        return AppSettings(
            frequency = parsedFrequency,
            networkPolicy = parsedNetworkPolicy,
            exclusions = exclusions,
            reserveSpaceMb = reserveSpaceMb,
            fifoRotationEnabled = fifoRotationEnabled,
            allowCloudOnlyEviction = allowCloudOnlyEviction,
            requireCharging = requireCharging
        )
    }

    companion object {
        const val DEFAULT_ID = 1
        private const val TAG = "AppSettingsEntity"

        /**
         * Crea un'entità partendo dal modello di dominio [AppSettings].
         */
        fun fromDomainModel(settings: AppSettings, id: Int = DEFAULT_ID): AppSettingsEntity {
            return AppSettingsEntity(
                id = id,
                frequency = settings.frequency.name,
                networkPolicy = settings.networkPolicy.name,
                reserveSpaceMb = settings.reserveSpaceMb,
                fifoRotationEnabled = settings.fifoRotationEnabled,
                allowCloudOnlyEviction = settings.allowCloudOnlyEviction,
                requireCharging = settings.requireCharging,
                lastModifiedTimestamp = System.currentTimeMillis()
            )
        }

        /**
         * Entità predefinita di fabbrica
         */
        fun defaultEntity(): AppSettingsEntity {
            return fromDomainModel(AppSettings())
        }

        fun safeLogWarn(tag: String, message: String) {
            try {
                android.util.Log.w(tag, message)
            } catch (_: Throwable) {
                System.err.println("WARN: [$tag] $message")
            }
        }
    }
}
