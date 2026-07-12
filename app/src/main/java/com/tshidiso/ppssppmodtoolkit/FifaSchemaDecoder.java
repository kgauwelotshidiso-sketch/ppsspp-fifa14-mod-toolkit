package com.tshidiso.ppssppmodtoolkit;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Read-only parser for verified FIFA 14 PSP table-schema blocks.
 *
 * <p>The user's real ULUS-10655 fifa.db proves that the 32-bit value after the two table hashes is
 * a descriptor-word count, not a field-name count. The descriptor array and the following aligned
 * field-name list therefore have different lengths. This parser verifies the three initial tables
 * against their observed descriptor counts, field-name counts, first/last field names, and the
 * exact structural successor table that terminates each schema block. It never maps descriptor
 * words to fields and never modifies database bytes.</p>
 */
public final class FifaSchemaDecoder {
    private static final int MAX_DESCRIPTORS = 512;
    private static final int MAX_FIELD_NAMES = 512;
    private static final int MAX_FIELD_NAME_BYTES = 96;
    private static final int MAX_REJECTED_OCCURRENCES = 12;

    private FifaSchemaDecoder() {
    }

    public static SchemaResult decode(byte[] data, String requestedTable) {
        if (data == null || data.length < 32) {
            throw new IllegalArgumentException("Database is too small for schema decoding");
        }
        String table = DatabaseRules.requireKnownTableName(requestedTable);
        TableProfile profile = profileFor(table);
        if (profile == null) {
            return new SchemaResult(
                    table,
                    false,
                    0,
                    null,
                    Collections.singletonList(
                            "Verified boundary profile is not yet available for table “" + table + "”"
                    )
            );
        }

        byte[] needle = table.getBytes(StandardCharsets.US_ASCII);
        byte[] searchable = asciiLower(data);
        List<String> rejected = new ArrayList<>();
        int occurrenceCount = 0;
        int from = 0;
        while (from <= data.length - needle.length) {
            int markerOffset = indexOf(searchable, needle, from);
            if (markerOffset < 0) {
                break;
            }
            occurrenceCount++;
            try {
                TableSchema schema = parseOccurrence(data, profile, markerOffset);
                return new SchemaResult(
                        table,
                        true,
                        occurrenceCount,
                        schema,
                        rejected
                );
            } catch (IllegalArgumentException error) {
                if (rejected.size() < MAX_REJECTED_OCCURRENCES) {
                    rejected.add("Occurrence " + occurrenceCount + " at "
                            + formatOffset(markerOffset) + " rejected: " + error.getMessage());
                }
            }
            from = markerOffset + Math.max(1, needle.length);
        }
        return new SchemaResult(table, false, occurrenceCount, null, rejected);
    }

