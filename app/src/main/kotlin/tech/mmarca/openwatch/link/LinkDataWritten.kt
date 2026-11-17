package tech.mmarca.openwatch.link

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.mmarca.openlink.OpenLinkDataWritten
import tech.mmarca.openwatch.healthconnect.HealthConnectWrite

/**
 * The writes as OpenLink's `onDataWritten`: one per burst, once [quiet] has passed without
 * another, so a sync that inserts page after page tells a client once, not per page.
 * No device: a burst may mix the watch's and the scale's records.
 */
internal fun Flow<HealthConnectWrite>.asDataWritten(quiet: Duration = DATA_WRITTEN_QUIET): Flow<OpenLinkDataWritten> =
    channelFlow {
        val lock = Mutex()
        var pending: HealthConnectWrite? = null
        var flush: Job? = null
        collect { write ->
            lock.withLock {
                pending = pending?.plus(write) ?: write
                flush?.cancel()
                flush = launch {
                    delay(quiet)
                    // Taken and no longer cancellable by the next write: that one starts a burst of its own.
                    val burst = lock.withLock { pending.also { pending = null; flush = null } } ?: return@launch
                    send(OpenLinkDataWritten(burst.recordTypes, burst.from.toEpochMilli(), burst.to.toEpochMilli()))
                }
            }
        }
    }

private val DATA_WRITTEN_QUIET = 5.seconds
