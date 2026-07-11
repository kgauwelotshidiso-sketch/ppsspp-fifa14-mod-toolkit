package com.tshidiso.ppssppmodtoolkit;

import org.junit.Test;

import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class DatabaseRulesTest {
    @Test
    public void detectsSqliteHeader() {
        byte[] data = "SQLite format 3\u0000rest".getBytes(StandardCharsets.US_ASCII);
        assertEquals("SQLite 3 database", DatabaseRules.detectFormat(data));
    }

    @Test
    public void detectsKnownFifaTableMarkersCaseInsensitively() {
        byte[] data = "header\u0000PLAYERS\u0000teamplayerlinks\u0000".getBytes(StandardCharsets.US_ASCII);
        List<String> markers = DatabaseRules.findKnownTableMarkers(data);
        assertTrue(markers.contains("players"));
        assertTrue(markers.contains("teamplayerlinks"));
        assertEquals("EA/FIFA binary database candidate", DatabaseRules.detectFormat(data));
    }

    @Test
    public void exactSearchReportsNonOverlappingOffsets() {
        byte[] data = "abc-abc-abc".getBytes(StandardCharsets.UTF_8);
        assertEquals(java.util.Arrays.asList(0, 4, 8),
                DatabaseRules.findTextOffsets(data, "abc", 50));
    }

    @Test
    public void patchReplacesOnlySelectedOccurrenceAndPreservesLength() {
        byte[] source = "HOME HOME HOME".getBytes(StandardCharsets.UTF_8);
        DatabaseRules.PatchPlan plan = DatabaseRules.buildPatchPlan(
                source,
                "HOME",
                "AWAY",
                2
        );
        assertEquals(5, plan.getOffset());
        assertEquals(3, plan.getTotalOccurrences());
        assertEquals(4, plan.getPatchedLength());
        assertEquals("HOME AWAY HOME", new String(plan.getEditedBytes(), StandardCharsets.UTF_8));
        assertEquals("HOME HOME HOME", new String(source, StandardCharsets.UTF_8));
    }

    @Test(expected = IllegalArgumentException.class)
    public void differentUtf8ByteLengthIsRejected() {
        DatabaseRules.buildPatchPlan(
                "name".getBytes(StandardCharsets.UTF_8),
                "name",
                "longer",
                1
        );
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingOccurrenceIsRejected() {
        DatabaseRules.buildPatchPlan(
                "one one".getBytes(StandardCharsets.UTF_8),
                "one",
                "two",
                3
        );
    }

    @Test
    public void databaseAssetRequiresDbExtension() {
        AssetRecord db = new AssetRecord(
                "PSP_GAME/USRDIR/data/cmn/fifa.db",
                10L,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        );
        AssetRecord big = new AssetRecord(
                "PSP_GAME/USRDIR/data/data.big",
                10L,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        );
        assertTrue(DatabaseRules.isDatabaseAsset(db));
        assertFalse(DatabaseRules.isDatabaseAsset(big));
    }

    @Test
    public void integerFingerprintSupportsBothByteOrders() {
        byte[] bytes = new byte[]{1, 0, 0, 0, 0, 0, 0, 1};
        assertEquals("1, 16777216", DatabaseRules.firstIntegers(bytes, ByteOrder.LITTLE_ENDIAN, 2));
        assertEquals("16777216, 1", DatabaseRules.firstIntegers(bytes, ByteOrder.BIG_ENDIAN, 2));
    }
    @Test
    public void knownTableNameIsNormalizedAndValidated() {
        assertEquals("players", DatabaseRules.requireKnownTableName(" PLAYERS "));
        assertTrue(DatabaseRules.knownTableMarkers().contains("teams"));
        assertTrue(DatabaseRules.knownTableMarkers().contains("teamplayerlinks"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownTableNameIsRejected() {
        DatabaseRules.requireKnownTableName("made_up_table");
    }

}
