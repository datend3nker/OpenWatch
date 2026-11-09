package tech.mmarca.openwatch.wear

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsUiStateTest {

    private val phone = TrustedPhone(address = "AA:BB", name = "Pixel", trustedAtMillis = 1L)

    @Test
    fun allowedPairedPhoneOnAListeningLinkIsConnected() {
        val link = WearLinkUiState(listening = true, trusted = listOf(phone))

        assertTrue(SettingsUiState.from(link, sensorAccessGranted = true).phoneConnected)
    }

    @Test
    fun noAllowedPhoneIsNotConnected() {
        val link = WearLinkUiState(listening = true)

        assertFalse(SettingsUiState.from(link, sensorAccessGranted = true).phoneConnected)
    }

    @Test
    fun aPhoneThatLostItsPairingIsNotConnected() {
        val link = WearLinkUiState(listening = true, trusted = listOf(phone), bondLost = listOf(phone))

        assertFalse(SettingsUiState.from(link, sensorAccessGranted = true).phoneConnected)
    }

    @Test
    fun aLinkThatDoesNotListenIsNotConnected() {
        val link = WearLinkUiState(listening = false, trusted = listOf(phone))

        assertFalse(SettingsUiState.from(link, sensorAccessGranted = true).phoneConnected)
    }

    @Test
    fun sensorAccessFollowsTheGrant() {
        val link = WearLinkUiState()

        assertTrue(SettingsUiState.from(link, sensorAccessGranted = true).sensorAccessGranted)
        assertFalse(SettingsUiState.from(link, sensorAccessGranted = false).sensorAccessGranted)
    }
}
