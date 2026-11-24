package tech.mmarca.openwatch.link

import tech.mmarca.openlink.OpenLinkSampleType
import tech.mmarca.openlink.host.OpenLinkActiveSession

/** What one app's session is doing to a watch, in the words the screen and notification use. */
data class LinkSessionStatus(
    val packageName: String,
    val kind: Kind,
    /** The devices streaming, or, while waiting, the devices waited for. */
    val deviceNames: List<String>,
) {
    enum class Kind {
        /** No reading yet from any of the session's devices. */
        WAITING,

        /** Heart rate goes to the app. */
        HEART_RATE,

        /** Heart rate and steps go to the app. */
        HEART_RATE_AND_STEPS,

        /** The watch is kept streaming for a recording; no live readings go to the app. */
        KEEPING_STREAMING,

        /** The watch records the workout for the app. */
        RECORDING_WORKOUT,
    }
}

/**
 * The sessions apps hold now, joined with what each is doing: who owns a session comes from
 * the OpenLink host, which devices it follows from [LinkLiveSessions]. One line per session,
 * in the order the host lists them; a recording and its subscription share a session id.
 */
fun linkSessionStatuses(
    active: List<OpenLinkActiveSession>,
    activity: Map<String, LinkSessionActivity>,
): List<LinkSessionStatus> =
    active.distinctBy { it.packageName to it.sessionId }.mapNotNull { session ->
        val doing = activity[session.sessionId] ?: return@mapNotNull null
        if (doing.recorded) return@mapNotNull LinkSessionStatus(session.packageName, LinkSessionStatus.Kind.RECORDING_WORKOUT, doing.devices.map { it.name })
        val streaming = doing.streaming
        if (streaming.isEmpty()) {
            return@mapNotNull LinkSessionStatus(session.packageName, LinkSessionStatus.Kind.WAITING, doing.devices.map { it.name })
        }
        val kind = when {
            OpenLinkSampleType.HEART_RATE in doing.liveTypes && OpenLinkSampleType.STEPS in doing.liveTypes ->
                LinkSessionStatus.Kind.HEART_RATE_AND_STEPS
            OpenLinkSampleType.HEART_RATE in doing.liveTypes -> LinkSessionStatus.Kind.HEART_RATE
            else -> LinkSessionStatus.Kind.KEEPING_STREAMING
        }
        LinkSessionStatus(session.packageName, kind, streaming.map { it.name })
    }
