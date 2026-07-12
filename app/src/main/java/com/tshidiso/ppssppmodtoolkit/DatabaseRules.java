package com.tshidiso.ppssppmodtoolkit;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Pure validation and byte-analysis rules for the Phase 1G database lab. */
public final class DatabaseRules {
    public static final int MAX_DATABASE_BYTES = 64 * 1024 * 1024;
    public static final int MAX_PATCH_TEXT_BYTES = 128;
    public static final int MAX_REPORTED_OFFSETS = 50;

    private static final byte[] SQLITE_HEADER = "SQLite format 3\u0000".getBytes(StandardCharsets.US_ASCII);
    private static final String[] KNOWN_TABLE_MARKERS = new String[]{
            "players",
            "playernames",
            "dcplayernames",
            "teams",
            "teamplayerlinks",
            "teamkits",
            "teamstadiumlinks",
            "stadiums",
            "leagues",
            "leagueteamlinks",
            "nations",
            "competition",
            "formations",
            "manager",
            "referee",
            "rivals",
            "playerboots",
            "playerloans",
            "previousteam",
            "teamnationlinks",
            "rowteamnationlinks",
            "teamballs",
            "shoecolors"
    };

    private DatabaseRules() {
    }

    public static boolean isDatabaseAsset(AssetRecord asset) {
        return asset != null && "db".equalsIgnoreCase(asset.getExtension());
    }

    public static List<String> knownTableMarkers() {
        return Collections.unmodifiableList(Arrays.asList(KNOWN_TABLE_MARKERS.clone()));
    }

