package com.tshidiso.ppssppmodtoolkit;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Read-only row-boundary validator and cross-table browser for the verified FIFA 14 PSP database.
 *
 * <p>The supported profile exposes only fields that are proven directly by the real ULUS-10655
 * database: table counts and fixed row widths, stable team/player identifiers, absolute pointers
 * to length-prefixed names, and team-player foreign-key links. All other packed words remain raw.
 * No database byte is ever changed by this class.</p>
 */
public final class FifaRowBrowser {
    private static final String TEAMS = "teams";
    private static final String LINKS = "teamplayerlinks";
    private static final String PLAYERS = "players";

    private static final int HEADER_WORD_TEAMS_START = 4;
    private static final int HEADER_WORD_TEAMS_END = 5;
    private static final int HEADER_WORD_LINKS_START = 6;
    private static final int HEADER_WORD_LINKS_END = 7;
    private static final int HEADER_WORD_PLAYERS_START = 17;
    private static final int HEADER_WORD_PLAYERS_END = 18;

    private static final int TABLE_HEADER_BYTES = 8;
    private static final int TEAM_RECORD_BYTES = 44;
    private static final int LINK_RECORD_BYTES = 12;
    private static final int PLAYER_RECORD_BYTES = 64;
    private static final int MAX_ROWS = 250_000;
    private static final int MAX_NAME_BYTES = 512;
    private static final int MAX_REPORTED_LINKS = 12;

    private FifaRowBrowser() {
    }

    public static BrowseResult browse(byte[] data, String requestedTable, String rawQuery) {
        if (data == null || data.length < 128) {
            throw new IllegalArgumentException("Database is too small for row browsing");
        }
        String table = normalizeTable(requestedTable);
        String query = rawQuery == null ? "" : rawQuery.trim();

        FifaSchemaDecoder.SchemaResult schemaResult = FifaSchemaDecoder.decode(data, table);
        if (!schemaResult.isVerified() || schemaResult.getSchema() == null) {
            throw new IllegalArgumentException(
                    "The " + table + " schema is not verified for this database"
            );
        }

        RowSection teamsSection = parseSection(
                data,
                TEAMS,
                HEADER_WORD_TEAMS_START,
                HEADER_WORD_TEAMS_END,
                TEAM_RECORD_BYTES
        );
        RowSection linksSection = parseSection(
                data,
                LINKS,
                HEADER_WORD_LINKS_START,
                HEADER_WORD_LINKS_END,
                LINK_RECORD_BYTES
        );
        RowSection playersSection = parseSection(
                data,
                PLAYERS,
                HEADER_WORD_PLAYERS_START,
                HEADER_WORD_PLAYERS_END,
                PLAYER_RECORD_BYTES
        );

        List<TeamRow> teams = parseTeams(data, teamsSection);
        List<PlayerRow> players = parsePlayers(data, playersSection);
        Validation validation = validateLinks(data, linksSection, teams, players);

        List<String> details = new ArrayList<>();
        List<String> findings = new ArrayList<>();
        FifaSchemaDecoder.TableSchema schema = schemaResult.getSchema();
        addCommonDetails(details, table, query, schema, teamsSection, linksSection, playersSection,
                teams, players, validation);
        addDescriptorAnalysis(details, findings, schema);

        int selectedIndex;
        if (TEAMS.equals(table)) {
            selectedIndex = findTeamIndex(teams, query);
            TeamRow selected = teams.get(selectedIndex);
            addTeamFindings(data, findings, selected, selectedIndex, teamsSection, validation);
        } else if (PLAYERS.equals(table)) {
            selectedIndex = findPlayerIndex(players, query);
            PlayerRow selected = players.get(selectedIndex);
            addPlayerFindings(data, findings, selected, selectedIndex, playersSection, validation);
        } else {
            selectedIndex = findLinkIndex(data, linksSection, query);
            addLinkFindings(
                    data,
                    findings,
                    selectedIndex,
                    linksSection,
                    validation.teamById,
                    validation.playerById
            );
        }

        RowSection selectedSection = sectionFor(table, teamsSection, linksSection, playersSection);
        details.add("Selected row index (zero-based): " + selectedIndex);
        details.add("Selected row position: " + (selectedIndex + 1) + " of "
                + selectedSection.rowCount);
        details.add("Selected row byte offset: "
                + formatOffset(selectedSection.rowsOffset + selectedIndex * selectedSection.recordBytes));
        details.add("Working database changed: no");
        details.add("Protected original changed: no");
        details.add("ISO and verified backup changed: no");

        String summary = "The table row section, record count, fixed record width, stable IDs, "
                + "name pointers, and team-player references were validated read-only. Packed numeric "
                + "words remain raw because descriptor-to-field bit mappings are not yet proven.";
        String fullReport = buildFullReport(table, query, selectedIndex, selectedSection, details, findings);
        return new BrowseResult(
                table,
                selectedIndex,
                selectedSection.rowCount,
                summary,
                details,
                findings,
                fullReport
        );
    }

