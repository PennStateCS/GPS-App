package com.example.surveyingapp.util.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tests for the pure (Android-free) diagnostic formatting helpers. */
class MapDiagnosticsTest {

    // ── signing fingerprint formatting ───────────────────────────────────────────
    @Test fun `fingerprint is upper-case colon-separated hex`() {
        val bytes = byteArrayOf(0x01, 0xAB.toByte(), 0xCD.toByte(), 0x0F)
        assertEquals("01:AB:CD:0F", AppSigningInfo.formatFingerprint(bytes))
    }

    @Test fun `fingerprint of empty bytes is empty`() {
        assertEquals("", AppSigningInfo.formatFingerprint(ByteArray(0)))
    }

    // ── API key redaction ────────────────────────────────────────────────────────
    @Test fun `redact reports missing for null or blank`() {
        assertEquals("MISSING / blank", MapDiagnosticCollector.redactApiKey(null))
        assertEquals("MISSING / blank", MapDiagnosticCollector.redactApiKey(""))
        assertEquals("MISSING / blank", MapDiagnosticCollector.redactApiKey("   "))
    }

    @Test fun `redact flags the build placeholder`() {
        assertEquals("PLACEHOLDER (not a real key)", MapDiagnosticCollector.redactApiKey("YOUR_API_KEY_HERE"))
    }

    @Test fun `redact never reveals a real key`() {
        val key = "AIzaSyA-FAKE-EXAMPLE-KEY-1234567890abcd"
        val out = MapDiagnosticCollector.redactApiKey(key)
        assertTrue("got: $out", out.startsWith("present (len=${key.length}, sha256="))
        assertTrue("must not contain the key", !out.contains(key))
    }

    @Test fun `short hash is deterministic 8 hex chars`() {
        // sha256("test") = 9f86d081884c7d65...
        assertEquals("9f86d081", MapDiagnosticCollector.shortHash("test"))
        assertEquals(8, MapDiagnosticCollector.shortHash("anything").length)
    }

    // ── network formatting ───────────────────────────────────────────────────────
    @Test fun `network warns when internet present but not validated`() {
        val out = MapDiagnosticCollector.formatNetwork(present = true, internet = true, validated = false, transport = "Wi-Fi")
        assertTrue(out.contains("Transport               : Wi-Fi"))
        assertTrue(out.contains("WARNING: connected but not validated"))
    }

    @Test fun `network has no warning when validated`() {
        val out = MapDiagnosticCollector.formatNetwork(present = true, internet = true, validated = true, transport = "Cellular")
        assertTrue(!out.contains("WARNING"))
    }

    @Test fun `network shows none when no active network`() {
        val out = MapDiagnosticCollector.formatNetwork(present = false, internet = false, validated = false, transport = "none")
        assertTrue(out.contains("Active network          : none"))
    }
}
