package com.tshidiso.ppssppmodtoolkit;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class FifaTableDecoderTest {
    @Test
    public void decodesHeaderMapAndRequestedMarker() {
        byte[] data = syntheticDatabase();
        FifaTableDecoder.DecodeResult result = FifaTableDecoder.decode(data, "players");

        assertTrue(result.isMarkerFound());
        assertTrue(contains(result.getDetails(), "Candidate little-endian header size: 32 bytes"));
        assertTrue(contains(result.getDetails(), "Requested marker occurrences: 1"));
        assertTrue(contains(result.getFindings(), "Table marker map: players"));
        assertTrue(result.getFullReportText().contains("database_changed=false"));
    }

    @Test
    public void findsConservativeLayoutHypothesisNearMarker() {
        byte[] data = syntheticDatabase();
        FifaTableDecoder.DecodeResult result = FifaTableDecoder.decode(data, "players");

        assertTrue(contains(result.getFindings(), "count=10, recordBytes=16"));
        assertTrue(contains(result.getFindings(), "UNCONFIRMED"));
        assertTrue(contains(result.getFindings(), "Hypothesis sample record 0"));
    }

    @Test
    public void reportsKnownTableThatIsMissing() {
        byte[] data = syntheticDatabase();
        FifaTableDecoder.DecodeResult result = FifaTableDecoder.decode(data, "manager");

        assertFalse(result.isMarkerFound());
        assertTrue(result.getSummary().contains("not present"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUnknownTableName() {
        FifaTableDecoder.decode(syntheticDatabase(), "not_a_real_table");
    }

    private static byte[] syntheticDatabase() {
        byte[] data = new byte[1024];
        putU32Le(data, 0, 0);
        putU32Le(data, 4, 4);
        putU32Le(data, 8, 32);
        putU32Le(data, 12, 128);
        putU32Le(data, 16, 256);
        putU32Le(data, 20, 512);
        putU32Le(data, 24, 768);
        putU32Le(data, 28, 896);

        putAscii(data, 180, "players");
        data[179] = 0;
        data[187] = 0;
        putAscii(data, 220, "teams");
        data[219] = 0;
        data[225] = 0;
        putAscii(data, 280, "teamplayerlinks");
        data[279] = 0;
        data[295] = 0;

        // A deliberately plausible, but still unconfirmed, layout triple near players.
        putU32Le(data, 160, 10);
        putU32Le(data, 164, 16);
        putU32Le(data, 168, 512);
        for (int record = 0; record < 10; record++) {
            for (int word = 0; word < 4; word++) {
                putU32Le(data, 512 + record * 16 + word * 4, record * 100 + word);
            }
        }
        return data;
    }

    private static void putAscii(byte[] data, int offset, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, data, offset, bytes.length);
    }

    private static void putU32Le(byte[] data, int offset, long value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }

    private static boolean contains(Iterable<String> values, String needle) {
        for (String value : values) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
