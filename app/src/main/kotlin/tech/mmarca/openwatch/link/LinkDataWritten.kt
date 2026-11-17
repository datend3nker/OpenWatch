package tech.mmarca.openwatch.link

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.transformLatest
import tech.mmarca.openlink.OpenLinkDataWritten
import tech.mmarca.openwatch.healthconnect.HealthConnectWrite

/**
 * The writes as OpenLink's `onDataWritten`: one per burst, once [quiet] has passed without
 * another, so a sync that inserts page after page tells a client once, not per page.
 * No device: a burst may mix the watch's and the scale's records.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal fun Flow<HealthConnectWrite>.asDataWritten(quiet: Duration = DATA_WRITTEN_QUIET): Flow<OpenLinkDataWritten> = flow {
    // One per collector. transformLatest runs one block at a time: a write cancels the wait of the one before.
    var pending: HealthConnectWrite? = null
    emitAll(this@asDataWritten.transformLatest { write ->
        val burst = pending?.plus(write) ?: write
        pending = burst
        delay(quiet)
        emit(OpenLinkDataWritten(burst.recordTypes, burst.from.toEpochMilli(), burst.to.toEpochMilli()))
        pending = null
    })
}

private val DATA_WRITTEN_QUIET = 5.seconds