    private static void addCommonDetails(
            List<String> details,
            String table,
            String query,
            FifaSchemaDecoder.TableSchema schema,
            RowSection teamsSection,
            RowSection linksSection,
            RowSection playersSection,
            List<TeamRow> teams,
            List<PlayerRow> players,
            Validation validation
    ) {
        details.add("Requested table: " + table);
        details.add("Row query: " + (query.isEmpty() ? "index:0" : query));
        details.add("Verified schema block: yes");
        details.add("Schema descriptor-word count: " + schema.getDescriptorCount());
        details.add("Schema field-name count: " + schema.getFieldCount());
        details.add("Schema successor table: " + schema.getSuccessorTableName());
        details.add("teams rows: " + teamsSection.rowCount + " × "
                + teamsSection.recordBytes + " bytes | "
                + formatOffset(teamsSection.rowsOffset) + " → "
                + formatOffset(teamsSection.rowsEndOffset));
        details.add("teamplayerlinks rows: " + linksSection.rowCount + " × "
                + linksSection.recordBytes + " bytes | "
                + formatOffset(linksSection.rowsOffset) + " → "
                + formatOffset(linksSection.rowsEndOffset));
        details.add("players rows: " + playersSection.rowCount + " × "
                + playersSection.recordBytes + " bytes | "
                + formatOffset(playersSection.rowsOffset) + " → "
                + formatOffset(playersSection.rowsEndOffset));
        details.add("Validated unique team IDs: " + teams.size());
        details.add("Validated team-name pointers: " + teams.size());
        details.add("Validated unique player IDs: " + players.size());
        details.add("Validated player-name pointers: " + players.size() + " firstname + "
                + players.size() + " surname");
        details.add("Validated team-player links: " + validation.linkCount);
        details.add("Links with missing team references: 0");
        details.add("Links with missing player references: 0");
        details.add("Duplicate team-player pairs: 0");
        details.add("Second 32-bit value in each table header remains unresolved: teams="
                + teamsSection.headerWordB + ", teamplayerlinks=" + linksSection.headerWordB
                + ", players=" + playersSection.headerWordB);
    }

    private static void addDescriptorAnalysis(
            List<String> details,
            List<String> findings,
            FifaSchemaDecoder.TableSchema schema
    ) {
        List<Integer> descriptors = schema.getDescriptors();
        int negative = 0;
        int zero = 0;
        int positive = 0;
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        Map<Integer, Integer> frequency = new LinkedHashMap<>();
        for (int value : descriptors) {
            if (value < 0) {
                negative++;
            } else if (value == 0) {
                zero++;
            } else {
                positive++;
            }
            min = Math.min(min, value);
            max = Math.max(max, value);
            frequency.put(value, frequency.getOrDefault(value, 0) + 1);
        }
        details.add("Descriptor pattern: negative=" + negative + ", zero=" + zero
                + ", positive=" + positive + ", min=" + min + ", max=" + max);
        List<Map.Entry<Integer, Integer>> repeated = new ArrayList<>();
        for (Map.Entry<Integer, Integer> entry : frequency.entrySet()) {
            if (entry.getValue() > 1) {
                repeated.add(entry);
            }
        }
        repeated.sort((left, right) -> {
            int byFrequency = Integer.compare(right.getValue(), left.getValue());
            return byFrequency != 0 ? byFrequency : Integer.compare(left.getKey(), right.getKey());
        });
        StringBuilder repeatedText = new StringBuilder();
        int shown = Math.min(10, repeated.size());
        for (int index = 0; index < shown; index++) {
            if (index > 0) {
                repeatedText.append(", ");
            }
            Map.Entry<Integer, Integer> entry = repeated.get(index);
            repeatedText.append(entry.getKey()).append("×").append(entry.getValue());
        }
        details.add("Repeated descriptor values: "
                + (repeatedText.length() == 0 ? "none" : repeatedText.toString()));
        findings.add("Descriptor safety: descriptor order and values are verified, but no "
                + "descriptor-to-field or bit-range mapping is claimed.");
    }

