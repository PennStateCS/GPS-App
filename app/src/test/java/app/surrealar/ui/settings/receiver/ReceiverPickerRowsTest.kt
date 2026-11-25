package app.surrealar.ui.settings.receiver

import app.surrealar.receiver.selection.ConnectError
import app.surrealar.receiver.selection.ConnectionAttempt
import app.surrealar.receiver.selection.NetworkScanState
import app.surrealar.receiver.selection.ReceiverTarget
import app.surrealar.receiver.selection.ScannedReceiver
import app.surrealar.receiver.EmlidDiscovery
import app.surrealar.settings.model.ExternalReceiverProfile
import app.surrealar.settings.model.RecentReceiver
import app.surrealar.ui.settings.receiver.ReceiverUiFixtures.TARGET
import app.surrealar.ui.settings.receiver.ReceiverUiFixtures.connected
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReceiverPickerRowsTest {

    private val saved = RecentReceiver(TARGET.host, TARGET.port, "reach_rs4", "Base", "Reach RS4", "SN1", "34.7", 5_000L)
    private val other = RecentReceiver("192.168.42.9", 9001, "generic_nmea_tcp", null, lastConnectedMs = 1_000L)

    @Test fun `sections in order with tablet active for device GNSS`() {
        val items = ReceiverPickerRows.build(ReceiverUiFixtures.internal)
        assertEquals(PickerItem.Header(PickerItem.Section.TABLET), items[0])
        assertEquals(PickerItem.Tablet(PickerRowStatus.Active), items[1])
        assertEquals(PickerItem.Header(PickerItem.Section.NETWORK), items[2])
        assertTrue(items[3] is PickerItem.Scan)
        assertEquals(PickerItem.Header(PickerItem.Section.RECENT), items[4])
        assertEquals(PickerItem.RecentEmpty, items[5])
        assertEquals(PickerItem.Manual, items.last())
    }

    @Test fun `recent rows show active, testing, failed and not found in the last scan`() {
        val scan = NetworkScanState.Done(listOf(ScannedReceiver(TARGET.host, 9001, "Reach", EmlidDiscovery.UNCONFIRMED_MODEL_LABEL)), 9_000L)
        val s = connected().copy(recent = listOf(saved, other), scan = scan)
        val recent = ReceiverPickerRows.build(s).filterIsInstance<PickerItem.Recent>()
        assertEquals(PickerRowStatus.Active, recent[0].status)
        assertEquals(ExternalReceiverProfile.REACH_RS4, recent[0].profile)
        assertEquals(PickerRowStatus.NotFoundInScan, recent[1].status)
        assertNull("tablet not active while external", ReceiverPickerRows.build(s).filterIsInstance<PickerItem.Tablet>().single().status)

        val testing = s.copy(attempt = ConnectionAttempt.Testing(2, ReceiverTarget("192.168.42.9", 9001), null))
        assertEquals(PickerRowStatus.Testing, ReceiverPickerRows.build(testing).filterIsInstance<PickerItem.Recent>()[1].status)
        val failed = s.copy(attempt = ConnectionAttempt.Failed(2, ReceiverTarget("192.168.42.9", 9001), ConnectError.Unreachable(9001)))
        assertEquals(PickerRowStatus.Failed(ConnectError.Unreachable(9001)),
            ReceiverPickerRows.build(failed).filterIsInstance<PickerItem.Recent>()[1].status)
    }

    @Test fun `scan results are listed with the unconfirmed-model label`() {
        val r = ScannedReceiver("192.168.42.30", 9001, "Reach", EmlidDiscovery.UNCONFIRMED_MODEL_LABEL)
        val items = ReceiverPickerRows.build(connected().copy(scan = NetworkScanState.Done(listOf(r), 1L)))
        val scanned = items.filterIsInstance<PickerItem.Scanned>().single()
        assertEquals(EmlidDiscovery.UNCONFIRMED_MODEL_LABEL, scanned.receiver.description)
        assertNull(scanned.status)
    }

    @Test fun `scanning starts on open only for an Emlid profile`() {
        assertTrue(ReceiverPickerRows.autoScan(connected(ExternalReceiverProfile.REACH_RS2_PLUS)))
        assertFalse(ReceiverPickerRows.autoScan(connected(ExternalReceiverProfile.GENERIC_NMEA_TCP)))
    }
}
