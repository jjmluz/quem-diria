package com.jjmluz.quemdiria

import kotlin.test.Test
import kotlin.test.assertEquals

class WifiTest {
    @Test
    fun aWifiQrCodeNamesTheNetworkAndPassword() {
        assertEquals("WIFI:T:WPA;S:Casa;P:segredo1;;", Wifi("Casa", "segredo1").qrText)
    }

    @Test
    fun specialCharactersAreEscaped() {
        assertEquals("""WIFI:T:WPA;S:a\;b\,c;P:x\:y\"z\\;;""", Wifi("a;b,c", """x:y"z\""").qrText)
    }

    @Test
    fun noPasswordIsAnOpenNetwork() {
        assertEquals("WIFI:T:nopass;S:Casa;;", Wifi("Casa", "").qrText)
    }
}