    private static TableSchema parseOccurrence(
            byte[] data,
            TableProfile profile,
            int markerOffset
    ) {
        int prefixOffset = markerOffset - 2;
        if (prefixOffset < 0) {
            throw new IllegalArgumentException("table-name length prefix is missing");
        }
        int encodedLength = readU16Le(data, prefixOffset);
        if (encodedLength != profile.tableName.length()) {
            throw new IllegalArgumentException("length prefix " + encodedLength
                    + " does not match table-name length " + profile.tableName.length());
        }
        int nameEnd = markerOffset + profile.tableName.length();
        if (nameEnd > data.length) {
            throw new IllegalArgumentException("table name is truncated");
        }
        String actualName = new String(
                data,
                markerOffset,
                profile.tableName.length(),
                StandardCharsets.US_ASCII
        ).toLowerCase(Locale.US);
        if (!profile.tableName.equals(actualName)) {
            throw new IllegalArgumentException("table-name bytes do not match the requested table");
        }

        int headerOffset = alignUp(nameEnd, 4);
        requireZeroPadding(data, nameEnd, headerOffset, "table name");
        if (headerOffset + 12 > data.length) {
            throw new IllegalArgumentException("schema header is truncated");
        }

        long hashA = readU32Le(data, headerOffset);
        long hashB = readU32Le(data, headerOffset + 4);
        long descriptorCountLong = readU32Le(data, headerOffset + 8);
        if (descriptorCountLong < 1L || descriptorCountLong > MAX_DESCRIPTORS) {
            throw new IllegalArgumentException("descriptor-word count is outside 1.."
                    + MAX_DESCRIPTORS + ": " + descriptorCountLong);
        }
        int descriptorCount = (int) descriptorCountLong;
        if (descriptorCount != profile.descriptorCount) {
            throw new IllegalArgumentException("descriptor-word count " + descriptorCount
                    + " does not match verified profile " + profile.descriptorCount);
        }

        int descriptorOffset = headerOffset + 12;
        long descriptorEndLong = (long) descriptorOffset + (long) descriptorCount * 4L;
        if (descriptorEndLong > data.length) {
            throw new IllegalArgumentException("descriptor array is truncated");
        }
        int descriptorEnd = (int) descriptorEndLong;
        List<Integer> descriptors = new ArrayList<>(descriptorCount);
        for (int index = 0; index < descriptorCount; index++) {
            descriptors.add(readS32Le(data, descriptorOffset + index * 4));
        }

        int cursor = descriptorEnd;
        List<FieldDefinition> fields = new ArrayList<>(profile.fieldNameCount);
        if (profile.fieldNameCount < 1 || profile.fieldNameCount > MAX_FIELD_NAMES) {
            throw new IllegalArgumentException("verified field-name count is invalid");
        }
        for (int index = 0; index < profile.fieldNameCount; index++) {
            NameRecord record = readNameRecord(data, cursor, "field name " + (index + 1));
            if (profile.successorTable.equals(record.name)) {
                throw new IllegalArgumentException("successor table appeared before all verified fields");
            }
            fields.add(new FieldDefinition(index, record.name, cursor, cursor + 2));
            cursor = record.nextOffset;
        }

        if (!profile.firstField.equals(fields.get(0).getName())) {
            throw new IllegalArgumentException("first field is “" + fields.get(0).getName()
                    + "”, expected “" + profile.firstField + "”");
        }
        if (!profile.lastField.equals(fields.get(fields.size() - 1).getName())) {
            throw new IllegalArgumentException("last field is “"
                    + fields.get(fields.size() - 1).getName() + "”, expected “"
                    + profile.lastField + "”");
        }

        SuccessorRecord successor = validateSuccessor(data, cursor, profile);
        return new TableSchema(
                profile.tableName,
                prefixOffset,
                markerOffset,
                headerOffset,
                hashA,
                hashB,
                descriptorCount,
                descriptorOffset,
                descriptorEnd,
                cursor,
                profile.successorTable,
                successor.markerOffset,
                descriptors,
                fields
        );
    }

    private static SuccessorRecord validateSuccessor(
            byte[] data,
            int prefixOffset,
            TableProfile profile
    ) {
        NameRecord successorName = readNameRecord(data, prefixOffset, "successor table name");
        if (!profile.successorTable.equals(successorName.name)) {
            throw new IllegalArgumentException("schema boundary is “" + successorName.name
                    + "”, expected successor “" + profile.successorTable + "”");
        }
        int headerOffset = successorName.nextOffset;
        if (headerOffset + 12 > data.length) {
            throw new IllegalArgumentException("successor schema header is truncated");
        }
        long hashA = readU32Le(data, headerOffset);
        long hashB = readU32Le(data, headerOffset + 4);
        int descriptorCount = checkedDescriptorCount(readU32Le(data, headerOffset + 8));
        if (hashA == 0L && hashB == 0L) {
            throw new IllegalArgumentException("successor schema hashes are both zero");
        }
        if (descriptorCount != profile.successorDescriptorCount) {
            throw new IllegalArgumentException("successor descriptor-word count "
                    + descriptorCount + " does not match verified profile "
                    + profile.successorDescriptorCount);
        }
        long descriptorEndLong = (long) headerOffset + 12L + (long) descriptorCount * 4L;
        if (descriptorEndLong > data.length) {
            throw new IllegalArgumentException("successor descriptor array is truncated");
        }
        int descriptorEnd = (int) descriptorEndLong;
        NameRecord firstSuccessorField = readNameRecord(
                data,
                descriptorEnd,
                "first successor field name"
        );
        if (!profile.successorFirstField.equals(firstSuccessorField.name)) {
            throw new IllegalArgumentException("successor first field is “"
                    + firstSuccessorField.name + "”, expected “"
                    + profile.successorFirstField + "”");
        }
        return new SuccessorRecord(prefixOffset + 2);
    }

    private static int checkedDescriptorCount(long value) {
        if (value < 1L || value > MAX_DESCRIPTORS) {
            throw new IllegalArgumentException("descriptor-word count is outside 1.."
                    + MAX_DESCRIPTORS + ": " + value);
        }
        return (int) value;
    }

