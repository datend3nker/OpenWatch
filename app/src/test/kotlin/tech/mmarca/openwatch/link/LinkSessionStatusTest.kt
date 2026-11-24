package tech.mmarca.openwatch.link

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.mmarca.openlink.OpenLink
import tech.mmarca.openlink.OpenLinkSampleType
import tech.mmarca.openlink.host.OpenLinkActiveSession

class LinkSessionStatusTest {

    private val vitals = "tech.mmarca.openvitals"

    @Test
    fun `a recording and its subscription read as one line, streaming heart rate from the watch that answered`() {
        val active = listOf(
            OpenLinkActiveSession(vitals, OpenLink.CAPABILITY_RECORDING, "s1"),
            OpenLinkActiveSession(vitals, OpenLink.CAPABILITY_LIVE, "s1"),
        )
        val activity = mapOf(
            "s1" to LinkSessionActivity(
                sessionId = "s1",
                devices = listOf(LinkSessionDevice("g", "Venu 3", streaming = true), LinkSessionDevice("w", "Pixel Watch", streaming = false)),
                liveTypes = setOf(OpenLinkSampleType.HEART_RATE),
            ),
        )

        assertEquals(
            listOf(LinkSessionStatus(vitals, LinkSessionStatus.Kind.HEART_RATE, listOf("Venu 3"))),
            linkSessionStatuses(active, activity),
        )
    }

    @Test
    fun `before the first reading it waits for every device, and without a subscription it only keeps the watch streaming`() {
        val active = listOf(OpenLinkActiveSession(vitals, OpenLink.CAPABILITY_RECORDING, "s1"))
        val waiting = mapOf("s1" to LinkSessionActivity("s1", listOf(LinkSessionDevice("g", "Venu 3", streaming = false))))
        val streaming = mapOf("s1" to LinkSessionActivity("s1", listOf(LinkSessionDevice("g", "Venu 3", streaming = true))))

        assertEquals(LinkSessionStatus.Kind.WAITING, linkSessionStatuses(active, waiting).single().kind)
        assertEquals(LinkSessionStatus.Kind.KEEPING_STREAMING, linkSessionStatuses(active, streaming).single().kind)
        assertEquals(emptyList<LinkSessionStatus>(), linkSessionStatuses(active, emptyMap()))
    }

    @Test
    fun `a workout the watch records for an app says so, whatever streams beside it`() {
        val statuses = linkSessionStatuses(
            listOf(OpenLinkActiveSession(vitals, OpenLink.CAPABILITY_RECORDING, "w1")),
            mapOf("w1" to LinkSessionActivity("w1", listOf(LinkSessionDevice("pixel", "Pixel Watch", streaming = true)), recorded = true)),
        )

        assertEquals(listOf(LinkSessionStatus(vitals, LinkSessionStatus.Kind.RECORDING_WORKOUT, listOf("Pixel Watch"))), statuses)
    }
}
