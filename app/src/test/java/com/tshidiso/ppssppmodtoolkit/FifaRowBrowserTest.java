package com.tshidiso.ppssppmodtoolkit;

import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public final class FifaRowBrowserTest {
    @Test
    public void resolvesTeamByNameAndCrossLinksPlayers() {
        byte[] data = fixture();

        FifaRowBrowser.BrowseResult result = FifaRowBrowser.browse(data, "teams", "Arsenal");

        assertEquals("teams", result.getTableName());
        assertEquals(0, result.getSelectedRowIndex());
        assertEquals(2, result.getRowCount());
        assertTrue(join(result.getFindings()).contains("teamid=1"));
        assertTrue(join(result.getFindings()).contains("Tomáš Rosický"));
        assertTrue(join(result.getDetails()).contains("Validated team-player links: 3"));
    }

    @Test
    public void resolvesPlayerByStableId() {
        byte[] data = fixture();

        FifaRowBrowser.BrowseResult result = FifaRowBrowser.browse(data, "players", "id:241");

        assertEquals(0, result.getSelectedRowIndex());
        assertTrue(join(result.getFindings()).contains("playerid=241"));
        assertTrue(join(result.getFindings()).contains("Manchester United"));
    }

    @Test
    public void resolvesLinkByPlayerReference() {
        byte[] data = fixture();

        FifaRowBrowser.BrowseResult result = FifaRowBrowser.browse(
                data,
                "teamplayerlinks",
                "player:241"
        );

        assertEquals(0, result.getSelectedRowIndex());
        assertTrue(join(result.getFindings()).contains("team=“Manchester United”"));
        assertTrue(join(result.getFindings()).contains("player=“Ryan Giggs”"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsLinkToMissingPlayer() {
        byte[] data = fixture();
        putU32Le(data, 1208 + 4, 999999L);

        FifaRowBrowser.browse(data, "teams", "index:0");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsIncorrectFixedRowBoundary() {
        byte[] data = fixture();
        putU32Le(data, HEADER_WORD_TEAMS_END * 4, 1092L);

        FifaRowBrowser.browse(data, "teams", "index:0");
    }

    @Test
    public void supportsExactZeroBasedIndexNavigation() {
        byte[] data = fixture();

        FifaRowBrowser.BrowseResult result = FifaRowBrowser.browse(data, "players", "index:2");

        assertEquals(2, result.getSelectedRowIndex());
        assertTrue(join(result.getFindings()).contains("playerid=45119"));
    }

    private static final int HEADER_WORD_TEAMS_START = 4;
    private static final int HEADER_WORD_TEAMS_END = 5;
    private static final int HEADER_WORD_LINKS_START = 6;
    private static final int HEADER_WORD_LINKS_END = 7;
    private static final int HEADER_WORD_PLAYERS_START = 17;
    private static final int HEADER_WORD_PLAYERS_END = 18;

    private static byte[] fixture() {
        byte[] data = new byte[65536];

        putU32Le(data, HEADER_WORD_TEAMS_START * 4, 1000L);
        putU32Le(data, HEADER_WORD_TEAMS_END * 4, 1096L);
        putU32Le(data, HEADER_WORD_LINKS_START * 4, 1200L);
        putU32Le(data, HEADER_WORD_LINKS_END * 4, 1244L);
        putU32Le(data, HEADER_WORD_PLAYERS_START * 4, 1400L);
        putU32Le(data, HEADER_WORD_PLAYERS_END * 4, 1600L);

        putU32Le(data, 1000, 2L);
        putU32Le(data, 1004, 384L);
        int arsenal = writeString(data, 20000, "Arsenal");
        int united = writeString(data, arsenal, "Manchester United");
        writeTeam(data, 1008, 1L, 20000L);
        writeTeam(data, 1052, 11L, arsenal);

        int ryan = writeString(data, united, "Ryan");
        int giggs = writeString(data, ryan, "Giggs");
        int tomas = writeString(data, giggs, "Tomáš");
        int rosicky = writeString(data, tomas, "Rosický");
        int mikel = writeString(data, rosicky, "Mikel");
        int arteta = writeString(data, mikel, "Arteta");

        putU32Le(data, 1400, 3L);
        putU32Le(data, 1404, 3364L);
        writePlayer(data, 1408, 241L, united, ryan);
        writePlayer(data, 1472, 8473L, giggs, tomas);
        writePlayer(data, 1536, 45119L, rosicky, mikel);

        putU32Le(data, 1200, 3L);
        putU32Le(data, 1204, 852L);
        writeLink(data, 1208, 11L, 241L, 0x0040020dL);
        writeLink(data, 1220, 1L, 8473L, 0x0040020eL);
        writeLink(data, 1232, 1L, 45119L, 0x0040020fL);

        writeVerifiedSchema(
                data,
                4002,
                "teams",
                40,
                fieldNames(35, "teamid", "defdefenderline"),
                "refereecountrylinks",
                3,
                "refereeid"
        );
        writeVerifiedSchema(
                data,
                12002,
                "teamplayerlinks",
                7,
                fieldNames(6, "teamid", "transferdone"),
                "jerseynames",
                3,
                "playerid"
        );
        writeVerifiedSchema(
                data,
                24002,
                "players",
                121,
                fieldNames(96, "playerid", "exportfromdb"),
                "leagueteamlinks",
                3,
                "leagueid"
        );
        return data;
    }

    private static int writeString(byte[] data, int offset, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        putU16Le(data, offset, bytes.length);
        System.arraycopy(bytes, 0, data, offset + 2, bytes.length);
        return offset + 2 + bytes.length;
    }

    private static void writeTeam(byte[] data, int offset, long teamId, long namePointer) {
        putU32Le(data, offset, teamId);
        putU32Le(data, offset + 4, namePointer);
    }

    private static void writePlayer(
            byte[] data,
            int offset,
            long playerId,
            long firstNamePointer,
            long surnamePointer
    ) {
        putU32Le(data, offset, playerId);
        putU32Le(data, offset + 4, firstNamePointer);
        putU32Le(data, offset + 8, surnamePointer);
    }

    private static void writeLink(
            byte[] data,
            int offset,
            long teamId,
            long playerId,
            long packed
    ) {
        putU32Le(data, offset, teamId);
        putU32Le(data, offset + 4, playerId);
        putU32Le(data, offset + 8, packed);
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
        writeName(data, successorDescriptors + successorDescriptorCount * 4, successorFirstField);
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
        int remainder = value % alignment;
        return remainder == 0 ? value : value + alignment - remainder;
    }

    private static String join(Iterable<String> values) {
        StringBuilder builder = new StringBuilder();
        for (String value : values) {
            builder.append(value).append('\n');
        }
        return builder.toString();
    }
}