    private static NameRecord readNameRecord(byte[] data, int prefixOffset, String label) {
        if (prefixOffset < 0 || prefixOffset + 2 > data.length) {
            throw new IllegalArgumentException(label + " length prefix is truncated");
        }
        int length = readU16Le(data, prefixOffset);
        if (length < 1 || length > MAX_FIELD_NAME_BYTES) {
            throw new IllegalArgumentException(label + " length is outside 1.."
                    + MAX_FIELD_NAME_BYTES + ": " + length);
        }
        int nameOffset = prefixOffset + 2;
        int nameEnd = nameOffset + length;
        if (nameEnd > data.length) {
            throw new IllegalArgumentException(label + " is truncated");
        }
        String name = new String(data, nameOffset, length, StandardCharsets.US_ASCII);
        if (!isValidIdentifier(name)) {
            throw new IllegalArgumentException("invalid " + label + ": “"
                    + printable(name) + "”");
        }
        int nextOffset = alignUp(nameEnd, 4);
        if (nextOffset > data.length) {
            throw new IllegalArgumentException(label + " alignment exceeds the database");
        }
        requireZeroPadding(data, nameEnd, nextOffset, label);
        return new NameRecord(name, nextOffset);
    }

    private static void requireZeroPadding(byte[] data, int start, int end, String label) {
        for (int offset = start; offset < end; offset++) {
            if (data[offset] != 0) {
                throw new IllegalArgumentException("nonzero alignment padding follows " + label);
            }
        }
    }

    private static TableProfile profileFor(String table) {
        switch (table) {
            case "teams":
                return new TableProfile(
                        "teams",
                        40,
                        35,
                        "teamid",
                        "defdefenderline",
                        "refereecountrylinks",
                        3,
                        "refereeid"
                );
            case "teamplayerlinks":
                return new TableProfile(
                        "teamplayerlinks",
                        7,
                        6,
                        "teamid",
                        "transferdone",
                        "jerseynames",
                        3,
                        "playerid"
                );
            case "players":
                return new TableProfile(
                        "players",
                        121,
                        96,
                        "playerid",
                        "exportfromdb",
                        "leagueteamlinks",
                        3,
                        "leagueid"
                );
            default:
                return null;
        }
    }

