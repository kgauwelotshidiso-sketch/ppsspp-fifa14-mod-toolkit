package com.tshidiso.ppssppmodtoolkit;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public final class FifaSchemaDecoderTest {
    @Test
    public void parsesAlignedHashesDescriptorsAndFieldNames() {
        byte[] data = new byte[512];
        writeSchema(
                data,
                130,
                "teams",
                0x1377538aL,
                0x19a12261L,
                new int[]{0, -2, 22},
                new String[]{"teamid", "teamname", "overallrating"}
        );

        FifaSchemaDecoder.SchemaResult result = FifaSchemaDecoder.decode(data, "teams");

        assertTrue(result.isVerified());
        assertEquals(1, result.getOccurrencesExamined());
        FifaSchemaDecoder.TableSchema schema = result.getSchema();
        assertEquals(130, schema.getMarkerOffset());
        assertEquals(3, schema.getFieldCount());
        assertEquals(0x1377538aL, schema.getHashA());
        assertEquals(0x19a12261L, schema.getHashB());
        assertEquals("teamid", schema.getFields().get(0).getName());
        assertEquals(-2, schema.getFields().get(1).getDescriptor());
        assertEquals("overallrating", schema.getFields().get(2).getName());
    }

    @Test
    public void ignoresLocalizedTeamsTextAndFindsStructuralOccurrence() {
        byte[] data = new byte[768];
        putAscii(data, 40, "Saved Teams");
        data[39] = ' ';
        data[51] = 0;
        writeSchema(
                data,
                258,
                "teams",
                1L,
                2L,
                new int[]{0},
                new String[]{"teamid"}
        );

        FifaSchemaDecoder.SchemaResult result = FifaSchemaDecoder.decode(data, "teams");

        assertTrue(result.isVerified());
        assertEquals(258, result.getSchema().getMarkerOffset());
        assertTrue(result.getOccurrencesExamined() >= 2);
        assertFalse(result.getRejectedOccurrences().isEmpty());
    }

    @Test
    public void parsesOneHundredTwentyOnePlayerFields() {
        byte[] data = new byte[8192];
        int[] descriptors = new int[121];
        String[] names = new String[121];
        for (int index = 0; index < 121; index++) {
            descriptors[index] = index % 3 == 0 ? -index : index;
            names[index] = "playerfield" + index;
        }
        writeSchema(data, 258, "players", 3L, 4L, descriptors, names);

        FifaSchemaDecoder.SchemaResult result = FifaSchemaDecoder.decode(data, "players");

        assertTrue(result.isVerified());
        assertEquals(121, result.getSchema().getFieldCount());
        assertEquals("playerfield120", result.getSchema().getFields().get(120).getName());
    }

    @Test
    public void rejectsDescriptorCountWithoutMatchingFieldNames() {
        byte[] data = new byte[256];
        int marker = 66;
        data[marker - 1] = 0;
        putAscii(data, marker, "players");
        data[marker + 7] = 0;
        int header = alignUp(marker + 8, 4);
        putU32Le(data, header, 1L);
        putU32Le(data, header + 4, 2L);
        putU32Le(data, header + 8, 121L);

        FifaSchemaDecoder.SchemaResult result = FifaSchemaDecoder.decode(data, "players");

        assertFalse(result.isVerified());
        assertFalse(result.getRejectedOccurrences().isEmpty());
    }

    private static void writeSchema(
            byte[] data,
            int marker,
            String table,
            long hashA,
            long hashB,
            int[] descriptors,
            String[] fieldNames
    ) {
        if (descriptors.length != fieldNames.length) {
            throw new IllegalArgumentException("test schema arrays must have equal length");
        }
        data[marker - 1] = 0;
        putAscii(data, marker, table);
        int terminator = marker + table.length();
        data[terminator] = 0;
        int header = alignUp(terminator + 1, 4);
        putU32Le(data, header, hashA);
        putU32Le(data, header + 4, hashB);
        putU32Le(data, header + 8, descriptors.length);
        int descriptorOffset = header + 12;
        for (int index = 0; index < descriptors.length; index++) {
            putU32Le(data, descriptorOffset + index * 4, descriptors[index]);
        }
        int cursor = descriptorOffset + descriptors.length * 4;
        for (String fieldName : fieldNames) {
            byte[] bytes = fieldName.getBytes(StandardCharsets.US_ASCII);
            putU16Le(data, cursor, bytes.length);
            System.arraycopy(bytes, 0, data, cursor + 2, bytes.length);
            cursor = alignUp(cursor + 2 + bytes.length, 4);
        }
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
