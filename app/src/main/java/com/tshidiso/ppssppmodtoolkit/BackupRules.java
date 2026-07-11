package com.tshidiso.ppssppmodtoolkit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

public final class BackupRules {
    static final long BACKUP_SAFETY_MARGIN_BYTES = 64L * 1024L * 1024L;
    static final long WORKSPACE_SAFETY_MARGIN_BYTES = 512L * 1024L * 1024L;

    private BackupRules() {
    }

    static long requiredBackupBytes(long sourceBytes) {
        if (sourceBytes < 0L) {
            return -1L;
        }
        return saturatingAdd(sourceBytes, BACKUP_SAFETY_MARGIN_BYTES);
    }

    static long recommendedWorkspaceBytes(long sourceBytes) {
        if (sourceBytes < 0L) {
            return -1L;
        }
        return saturatingAdd(saturatingMultiply(sourceBytes, 2L), WORKSPACE_SAFETY_MARGIN_BYTES);
    }

    static String sanitizeDocumentName(String name, String fallback) {
        String safeFallback = fallback == null || fallback.trim().isEmpty()
                ? "selected_item"
                : fallback.trim();
        if (name == null || name.trim().isEmpty()) {
            return safeFallback;
        }

        String sanitized = name.trim()
                .replace('/', '_')
                .replace('\\', '_')
                .replace('\u0000', '_');
        if (sanitized.equals(".") || sanitized.equals("..") || sanitized.isEmpty()) {
            return safeFallback;
        }
        return sanitized;
    }

    static boolean isSameOrDescendantDocumentId(String sourceId, String destinationId) {
        if (sourceId == null || destinationId == null) {
            return false;
        }
        String source = sourceId.endsWith("/")
                ? sourceId.substring(0, sourceId.length() - 1)
                : sourceId;
        return destinationId.equals(source) || destinationId.startsWith(source + "/");
    }

    static boolean hashesMatch(String sourceHash, String backupHash) {
        if (sourceHash == null || backupHash == null) {
            return false;
        }
        return MessageDigest.isEqual(
                sourceHash.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII),
                backupHash.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.US_ASCII)
        );
    }

    static String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return toHex(digest.digest(data == null ? new byte[0] : data));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    static String toHex(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte value : data) {
            out.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return out.toString();
    }

    private static long saturatingAdd(long left, long right) {
        if (left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private static long saturatingMultiply(long value, long multiplier) {
        if (value == 0L || multiplier == 0L) {
            return 0L;
        }
        if (value > Long.MAX_VALUE / multiplier) {
            return Long.MAX_VALUE;
        }
        return value * multiplier;
    }
}
