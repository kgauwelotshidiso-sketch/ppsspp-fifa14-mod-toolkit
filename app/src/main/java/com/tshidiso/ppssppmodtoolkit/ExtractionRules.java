package com.tshidiso.ppssppmodtoolkit;

public final class ExtractionRules {
    private static final long MIN_MARGIN_BYTES = 256L * 1024L * 1024L;
    private static final long WORKING_COPY_MARGIN_BYTES = 256L * 1024L * 1024L;

    private ExtractionRules() {
    }

    static long requiredForOriginalExtraction(long fileBytes) {
        if (fileBytes < 0L) {
            return -1L;
        }
        long percentageMargin = fileBytes / 20L;
        return safeAdd(fileBytes, Math.max(MIN_MARGIN_BYTES, percentageMargin));
    }

    static long requiredForWorkingCopy(long fileBytes) {
        if (fileBytes < 0L) {
            return -1L;
        }
        return safeAdd(fileBytes, WORKING_COPY_MARGIN_BYTES);
    }

    static boolean hasEnoughSpace(long availableBytes, long requiredBytes) {
        return availableBytes < 0L || requiredBytes < 0L || availableBytes >= requiredBytes;
    }

    static String csv(String value) {
        String safe = value == null ? "" : value;
        if (safe.indexOf(',') < 0
                && safe.indexOf('"') < 0
                && safe.indexOf('\r') < 0
                && safe.indexOf('\n') < 0) {
            return safe;
        }
        return '"' + safe.replace("\"", "\"\"") + '"';
    }

    private static long safeAdd(long left, long right) {
        if (left < 0L || right < 0L) {
            return -1L;
        }
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }
}
