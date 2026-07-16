/*      This file is part of Mogluco, a Material 3 redesign fork of                  */
/*      Juggluco, for Freestyle Libre 2 and 3 glucose sensors.                       */
/*                                                                                   */
/*      Copyright (C) 2026 Joshua Meyer                                              */
/*                                                                                   */
/*      Mogluco is free software: you can redistribute it and/or modify              */
/*      it under the terms of the GNU General Public License as published            */
/*      by the Free Software Foundation, either version 3 of the License, or         */
/*      (at your option) any later version.                                          */
/*                                                                                   */
/*      Mogluco is distributed in the hope that it will be useful, but               */
/*      WITHOUT ANY WARRANTY; without even the implied warranty of                   */
/*      MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.                         */
/*      See the GNU General Public License for more details.                         */
/*                                                                                   */
/*      You should have received a copy of the GNU General Public License            */
/*      along with Mogluco. If not, see <https://www.gnu.org/licenses/>.             */
/*                                                                                   */
package de.mogluco.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.BloodGlucoseRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.BloodGlucose
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import de.mogluco.data.AppPreferences
import de.mogluco.data.GlucoseRepository
import java.time.Instant

/**
 * Optional one-way export of new glucose readings to Health Connect (off by default).
 *
 * Each reading is written as a [BloodGlucoseRecord] keyed by a clientRecordId = its timestamp, so
 * re-writing the same reading is an idempotent upsert rather than a duplicate. New readings stream
 * live via [start]; [backfill] is a one-shot push of the whole stored history (a manual button).
 *
 * Everything is defensive: if the platform SDK is unavailable, the toggle is off, or the WRITE
 * permission isn't held, writes are silently skipped. It must never throw into the glucose flow —
 * Health Connect is a nice-to-have, the sensor path is not.
 */
object HealthConnectSync {

    /** The one permission this feature needs. */
    val permissions: Set<String> = setOf(HealthPermission.getWritePermission(BloodGlucoseRecord::class))

    /** Whether the Health Connect SDK is present and usable on this device. */
    fun isAvailable(context: Context): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    fun clientOrNull(context: Context): HealthConnectClient? =
        if (isAvailable(context)) runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull() else null

    suspend fun hasWritePermission(context: Context): Boolean {
        val client = clientOrNull(context) ?: return false
        return runCatching {
            client.permissionController.getGrantedPermissions().containsAll(permissions)
        }.getOrDefault(false)
    }

    /**
     * Observe new readings for the process lifetime and export each one while the feature is enabled
     * and the permission is held. Called once from [de.mogluco.MoglucoApp.onCreate].
     */
    fun start(context: Context, repo: GlucoseRepository, prefs: AppPreferences) {
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO + SupervisorJob()).launch {
            var lastWritten = Long.MIN_VALUE
            repo.latest.collectLatest { reading ->
                if (reading == null || reading.epochMillis <= lastWritten) return@collectLatest
                if (!prefs.healthConnectEnabled.value) return@collectLatest
                val client = clientOrNull(appContext) ?: return@collectLatest
                if (!hasWritePermission(appContext)) return@collectLatest
                val ok = runCatching { client.insertRecords(listOf(record(reading.epochMillis, reading.rawMgdl))) }.isSuccess
                if (ok) lastWritten = reading.epochMillis
            }
        }
    }

    /**
     * One-shot export of the entire in-memory history to Health Connect. Idempotent (clientRecordId
     * per point), so re-running upserts rather than duplicating. Returns the number of points
     * written, or -1 when Health Connect is unavailable or the WRITE permission isn't held.
     */
    suspend fun backfill(context: Context, repo: GlucoseRepository): Int {
        val client = clientOrNull(context) ?: return -1
        if (!hasWritePermission(context)) return -1
        val points = repo.history.first()
        if (points.isEmpty()) return 0
        var written = 0
        // Health Connect caps records per insert (~a few thousand); stay well under it.
        points.chunked(900).forEach { chunk ->
            runCatching { client.insertRecords(chunk.map { record(it.epochMillis, it.rawMgdl) }) }
                .onSuccess { written += chunk.size }
        }
        return written
    }

    /** A single (time, mg/dL) point as an interstitial-fluid record with a stable client id. */
    private fun record(epochMillis: Long, mgdl: Double): BloodGlucoseRecord = BloodGlucoseRecord(
        time = Instant.ofEpochMilli(epochMillis),
        zoneOffset = null,
        // id = the point's timestamp → re-writing the same point upserts instead of duplicating.
        metadata = Metadata.autoRecordedWithId(epochMillis.toString(), Device(type = Device.TYPE_UNKNOWN)),
        level = BloodGlucose.milligramsPerDeciliter(mgdl),
        specimenSource = BloodGlucoseRecord.SPECIMEN_SOURCE_INTERSTITIAL_FLUID,
    )
}
