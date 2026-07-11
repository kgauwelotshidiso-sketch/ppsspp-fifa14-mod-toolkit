package com.tshidiso.ppssppmodtoolkit;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class ReplacementRules {
    private static final long SAFETY_BYTES = 32L * 1024L * 1024L;

    private ReplacementRules() {
    }

    public static List<AssetRecord> parseAssetIndex(String text) {
        List<AssetRecord> records = new ArrayList<>();
        if (text == null || text.trim().isEmpty()) {
            return records;
        }
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.trim().isEmpty() || line.startsWith("#")) {
                continue;
            }
            List<String> columns = parseCsvRow(line);
            if (i == 0 && !columns.isEmpty() && "path".equalsIgnoreCase(columns.get(0).trim())) {
                continue;
            }
            if (columns.size() != 3) {
                throw new IllegalArgumentException("Invalid asset index row " + (i + 1));
            }
            String path = requireSafeRelativePath(columns.get(0));
            long size = parseNonNegativeLong(columns.get(1), "asset size", i + 1);
            String hash = requireSha256(columns.get(2), i + 1);
            records.add(new AssetRecord(path, size, hash));
        }
        records.sort(assetComparator());
        return records;
    }

    public static List<AssetRecord> filterAssets(List<AssetRecord> records, String query) {
        List<AssetRecord> matches = new ArrayList<>();
        if (records == null) {
            return matches;
        }
        for (AssetRecord record : records) {
            if (record != null && matchesQuery(record, query)) {
                matches.add(record);
            }
        }
        matches.sort(assetComparator());
        return matches;
    }

    public static boolean matchesQuery(AssetRecord record, String query) {
        if (record == null) {
            return false;
        }
        if (query == null || query.trim().isEmpty()) {
            return true;
        }
        String path = record.getPath().toLowerCase(Locale.US);
        String name = record.getName().toLowerCase(Locale.US);
        String extension = record.getExtension().toLowerCase(Locale.US);
        String category = record.getCategory().toLowerCase(Locale.US);
        String[] tokens = query.trim().toLowerCase(Locale.US).split("\\s+");
        for (String token : tokens) {
            if (token.isEmpty()) {
                continue;
            }
            if (token.startsWith("ext:")) {
                String value = stripDot(token.substring(4));
                if (value.isEmpty() || !extension.equals(value)) {
                    return false;
                }
            } else if (token.startsWith("name:")) {
                String value = token.substring(5);
                if (value.isEmpty() || !name.contains(value)) {
                    return false;
                }
            } else if (token.startsWith("path:")) {
                String value = token.substring(5);
                if (value.isEmpty() || !path.contains(value)) {
                    return false;
                }
            } else if (token.startsWith("type:")) {
                String value = token.substring(5);
                if (value.isEmpty() || !category.contains(value)) {
                    return false;
                }
            } else if (token.startsWith("min:")) {
                long minimum = parseSizeExpression(token.substring(4));
                if (minimum < 0L || record.getSizeBytes() < minimum) {
                    return false;
                }
            } else if (token.startsWith("max:")) {
                long maximum = parseSizeExpression(token.substring(4));
                if (maximum < 0L || record.getSizeBytes() > maximum) {
                    return false;
                }
            } else if (!path.contains(token) && !category.contains(token)) {
                return false;
            }
        }
        return true;
    }

    public static String categoryForPath(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.US);
        String name = lower;
        int slash = lower.lastIndexOf('/');
        if (slash >= 0) {
            name = lower.substring(slash + 1);
        }
        if ("fifa.db".equals(name) || name.endsWith(".db")) {
            return "Database";
        }
        if (name.endsWith(".bh")) {
            return "BIG index";
        }
        if (name.endsWith(".big")) {
            return "BIG archive";
        }
        if (name.endsWith(".rx3") || name.endsWith(".fsh")) {
            return "Graphics/model";
        }
        if (name.endsWith(".loc")) {
            return "Localization";
        }
        if (name.endsWith(".ini") || name.endsWith(".xml") || name.endsWith(".cfg")
                || name.endsWith(".txt") || name.endsWith(".csv")) {
            return "Configuration/text";
        }
        if (name.endsWith(".bin") || name.endsWith(".dat")) {
            return "Binary data";
        }
        return "Other modding asset";
    }

    public static String requireSafeRelativePath(String value) {
        String path = value == null ? "" : value.trim();
        if (path.isEmpty() || path.startsWith("/") || path.endsWith("/") || path.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Unsafe or empty asset path");
        }
        String[] segments = path.split("/", -1);
        if (segments.length > 64) {
            throw new IllegalArgumentException("Asset path is too deep");
        }
        for (String segment : segments) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                throw new IllegalArgumentException("Unsafe asset path segment");
            }
            for (int index = 0; index < segment.length(); index++) {
                char character = segment.charAt(index);
                if (character == 0 || character < 0x20 || character == 0x7f) {
                    throw new IllegalArgumentException("Asset path contains a control character");
                }
            }
        }
        return path;
    }

    public static String fileName(String path) {
        String safe = requireSafeRelativePath(path);
        int slash = safe.lastIndexOf('/');
        return slash >= 0 ? safe.substring(slash + 1) : safe;
    }

    public static long requiredWorkspaceBytes(long currentBytes, long replacementBytes) {
        if (currentBytes < 0L || replacementBytes < 0L) {
            return -1L;
        }
        long total = safeAdd(currentBytes, safeMultiply(replacementBytes, 2L));
        return safeAdd(total, SAFETY_BYTES);
    }

    public static boolean hasEnoughSpace(long availableBytes, long requiredBytes) {
        return availableBytes >= 0L && requiredBytes >= 0L && availableBytes >= requiredBytes;
    }

    public static String updateAssetIndex(String text, String targetPath, long size, String sha256) {
        return updateCsvManifest(text, targetPath, size, sha256, 3, "working asset index");
    }

    public static String updateWorkingManifest(String text, String targetPath, long size, String sha256) {
        return updateCsvManifest(text, targetPath, size, sha256, 6, "working manifest");
    }

    private static String updateCsvManifest(
            String text,
            String targetPath,
            long size,
            String sha256,
            int minimumColumns,
            String label
    ) {
        String safePath = requireSafeRelativePath(targetPath);
        String safeHash = requireSha256(sha256, 0);
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException(label + " is empty");
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        StringBuilder output = new StringBuilder(normalized.length() + 64);
        int updated = 0;
        for (String line : lines) {
            if (!line.isEmpty() && !line.startsWith("#")) {
                List<String> columns = parseCsvRow(line);
                boolean header = !columns.isEmpty() && "path".equalsIgnoreCase(columns.get(0).trim());
                if (!header && columns.size() >= minimumColumns && safePath.equals(columns.get(0))) {
                    columns.set(1, Long.toString(size));
                    columns.set(2, safeHash);
                    line = joinCsv(columns);
                    updated++;
                }
            }
            output.append(line).append('\n');
        }
        if (updated != 1) {
            throw new IllegalArgumentException(label + " target count was " + updated + " instead of 1");
        }
        return trimOneTrailingExtraNewline(output.toString(), normalized.endsWith("\n"));
    }

    public static String updateWorkingHashes(String text, String targetPath, long size, String sha256) {
        String safePath = requireSafeRelativePath(targetPath);
        String safeHash = requireSha256(sha256, 0);
        if (text == null || text.isEmpty()) {
            throw new IllegalArgumentException("Working hash manifest is empty");
        }
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
                safePath.getBytes(StandardCharsets.UTF_8)
        );
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        StringBuilder output = new StringBuilder(normalized.length() + 64);
        int updated = 0;
        for (String line : lines) {
            if (!line.isEmpty() && !line.startsWith("#")) {
                String[] columns = line.split("\\t", -1);
                if (columns.length == 3 && encoded.equals(columns[0])) {
                    line = encoded + '\t' + size + '\t' + safeHash;
                    updated++;
                }
            }
            output.append(line).append('\n');
        }
        if (updated != 1) {
            throw new IllegalArgumentException("Working hash target count was " + updated + " instead of 1");
        }
        return trimOneTrailingExtraNewline(output.toString(), normalized.endsWith("\n"));
    }

    public static List<String> parseCsvRow(String line) {
        List<String> columns = new ArrayList<>();
        if (line == null) {
            columns.add("");
            return columns;
        }
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int index = 0; index < line.length(); index++) {
            char character = line.charAt(index);
            if (quoted) {
                if (character == '"') {
                    if (index + 1 < line.length() && line.charAt(index + 1) == '"') {
                        current.append('"');
                        index++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(character);
                }
            } else if (character == ',') {
                columns.add(current.toString());
                current.setLength(0);
            } else if (character == '"' && current.length() == 0) {
                quoted = true;
            } else {
                current.append(character);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("Unterminated CSV quote");
        }
        columns.add(current.toString());
        return columns;
    }

    public static String csv(String value) {
        String safe = value == null ? "" : value;
        if (safe.indexOf(',') < 0 && safe.indexOf('"') < 0 && safe.indexOf('\n') < 0
                && safe.indexOf('\r') < 0) {
            return safe;
        }
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private static String joinCsv(List<String> columns) {
        StringBuilder output = new StringBuilder();
        for (int index = 0; index < columns.size(); index++) {
            if (index > 0) {
                output.append(',');
            }
            output.append(csv(columns.get(index)));
        }
        return output.toString();
    }

    private static Comparator<AssetRecord> assetComparator() {
        return Comparator
                .comparingInt((AssetRecord record) -> priority(record.getPath()))
                .thenComparing(record -> record.getPath().toLowerCase(Locale.US));
    }

    private static int priority(String path) {
        String lower = path == null ? "" : path.toLowerCase(Locale.US);
        String name = lower;
        int slash = lower.lastIndexOf('/');
        if (slash >= 0) {
            name = lower.substring(slash + 1);
        }
        if ("fifa.db".equals(name)) {
            return 0;
        }
        if (name.endsWith(".db")) {
            return 1;
        }
        if (name.endsWith(".bh")) {
            return 2;
        }
        if (name.endsWith(".big")) {
            return 3;
        }
        if (name.endsWith(".rx3") || name.endsWith(".fsh")) {
            return 4;
        }
        if (name.endsWith(".loc")) {
            return 5;
        }
        return 10;
    }

    private static long parseSizeExpression(String value) {
        if (value == null) {
            return -1L;
        }
        String normalized = value.trim().toLowerCase(Locale.US);
        if (normalized.isEmpty()) {
            return -1L;
        }
        long multiplier = 1L;
        if (normalized.endsWith("kb")) {
            multiplier = 1024L;
            normalized = normalized.substring(0, normalized.length() - 2);
        } else if (normalized.endsWith("mb")) {
            multiplier = 1024L * 1024L;
            normalized = normalized.substring(0, normalized.length() - 2);
        } else if (normalized.endsWith("gb")) {
            multiplier = 1024L * 1024L * 1024L;
            normalized = normalized.substring(0, normalized.length() - 2);
        } else if (normalized.endsWith("b")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        try {
            double number = Double.parseDouble(normalized);
            if (!Double.isFinite(number) || number < 0.0) {
                return -1L;
            }
            double bytes = number * multiplier;
            if (bytes > Long.MAX_VALUE) {
                return -1L;
            }
            return (long) Math.ceil(bytes);
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private static long parseNonNegativeLong(String value, String label, int row) {
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed < 0L) {
                throw new NumberFormatException("negative");
            }
            return parsed;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid " + label + " on row " + row, error);
        }
    }

    private static String requireSha256(String value, int row) {
        String hash = value == null ? "" : value.trim().toLowerCase(Locale.US);
        if (hash.length() != 64) {
            throw new IllegalArgumentException("Invalid SHA-256" + rowSuffix(row));
        }
        for (int index = 0; index < hash.length(); index++) {
            char character = hash.charAt(index);
            if (!((character >= '0' && character <= '9') || (character >= 'a' && character <= 'f'))) {
                throw new IllegalArgumentException("Invalid SHA-256" + rowSuffix(row));
            }
        }
        return hash;
    }

    private static String rowSuffix(int row) {
        return row > 0 ? " on row " + row : "";
    }

    private static String stripDot(String value) {
        String stripped = value == null ? "" : value.trim();
        while (stripped.startsWith(".")) {
            stripped = stripped.substring(1);
        }
        return stripped;
    }

    private static String trimOneTrailingExtraNewline(String value, boolean shouldEndWithNewline) {
        String output = value;
        while (output.endsWith("\n\n")) {
            output = output.substring(0, output.length() - 1);
        }
        if (!shouldEndWithNewline && output.endsWith("\n")) {
            output = output.substring(0, output.length() - 1);
        }
        return output;
    }

    private static long safeAdd(long first, long second) {
        if (first > Long.MAX_VALUE - second) {
            return Long.MAX_VALUE;
        }
        return first + second;
    }

    private static long safeMultiply(long value, long multiplier) {
        if (value == 0L || multiplier == 0L) {
            return 0L;
        }
        if (value > Long.MAX_VALUE / multiplier) {
            return Long.MAX_VALUE;
        }
        return value * multiplier;
    }
}