    private static boolean isValidIdentifier(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!((character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')
                    || character == '_')) {
                return false;
            }
        }
        return true;
    }

    private static String printable(String value) {
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            output.append(character >= 0x20 && character <= 0x7e ? character : '.');
        }
        return output.toString();
    }

    private static byte[] asciiLower(byte[] data) {
        byte[] output = data.clone();
        for (int index = 0; index < output.length; index++) {
            int value = output[index] & 0xff;
            if (value >= 'A' && value <= 'Z') {
                output[index] = (byte) (value + ('a' - 'A'));
            }
        }
        return output;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        for (int index = Math.max(0, from); index <= haystack.length - needle.length; index++) {
            boolean match = true;
            for (int needleIndex = 0; needleIndex < needle.length; needleIndex++) {
                if (haystack[index + needleIndex] != needle[needleIndex]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return index;
            }
        }
        return -1;
    }

    private static int readU16Le(byte[] data, int offset) {
        return (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static int readS32Le(byte[] data, int offset) {
        return (data[offset] & 0xff)
                | ((data[offset + 1] & 0xff) << 8)
                | ((data[offset + 2] & 0xff) << 16)
                | (data[offset + 3] << 24);
    }

    private static long readU32Le(byte[] data, int offset) {
        return Integer.toUnsignedLong(readS32Le(data, offset));
    }

    private static int alignUp(int value, int alignment) {
        int remainder = Math.floorMod(value, alignment);
        return remainder == 0 ? value : value + alignment - remainder;
    }

    private static String formatOffset(int offset) {
        return offset + " (0x" + Integer.toHexString(offset) + ")";
    }

    private static final class TableProfile {
        final String tableName;
        final int descriptorCount;
        final int fieldNameCount;
        final String firstField;
        final String lastField;
        final String successorTable;
        final int successorDescriptorCount;
        final String successorFirstField;

        TableProfile(
                String tableName,
                int descriptorCount,
                int fieldNameCount,
                String firstField,
                String lastField,
                String successorTable,
                int successorDescriptorCount,
                String successorFirstField
        ) {
            this.tableName = tableName;
            this.descriptorCount = descriptorCount;
            this.fieldNameCount = fieldNameCount;
            this.firstField = firstField;
            this.lastField = lastField;
            this.successorTable = successorTable;
            this.successorDescriptorCount = successorDescriptorCount;
            this.successorFirstField = successorFirstField;
        }
    }

    private static final class NameRecord {
        final String name;
        final int nextOffset;

        NameRecord(String name, int nextOffset) {
            this.name = name;
            this.nextOffset = nextOffset;
        }
    }

    private static final class SuccessorRecord {
        final int markerOffset;

        SuccessorRecord(int markerOffset) {
            this.markerOffset = markerOffset;
        }
    }

    public static final class SchemaResult {
        private final String tableName;
        private final boolean verified;
        private final int occurrencesExamined;
        private final TableSchema schema;
        private final List<String> rejectedOccurrences;

        SchemaResult(
                String tableName,
                boolean verified,
                int occurrencesExamined,
                TableSchema schema,
                List<String> rejectedOccurrences
        ) {
            this.tableName = tableName;
            this.verified = verified;
            this.occurrencesExamined = occurrencesExamined;
            this.schema = schema;
            this.rejectedOccurrences = Collections.unmodifiableList(
                    new ArrayList<>(rejectedOccurrences)
            );
        }

        public String getTableName() {
            return tableName;
        }

        public boolean isVerified() {
            return verified;
        }

        public int getOccurrencesExamined() {
            return occurrencesExamined;
        }

        public TableSchema getSchema() {
            return schema;
        }

        public List<String> getRejectedOccurrences() {
            return rejectedOccurrences;
        }
    }

    public static final class TableSchema {
        private final String tableName;
        private final int lengthPrefixOffset;
        private final int markerOffset;
        private final int headerOffset;
        private final long hashA;
        private final long hashB;
        private final int descriptorCount;
        private final int descriptorOffset;
        private final int descriptorEndOffset;
        private final int schemaEndOffset;
        private final String successorTableName;
        private final int successorMarkerOffset;
        private final List<Integer> descriptors;
        private final List<FieldDefinition> fields;

        TableSchema(
                String tableName,
                int lengthPrefixOffset,
                int markerOffset,
                int headerOffset,
                long hashA,
                long hashB,
                int descriptorCount,
                int descriptorOffset,
                int descriptorEndOffset,
                int schemaEndOffset,
                String successorTableName,
                int successorMarkerOffset,
                List<Integer> descriptors,
                List<FieldDefinition> fields
        ) {
            this.tableName = tableName;
            this.lengthPrefixOffset = lengthPrefixOffset;
            this.markerOffset = markerOffset;
            this.headerOffset = headerOffset;
            this.hashA = hashA;
            this.hashB = hashB;
            this.descriptorCount = descriptorCount;
            this.descriptorOffset = descriptorOffset;
            this.descriptorEndOffset = descriptorEndOffset;
            this.schemaEndOffset = schemaEndOffset;
            this.successorTableName = successorTableName;
            this.successorMarkerOffset = successorMarkerOffset;
            this.descriptors = Collections.unmodifiableList(new ArrayList<>(descriptors));
            this.fields = Collections.unmodifiableList(new ArrayList<>(fields));
        }

        public String getTableName() {
            return tableName;
        }

        public int getLengthPrefixOffset() {
            return lengthPrefixOffset;
        }

        public int getMarkerOffset() {
            return markerOffset;
        }

        public int getHeaderOffset() {
            return headerOffset;
        }

        public long getHashA() {
            return hashA;
        }

        public long getHashB() {
            return hashB;
        }

        public int getDescriptorCount() {
            return descriptorCount;
        }

        public int getFieldCount() {
            return fields.size();
        }

        public int getDescriptorOffset() {
            return descriptorOffset;
        }

        public int getDescriptorEndOffset() {
            return descriptorEndOffset;
        }

        public int getSchemaEndOffset() {
            return schemaEndOffset;
        }

        public String getSuccessorTableName() {
            return successorTableName;
        }

        public int getSuccessorMarkerOffset() {
            return successorMarkerOffset;
        }

        public List<Integer> getDescriptors() {
            return descriptors;
        }

        public List<FieldDefinition> getFields() {
            return fields;
        }

        public String hashAHex() {
            return String.format(Locale.US, "0x%08x", hashA);
        }

        public String hashBHex() {
            return String.format(Locale.US, "0x%08x", hashB);
        }
    }

    public static final class FieldDefinition {
        private final int index;
        private final String name;
        private final int lengthPrefixOffset;
        private final int nameOffset;

        FieldDefinition(
                int index,
                String name,
                int lengthPrefixOffset,
                int nameOffset
        ) {
            this.index = index;
            this.name = name;
            this.lengthPrefixOffset = lengthPrefixOffset;
            this.nameOffset = nameOffset;
        }

        public int getIndex() {
            return index;
        }

        public String getName() {
            return name;
        }

        public int getLengthPrefixOffset() {
            return lengthPrefixOffset;
        }

        public int getNameOffset() {
            return nameOffset;
        }
    }
}
