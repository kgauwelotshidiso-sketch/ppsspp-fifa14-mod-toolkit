package com.tshidiso.ppssppmodtoolkit;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Read-only parser for the table-schema blocks observed in FIFA 14 PSP fifa.db.
 *
 * <p>The verified reports supplied from the user's ULUS-10655 database show the same structure for
 * players, teams, and teamplayerlinks: a NUL-delimited table name, zero alignment padding, two
 * 32-bit table hashes, one 32-bit field count, exactly one signed 32-bit descriptor per field, and
 * then one length-prefixed, four-byte-aligned field name per descriptor. This class only accepts a
 * schema when every boundary and every field name validates. It does not interpret descriptor
 * semantics or modify database bytes.</p>
 */
public final class FifaSchemaDecoder {
    private static final int MAX_FIELDS = 512;
    private static final int MAX_FIELD_NAME_BYTES = 96;
    private static final int MAX_REJECTED_OCCURRENCES = 12;

    private FifaSchemaDecoder() {
    }

    public static SchemaResult decode(byte[] data, String requestedTable) {
        if (data == null || data.length < 32) {
            throw new IllegalArgumentException("Database is too small for schema decoding");
        }
        String table = DatabaseRules.requireKnownTableName(requestedTable);
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
                TableSchema schema = parseOccurrence(data, table, markerOffset);
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

    private static TableSchema parseOccurrence(byte[] data, String table, int markerOffset) {
        int nameLength = table.length();
        if (markerOffset <= 0 || data[markerOffset - 1] != 0) {
            throw new IllegalArgumentException("byte before the table name is not NUL");
        }
        int terminatorOffset = markerOffset + nameLength;
        if (terminatorOffset >= data.length || data[terminatorOffset] != 0) {
            throw new IllegalArgumentException("table name is not NUL terminated");
        }

        int headerOffset = alignUp(terminatorOffset + 1, 4);
        for (int offset = terminatorOffset + 1; offset < headerOffset; offset++) {
            if (data[offset] != 0) {
                throw new IllegalArgumentException("nonzero alignment padding follows the table name");
            }
        }
        if (headerOffset + 12 > data.length) {
            throw new IllegalArgumentException("schema header is truncated");
        }

        long hashA = readU32Le(data, headerOffset);
        long hashB = readU32Le(data, headerOffset + 4);
        long fieldCountLong = readU32Le(data, headerOffset + 8);
        if (fieldCountLong < 1L || fieldCountLong > MAX_FIELDS) {
            throw new IllegalArgumentException("field count is outside 1.." + MAX_FIELDS
                    + ": " + fieldCountLong);
        }
        int fieldCount = (int) fieldCountLong;
        int descriptorOffset = headerOffset + 12;
        long descriptorEndLong = (long) descriptorOffset + (long) fieldCount * 4L;
        if (descriptorEndLong > data.length) {
            throw new IllegalArgumentException("descriptor array is truncated");
        }
        int descriptorEnd = (int) descriptorEndLong;

        List<Integer> descriptors = new ArrayList<>(fieldCount);
        for (int index = 0; index < fieldCount; index++) {
            descriptors.add(readS32Le(data, descriptorOffset + index * 4));
        }

        int cursor = descriptorEnd;
        List<FieldDefinition> fields = new ArrayList<>(fieldCount);
        for (int index = 0; index < fieldCount; index++) {
            if (cursor + 2 > data.length) {
                throw new IllegalArgumentException("field-name length " + (index + 1) + " is truncated");
            }
            int fieldNameLength = readU16Le(data, cursor);
            int fieldNameOffset = cursor + 2;
            if (fieldNameLength < 1 || fieldNameLength > MAX_FIELD_NAME_BYTES) {
                throw new IllegalArgumentException("field-name length " + (index + 1)
                        + " is outside 1.." + MAX_FIELD_NAME_BYTES + ": " + fieldNameLength);
            }
            int fieldNameEnd = fieldNameOffset + fieldNameLength;
            if (fieldNameEnd > data.length) {
                throw new IllegalArgumentException("field name " + (index + 1) + " is truncated");
            }
            String name = new String(
                    data,
                    fieldNameOffset,
                    fieldNameLength,
                    StandardCharsets.US_ASCII
            );
            if (!isValidFieldName(name)) {
                throw new IllegalArgumentException("invalid field name " + (index + 1)
                        + ": “" + printable(name) + "”");
            }
            fields.add(new FieldDefinition(
                    index,
                    name,
                    descriptors.get(index),
                    cursor,
                    fieldNameOffset
            ));

            int nextCursor = alignUp(fieldNameEnd, 4);
            if (nextCursor > data.length) {
                throw new IllegalArgumentException("field-name alignment exceeds the database");
            }
            for (int padding = fieldNameEnd; padding < nextCursor; padding++) {
                if (data[padding] != 0) {
                    throw new IllegalArgumentException("nonzero padding follows field “" + name + "”");
                }
            }
            cursor = nextCursor;
        }

        return new TableSchema(
                table,
                markerOffset,
                headerOffset,
                hashA,
                hashB,
                fieldCount,
                descriptorOffset,
                descriptorEnd,
                cursor,
                fields
        );
    }

    private static boolean isValidFieldName(String value) {
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
        private final int markerOffset;
        private final int headerOffset;
        private final long hashA;
        private final long hashB;
        private final int fieldCount;
        private final int descriptorOffset;
        private final int descriptorEndOffset;
        private final int schemaEndOffset;
        private final List<FieldDefinition> fields;

        TableSchema(
                String tableName,
                int markerOffset,
                int headerOffset,
                long hashA,
                long hashB,
                int fieldCount,
                int descriptorOffset,
                int descriptorEndOffset,
                int schemaEndOffset,
                List<FieldDefinition> fields
        ) {
            this.tableName = tableName;
            this.markerOffset = markerOffset;
            this.headerOffset = headerOffset;
            this.hashA = hashA;
            this.hashB = hashB;
            this.fieldCount = fieldCount;
            this.descriptorOffset = descriptorOffset;
            this.descriptorEndOffset = descriptorEndOffset;
            this.schemaEndOffset = schemaEndOffset;
            this.fields = Collections.unmodifiableList(new ArrayList<>(fields));
        }

        public String getTableName() {
            return tableName;
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

        public int getFieldCount() {
            return fieldCount;
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
        private final int descriptor;
        private final int lengthPrefixOffset;
        private final int nameOffset;

        FieldDefinition(
                int index,
                String name,
                int descriptor,
                int lengthPrefixOffset,
                int nameOffset
        ) {
            this.index = index;
            this.name = name;
            this.descriptor = descriptor;
            this.lengthPrefixOffset = lengthPrefixOffset;
            this.nameOffset = nameOffset;
        }

        public int getIndex() {
            return index;
        }

        public String getName() {
            return name;
        }

        public int getDescriptor() {
            return descriptor;
        }

        public long getDescriptorUnsigned() {
            return Integer.toUnsignedLong(descriptor);
        }

        public int getLengthPrefixOffset() {
            return lengthPrefixOffset;
        }

        public int getNameOffset() {
            return nameOffset;
        }

        public String descriptorHex() {
            return String.format(Locale.US, "0x%08x", getDescriptorUnsigned());
        }
    }
}
