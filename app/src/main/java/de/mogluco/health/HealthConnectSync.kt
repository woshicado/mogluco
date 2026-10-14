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
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.BloodGlucose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import de.mogluco.data.AppPreferences
import de.mogluco.data.GlucosePoint
import de.mogluco.data.GlucoseRepository
import de.mogluco.data.windowed
import java.time.Instant
import java.util.NavigableSet
import java.util.TreeSet

/**
 * Optional one-way export of glucose readings to Health Connect (off by default).
 *
 * Each reading is written as a [BloodGlucoseRecord] keyed by a clientRecordId = its time in whole
 * seconds ([recordId]), so re-writing the same point is an idempotent upsert rather than a
 * duplicate. The id alone does not make one record of one reading — its time changes when the
 * store's replaces the live one ([SAME_READING_MS]), now and then into the next second — so
 * readings that may have been written before are first looked up in Health Connect and left out
 * where a record of this app is already there ([insert]; reading back what the app itself wrote
 * needs no permission beyond the one to write).
 *
 * [start] exports what is new in the stored history whenever it changes: the live readings, and
 * those that arrive later for a time already past (the sensor's backfill after a reconnect, a deep
 * pull, the watch's catch-up). [pushHistory] is a one-shot push of the whole stored history (a
 * manual button). Both run in this object's own scope, for as long as the process lives, and write
 * one request at a time ([writes]).
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
        return attempt {
            client.permissionController.getGrantedPermissions().containsAll(permissions)
        }.getOrDefault(false)
    }

    /** Lives as long as the process: neither export belongs to a screen that can go away under it. */
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * Held for one request — the lookup and the write that follows from it — so that the automatic
     * export and a push never write side by side, each not seeing what the other is writing.
     */
    private val writes = Mutex()

    /**
     * A reading up to here may be in Health Connect without the automatic export having put it
     * there itself: written by the process before this one, or by a push. See [start].
     */
    @Volatile
    private var mayBeWrittenUntil = 0L

    private val _pushState = MutableStateFlow<PushState>(PushState.Idle)

    /** Where the manual push stands ([pushHistory]). */
    val pushState: StateFlow<PushState> = _pushState.asStateFlow()

    /**
     * Follow the stored history for the process lifetime and export what is not in Health Connect
     * yet, while the feature is enabled and the permission is held. Called once from
     * [de.mogluco.MoglucoApp.onCreate].
     *
     * The history rather than the latest reading: a reading that arrives for a time already past
     * only shows up there, and following the latest one left that stretch out of Health Connect for
     * good. How far the export has come is persisted ([AppPreferences.healthConnectSyncedMillis]),
     * so readings that were not written — no permission, Health Connect unavailable, the process
     * gone — are written with the next change of the history.
     */
    fun start(context: Context, repo: GlucoseRepository, prefs: AppPreferences) {
        val appContext = context.applicationContext
        val origin = DataOrigin(appContext.packageName)
        // A reading up to here may have been written by the process before this one: what follows
        // a mark that was pulled back ([ExportProgress.notWritten]), or what was written just
        // before that process went, the mark not yet saved. This process finds it at the store's
        // time, so it is looked up before it is written ([insert]). A newer one is this process's
        // own to account for ([ExportProgress]) and is written as it comes, without the lookup.
        // The same goes for what a push wrote in between ([backfill]).
        mayBeWrittenUntil = System.currentTimeMillis() + SAME_READING_MS
        scope.launch {
            var progress: ExportProgress? = null
            // One collector, and `collect` rather than `collectLatest`: a write is never cancelled
            // by the next emission and never runs beside another one. What changes during a write
            // is conflated into the one snapshot looked at after it.
            // Not before the stored readings are in ([GlucoseRepository.historyLoaded]): a process
            // that has only just started may hold a single live reading ahead of them, and what
            // the export would make of that snapshot is not what it has to send. (Switching the
            // export off is taken note of at any time.)
            combine(repo.history, repo.historyLoaded, prefs.healthConnectEnabled) { points, loaded, enabled ->
                Triple(points, loaded, enabled)
            }
                .filter { (_, loaded, enabled) -> loaded || !enabled }
                .map { (points, _, enabled) -> points.takeIf { enabled } }
                .conflate()
                .collect { points ->
                    if (points == null) {
                        // Off: forget how far the export came, so that switching it on again starts
                        // at that moment instead of sending what was measured in between.
                        progress = null
                        if (prefs.healthConnectSyncedMillis.value != 0L) prefs.setHealthConnectSyncedMillis(0L)
                        return@collect
                    }
                    attempt {
                        val now = System.currentTimeMillis()
                        // Switched on (or a mark from a clock that ran ahead): start now. The past
                        // is what the explicit "Push history" is for.
                        val export = progress
                            ?: ExportProgress(prefs.healthConnectSyncedMillis.value.takeIf { it in 1..now } ?: now)
                                .also { progress = it }
                        val pending = export.pending(points, now)
                        if (pending.isNotEmpty()) {
                            val client = if (hasWritePermission(appContext)) clientOrNull(appContext) else null
                            for (chunk in pending.chunked(BATCH_SIZE)) {
                                // Oldest first, and stop at the first request that fails: the rest is
                                // tried again with the next change, not hammered at Health Connect now.
                                val taken = client != null && writes.withLock {
                                    val lookUp = origin.takeIf { chunk.first().epochMillis <= mayBeWrittenUntil }
                                    insert(client, chunk, lookUp).failed == 0
                                }
                                if (!taken) {
                                    export.notWritten(chunk.first())
                                    break
                                }
                                export.written(chunk)
                            }
                        }
                        if (export.mark != prefs.healthConnectSyncedMillis.value) {
                            prefs.setHealthConnectSyncedMillis(export.mark)
                        }
                    }
                }
        }
    }

    /**
     * Pushes the whole stored history to Health Connect once (the manual button); [pushState] says
     * where it stands and, afterwards, what it did. Safe to repeat: a reading this app already has
     * a record of there — from the automatic export or an earlier push — is left as it is.
     *
     * It runs in this object's scope and not in the caller's: leaving the screen it was started
     * from does not end it half-way. One at a time — a call while a push is running does nothing.
     */
    fun pushHistory(context: Context, repo: GlucoseRepository) {
        val appContext = context.applicationContext
        val before = _pushState.value
        if (before == PushState.Pushing || !_pushState.compareAndSet(before, PushState.Pushing)) return
        scope.launch {
            val message = try {
                attempt { pushMessage(backfill(appContext, repo)) }
                    .getOrDefault("Pushing history to Health Connect failed. Push again to complete it")
            } catch (e: CancellationException) {
                // Nothing cancels this scope today; if something does, the row must not go on
                // saying "Pushing", and no outcome is made up for a push that did not end.
                _pushState.value = PushState.Idle
                throw e
            }
            _pushState.value = PushState.Done(message)
            // The screen that asked may be gone by now, so the outcome is announced from here.
            attempt {
                withContext(Dispatchers.Main) {
                    android.widget.Toast.makeText(appContext, message, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * The push itself: every stored reading, a request's worth at a time. Null when Health Connect
     * is unavailable or the WRITE permission isn't held.
     */
    private suspend fun backfill(context: Context, repo: GlucoseRepository): PushResult? {
        val client = clientOrNull(context) ?: return null
        if (!hasWritePermission(context)) return null
        // From here on the automatic export looks up what it writes up to this moment: the push
        // may have written it first, at the time the history had it under just then.
        mayBeWrittenUntil = maxOf(mayBeWrittenUntil, System.currentTimeMillis() + SAME_READING_MS)
        // One point per reading: where the history holds a reading twice (see [SAME_READING_MS])
        // the two would carry the same clientRecordId within one request.
        val points = notExported(repo.history.first(), TreeSet())
        val origin = DataOrigin(context.packageName)
        return pushInChunks(points) { chunk -> writes.withLock { insert(client, chunk, origin) } }
    }

    /**
     * Writes [points] (time-sorted) in one request, and says what became of them: [PushResult.failed]
     * when Health Connect did not take them.
     *
     * With [lookUp] — this app as a data origin, given when some of the points may have been
     * written before — those this app already has a record of within [SAME_READING_MS] are left
     * out: the earlier record can carry another clientRecordId than the point would get now (the
     * live time of the reading against the store's a second later; the full millisecond older
     * versions used), and writing it again made a second record beside it. Where the records
     * cannot be read, everything is written as before: the same point then still replaces itself
     * through its clientRecordId. Not so where the caller was cancelled: that ends the request
     * ([attempt]) instead of passing for records that cannot be read.
     */
    private suspend fun insert(client: HealthConnectClient, points: List<GlucosePoint>, lookUp: DataOrigin?): PushResult {
        val missing = notInHealthConnect(points, lookUp?.let { recordTimes(client, it, points) })
        // A point no record can be made of (a value outside what Health Connect accepts) is left
        // out rather than holding up every reading after it.
        val records = missing.mapNotNull { runCatching { record(it.epochMillis, it.rawMgdl) }.getOrNull() }
        val taken = records.isEmpty() || attempt { client.insertRecords(records) }.isSuccess
        return PushResult(
            written = if (taken) records.size else 0,
            alreadyThere = points.size - missing.size,
            failed = if (taken) 0 else records.size,
        )
    }

    /**
     * The times of the records [origin] (this app) has in Health Connect over the span of [points]
     * (time-sorted, not empty), a reading's width to either side. Null when they could not be read.
     */
    private suspend fun recordTimes(
        client: HealthConnectClient,
        origin: DataOrigin,
        points: List<GlucosePoint>,
    ): NavigableSet<Long>? = attempt {
        val span = TimeRangeFilter.between(
            Instant.ofEpochMilli(points.first().epochMillis - SAME_READING_MS),
            Instant.ofEpochMilli(points.last().epochMillis + SAME_READING_MS + 1), // the end is exclusive
        )
        val times = TreeSet<Long>()
        var pageToken: String? = null
        do {
            val page = client.readRecords(
                ReadRecordsRequest(
                    recordType = BloodGlucoseRecord::class,
                    timeRangeFilter = span,
                    dataOriginFilter = setOf(origin),
                    pageToken = pageToken,
                ),
            )
            page.records.forEach { times.add(it.time.toEpochMilli()) }
            pageToken = page.pageToken?.takeIf { it.isNotEmpty() && page.records.isNotEmpty() }
        } while (pageToken != null)
        times
    }.getOrNull()

    /** A single (time, mg/dL) point as an interstitial-fluid record with a stable client id. */
    private fun record(epochMillis: Long, mgdl: Double): BloodGlucoseRecord = BloodGlucoseRecord(
        time = Instant.ofEpochMilli(epochMillis),
        zoneOffset = null,
        // id = the point's time → re-writing the same point upserts instead of duplicating.
        metadata = Metadata.autoRecordedWithId(recordId(epochMillis), Device(type = Device.TYPE_UNKNOWN)),
        level = BloodGlucose.milligramsPerDeciliter(mgdl),
        specimenSource = BloodGlucoseRecord.SPECIMEN_SOURCE_INTERSTITIAL_FLUID,
    )
}

// Health Connect caps records per insert (~a few thousand); stay well under it.
internal const val BATCH_SIZE = 900

/**
 * [runCatching] around a suspending call: a failure is a failure, but not where the coroutine was
 * cancelled meanwhile — then the cancellation goes on to the caller. Taken for a failure, it had
 * the loop around the call carry on with everything that was left, the lookups all "not readable".
 *
 * A CancellationException from somewhere else, the coroutine itself still active, stays a failure:
 * passed on, it would quietly end the automatic export for the rest of the process.
 */
internal suspend inline fun <T> attempt(block: () -> T): Result<T> =
    runCatching(block).onFailure { currentCoroutineContext().ensureActive() }

/** Where the manual "Push history" stands ([HealthConnectSync.pushState]). */
sealed interface PushState {
    /** Not asked for in this process yet. */
    data object Idle : PushState

    data object Pushing : PushState

    /** The last push ended; [message] says what it did. */
    data class Done(val message: String) : PushState
}

/**
 * What became of the readings of a push, or of one request of it: [written] now, [alreadyThere] as
 * a record of this app and left alone, or in a request Health Connect did not take ([failed]).
 */
internal data class PushResult(val written: Int = 0, val alreadyThere: Int = 0, val failed: Int = 0) {
    operator fun plus(other: PushResult) =
        PushResult(written + other.written, alreadyThere + other.alreadyThere, failed + other.failed)
}

/**
 * [write]s [points] (time-sorted) a request's worth at a time, oldest first, and adds up what
 * became of them. A request that fails does not stop it — pushing again fills in what is missing —
 * but a cancellation does, before the next request.
 */
internal suspend fun pushInChunks(
    points: List<GlucosePoint>,
    write: suspend (List<GlucosePoint>) -> PushResult,
): PushResult {
    var result = PushResult()
    for (chunk in points.chunked(BATCH_SIZE)) {
        currentCoroutineContext().ensureActive()
        result += write(chunk)
    }
    return result
}

/** What the row and the toast say after a push; null: it could not be started (see [HealthConnectSync]). */
internal fun pushMessage(result: PushResult?): String {
    fun readings(n: Int) = if (n == 1) "1 reading" else "$n readings"
    if (result == null) return "Grant Health Connect access first"
    val (written, alreadyThere, failed) = result
    val done = when {
        written > 0 || failed > 0 ->
            (if (written > 0) "Wrote ${readings(written)} to Health Connect" else "Wrote nothing to Health Connect") +
                if (alreadyThere > 0) ", $alreadyThere ${if (alreadyThere == 1) "was" else "were"} already there" else ""
        alreadyThere > 0 -> "Nothing new: ${readings(alreadyThere)} already in Health Connect"
        else -> "No stored readings to push"
    }
    return if (failed > 0) "$done. ${readings(failed)} could not be written. Push again to retry" else done
}

/**
 * Two points at most this far apart are the same reading. The live path stamps a reading with the
 * millisecond it was received at, the engine's store with the second it was saved in, a moment
 * later; the history shows the first until the next reload and the second from then on (and, at the
 * newest reading, sometimes both). Readings of one sensor are a minute apart.
 */
internal const val SAME_READING_MS = 5_000L

/** How far back a reading that arrives late (a filled gap) is still looked for and exported. */
internal const val RECONCILE_WINDOW_MS = 24 * 60 * 60_000L

/**
 * How long [ExportProgress] gives the stored history to turn up. It is read when the process
 * starts; until then the history is empty, or holds only what came in live. The export only looks
 * once the repository says the store is read ([HealthConnectSync.start]), so this is the second
 * line: for a snapshot that reaches it before that all the same. A history that still begins
 * after the mark this long after the export first looked is taken to begin there — a first
 * sensor, or the older readings are gone.
 */
internal const val HISTORY_LOAD_MS = 5 * 60_000L

/**
 * The clientRecordId of the reading at [epochMillis]: its time in whole seconds, so that a reading
 * mostly keeps its id when the store's time replaces the live one (see [SAME_READING_MS]) — on the
 * full millisecond that made two records of it. Unchanged for a point of the store.
 *
 * Mostly: the store takes its time a moment after the live one, so a reading received just before
 * a second ends is stored in the next and gets another id there. The id therefore only keeps the
 * same point from being written twice; that a reading already in Health Connect under its other
 * time is not written again is for the lookup in [HealthConnectSync] to see to.
 */
internal fun recordId(epochMillis: Long): String = (epochMillis / 1000 * 1000).toString()

/**
 * Those of [points] (time-sorted) that are not exported yet: no time in [exported] is within
 * [SAME_READING_MS] of them, and neither is the point taken before them.
 */
internal fun notExported(points: List<GlucosePoint>, exported: NavigableSet<Long>): List<GlucosePoint> {
    val out = ArrayList<GlucosePoint>()
    for (p in points) {
        val t = p.epochMillis
        if (out.isNotEmpty() && t - out.last().epochMillis <= SAME_READING_MS) continue
        val before = exported.floor(t)
        val after = exported.ceiling(t)
        if (before != null && t - before <= SAME_READING_MS) continue
        if (after != null && after - t <= SAME_READING_MS) continue
        out.add(p)
    }
    return out
}

/**
 * Those of [points] (time-sorted) to write, given the times [recordTimes] of the records this app
 * has in Health Connect around them: the ones it has no record of yet. All of them where the
 * records are not known (null) — not looked up, or not readable.
 */
internal fun notInHealthConnect(points: List<GlucosePoint>, recordTimes: NavigableSet<Long>?): List<GlucosePoint> =
    if (recordTimes == null) points else notExported(points, recordTimes)

/**
 * How far the automatic export has come — the bookkeeping of [HealthConnectSync.start] without
 * Health Connect in it.
 *
 * [mark] is the part that is persisted: every reading up to that time is in Health Connect, or was
 * there before the export was switched on. What is newer is still to be written. A reading that
 * turns up later at or before the mark — a gap that was filled — is recognised by not having been
 * there before: [exported] holds the times of the last [RECONCILE_WINDOW_MS] that are accounted
 * for, in memory only. It starts from the history as this process found it (the baseline); a gap
 * that was filled but could not be written pulls the mark back to before it ([notWritten]), so
 * that it is not taken as given by the next process. Until the baseline is taken the mark does not
 * move: what is written before it is accounted for in memory only ([written]).
 */
internal class ExportProgress(mark: Long) {

    var mark = mark
        private set

    /** The mark this process started from: what the history held up to it is taken as exported. */
    private val startMark = mark
    private var baselined = false
    private val exported = TreeSet<Long>()

    /** When [pending] was first asked: from then on the stored history is waited for ([HISTORY_LOAD_MS]). */
    private var firstAsked = 0L

    /** How far the readings written before the baseline reach — where the mark goes once it may. */
    private var ahead = 0L

    /** The newest point [pending] last returned: with it, everything it returned is written. */
    private var lastPending = 0L

    /** The points of [history] (time-sorted) to write now, oldest first. */
    fun pending(history: List<GlucosePoint>, now: Long): List<GlucosePoint> {
        val windowStart = now - RECONCILE_WINDOW_MS
        if (firstAsked == 0L) firstAsked = now
        // Taken from the first snapshot that reaches back to the mark: the history starts out
        // empty, and a live reading can land in it before the stored readings are loaded — neither
        // says what was there before, and the stored readings would then all look newly arrived.
        // A history that begins after the mark even when the store has had its time to load does
        // begin there: nothing in it is taken as exported, and the wait is over all the same — the
        // mark would otherwise stay behind it for good, and every process look up all of it again.
        if (!baselined && history.isNotEmpty() &&
            (history.first().epochMillis <= startMark || now - firstAsked >= HISTORY_LOAD_MS)
        ) {
            history.windowed(windowStart, startMark).forEach { exported.add(it.epochMillis) }
            baselined = true
        }
        var from = minOf(windowStart, mark + 1)
        if (!baselined) from = maxOf(from, startMark + 1)
        exported.headSet(from - SAME_READING_MS).clear()
        val pending = notExported(history.windowed(from, Long.MAX_VALUE - 1), exported)
        lastPending = pending.lastOrNull()?.epochMillis ?: 0L
        // Nothing to write: then what was written before the baseline is all there was after the mark.
        if (baselined && pending.isEmpty()) mark = maxOf(mark, ahead)
        return pending
    }

    /** The oldest [points] of what was pending are in Health Connect now. */
    fun written(points: List<GlucosePoint>) {
        points.forEach { exported.add(it.epochMillis) }
        // A little past the newest one, to cover the time the store gives the same reading.
        val upTo = points.last().epochMillis + SAME_READING_MS
        if (!baselined) {
            // A live reading ahead of the stored history: what the store holds between the mark
            // and this reading is not written yet, and with the mark moved past it, it would not
            // be — older than the window it is not looked for, and the next process, should this
            // one end before the store is loaded, takes everything up to its mark as exported.
            // So the mark waits; [exported] keeps the reading from being written again here, and
            // the next process, which it is older than, looks it up before writing it.
            ahead = maxOf(ahead, upTo)
            return
        }
        mark = maxOf(mark, upTo)
        // That was the last of it: what waited between the mark and the readings written before
        // the baseline is in Health Connect, so the mark may pass those too.
        if (points.last().epochMillis >= lastPending) mark = maxOf(mark, ahead)
    }

    /** [first] and what was pending after it could not be written. */
    fun notWritten(first: GlucosePoint) {
        mark = minOf(mark, first.epochMillis - SAME_READING_MS - 1)
    }
}