    private static void addTeamFindings(
            byte[] data,
            List<String> findings,
            TeamRow team,
            int rowIndex,
            RowSection section,
            Validation validation
    ) {
        findings.add("Resolved team row " + rowIndex + ": teamid=" + team.teamId
                + " | teamname=“" + team.name + "”");
        List<Integer> linkIndexes = validation.linksByTeam.getOrDefault(
                team.teamId,
                Collections.emptyList()
        );
        findings.add("Validated linked-player count: " + linkIndexes.size());
        int shown = Math.min(MAX_REPORTED_LINKS, linkIndexes.size());
        for (int index = 0; index < shown; index++) {
            int linkIndex = linkIndexes.get(index);
            LinkRow link = validation.linkRows.get(linkIndex);
            PlayerRow player = validation.playerById.get(link.playerId);
            findings.add("Linked player " + (index + 1) + ": playerid=" + link.playerId
                    + " | " + player.displayName() + " | link row=" + linkIndex);
        }
        if (linkIndexes.size() > shown) {
            findings.add("…and " + (linkIndexes.size() - shown) + " more linked players");
        }
        addRawWords(
                findings,
                "Team raw record",
                readWords(
                        data,
                        section.rowsOffset + rowIndex * section.recordBytes,
                        section.recordBytes / 4
                ),
                section.rowsOffset + rowIndex * section.recordBytes
        );
        findings.add("Proven team fields: word 0=teamid; word 1=absolute pointer to "
                + "length-prefixed teamname. Words 2..10 remain unresolved raw values.");
    }

    private static void addPlayerFindings(
            byte[] data,
            List<String> findings,
            PlayerRow player,
            int rowIndex,
            RowSection section,
            Validation validation
    ) {
        findings.add("Resolved player row " + rowIndex + ": playerid=" + player.playerId
                + " | firstname=“" + player.firstName + "” | surname=“"
                + player.surname + "” | display=“" + player.displayName() + "”");
        List<Integer> linkIndexes = validation.linksByPlayer.getOrDefault(
                player.playerId,
                Collections.emptyList()
        );
        findings.add("Validated linked-team count: " + linkIndexes.size());
        int shown = Math.min(MAX_REPORTED_LINKS, linkIndexes.size());
        for (int index = 0; index < shown; index++) {
            int linkIndex = linkIndexes.get(index);
            LinkRow link = validation.linkRows.get(linkIndex);
            TeamRow team = validation.teamById.get(link.teamId);
            findings.add("Linked team " + (index + 1) + ": teamid=" + link.teamId
                    + " | " + team.name + " | link row=" + linkIndex);
        }
        if (linkIndexes.size() > shown) {
            findings.add("…and " + (linkIndexes.size() - shown) + " more linked teams");
        }
        addRawWords(
                findings,
                "Player raw record",
                readWords(
                        data,
                        section.rowsOffset + rowIndex * section.recordBytes,
                        section.recordBytes / 4
                ),
                section.rowsOffset + rowIndex * section.recordBytes
        );
        findings.add("Proven player fields: word 0=playerid; word 1=absolute firstname pointer; "
                + "word 2=absolute surname pointer. Words 3..15 remain unresolved packed/raw values.");
    }