    public static String requireKnownTableName(String tableName) {
        String normalized = tableName == null ? "" : tableName.trim().toLowerCase(Locale.US);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Table name is empty");
        }
        if (normalized.length() > 64) {
            throw new IllegalArgumentException("Table name is too long");
        }
        for (int index = 0; index < normalized.length(); index++) {
            char value = normalized.charAt(index);
            if (!((value >= 'a' && value <= 'z')
                    || (value >= '0' && value <= '9')
                    || value == '_')) {
                throw new IllegalArgumentException(
                        "Table name must contain only lowercase letters, digits, or underscores"
                );
            }
        }
        for (String known : KNOWN_TABLE_MARKERS) {
            if (known.equals(normalized)) {
                return normalized;
            }
        }
        throw new IllegalArgumentException(
                "Unsupported table name. Start with players, teams, or teamplayerlinks"
        );
    }

    public static String detectFormat(byte[] data) {
        if (startsWith(data, SQLITE_HEADER)) {
            return "SQLite 3 database";
        }
        List<String> markers = findKnownTableMarkers(data);
        if (!markers.isEmpty()) {
            return "EA/FIFA binary database candidate";
        }
        if (data != null && data.length >= 4) {
            int printable = 0;
            int checked = Math.min(data.length, 4096);
            for (int index = 0; index < checked; index++) {
                int value = data[index] & 0xff;
                if (value == 0 || (value >= 0x20 && value <= 0x7e)) {
                    printable++;
                }
            }
            if (printable * 100 / Math.max(1, checked) >= 85) {
                return "Mostly text data";
            }
        }
        return "Unknown binary database";
    }

    public static List<String> findKnownTableMarkers(byte[] data) {
        Set<String> found = new LinkedHashSet<>();
        if (data == null || data.length == 0) {
            return new ArrayList<>();
        }
        byte[] lower = asciiLower(data);
        for (String marker : KNOWN_TABLE_MARKERS) {
            if (!findOffsets(lower, marker.getBytes(StandardCharsets.US_ASCII), 1).isEmpty()) {
                found.add(marker);
            }
        }
        return new ArrayList<>(found);
    }

    public static int countPrintableStrings(byte[] data, int minimumLength, int maximumCount) {
        if (data == null || data.length == 0 || minimumLength < 1 || maximumCount < 1) {
            return 0;
        }
        int count = 0;
        int run = 0;
        for (byte datum : data) {
            int value = datum & 0xff;
            if (value >= 0x20 && value <= 0x7e) {
                run++;
            } else {
                if (run >= minimumLength) {
                    count++;
                    if (count >= maximumCount) {
                        return count;
                    }
                }
                run = 0;
            }
        }
        if (run >= minimumLength && count < maximumCount) {
            count++;
        }
        return count;
    }

    public static String headerHex(byte[] data, int maximumBytes) {
        if (data == null || data.length == 0 || maximumBytes <= 0) {
            return "";
        }
        int length = Math.min(data.length, maximumBytes);
        StringBuilder output = new StringBuilder(length * 3);
        for (int index = 0; index < length; index++) {
            if (index > 0) {
                output.append(index % 16 == 0 ? '\n' : ' ');
            }
            output.append(String.format(Locale.US, "%02x", data[index] & 0xff));
        }
        return output.toString();
    }

    public static String firstIntegers(byte[] data, ByteOrder order, int maximumValues) {
        if (data == null || data.length < 4 || maximumValues <= 0) {
            return "none";
        }
        int count = Math.min(maximumValues, data.length / 4);
        ByteBuffer buffer = ByteBuffer.wrap(data).order(order);
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                output.append(", ");
            }
            long unsigned = Integer.toUnsignedLong(buffer.getInt(index * 4));
            output.append(unsigned);
        }
        return output.toString();
    }

    public static List<Integer> findTextOffsets(byte[] data, String text, int maximumMatches) {
        byte[] needle = requirePatchText(text, "Search text");
        return findOffsets(data, needle, maximumMatches);
    }

    public static int countTextOccurrences(byte[] data, String text) {
        byte[] needle = requirePatchText(text, "Search text");
        return countOccurrences(data, needle);
    }

    public static PatchPlan buildPatchPlan(
            byte[] source,
            String findText,
            String replacementText,
            int occurrenceNumber
    ) {
        if (source == null) {
            throw new IllegalArgumentException("Database bytes are missing");
        }
        byte[] find = requirePatchText(findText, "Find text");
        byte[] replacement = requirePatchText(replacementText, "Replacement text");
        if (find.length != replacement.length) {
            throw new IllegalArgumentException(
                    "Find and replacement text must use exactly the same number of UTF-8 bytes"
            );
        }
        if (Arrays.equals(find, replacement)) {
            throw new IllegalArgumentException("Replacement text must differ from the find text");
        }
        if (occurrenceNumber < 1) {
            throw new IllegalArgumentException("Occurrence number must be 1 or higher");
        }
        int totalOccurrences = 0;
        int selectedOffset = -1;
        int index = 0;
        while (index <= source.length - find.length) {
            boolean match = true;
            for (int needleIndex = 0; needleIndex < find.length; needleIndex++) {
                if (source[index + needleIndex] != find[needleIndex]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                totalOccurrences++;
                if (totalOccurrences == occurrenceNumber) {
                    selectedOffset = index;
                }
                index += find.length;
            } else {
                index++;
            }
        }
        if (totalOccurrences == 0) {
            throw new IllegalArgumentException("Find text was not present in the selected database");
        }
        if (selectedOffset < 0) {
            throw new IllegalArgumentException(
                    "Occurrence " + occurrenceNumber + " exceeds the " + totalOccurrences + " matches found"
            );
        }
        byte[] edited = Arrays.copyOf(source, source.length);
        System.arraycopy(replacement, 0, edited, selectedOffset, replacement.length);
        return new PatchPlan(edited, selectedOffset, totalOccurrences, find.length);
    }

    public static String contextPreview(byte[] data, int offset, int needleLength, int radius) {
        if (data == null || data.length == 0 || offset < 0 || offset >= data.length) {
            return "";
        }
        int start = Math.max(0, offset - Math.max(0, radius));
        int end = Math.min(data.length, offset + Math.max(0, needleLength) + Math.max(0, radius));
        StringBuilder output = new StringBuilder(end - start);
        for (int index = start; index < end; index++) {
            int value = data[index] & 0xff;
            if (value >= 0x20 && value <= 0x7e) {
                output.append((char) value);
            } else {
                output.append('.');
            }
        }
        return output.toString();
    }

    public static byte[] requirePatchText(String text, String label) {
        String safe = text == null ? "" : text;
        if (safe.isEmpty()) {
            throw new IllegalArgumentException(label + " is empty");
        }
        byte[] bytes = safe.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_PATCH_TEXT_BYTES) {
            throw new IllegalArgumentException(label + " exceeds " + MAX_PATCH_TEXT_BYTES + " UTF-8 bytes");
        }
        for (byte value : bytes) {
            int unsigned = value & 0xff;
            if (unsigned == 0 || unsigned == '\n' || unsigned == '\r') {
                throw new IllegalArgumentException(label + " cannot contain NUL or line-break bytes");
            }
        }
        return bytes;
    }


    private static int countOccurrences(byte[] data, byte[] needle) {
        if (data == null || needle == null || needle.length == 0 || data.length < needle.length) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while (index <= data.length - needle.length) {
            boolean match = true;
            for (int needleIndex = 0; needleIndex < needle.length; needleIndex++) {
                if (data[index + needleIndex] != needle[needleIndex]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                count++;
                index += needle.length;
            } else {
                index++;
            }
        }
        return count;
    }

    private static List<Integer> findOffsets(byte[] data, byte[] needle, int maximumMatches) {
        List<Integer> offsets = new ArrayList<>();
        if (data == null || needle == null || needle.length == 0 || data.length < needle.length
                || maximumMatches <= 0) {
            return offsets;
        }
        int index = 0;
        while (index <= data.length - needle.length && offsets.size() < maximumMatches) {
            boolean match = true;
            for (int needleIndex = 0; needleIndex < needle.length; needleIndex++) {
                if (data[index + needleIndex] != needle[needleIndex]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                offsets.add(index);
                index += needle.length;
            } else {
                index++;
            }
        }
        return offsets;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data == null || prefix == null || data.length < prefix.length) {
            return false;
        }
        for (int index = 0; index < prefix.length; index++) {
            if (data[index] != prefix[index]) {
                return false;
            }
        }
        return true;
    }

    private static byte[] asciiLower(byte[] data) {
        byte[] lower = Arrays.copyOf(data, data.length);
        for (int index = 0; index < lower.length; index++) {
            int value = lower[index] & 0xff;
            if (value >= 'A' && value <= 'Z') {
                lower[index] = (byte) (value + ('a' - 'A'));
            }
        }
        return lower;
    }

    public static final class PatchPlan {
        private final byte[] editedBytes;
        private final int offset;
        private final int totalOccurrences;
        private final int patchedLength;

        PatchPlan(byte[] editedBytes, int offset, int totalOccurrences, int patchedLength) {
            this.editedBytes = editedBytes;
            this.offset = offset;
            this.totalOccurrences = totalOccurrences;
            this.patchedLength = patchedLength;
        }

        public byte[] getEditedBytes() {
            return editedBytes;
        }

        public int getOffset() {
            return offset;
        }

        public int getTotalOccurrences() {
            return totalOccurrences;
        }

        public int getPatchedLength() {
            return patchedLength;
        }
    }
}
