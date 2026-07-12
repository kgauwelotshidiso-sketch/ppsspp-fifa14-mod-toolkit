package com.tshidiso.ppssppmodtoolkit;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class FifaSchemaDecoderTest {
    @Test
    public void parsesTeamsDescriptorAndFieldArraysSeparately() {
        byte[] data = new byte[8192];
        writeVerifiedSchema(
                data,
                130,
                "teams",
                40,
                fieldNames(35, "teamid", "defdefenderline"),
                "refereecountrylinks",
                3,
                "refereeid"
        );

        FifaSchemaDecoder.SchemaResult result = FifaSchemaDecoder.decode(data, "teams");

        assertTrue(result.isVerified());
        FifaSchemaDecoder.TableSchema schema = result.getSchema();
        assertEquals(130, schema.getMarkerOffset());
        assertEquals(40, schema.getDescriptorCount());
        assertEquals(35, schema.getFieldCount());
        assertEquals("teamid", schema.getFields().get(0).getName());
        assertEquals("defdefenderline", schema.getFields().get(34).getName());
        assertEquals("refereecountrylinks", schema.getSuccessorTableName());
        assertEquals(40, schema.getDescriptors().size());
    }

    @Test
    public void parsesTeamPlayerLinksProfile() {
        byte[] data = new byte[4096];
        writeVerifiedSchema(
                data,
                258,
                "teamplayerlinks",
                7,
                fieldNames(6, "teamid", "transferdone"),
                "jerseynames",
                3,
                "playerid"
        );

        FifaSchemaDecoder.SchemaResult result = FifaSchemaDecoder.decode(
                data,
                "teamplayerlinks"
        );

        assertTrue(result.isVerified());
        assertEquals(7, result.getSchema().getDescriptorCount());
        assertEquals(6, result.getSchema().getFieldCount());
        assertEquals("jerseynames", result.getSchema().getSuccessorTableName());
    }

    @Test
    public void parsesPlayersProfileWithNinetySixFieldNames() {
        byte[] data = new byte[32768];
        writeVerifiedSchema(
                data,
                514,
                "players",
                121,
                fieldNames(96, "playerid", "exportfromdb"),
                "leagueteamlinks",
                3,
                "leagueid"
        );

        FifaSchemaDecoder.SchemaResult result = FifaSchemaDecoder.decode(data, "players");

        assertTrue(result.isVerified());
        assertEquals(121, result.getSchema().getDescriptorCount());
        assertEquals(96, result.getSchema().getFieldCount());
        assertEquals("exportfromdb", result.getSchema().getFields().get(95).getName());
    }

    @Test
    public void ignoresLocalizedTeamsTextAndFindsStructuralOccurrence() {
        byte[] data = new byte[8192];
        putAscii(data, 40, "FIFA Career Saved Teams");
        writeVerifiedSchema(
                data,
                514,
                "teams",
                40,
                fieldNames(35, "teamid", "defdefenderline"),
                "refereecountrylinks",
                3,
                "refereeid"
        );

        FifaSchemaDecoder.SchemaResult result = FifaSchemaDecoder.decode(data, "teams");

        assertTrue(result.isVerified());
        assertEquals(514, result.getSchema().getMarkerOffset());
        assertTrue(result.getOccurrencesExamined() >= 2);
        assertFalse(result.getRejectedOccurrences().isEmpty());
    }

    @Test
    public void rejectsWrongSuccessorBoundary() {
        byte[] data = new byte[8192];
        writeVerifiedSchema(
                data,
                130,
                "teams",
                40,
                fieldNames(35, "teamid", "defdefenderline"),
                "wrongtable",
                3,
                "refereeid"
        );

        FifaSchemaDecoder.SchemaResult result = FifaSchemaDecoder.decode(data, "teams");

        assertFalse(result.isVerified());
        assertFalse(result.getRejectedOccurrences().isEmpty());
    }

    @Test
    public void rejectsDescriptorCountFromOldOneToOneAssumption() {
        byte[] data = new byte[8192];
        writeVerifiedSchema(
                data,
                130,
                "teams",
                35,
                fieldNames(35, "teamid", "defdefenderline"),
                "refereecountrylinks",
                3,
                "refereeid"
        );

        FifaSchemaDecoder.SchemaResult result = FifaSchemaDecoder.decode(data, "teams");

        assertFalse(result.isVerified());
        assertFalse(result.getRejectedOccurrences().isEmpty());
    }

    private static String[] fieldNames(int count, String first, String last) {
        String[] names = new String[count];
        names[0] = first;
        for (int index = 1; index < count - 1; index++) {
            names[index] = "field" + index;
        }
        names[count - 1] = last;
        return names;
    }

    private static void writeVerifiedSchema(
            byte[] data,
            int marker,
            String table,
            int descriptorCount,
            String[] fieldNames,
            String successorTable,
            int successorDescriptorCount,
            String successorFirstField
    ) {
        int prefix = marker - 2;
        putU16Le(data, prefix, table.length());
        putAscii(data, marker, table);
        int header = alignUp(marker + table.length(), 4);
        putU32Le(data, header, 0x1377538aL);
        putU32Le(data, header + 4, 0x19a12261L);
        putU32Le(data, header + 8, descriptorCount);
        int descriptorOffset = header + 12;
        for (int index = 0; index < descriptorCount; index++) {
            putU32Le(data, descriptorOffset + index * 4, index % 3 == 0 ? -index : index);
        }
        int cursor = descriptorOffset + descriptorCount * 4;
        for (String fieldName : fieldNames) {
            cursor = writeName(data, cursor, fieldName);
        }

        cursor = writeName(data, cursor, successorTable);
        int successorHeader = cursor;
        putU32Le(data, successorHeader, 0x6203bc64L);
        putU32Le(data, successorHeader + 4, 0x5405e3cbL);
        putU32Le(data, successorHeader + 8, successorDescriptorCount);
        int successorDescriptors = successorHeader + 12;
        for (int index = 0; index < successorDescriptorCount; index++) {
            putU32Le(data, successorDescriptors + index * 4, index);
        }
        writeName(
                data,
                successorDescriptors + successorDescriptorCount * 4,
                successorFirstField
        );
    }

    private static int writeName(byte[] data, int prefixOffset, String name) {
        byte[] bytes = name.getBytes(StandardCharsets.US_ASCII);
        putU16Le(data, prefixOffset, bytes.length);
        System.arraycopy(bytes, 0, data, prefixOffset + 2, bytes.length);
        return alignUp(prefixOffset + 2 + bytes.length, 4);
    }

    private static void putAscii(byte[] data, int offset, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, data, offset, bytes.length);
    }

    private static void putU16Le(byte[] data, int offset, long value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
    }

    private static void putU32Le(byte[] data, int offset, long value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }

    private static int alignUp(int value, int alignment) {
        int remainder = Math.floorMod(value, alignment);
        return remainder == 0 ? value : value + alignment - remainder;
    }
}