    private static void addLinkFindings(
            byte[] data,
            List<String> findings,
            int rowIndex,
            RowSection section,
            Map<Long, TeamRow> teamById,
            Map<Long, PlayerRow> playerById
    ) {
        int offset = section.rowsOffset + rowIndex * section.recordBytes;
        long teamId = readU32Le(data, offset);
        long playerId = readU32Le(data, offset + 4);
        long packed = readU32Le(data, offset + 8);
        TeamRow team = teamById.get(teamId);
        PlayerRow player = playerById.get(playerId);
        findings.add("Resolved teamplayerlinks row " + rowIndex + ": teamid=" + teamId
                + " | team=“" + team.name + "”");
        findings.add("Resolved player reference: playerid=" + playerId
                + " | player=“" + player.displayName() + "”");
        findings.add("Unresolved packed link word: " + packed + " (0x"
                + String.format(Locale.US, "%08x", packed) + ")");
        List<Long> raw = new ArrayList<>(3);
        raw.add(teamId);
        raw.add(playerId);
        raw.add(packed);
        addRawWords(findings, "Link raw record", raw, offset);
        findings.add("Proven link fields: word 0=teamid and word 1=playerid. Word 2 contains "
                + "unresolved packed values; jersey number, position, contract year, and transfer "
                + "status are not editable yet.");
    }

    private static void addRawWords(
            List<String> findings,
            String label,
            List<Long> words,
            int rowOffset
    ) {
        StringBuilder line = new StringBuilder(label).append(":");
        for (int index = 0; index < words.size(); index++) {
            long value = words.get(index);
            line.append("\n  word[").append(index).append("] @ ")
                    .append(formatOffset(rowOffset + index * 4))
                    .append(" = ").append(value)
                    .append(" (0x")
                    .append(String.format(Locale.US, "%08x", value))
                    .append(")");
        }
        findings.add(line.toString());
    }

    private static RowSection parseSection(
            byte[] data,
            String name,
            int startWordIndex,
            int endWordIndex,
            int expectedRecordBytes
    ) {
        int startWordOffset = startWordIndex * 4;
        int endWordOffset = endWordIndex * 4;
        int tableOffset = checkedOffset(readU32Le(data, startWordOffset), data.length,
                name + " table header");
        int rowsEnd = checkedOffset(readU32Le(data, endWordOffset), data.length,
                name + " row section end");
        if (tableOffset + TABLE_HEADER_BYTES > rowsEnd) {
            throw new IllegalArgumentException(name + " row header is outside its section");
        }
        long rowCountLong = readU32Le(data, tableOffset);
        long headerWordB = readU32Le(data, tableOffset + 4);
        if (rowCountLong < 1 || rowCountLong > MAX_ROWS) {
            throw new IllegalArgumentException(name + " row count is outside 1.." + MAX_ROWS
                    + ": " + rowCountLong);
        }
        int rowCount = (int) rowCountLong;
        int rowsOffset = tableOffset + TABLE_HEADER_BYTES;
        long expectedEnd = (long) rowsOffset + (long) rowCount * expectedRecordBytes;
        if (expectedEnd != rowsEnd) {
            long payload = (long) rowsEnd - rowsOffset;
            long derived = payload > 0 && payload % rowCount == 0 ? payload / rowCount : -1;
            throw new IllegalArgumentException(name + " row boundary mismatch: expected record "
                    + "width " + expectedRecordBytes + ", derived " + derived);
        }
        return new RowSection(
                name,
                tableOffset,
                rowsOffset,
                rowsEnd,
                rowCount,
                expectedRecordBytes,
                headerWordB
        );
    }

    private static List<TeamRow> parseTeams(byte[] data, RowSection section) {
        List<TeamRow> rows = new ArrayList<>(section.rowCount);
        Set<Long> ids = new HashSet<>(section.rowCount * 2);
        for (int index = 0; index < section.rowCount; index++) {
            int offset = section.rowsOffset + index * section.recordBytes;
            long teamId = readU32Le(data, offset);
            if (teamId == 0L || !ids.add(teamId)) {
                throw new IllegalArgumentException("teams contains a zero or duplicate teamid at row "
                        + index + ": " + teamId);
            }
            int namePointer = checkedOffset(
                    readU32Le(data, offset + 4),
                    data.length,
                    "teamname pointer at row " + index
            );
            String name = readLengthPrefixedUtf8(data, namePointer, "teamname at row " + index);
            rows.add(new TeamRow(teamId, name, namePointer));
        }
        return Collections.unmodifiableList(rows);
    }

