package com.nvmex.networkhelper.util.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DiagnosticRedactorTest {
    @Test fun r8BuildIdentitySurvivesButUnlabelledHashesRemainRedacted() {
        val hash = "a".repeat(64)
        val frame = "\tat app.Reader.read(r8-map-id-$hash:71)"
        assertEquals(frame, DiagnosticRedactor.redact(frame))
        assertFalse(DiagnosticRedactor.redact("secret=$hash").contains(hash))
    }
    @Test fun identifiersCredentialsAndLocationAreRemovedFromMixedLogs() {
        val input = "IMEI1=123456789012345 IMSI: 460001234567890 serial=ABCDEF123 password=\"s p a c e\" " +
            "Bearer secret-token https://bucket.example/object?signature=private " +
            "aa:bb:cc:dd:ee:ff 192.168.1.123 2001:db8::1 user@example.com latitude=31.23456 13800138000"
        val result = DiagnosticRedactor.redact(input)
        listOf("123456789012345", "460001234567890", "ABCDEF123", "s p a c e", "secret-token", "signature=private",
            "aa:bb:cc:dd:ee:ff", "192.168.1.123", "2001:db8::1", "user@example.com", "31.23456", "13800138000")
            .forEach { assertFalse("Leaked: $it", result.contains(it)) }
    }

    @Test fun timestampsBlockMappingsAndErrorCodesRemainUseful() {
        val line = "2026-11-09 16:14:55.409 SM8850 Android 17 EFS2 0x13 /dev/block/sdf2 -> modemst1 errno=13"
        assertEquals(line, DiagnosticRedactor.redact(line))
    }

    @Test fun rawPayloadIsNeverExported() {
        val result = DiagnosticRedactor.redact("packet payload=01 02 03 04 05 06 07 08 09 10")
        assertFalse(result.contains("01 02 03"))
    }

    @Test fun structuredSecretsAreRecognizedWithoutDependingOnValueFormat() {
        listOf("password", "vault_secret", "OSSAccessKeyId", "phoneNumber", "SSID", "latitude", "hardware_serial", "IMEI1")
            .forEach { assertTrue(it, DiagnosticRedactor.isSensitiveKey(it)) }
        listOf("model", "socModel", "osVersion", "appVersionCode", "baseband", "source")
            .forEach { assertFalse(it, DiagnosticRedactor.isSensitiveKey(it)) }
    }
}
