package com.tshidiso.ppssppmodtoolkit;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class GameScanner {
    private static final long RAW_SCAN_LIMIT = 64L * 1024L * 1024L;
    private static final int RAW_CHUNK_SIZE = 256 * 1024;
    private static final int SMALL_FILE_LIMIT = 256 * 1024;
    private static final int FOLDER_DOCUMENT_LIMIT = 4_000;
    private static final int FOLDER_DEPTH_LIMIT = 18;
    private static final int CANDIDATE_LIMIT = 300;
    private static final int ZIP_ENTRY_LIMIT = 10_000;

    private static final String[] FIFA_14_IDS = new String[]{
            "ULUS-10655",
            "ULES-01586",
            "ULES-01587",
            "ULES-01588",
            "ULES-01589",
            "ULES-01590",
            "ULES-01591",
            "ULES-01592",
            "ULES-01593",
            "ULES-01594",
            "ULJM-06320",
            "NPJH-50813"
    };

    private static final Pattern CANDIDATE_PATTERN = Pattern.compile(
            "(?i)([A-Z0-9_./\\\\-]{2,96}\\.(?:DB|BIG|BH|RX3|FSH|INI|XML|CSV|TXT|LOC))(?:;1)?"
    );

    private GameScanner() {
    }

    public static String describeUri(Context context, Uri uri) {
        if (uri == null) {
            return "Not selected";
        }

        Metadata metadata = queryMetadata(context.getContentResolver(), uri);
        String name = metadata.name == null ? uri.getLastPathSegment() : metadata.name;
        if (name == null || name.trim().isEmpty()) {
            name = "Selected item";
        }

        if (metadata.size >= 0) {
            return name + "  •  " + formatBytes(metadata.size);
        }
        return name;
    }

    public static ScanReport scanGameFile(Context context, Uri uri) {
        List<String> details = new ArrayList<>();
        LinkedHashSet<String> candidates = new LinkedHashSet<>();

        if (uri == null) {
            return new ScanReport(
                    "Game source scan",
                    "No source selected",
                    "Choose a FIFA 14 PSP ISO, CSO/ZSO, PBP, or extracted folder first.",
                    details,
                    new ArrayList<>(candidates)
            );
        }

        ContentResolver resolver = context.getContentResolver();
        Metadata metadata = queryMetadata(resolver, uri);
        String name = safeName(metadata.name, uri);
        FileFormat format;

        try {
            format = detectFileFormat(resolver, uri, name);
        } catch (IOException | SecurityException error) {
            details.add(error.getClass().getSimpleName() + ": " + safeMessage(error));
            return new ScanReport(
                    "Game source scan",
                    "Read failed",
                    "The selected file could not be inspected. Re-select it and grant read access.",
                    details,
                    new ArrayList<>(candidates)
            );
        }

        ScanSignals signals = new ScanSignals();
        boolean sourceNameSuggestsFifa14 = looksLikeFifa14Name(name) || containsKnownTitleId(name);

        long bytesScanned = 0L;
        boolean deepScanAvailable = format == FileFormat.ISO
                || format == FileFormat.PBP
                || format == FileFormat.UNKNOWN;

        if (deepScanAvailable) {
            try {
                bytesScanned = scanRawStream(resolver, uri, signals, candidates);
            } catch (IOException | SecurityException error) {
                details.add("Raw marker scan stopped: " + safeMessage(error));
            }
        }

        details.add("Selected file: " + name);
        details.add("Detected container: " + format.label);
        if (metadata.size >= 0) {
            details.add("File size: " + formatBytes(metadata.size));
        }
        details.add("Safety mode: read-only; the original file was not changed");

        if (bytesScanned > 0) {
            details.add("Marker scan: first " + formatBytes(bytesScanned));
        } else if (format == FileFormat.CSO || format == FileFormat.ZSO || format == FileFormat.DAX) {
            details.add("Compressed image detected; deep decompression is reserved for the next scanner stage");
        }

        if (signals.pspGameDirectory || signals.paramSfo || signals.eboot) {
            details.add("PSP game structure markers found");
        }
        if (signals.titleId != null) {
            details.add("FIFA 14 title ID found: " + signals.titleId);
        }
        if (signals.fifa14Text) {
            details.add("FIFA 14 title text found inside the source");
        }

        String status;
        String summary;
        if (signals.titleId != null || signals.fifa14Text) {
            status = "FIFA 14 confirmed";
            summary = "This source contains a recognized FIFA 14 PSP identifier. It is ready for backup and deeper asset mapping in the next Phase 1 sprint.";
        } else if (sourceNameSuggestsFifa14) {
            status = "FIFA 14 probable";
            summary = "The filename strongly suggests FIFA 14, but the title could not yet be confirmed from readable internal markers.";
        } else if (format == FileFormat.ISO && (signals.pspGameDirectory || signals.paramSfo)) {
            status = "PSP game detected";
            summary = "A PSP ISO structure was detected, but it was not identified as FIFA 14.";
        } else if (format == FileFormat.CSO || format == FileFormat.ZSO) {
            status = "Compressed PSP image detected";
            summary = "The game image is valid enough for the next decompression-aware scanner, but FIFA 14 is not yet confirmed.";
        } else {
            status = "Unconfirmed source";
            summary = "The file was readable, but the current read-only scanner could not confirm a FIFA 14 PSP source.";
        }

        return new ScanReport(
                "Game source scan",
                status,
                summary,
                details,
                new ArrayList<>(candidates)
        );
    }

    public static ScanReport scanFolder(Context context, Uri treeUri) {
        List<String> details = new ArrayList<>();
        LinkedHashSet<String> candidates = new LinkedHashSet<>();

        if (treeUri == null) {
            return new ScanReport(
                    "Extracted folder scan",
                    "No folder selected",
                    "Choose an extracted PSP game folder first.",
                    details,
                    new ArrayList<>(candidates)
            );
        }

        ContentResolver resolver = context.getContentResolver();
        FolderSignals signals = new FolderSignals();
        int documentCount = 0;
        int directoryCount = 0;
        int fileCount = 0;
        long knownBytes = 0L;
        boolean truncated = false;
        boolean sourceNameSuggestsFifa14 = false;

        try {
            String rootDocumentId = DocumentsContract.getTreeDocumentId(treeUri);
            String rootName = queryTreeDocumentName(resolver, treeUri, rootDocumentId);
            if (rootName == null || rootName.trim().isEmpty()) {
                rootName = "Selected folder";
            }
            sourceNameSuggestsFifa14 = looksLikeFifa14Name(rootName) || containsKnownTitleId(rootName);

            ArrayDeque<FolderNode> pending = new ArrayDeque<>();
            Set<String> visited = new HashSet<>();
            pending.add(new FolderNode(rootDocumentId, rootName, 0));

            while (!pending.isEmpty()) {
                FolderNode parent = pending.removeFirst();
                if (!visited.add(parent.documentId)) {
                    continue;
                }
                if (parent.depth > FOLDER_DEPTH_LIMIT) {
                    truncated = true;
                    continue;
                }

                Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                        treeUri,
                        parent.documentId
                );

                String[] projection = new String[]{
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                        DocumentsContract.Document.COLUMN_MIME_TYPE,
                        DocumentsContract.Document.COLUMN_SIZE
                };

                try (Cursor cursor = resolver.query(childrenUri, projection, null, null, null)) {
                    if (cursor == null) {
                        continue;
                    }

                    int idIndex = cursor.getColumnIndexOrThrow(
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID
                    );
                    int nameIndex = cursor.getColumnIndexOrThrow(
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME
                    );
                    int mimeIndex = cursor.getColumnIndexOrThrow(
                            DocumentsContract.Document.COLUMN_MIME_TYPE
                    );
                    int sizeIndex = cursor.getColumnIndex(
                            DocumentsContract.Document.COLUMN_SIZE
                    );

                    while (cursor.moveToNext()) {
                        documentCount++;
                        if (documentCount > FOLDER_DOCUMENT_LIMIT) {
                            truncated = true;
                            pending.clear();
                            break;
                        }

                        String documentId = cursor.getString(idIndex);
                        String displayName = cursor.getString(nameIndex);
                        String mimeType = cursor.getString(mimeIndex);
                        long size = sizeIndex >= 0 && !cursor.isNull(sizeIndex)
                                ? cursor.getLong(sizeIndex)
                                : -1L;
                        String safeDisplayName = displayName == null ? "unnamed" : displayName;
                        String path = parent.path + "/" + safeDisplayName;

                        if (looksLikeFifa14Name(safeDisplayName) || containsKnownTitleId(safeDisplayName)) {
                            sourceNameSuggestsFifa14 = true;
                        }

                        if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                            directoryCount++;
                            if ("PSP_GAME".equalsIgnoreCase(safeDisplayName)) {
                                signals.scanSignals.pspGameDirectory = true;
                            }
                            pending.addLast(new FolderNode(documentId, path, parent.depth + 1));
                            continue;
                        }

                        fileCount++;
                        if (size >= 0) {
                            knownBytes += size;
                        }

                        String upperName = safeDisplayName.toUpperCase(Locale.ROOT);
                        if ("PARAM.SFO".equals(upperName)) {
                            signals.scanSignals.paramSfo = true;
                        }
                        if ("EBOOT.BIN".equals(upperName)) {
                            signals.scanSignals.eboot = true;
                        }

                        if (isModdingCandidate(safeDisplayName) && candidates.size() < CANDIDATE_LIMIT) {
                            candidates.add(path);
                        }

                        if ("PARAM.SFO".equals(upperName) || "UMD_DATA.BIN".equals(upperName)) {
                            Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(
                                    treeUri,
                                    documentId
                            );
                            inspectSmallDocument(resolver, documentUri, signals.scanSignals);
                        }
                    }
                }
            }

            details.add("Selected folder: " + rootName);
        } catch (IOException | SecurityException | IllegalArgumentException error) {
            details.add(error.getClass().getSimpleName() + ": " + safeMessage(error));
            return new ScanReport(
                    "Extracted folder scan",
                    "Scan failed",
                    "The folder could not be scanned. Re-select it and allow access when Android asks.",
                    details,
                    new ArrayList<>(candidates)
            );
        }

        details.add("Folders scanned: " + directoryCount);
        details.add("Files scanned: " + fileCount);
        if (knownBytes > 0) {
            details.add("Known file size total: " + formatBytes(knownBytes));
        }
        details.add("Modding candidates found: " + candidates.size());
        details.add("Safety mode: read-only; no file was changed");
        if (truncated) {
            details.add("Scan limit reached; results are partial to protect phone performance");
        }
        if (signals.scanSignals.titleId != null) {
            details.add("FIFA 14 title ID found: " + signals.scanSignals.titleId);
        }

        String status;
        String summary;
        if (signals.scanSignals.titleId != null || signals.scanSignals.fifa14Text) {
            status = "FIFA 14 confirmed";
            summary = "The extracted folder contains internal FIFA 14 markers and can be used for backup-first modding.";
        } else if (sourceNameSuggestsFifa14) {
            status = "FIFA 14 probable";
            summary = "The folder name or a contained item suggests FIFA 14, but PARAM.SFO or UMD metadata did not confirm it yet.";
        } else if (signals.scanSignals.pspGameDirectory
                && signals.scanSignals.paramSfo
                && signals.scanSignals.eboot) {
            status = "PSP game folder detected";
            summary = "A valid-looking PSP game structure was found, but FIFA 14 was not confirmed.";
        } else {
            status = "Folder scanned";
            summary = "The folder was readable. Review the detected asset candidates before the next phase adds backup and replacement operations.";
        }

        return new ScanReport(
                "Extracted folder scan",
                status,
                summary,
                details,
                new ArrayList<>(candidates)
        );
    }

    public static ScanReport scanPatchZip(Context context, Uri uri) {
        List<String> details = new ArrayList<>();
        LinkedHashSet<String> candidates = new LinkedHashSet<>();

        if (uri == null) {
            return new ScanReport(
                    "Patch package scan",
                    "No patch selected",
                    "Choose a ZIP patch package first.",
                    details,
                    new ArrayList<>(candidates)
            );
        }

        ContentResolver resolver = context.getContentResolver();
        Metadata metadata = queryMetadata(resolver, uri);
        String name = safeName(metadata.name, uri);

        try {
            byte[] start = readAt(resolver, uri, 0L, 4);
            if (!isZipMagic(start)) {
                details.add("Selected file: " + name);
                return new ScanReport(
                        "Patch package scan",
                        "Invalid ZIP",
                        "The selected file does not have a ZIP signature. Nothing was extracted.",
                        details,
                        new ArrayList<>(candidates)
                );
            }
        } catch (IOException | SecurityException error) {
            details.add(error.getClass().getSimpleName() + ": " + safeMessage(error));
            return new ScanReport(
                    "Patch package scan",
                    "Read failed",
                    "The selected patch could not be inspected.",
                    details,
                    new ArrayList<>(candidates)
            );
        }

        int entryCount = 0;
        int fileCount = 0;
        int directoryCount = 0;
        int unsafePathCount = 0;
        long declaredBytes = 0L;
        boolean truncated = false;

        try (InputStream raw = resolver.openInputStream(uri)) {
            if (raw == null) {
                throw new IOException("Android returned no input stream");
            }

            try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(raw))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    entryCount++;
                    if (entryCount > ZIP_ENTRY_LIMIT) {
                        truncated = true;
                        break;
                    }

                    String entryName = entry.getName() == null ? "unnamed" : entry.getName();
                    if (isUnsafeArchivePath(entryName)) {
                        unsafePathCount++;
                    }

                    if (entry.isDirectory()) {
                        directoryCount++;
                    } else {
                        fileCount++;
                        if (entry.getSize() > 0) {
                            declaredBytes += entry.getSize();
                        }
                        if (isModdingCandidate(entryName) && candidates.size() < CANDIDATE_LIMIT) {
                            candidates.add(entryName.replace('\\', '/'));
                        }
                    }
                    zip.closeEntry();
                }
            }
        } catch (IOException | SecurityException error) {
            details.add(error.getClass().getSimpleName() + ": " + safeMessage(error));
            return new ScanReport(
                    "Patch package scan",
                    "ZIP scan failed",
                    "The package started like a ZIP but could not be read safely. Nothing was extracted.",
                    details,
                    new ArrayList<>(candidates)
            );
        }

        details.add("Selected patch: " + name);
        if (metadata.size >= 0) {
            details.add("Compressed size: " + formatBytes(metadata.size));
        }
        details.add("ZIP entries: " + entryCount + " (" + fileCount + " files, " + directoryCount + " folders)");
        if (declaredBytes > 0) {
            details.add("Declared unpacked size: " + formatBytes(declaredBytes));
        }
        details.add("Modding candidates found: " + candidates.size());
        details.add("Path traversal checks: " + (unsafePathCount == 0 ? "passed" : "failed"));
        details.add("Safety mode: inspected only; nothing was extracted or installed");
        if (truncated) {
            details.add("Entry limit reached; package report is partial");
        }

        String status;
        String summary;
        if (unsafePathCount > 0) {
            status = "Unsafe package blocked";
            summary = "The ZIP contains one or more dangerous paths such as parent-directory traversal. It must not be installed.";
        } else if (fileCount == 0) {
            status = "Empty package";
            summary = "The ZIP is readable but contains no files.";
        } else {
            status = "Package structure passed";
            summary = "The ZIP passed the Phase 1 structural and path checks. Actual installation remains locked until backup and replacement verification are added.";
        }

        return new ScanReport(
                "Patch package scan",
                status,
                summary,
                details,
                new ArrayList<>(candidates)
        );
    }

    private static long scanRawStream(
            ContentResolver resolver,
            Uri uri,
            ScanSignals signals,
            LinkedHashSet<String> candidates
    ) throws IOException {
        long total = 0L;
        String carry = "";
        byte[] buffer = new byte[RAW_CHUNK_SIZE];

        try (InputStream raw = resolver.openInputStream(uri)) {
            if (raw == null) {
                throw new IOException("Android returned no input stream");
            }

            try (BufferedInputStream input = new BufferedInputStream(raw, RAW_CHUNK_SIZE)) {
                while (total < RAW_SCAN_LIMIT) {
                    int allowed = (int) Math.min(buffer.length, RAW_SCAN_LIMIT - total);
                    int read = input.read(buffer, 0, allowed);
                    if (read < 0) {
                        break;
                    }
                    if (read == 0) {
                        continue;
                    }

                    total += read;
                    String block = carry + new String(buffer, 0, read, StandardCharsets.ISO_8859_1);
                    inspectTextSignals(block, signals);
                    collectCandidates(block, candidates);

                    int carryLength = Math.min(192, block.length());
                    carry = block.substring(block.length() - carryLength);
                }
            }
        }

        return total;
    }

    private static void inspectSmallDocument(
            ContentResolver resolver,
            Uri uri,
            ScanSignals signals
    ) throws IOException {
        byte[] buffer = new byte[SMALL_FILE_LIMIT];
        int total = 0;

        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) {
                return;
            }
            while (total < buffer.length) {
                int read = input.read(buffer, total, buffer.length - total);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                total += read;
            }
        }

        if (total > 0) {
            inspectTextSignals(
                    new String(buffer, 0, total, StandardCharsets.ISO_8859_1),
                    signals
            );
        }
    }

    private static void inspectTextSignals(String text, ScanSignals signals) {
        if (text == null || text.isEmpty()) {
            return;
        }

        String upper = text.toUpperCase(Locale.ROOT);
        if (upper.contains("FIFA 14") || upper.contains("FIFA14")) {
            signals.fifa14Text = true;
        }
        if (upper.contains("PSP_GAME")) {
            signals.pspGameDirectory = true;
        }
        if (upper.contains("PARAM.SFO")) {
            signals.paramSfo = true;
        }
        if (upper.contains("EBOOT.BIN")) {
            signals.eboot = true;
        }

        if (signals.titleId == null) {
            for (String id : FIFA_14_IDS) {
                if (upper.contains(id) || upper.contains(id.replace('-', '_'))) {
                    signals.titleId = id;
                    signals.fifa14Text = true;
                    break;
                }
            }
        }
    }

    private static void collectCandidates(String block, LinkedHashSet<String> candidates) {
        if (candidates.size() >= CANDIDATE_LIMIT) {
            return;
        }

        Matcher matcher = CANDIDATE_PATTERN.matcher(block);
        while (matcher.find() && candidates.size() < CANDIDATE_LIMIT) {
            String candidate = matcher.group(1);
            if (candidate == null) {
                continue;
            }
            candidate = candidate.replace('\\', '/');
            int slash = candidate.lastIndexOf('/');
            if (slash >= 0 && slash < candidate.length() - 1) {
                candidate = candidate.substring(slash + 1);
            }
            candidate = candidate.replace(";1", "").trim();
            if (isModdingCandidate(candidate)) {
                candidates.add(candidate);
            }
        }
    }

    private static FileFormat detectFileFormat(
            ContentResolver resolver,
            Uri uri,
            String name
    ) throws IOException {
        byte[] start = readAt(resolver, uri, 0L, 16);

        if (startsWithAscii(start, "CISO")) {
            return FileFormat.CSO;
        }
        if (startsWithAscii(start, "ZISO")) {
            return FileFormat.ZSO;
        }
        if (startsWithAscii(start, "DAX\u0000")) {
            return FileFormat.DAX;
        }
        if (start.length >= 4
                && start[0] == 0
                && start[1] == 'P'
                && start[2] == 'B'
                && start[3] == 'P') {
            return FileFormat.PBP;
        }
        if (isZipMagic(start)) {
            return FileFormat.ZIP;
        }

        byte[] isoSignature = readAt(resolver, uri, 16L * 2048L + 1L, 5);
        if (startsWithAscii(isoSignature, "CD001")) {
            return FileFormat.ISO;
        }

        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".iso")) {
            return FileFormat.ISO;
        }
        if (lower.endsWith(".cso")) {
            return FileFormat.CSO;
        }
        if (lower.endsWith(".zso")) {
            return FileFormat.ZSO;
        }
        if (lower.endsWith(".pbp")) {
            return FileFormat.PBP;
        }
        if (lower.endsWith(".zip")) {
            return FileFormat.ZIP;
        }
        return FileFormat.UNKNOWN;
    }

    private static byte[] readAt(
            ContentResolver resolver,
            Uri uri,
            long offset,
            int length
    ) throws IOException {
        if (length <= 0) {
            return new byte[0];
        }

        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) {
                throw new IOException("Android returned no input stream");
            }

            long remaining = offset;
            while (remaining > 0) {
                long skipped = input.skip(remaining);
                if (skipped > 0) {
                    remaining -= skipped;
                    continue;
                }
                if (input.read() < 0) {
                    return new byte[0];
                }
                remaining--;
            }

            byte[] result = new byte[length];
            int total = 0;
            while (total < length) {
                int read = input.read(result, total, length - total);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                total += read;
            }
            return total == result.length ? result : Arrays.copyOf(result, total);
        }
    }

    private static Metadata queryMetadata(ContentResolver resolver, Uri uri) {
        String name = null;
        long size = -1L;

        try (Cursor cursor = resolver.query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    name = cursor.getString(nameIndex);
                }
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    size = cursor.getLong(sizeIndex);
                }
            }
        } catch (RuntimeException ignored) {
            // Some document providers do not expose OpenableColumns for tree URIs.
        }

        return new Metadata(name, size);
    }

    private static String queryTreeDocumentName(
            ContentResolver resolver,
            Uri treeUri,
            String documentId
    ) {
        Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
        try (Cursor cursor = resolver.query(
                documentUri,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                return cursor.getString(0);
            }
        } catch (RuntimeException ignored) {
            // Fall back to a generic label.
        }
        return null;
    }

    static boolean isModdingCandidate(String name) {
        if (name == null) {
            return false;
        }
        String normalized = name.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        String fileName = slash >= 0 ? normalized.substring(slash + 1) : normalized;
        String lower = fileName.toLowerCase(Locale.ROOT);

        return lower.equals("fifa.db")
                || lower.endsWith(".db")
                || lower.endsWith(".big")
                || lower.endsWith(".bh")
                || lower.endsWith(".rx3")
                || lower.endsWith(".fsh")
                || lower.endsWith(".ini")
                || lower.endsWith(".xml")
                || lower.endsWith(".csv")
                || lower.endsWith(".txt")
                || lower.endsWith(".loc");
    }

    static boolean isUnsafeArchivePath(String name) {
        if (name == null) {
            return true;
        }
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.startsWith("~")) {
            return true;
        }
        if (normalized.length() >= 3
                && Character.isLetter(normalized.charAt(0))
                && normalized.charAt(1) == ':'
                && normalized.charAt(2) == '/') {
            return true;
        }
        String[] parts = normalized.split("/");
        for (String part : parts) {
            if ("..".equals(part)) {
                return true;
            }
        }
        return false;
    }

    static boolean isZipMagic(byte[] bytes) {
        return bytes != null
                && bytes.length >= 4
                && bytes[0] == 'P'
                && bytes[1] == 'K'
                && ((bytes[2] == 3 && bytes[3] == 4)
                || (bytes[2] == 5 && bytes[3] == 6)
                || (bytes[2] == 7 && bytes[3] == 8));
    }

    private static boolean startsWithAscii(byte[] bytes, String value) {
        if (bytes == null || value == null || bytes.length < value.length()) {
            return false;
        }
        byte[] expected = value.getBytes(StandardCharsets.ISO_8859_1);
        for (int i = 0; i < expected.length; i++) {
            if (bytes[i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    static boolean containsKnownTitleId(String text) {
        if (text == null || text.trim().isEmpty()) {
            return false;
        }
        String upper = text.toUpperCase(Locale.ROOT);
        for (String id : FIFA_14_IDS) {
            if (upper.contains(id) || upper.contains(id.replace('-', '_'))) {
                return true;
            }
        }
        return false;
    }

    static boolean looksLikeFifa14Name(String name) {
        if (name == null) {
            return false;
        }
        String upper = name.toUpperCase(Locale.ROOT)
                .replace('_', ' ')
                .replace('-', ' ');
        return upper.contains("FIFA 14") || upper.contains("FIFA14");
    }

    private static String safeName(String name, Uri uri) {
        if (name != null && !name.trim().isEmpty()) {
            return name;
        }
        String last = uri == null ? null : uri.getLastPathSegment();
        return last == null || last.trim().isEmpty() ? "Selected file" : last;
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? "No additional details"
                : message;
    }

    public static String formatBytes(long bytes) {
        if (bytes < 0) {
            return "Unknown size";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = new String[]{"KB", "MB", "GB", "TB"};
        int unit = -1;
        do {
            value /= 1024.0;
            unit++;
        } while (value >= 1024.0 && unit < units.length - 1);
        return String.format(Locale.US, "%.2f %s", value, units[unit]);
    }

    private enum FileFormat {
        ISO("ISO 9660 image"),
        CSO("CSO compressed image"),
        ZSO("ZSO compressed image"),
        DAX("DAX compressed image"),
        PBP("PBP/EBOOT package"),
        ZIP("ZIP archive"),
        UNKNOWN("Unknown file type");

        private final String label;

        FileFormat(String label) {
            this.label = label;
        }
    }

    private static final class Metadata {
        private final String name;
        private final long size;

        private Metadata(String name, long size) {
            this.name = name;
            this.size = size;
        }
    }

    private static class ScanSignals {
        private boolean fifa14Text;
        private boolean pspGameDirectory;
        private boolean paramSfo;
        private boolean eboot;
        private String titleId;
    }

    private static final class FolderSignals {
        private final ScanSignals scanSignals = new ScanSignals();
    }

    private static final class FolderNode {
        private final String documentId;
        private final String path;
        private final int depth;

        private FolderNode(String documentId, String path, int depth) {
            this.documentId = documentId;
            this.path = path;
            this.depth = depth;
        }
    }
}