    private static List<PlayerRow> parsePlayers(byte[] data, RowSection section) {
        List<PlayerRow> rows = new ArrayList<>(section.rowCount);
        Set<Long> ids = new HashSet<>(section.rowCount * 2);
        for (int index = 0; index < section.rowCount; index++) {
            int offset = section.rowsOffset + index * section.recordBytes;
            long playerId = readU32Le(data, offset);
            if (playerId == 0L || !ids.add(playerId)) {
                throw new IllegalArgumentException("players contains a zero or duplicate playerid at row "
                        + index + ": " + playerId);
            }
            int firstPointer = checkedOffset(
                    readU32Le(data, offset + 4),
                    data.length,
                    "firstname pointer at row " + index
            );
            int surnamePointer = checkedOffset(
                    readU32Le(data, offset + 8),
                    data.length,
                    "surname pointer at row " + index
            );
            String firstName = readLengthPrefixedUtf8(
                    data,
                    firstPointer,
                    "firstname at row " + index
            );
            String surname = readLengthPrefixedUtf8(
                    data,
                    surnamePointer,
                    "surname at row " + index
            );
            rows.add(new PlayerRow(
                    playerId,
                    firstName,
                    surname,
                    firstPointer,
                    surnamePointer
            ));
        }
        return Collections.unmodifiableList(rows);
    }

    private static Validation validateLinks(
            byte[] data,
            RowSection linksSection,
            List<TeamRow> teams,
            List<PlayerRow> players
    ) {
        Map<Long, TeamRow> teamById = new HashMap<>(teams.size() * 2);
        for (TeamRow team : teams) {
            teamById.put(team.teamId, team);
        }
        Map<Long, PlayerRow> playerById = new HashMap<>(players.size() * 2);
        for (PlayerRow player : players) {
            playerById.put(player.playerId, player);
        }
        List<LinkRow> linkRows = new ArrayList<>(linksSection.rowCount);
        Map<Long, List<Integer>> linksByTeam = new HashMap<>();
        Map<Long, List<Integer>> linksByPlayer = new HashMap<>();
        Set<String> pairs = new HashSet<>(linksSection.rowCount * 2);
        for (int index = 0; index < linksSection.rowCount; index++) {
            int offset = linksSection.rowsOffset + index * linksSection.recordBytes;
            long teamId = readU32Le(data, offset);
            long playerId = readU32Le(data, offset + 4);
            long packed = readU32Le(data, offset + 8);
            if (!teamById.containsKey(teamId)) {
                throw new IllegalArgumentException("teamplayerlinks row " + index
                        + " references missing teamid " + teamId);
            }
            if (!playerById.containsKey(playerId)) {
                throw new IllegalArgumentException("teamplayerlinks row " + index
                        + " references missing playerid " + playerId);
            }
            String pair = teamId + ":" + playerId;
            if (!pairs.add(pair)) {
                throw new IllegalArgumentException("duplicate team-player pair at link row " + index
                        + ": " + pair);
            }
            linkRows.add(new LinkRow(teamId, playerId, packed));
            linksByTeam.computeIfAbsent(teamId, ignored -> new ArrayList<>()).add(index);
            linksByPlayer.computeIfAbsent(playerId, ignored -> new ArrayList<>()).add(index);
        }
        return new Validation(
                linksSection.rowCount,
                Collections.unmodifiableMap(teamById),
                Collections.unmodifiableMap(playerById),
                Collections.unmodifiableList(linkRows),
                immutableListMap(linksByTeam),
                immutableListMap(linksByPlayer)
        );
    }

