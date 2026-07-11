package com.tshidiso.ppssppmodtoolkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.Test;

public final class BackupRulesTest {
    @Test
    public void sha256MatchesKnownVector() {
        assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                BackupRules.sha256Hex("abc".getBytes(StandardCharsets.UTF_8))
        );
    }

    @Test
    public void hashComparisonIsCaseInsensitiveAndRejectsMissingValues() {
        assertTrue(BackupRules.hashesMatch("a1b2", "A1B2"));
        assertFalse(BackupRules.hashesMatch("a1b2", "a1b3"));
        assertFalse(BackupRules.hashesMatch(null, "a1b2"));
    }

    @Test
    public void unsafeDocumentSeparatorsAreSanitized() {
        assertEquals("PSP_GAME_USRDIR_fifa.db", BackupRules.sanitizeDocumentName(
                "PSP_GAME/USRDIR\\fifa.db",
                "fallback.bin"
        ));
        assertEquals("fallback.bin", BackupRules.sanitizeDocumentName("..", "fallback.bin"));
    }

    @Test
    public void destinationInsideSourceIsDetected() {
        assertTrue(BackupRules.isSameOrDescendantDocumentId(
                "primary:PSP/GAME/FIFA14",
                "primary:PSP/GAME/FIFA14"
        ));
        assertTrue(BackupRules.isSameOrDescendantDocumentId(
                "primary:PSP/GAME/FIFA14",
                "primary:PSP/GAME/FIFA14/Backups"
        ));
        assertFalse(BackupRules.isSameOrDescendantDocumentId(
                "primary:PSP/GAME/FIFA14",
                "primary:PPSSPP-Backups"
        ));
    }

    @Test
    public void spaceRequirementsIncludeSafetyMargins() {
        long gibibyte = 1024L * 1024L * 1024L;
        assertEquals(
                gibibyte + BackupRules.BACKUP_SAFETY_MARGIN_BYTES,
                BackupRules.requiredBackupBytes(gibibyte)
        );
        assertEquals(
                (2L * gibibyte) + BackupRules.WORKSPACE_SAFETY_MARGIN_BYTES,
                BackupRules.recommendedWorkspaceBytes(gibibyte)
        );
        assertEquals(-1L, BackupRules.requiredBackupBytes(-1L));
    }
}
