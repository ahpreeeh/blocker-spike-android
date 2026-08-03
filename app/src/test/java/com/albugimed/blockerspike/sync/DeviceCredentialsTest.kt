package com.albugimed.blockerspike.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeviceCredentialsTest {

    @Test
    fun completeUneAdresseSansSchema() {
        assertEquals("https://atelier.example", normalizeBaseUrl("atelier.example"))
        assertEquals("https://atelier.example", normalizeBaseUrl("  atelier.example/  "))
        assertEquals("https://atelier.example", normalizeBaseUrl("https://atelier.example"))
    }

    @Test
    fun refuseLeTraficEnClair() {
        // Le contrat §2 est explicite : HTTPS uniquement, et on ne contourne
        // pas le refus d'Android 16. Un jeton ne part pas en clair.
        assertNull(normalizeBaseUrl("http://atelier.example"))
        assertNull(normalizeBaseUrl("ftp://atelier.example"))
    }

    @Test
    fun ecarteChemin_requeteEtFragment() {
        // Un jeton voyage dans un en-tête. Une base porteuse d'une chaîne de
        // requête est le premier pas vers l'inverse.
        assertEquals("https://atelier.example", normalizeBaseUrl("https://atelier.example/api/v1"))
        assertEquals("https://atelier.example", normalizeBaseUrl("https://atelier.example/?token=x"))
        assertEquals("https://atelier.example:8443", normalizeBaseUrl("https://atelier.example:8443/x"))
    }

    @Test
    fun refuseCeQuiNEstPasUneAdresse() {
        assertNull(normalizeBaseUrl(""))
        assertNull(normalizeBaseUrl("   "))
        assertNull(normalizeBaseUrl("https://"))
    }

    @Test
    fun pardonneLesAidesALaSaisieDuJeton() {
        assertEquals("oat_0123456789ABCDEF", normalizeDeviceToken("oat_0123-4567-89AB-CDEF"))
        assertEquals("oat_0123456789ABCDEF", normalizeDeviceToken("  oat_0123 4567 89AB CDEF\n"))
    }

    @Test
    fun refuseUneSaisieQuiNePeutPasEtreUnJeton() {
        assertNull(normalizeDeviceToken(""))
        assertNull(normalizeDeviceToken("court"))
        assertNull(normalizeDeviceToken("x".repeat(600)))
    }
}