    private static Map<Long, List<Integer>> immutableListMap(Map<Long, List<Integer>> source) {
        Map<Long, List<Integer>> result = new HashMap<>();
        for (Map.Entry<Long, List<Integer>> entry : source.entrySet()) {
            result.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(result);
    }

    private static int findTeamIndex(List<TeamRow> rows, String query) {
        Query parsed = Query.parse(query);
        if (parsed.kind == QueryKind.INDEX) {
            return checkedIndex(parsed.number, rows.size(), TEAMS);
        }
        if (parsed.kind == QueryKind.ID || parsed.kind == QueryKind.NUMBER) {
            for (int index = 0; index < rows.size(); index++) {
                if (rows.get(index).teamId == parsed.number) {
                    return index;
                }
            }
            throw new IllegalArgumentException("No team row has teamid " + parsed.number);
        }
        String needle = parsed.text.toLowerCase(Locale.US);
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).name.toLowerCase(Locale.US).contains(needle)) {
                return index;
            }
        }
        throw new IllegalArgumentException("No team name contains “" + parsed.text + "”");
    }

    private static int findPlayerIndex(List<PlayerRow> rows, String query) {
        Query parsed = Query.parse(query);
        if (parsed.kind == QueryKind.INDEX) {
            return checkedIndex(parsed.number, rows.size(), PLAYERS);
        }
        if (parsed.kind == QueryKind.ID || parsed.kind == QueryKind.PLAYER
                || parsed.kind == QueryKind.NUMBER) {
            for (int index = 0; index < rows.size(); index++) {
                if (rows.get(index).playerId == parsed.number) {
                    return index;
                }
            }
            throw new IllegalArgumentException("No player row has playerid " + parsed.number);
        }
        String needle = parsed.text.toLowerCase(Locale.US);
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).displayName().toLowerCase(Locale.US).contains(needle)) {
                return index;
            }
        }
        throw new IllegalArgumentException("No player name contains “" + parsed.text + "”");
    }

    private static int findLinkIndex(byte[] data, RowSection section, String query) {
        Query parsed = Query.parse(query);
        if (parsed.kind == QueryKind.INDEX || parsed.kind == QueryKind.NUMBER) {
            return checkedIndex(parsed.number, section.rowCount, LINKS);
        }
        if (parsed.kind != QueryKind.TEAM && parsed.kind != QueryKind.PLAYER
                && parsed.kind != QueryKind.ID) {
            throw new IllegalArgumentException(
                    "For teamplayerlinks use index:N, team:N, or player:N"
            );
        }
        for (int index = 0; index < section.rowCount; index++) {
            int offset = section.rowsOffset + index * section.recordBytes;
            long candidate = parsed.kind == QueryKind.TEAM
                    ? readU32Le(data, offset)
                    : readU32Le(data, offset + 4);
            if (candidate == parsed.number) {
                return index;
            }
        }
        String label = parsed.kind == QueryKind.TEAM ? "teamid" : "playerid";
        throw new IllegalArgumentException("No teamplayerlinks row has " + label + " "
                + parsed.number);
    }

    private static int checkedIndex(long value, int count, String table) {
        if (value < 0 || value >= count) {
            throw new IllegalArgumentException(table + " row index is outside 0.."
                    + (count - 1) + ": " + value);
        }
        return (int) value;
    }

    private static RowSection sectionFor(
            String table,
            RowSection teams,
            RowSection links,
            RowSection players
    ) {
        if (TEAMS.equals(table)) {
            return teams;
        }
        if (PLAYERS.equals(table)) {
            return players;
        }
        return links;
    }

    private static String normalizeTable(String requestedTable) {
        String table = requestedTable == null
                ? ""
                : requestedTable.trim().toLowerCase(Locale.US);
        if (!TEAMS.equals(table) && !LINKS.equals(table) && !PLAYERS.equals(table)) {
            throw new IllegalArgumentException(
                    "Phase 1H row browsing supports teams, players, or teamplayerlinks"
            );
        }
        return table;
    }

    private static List<Long> readWords(byte[] data, int offset, int count) {
        if (offset < 0 || count < 0 || (long) offset + (long) count * 4L > data.length) {
            throw new IllegalArgumentException("Raw row words exceed the database");
        }
        List<Long> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(readU32Le(data, offset + index * 4));
        }
        return Collections.unmodifiableList(values);
    }

    private static String readLengthPrefixedUtf8(byte[] data, int pointer, String label) {
        if (pointer < 0 || pointer + 2 > data.length) {
            throw new IllegalArgumentException(label + " pointer is outside the database");
        }
        int length = readU16Le(data, pointer);
        if (length < 0 || length > MAX_NAME_BYTES) {
            throw new IllegalArgumentException(label + " length is outside 0.."
                    + MAX_NAME_BYTES + ": " + length);
        }
        int start = pointer + 2;
        int end = start + length;
        if (end > data.length) {
            throw new IllegalArgumentException(label + " bytes are truncated");
        }
        String value = new String(data, start, length, StandardCharsets.UTF_8);
        if (value.indexOf('\u0000') >= 0 || value.indexOf('\ufffd') >= 0) {
            throw new IllegalArgumentException(label + " is not valid plain UTF-8 text");
        }
        return value;
    }

    private static int checkedOffset(long value, int length, String label) {
        if (value < 0 || value > Integer.MAX_VALUE || value >= length) {
            throw new IllegalArgumentException(label + " is outside the database: " + value);
        }
        return (int) value;
    }

    private static int readU16Le(byte[] data, int offset) {
        if (offset < 0 || offset + 2 > data.length) {
            throw new IllegalArgumentException("16-bit read exceeds the database");
        }
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static long readU32Le(byte[] data, int offset) {
        if (offset < 0 || offset + 4 > data.length) {
            throw new IllegalArgumentException("32-bit read exceeds the database");
        }
        int signed = (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16)
                | (data[offset + 3] << 24);
        return Integer.toUnsignedLong(signed);
    }

    private static String buildFullReport(
            String table,
            String query,
            int selectedIndex,
            RowSection section,
            List<String> details,
            List<String> findings
    ) {
        StringBuilder report = new StringBuilder();
        report.append("PPSSPP Mod Toolkit — Phase 1H verified row browser report\n")
                .append("mode=READ_ONLY\n")
                .append("requested_table=").append(table).append('\n')
                .append("row_query=").append(query.isEmpty() ? "index:0" : query).append('\n')
                .append("selected_row_index=").append(selectedIndex).append('\n')
                .append("row_count=").append(section.rowCount).append('\n')
                .append("record_bytes=").append(section.recordBytes).append('\n')
                .append("database_changed=false\n\nDETAILS\n");
        for (String detail : details) {
            report.append("- ").append(detail).append('\n');
        }
        report.append("\nFINDINGS\n");
        for (String finding : findings) {
            report.append("- ").append(finding).append('\n');
        }
        report.append("\nSAFETY\n")
                .append("- Stable IDs, name pointers, row boundaries, and foreign-key references are verified.\n")
                .append("- Remaining record words are raw and may contain packed bit fields.\n")
                .append("- Numeric editing is disabled.\n");
        return report.toString();
    }

    private static String formatOffset(int offset) {
        return offset + " (0x" + Integer.toHexString(offset) + ")";
    }

    private enum QueryKind {
        INDEX,
        ID,
        TEAM,
        PLAYER,
        NUMBER,
        TEXT
    }

    private static final class Query {
        final QueryKind kind;
        final long number;
        final String text;

        Query(QueryKind kind, long number, String text) {
            this.kind = kind;
            this.number = number;
            this.text = text;
        }

        static Query parse(String raw) {
            String value = raw == null ? "" : raw.trim();
            if (value.isEmpty()) {
                return new Query(QueryKind.INDEX, 0L, "");
            }
            int colon = value.indexOf(':');
            if (colon > 0) {
                String prefix = value.substring(0, colon).trim().toLowerCase(Locale.US);
                String suffix = value.substring(colon + 1).trim();
                QueryKind kind;
                if ("index".equals(prefix) || "row".equals(prefix)) {
                    kind = QueryKind.INDEX;
                } else if ("id".equals(prefix)) {
                    kind = QueryKind.ID;
                } else if ("team".equals(prefix) || "teamid".equals(prefix)) {
                    kind = QueryKind.TEAM;
                } else if ("player".equals(prefix) || "playerid".equals(prefix)) {
                    kind = QueryKind.PLAYER;
                } else {
                    throw new IllegalArgumentException("Unsupported row-query prefix: " + prefix);
                }
                return new Query(kind, parseNonNegativeLong(suffix, prefix), "");
            }
            if (isDigits(value)) {
                return new Query(QueryKind.NUMBER, parseNonNegativeLong(value, "number"), "");
            }
            return new Query(QueryKind.TEXT, -1L, value);
        }

        private static long parseNonNegativeLong(String value, String label) {
            try {
                long parsed = Long.parseLong(value);
                if (parsed < 0L) {
                    throw new IllegalArgumentException(label + " must not be negative");
                }
                return parsed;
            } catch (NumberFormatException error) {
                throw new IllegalArgumentException(label + " must be a whole number");
            }
        }

        private static boolean isDigits(String value) {
            if (value.isEmpty()) {
                return false;
            }
            for (int index = 0; index < value.length(); index++) {
                if (!Character.isDigit(value.charAt(index))) {
                    return false;
                }
            }
            return true;
        }
    }

    private static final class RowSection {
        final String name;
        final int tableOffset;
        final int rowsOffset;
        final int rowsEndOffset;
        final int rowCount;
        final int recordBytes;
        final long headerWordB;

        RowSection(
                String name,
                int tableOffset,
                int rowsOffset,
                int rowsEndOffset,
                int rowCount,
                int recordBytes,
                long headerWordB
        ) {
            this.name = name;
            this.tableOffset = tableOffset;
            this.rowsOffset = rowsOffset;
            this.rowsEndOffset = rowsEndOffset;
            this.rowCount = rowCount;
            this.recordBytes = recordBytes;
            this.headerWordB = headerWordB;
        }
    }

    private static final class TeamRow {
        final long teamId;
        final String name;
        final int namePointer;

        TeamRow(long teamId, String name, int namePointer) {
            this.teamId = teamId;
            this.name = name;
            this.namePointer = namePointer;
        }
    }

    private static final class PlayerRow {
        final long playerId;
        final String firstName;
        final String surname;
        final int firstNamePointer;
        final int surnamePointer;

        PlayerRow(
                long playerId,
                String firstName,
                String surname,
                int firstNamePointer,
                int surnamePointer
        ) {
            this.playerId = playerId;
            this.firstName = firstName;
            this.surname = surname;
            this.firstNamePointer = firstNamePointer;
            this.surnamePointer = surnamePointer;
        }

        String displayName() {
            String combined = (firstName + " " + surname).trim();
            return combined.isEmpty() ? "Unnamed player " + playerId : combined;
        }
    }

    private static final class LinkRow {
        final long teamId;
        final long playerId;
        final long packedWord;

        LinkRow(long teamId, long playerId, long packedWord) {
            this.teamId = teamId;
            this.playerId = playerId;
            this.packedWord = packedWord;
        }
    }

    private static final class Validation {
        final int linkCount;
        final Map<Long, TeamRow> teamById;
        final Map<Long, PlayerRow> playerById;
        final List<LinkRow> linkRows;
        final Map<Long, List<Integer>> linksByTeam;
        final Map<Long, List<Integer>> linksByPlayer;

        Validation(
                int linkCount,
                Map<Long, TeamRow> teamById,
                Map<Long, PlayerRow> playerById,
                List<LinkRow> linkRows,
                Map<Long, List<Integer>> linksByTeam,
                Map<Long, List<Integer>> linksByPlayer
        ) {
            this.linkCount = linkCount;
            this.teamById = teamById;
            this.playerById = playerById;
            this.linkRows = linkRows;
            this.linksByTeam = linksByTeam;
            this.linksByPlayer = linksByPlayer;
        }
    }

    public static final class BrowseResult {
        private final String tableName;
        private final int selectedRowIndex;
        private final int rowCount;
        private final String summary;
        private final List<String> details;
        private final List<String> findings;
        private final String fullReportText;

        BrowseResult(
                String tableName,
                int selectedRowIndex,
                int rowCount,
                String summary,
                List<String> details,
                List<String> findings,
                String fullReportText
        ) {
            this.tableName = tableName;
            this.selectedRowIndex = selectedRowIndex;
            this.rowCount = rowCount;
            this.summary = summary;
            this.details = Collections.unmodifiableList(new ArrayList<>(details));
            this.findings = Collections.unmodifiableList(new ArrayList<>(findings));
            this.fullReportText = fullReportText;
        }

        public String getTableName() {
            return tableName;
        }

        public int getSelectedRowIndex() {
            return selectedRowIndex;
        }

        public int getRowCount() {
            return rowCount;
        }

        public String getSummary() {
            return summary;
        }

        public List<String> getDetails() {
            return details;
        }

        public List<String> getFindings() {
            return findings;
        }

        public String getFullReportText() {
            return fullReportText;
        }
    }
}
