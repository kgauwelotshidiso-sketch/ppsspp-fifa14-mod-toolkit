package com.tshidiso.ppssppmodtoolkit;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Phase 1G FIFA database inspection, verified schema decoding, and safe edited-copy builder.
 *
 * <p>This class never edits the selected working database. It creates a complete edited copy in
 * 30_patch_import. Phase 1D then performs its normal staging, rollback-copy, apply, and verification
 * sequence. Phase 1G schema decoding writes only a verified text report inside 90_logs.</p>
 */
public final class DatabaseLab {
    private static final int BUFFER_BYTES = 256 * 1024;
    private static final String WORKING_CONTAINER = "20_working_files";
    private static final String WORKING_ROOT = "source_working";
    private static final String PATCH_CONTAINER = "30_patch_import";
    private static final String LOGS_CONTAINER = "90_logs";
    private static final String EDITS_ROOT = "phase1e_database_edits";
    private static final String DECODER_REPORTS_ROOT = "phase1g_schema_decoder_reports";
    private static final String EDIT_MANIFEST = "database_edit_manifest.txt";

    private DatabaseLab() {
    }

    public static ScanReport inspectDatabase(
            Context context,
            Uri workspaceProjectUri,
            AssetRecord asset
    ) {
        List<String> details = new ArrayList<>();
        List<String> candidates = new ArrayList<>();
        if (!DatabaseRules.isDatabaseAsset(asset)) {
            return report(
                    "FIFA database inspection",
                    "Database target required",
                    "Select a .db working asset such as fifa.db first.",
                    details,
                    candidates
            );
        }
        try {
            LoadedDatabase loaded = loadVerifiedDatabase(context, workspaceProjectUri, asset, null);
            byte[] bytes = loaded.bytes;
            String format = DatabaseRules.detectFormat(bytes);
            List<String> markers = DatabaseRules.findKnownTableMarkers(bytes);
            details.add("Target path: " + asset.getPath());
            details.add("Format fingerprint: " + format);
            details.add("Size: " + GameScanner.formatBytes(bytes.length));
            details.add("SHA-256: " + loaded.sha256);
            details.add("Printable ASCII strings (minimum 4 bytes, capped count): "
                    + DatabaseRules.countPrintableStrings(bytes, 4, 100000));
            details.add("Known FIFA table-name markers found: " + markers.size());
            details.add("First 32-bit little-endian values: "
                    + DatabaseRules.firstIntegers(bytes, ByteOrder.LITTLE_ENDIAN, 8));
            details.add("First 32-bit big-endian values: "
                    + DatabaseRules.firstIntegers(bytes, ByteOrder.BIG_ENDIAN, 8));
            details.add("Header bytes (first 64):\n" + DatabaseRules.headerHex(bytes, 64));
            for (String marker : markers) {
                candidates.add("Table marker: " + marker);
            }
            String summary = markers.isEmpty()
                    ? "The file was verified and fingerprinted. No known table-name marker was found, so Phase 1G will not claim a verified schema block. Exact text search remains available."
                    : "The file was verified and known FIFA table-name markers were detected. Phase 1G verifies descriptor-word counts, independent aligned field-name lists, successor-table boundaries, and exact byte offsets while retaining same-length edited copies without touching the working database.";
            return report(
                    "FIFA database inspection",
                    "Database verified",
                    summary,
                    details,
                    candidates
            );
        } catch (IOException | IllegalArgumentException | SecurityException error) {
            details.add(safeMessage(error));
            return report(
                    "FIFA database inspection",
                    "Inspection stopped safely",
                    "No database file was changed.",
                    details,
                    candidates
            );
        }
    }

    public static ScanReport searchDatabaseText(
            Context context,
            Uri workspaceProjectUri,
            AssetRecord asset,
            String searchText
    ) {
        List<String> details = new ArrayList<>();
        List<String> candidates = new ArrayList<>();
        if (!DatabaseRules.isDatabaseAsset(asset)) {
            return report(
                    "Database text search",
                    "Database target required",
                    "Select a .db working asset first.",
                    details,
                    candidates
            );
        }
        try {
            byte[] searchBytes = DatabaseRules.requirePatchText(searchText, "Search text");
            LoadedDatabase loaded = loadVerifiedDatabase(context, workspaceProjectUri, asset, null);
            int total = DatabaseRules.countTextOccurrences(loaded.bytes, searchText);
            List<Integer> offsets = DatabaseRules.findTextOffsets(
                    loaded.bytes,
                    searchText,
                    DatabaseRules.MAX_REPORTED_OFFSETS
            );
            details.add("Target path: " + asset.getPath());
            details.add("Search bytes: " + searchBytes.length);
            details.add("Exact case-sensitive matches: " + total);
            details.add("SHA-256 checked before search: " + loaded.sha256);
            for (int index = 0; index < offsets.size(); index++) {
                int offset = offsets.get(index);
                candidates.add(
                        "Occurrence " + (index + 1)
                                + " at byte offset " + offset
                                + " (0x" + Integer.toHexString(offset) + ")"
                                + " | …" + DatabaseRules.contextPreview(
                                loaded.bytes,
                                offset,
                                searchBytes.length,
                                24
                        ) + "…"
                );
            }
            return report(
                    "Database text search",
                    total == 0 ? "No exact match" : "Exact matches found",
                    total == 0
                            ? "Try a different case-sensitive marker. No file was changed."
                            : "Use the occurrence number shown here when building an edited database copy.",
                    details,
                    candidates
            );
        } catch (IOException | IllegalArgumentException | SecurityException error) {
            details.add(safeMessage(error));
            return report(
                    "Database text search",
                    "Search stopped safely",
                    "No database file was changed.",
                    details,
                    candidates
            );
        }
    }

    public static OperationResult decodeTableStructure(
            Context context,
            Uri workspaceProjectUri,
            AssetRecord asset,
            String tableName,
            ReplacementEngine.ProgressListener listener
    ) {
        List<String> details = new ArrayList<>();
        if (!DatabaseRules.isDatabaseAsset(asset)) {
            return failed(
                    "FIFA verified schema decoder",
                    "Database target required",
                    "Select fifa.db or another verified .db working asset first.",
                    details
            );
        }
        ContentResolver resolver = context.getContentResolver();
        Uri reportFile = null;
        try {
            notifyProgress(listener, "Reading and verifying selected database", 0L, asset.getSizeBytes());
            LoadedDatabase loaded = loadVerifiedDatabase(context, workspaceProjectUri, asset, listener);
            notifyProgress(listener, "Mapping FIFA table structures read-only", 0L, loaded.bytes.length);
            FifaTableDecoder.DecodeResult decoded = FifaTableDecoder.decode(
                    loaded.bytes,
                    tableName
            );

            WorkspacePaths workspace = requireWorkspace(resolver, workspaceProjectUri);
            Uri reportsRoot = findOrCreateDirectory(
                    resolver,
                    workspace.logs,
                    DECODER_REPORTS_ROOT
            );
            String reportName = "schema_decoder_"
                    + decoded.getTableName()
                    + "_"
                    + newEditId()
                    + ".txt";
            reportFile = createFileExact(resolver, reportsRoot, reportName);
            String reportText = "phase=1G\n"
                    + "database_path_b64=" + b64(asset.getPath()) + "\n"
                    + "database_sha256=" + loaded.sha256 + "\n"
                    + "database_changed=false\n\n"
                    + decoded.getFullReportText();
            byte[] reportBytes = reportText.getBytes(StandardCharsets.UTF_8);
            writeBytes(
                    resolver,
                    reportFile,
                    reportBytes,
                    "Writing schema decoder report",
                    listener
            );
            LoadedBytes verifiedReport = readBytesAndHash(
                    resolver,
                    reportFile,
                    "Verifying schema decoder report",
                    listener
            );
            if (verifiedReport.bytes.length != reportBytes.length
                    || !sha256(reportBytes).equals(verifiedReport.sha256)) {
                throw new IOException("Saved schema decoder report failed SHA-256 verification");
            }

            details.addAll(decoded.getDetails());
            details.add("Database SHA-256 checked before decode: " + loaded.sha256);
            details.add("Saved report: 90_logs/" + DECODER_REPORTS_ROOT + "/" + reportName);
            details.add("Saved report SHA-256: " + verifiedReport.sha256);
            details.add("Working database changed: no");
            details.add("Protected original changed: no");
            details.add("ISO and verified backup changed: no");
            ScanReport report = report(
                    "FIFA verified schema decoder",
                    decoded.isMarkerFound()
                            ? "Verified schema probe complete"
                            : "Requested marker not found",
                    decoded.getSummary(),
                    details,
                    decoded.getFindings()
            );
            return new OperationResult(
                    report,
                    decoded.isMarkerFound(),
                    reportFile,
                    decoded.getTableName() + " | " + reportName
            );
        } catch (IOException | IllegalArgumentException | SecurityException error) {
            if (reportFile != null) {
                deleteQuietly(resolver, reportFile);
            }
            details.add(safeMessage(error));
            details.add("Working database changed: no");
            return failed(
                    "FIFA verified schema decoder",
                    "Schema decode stopped safely",
                    "No working database, protected original, ISO, or verified backup was changed.",
                    details
            );
        }
    }

    public static OperationResult createEditedDatabaseCopy(
            Context context,
            Uri workspaceProjectUri,
            AssetRecord asset,
            String findText,
            String replacementText,
            int occurrenceNumber,
            ReplacementEngine.ProgressListener listener
    ) {
        List<String> details = new ArrayList<>();
        if (!DatabaseRules.isDatabaseAsset(asset)) {
            return failed(
                    "Database edit builder",
                    "Database target required",
                    "Select a .db working asset such as fifa.db first.",
                    details
            );
        }
        ContentResolver resolver = context.getContentResolver();
        Uri transactionDirectory = null;
        try {
            notifyProgress(listener, "Reading and verifying selected database", 0L, asset.getSizeBytes());
            LoadedDatabase loaded = loadVerifiedDatabase(context, workspaceProjectUri, asset, listener);
            DatabaseRules.PatchPlan plan = DatabaseRules.buildPatchPlan(
                    loaded.bytes,
                    findText,
                    replacementText,
                    occurrenceNumber
            );
            byte[] edited = plan.getEditedBytes();
            String editedHash = sha256(edited);
            if (loaded.sha256.equals(editedHash)) {
                throw new IOException("The generated edited copy did not change the database hash");
            }

            WorkspacePaths workspace = requireWorkspace(resolver, workspaceProjectUri);
            Uri editsRoot = findOrCreateDirectory(resolver, workspace.patchImport, EDITS_ROOT);
            String editId = newEditId();
            transactionDirectory = createDirectoryExact(resolver, editsRoot, editId);
            Uri editedFile = createFileExact(resolver, transactionDirectory, asset.getName());

            writeBytes(
                    resolver,
                    editedFile,
                    edited,
                    "Writing edited database copy",
                    listener
            );
            LoadedBytes verifiedOutput = readBytesAndHash(
                    resolver,
                    editedFile,
                    "Verifying edited database copy",
                    listener
            );
            if (verifiedOutput.bytes.length != edited.length
                    || !editedHash.equals(verifiedOutput.sha256)) {
                throw new IOException("Edited database copy failed SHA-256 verification");
            }

            String manifest = buildManifest(
                    editId,
                    asset,
                    loaded.sha256,
                    editedHash,
                    findText,
                    replacementText,
                    occurrenceNumber,
                    plan
            );
            Uri manifestUri = createFileExact(resolver, transactionDirectory, EDIT_MANIFEST);
            writeBytes(
                    resolver,
                    manifestUri,
                    manifest.getBytes(StandardCharsets.UTF_8),
                    "Writing database edit manifest",
                    listener
            );

            details.add("Target path: " + asset.getPath());
            details.add("Database format fingerprint: " + DatabaseRules.detectFormat(loaded.bytes));
            details.add("Selected occurrence: " + occurrenceNumber + " of " + plan.getTotalOccurrences());
            details.add("Patched byte offset: " + plan.getOffset()
                    + " (0x" + Integer.toHexString(plan.getOffset()) + ")");
            details.add("Patched byte length: " + plan.getPatchedLength());
            details.add("Original SHA-256: " + loaded.sha256);
            details.add("Edited-copy SHA-256: " + editedHash);
            details.add("Edited copy location: 30_patch_import/" + EDITS_ROOT + "/"
                    + editId + "/" + asset.getName());
            details.add("Working database changed: no");
            details.add("Protected original changed: no");
            details.add("ISO and verified backup changed: no");
            ScanReport report = report(
                    "Database edit builder",
                    "Edited full database created and verified",
                    "The complete edited database copy is ready for the existing Phase 1D validation and rollback-protected replacement pipeline.",
                    details,
                    Collections.emptyList()
            );
            return new OperationResult(
                    report,
                    true,
                    editedFile,
                    editId + " | " + asset.getPath() + " | offset " + plan.getOffset()
            );
        } catch (IOException | IllegalArgumentException | SecurityException error) {
            if (transactionDirectory != null) {
                deleteQuietly(resolver, transactionDirectory);
            }
            details.add(safeMessage(error));
            details.add("Working database changed: no");
            return failed(
                    "Database edit builder",
                    "Edit generation stopped safely",
                    "No working database, protected original, ISO, or verified backup was changed.",
                    details
            );
        }
    }

    private static LoadedDatabase loadVerifiedDatabase(
            Context context,
            Uri workspaceProjectUri,
            AssetRecord asset,
            ReplacementEngine.ProgressListener listener
    ) throws IOException {
        if (workspaceProjectUri == null) {
            throw new IOException("Prepared workspace record is missing");
        }
        if (asset == null) {
            throw new IOException("Selected asset is missing");
        }
        if (asset.getSizeBytes() > DatabaseRules.MAX_DATABASE_BYTES) {
            throw new IOException(
                    "Database exceeds the Database Lab in-memory safety limit of "
                            + GameScanner.formatBytes(DatabaseRules.MAX_DATABASE_BYTES)
            );
        }
        ContentResolver resolver = context.getContentResolver();
        WorkspacePaths workspace = requireWorkspace(resolver, workspaceProjectUri);
        Uri target = resolvePath(resolver, workspace.workingRoot, asset.getPath());
        if (target == null || isDirectory(resolver, target)) {
            throw new IOException("Selected working database no longer exists");
        }
        LoadedBytes loaded = readBytesAndHash(
                resolver,
                target,
                "Reading selected database",
                listener
        );
        if (loaded.bytes.length != asset.getSizeBytes()
                || !loaded.sha256.equalsIgnoreCase(asset.getSha256())) {
            throw new IOException(
                    "Selected asset index is stale; search the working asset index again before database work"
            );
        }
        return new LoadedDatabase(target, loaded.bytes, loaded.sha256);
    }

    private static LoadedBytes readBytesAndHash(
            ContentResolver resolver,
            Uri uri,
            String stage,
            ReplacementEngine.ProgressListener listener
    ) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream raw = resolver.openInputStream(uri)) {
            if (raw == null) {
                throw new IOException("Android returned no database input stream");
            }
            try (BufferedInputStream input = new BufferedInputStream(raw, BUFFER_BYTES);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[BUFFER_BYTES];
                long total = 0L;
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    checkInterrupted();
                    if (read == 0) {
                        continue;
                    }
                    total += read;
                    if (total > DatabaseRules.MAX_DATABASE_BYTES) {
                        throw new IOException("Database exceeds the Database Lab in-memory safety limit");
                    }
                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    notifyProgress(listener, stage, total, 0L);
                }
                return new LoadedBytes(output.toByteArray(), hex(digest.digest()));
            }
        }
    }

    private static void writeBytes(
            ContentResolver resolver,
            Uri uri,
            byte[] data,
            String stage,
            ReplacementEngine.ProgressListener listener
    ) throws IOException {
        try (OutputStream raw = resolver.openOutputStream(uri, "w")) {
            if (raw == null) {
                throw new IOException("Android returned no output stream for the edited database");
            }
            try (BufferedOutputStream output = new BufferedOutputStream(raw, BUFFER_BYTES)) {
                int offset = 0;
                while (offset < data.length) {
                    checkInterrupted();
                    int length = Math.min(BUFFER_BYTES, data.length - offset);
                    output.write(data, offset, length);
                    offset += length;
                    notifyProgress(listener, stage, offset, data.length);
                }
                output.flush();
            }
        }
    }

    private static WorkspacePaths requireWorkspace(
            ContentResolver resolver,
            Uri workspaceProjectUri
    ) throws IOException {
        Uri workingContainer = requireChild(resolver, workspaceProjectUri, WORKING_CONTAINER);
        Uri workingRoot = requireChild(resolver, workingContainer, WORKING_ROOT);
        Uri patchImport = requireChild(resolver, workspaceProjectUri, PATCH_CONTAINER);
        Uri logs = requireChild(resolver, workspaceProjectUri, LOGS_CONTAINER);
        if (!isDirectory(resolver, workingRoot)
                || !isDirectory(resolver, patchImport)
                || !isDirectory(resolver, logs)) {
            throw new IOException("Workspace structure is incomplete");
        }
        return new WorkspacePaths(workingRoot, patchImport, logs);
    }

    private static Uri resolvePath(
            ContentResolver resolver,
            Uri root,
            String path
    ) throws IOException {
        String safe = ReplacementRules.requireSafeRelativePath(path);
        Uri current = root;
        String[] segments = safe.split("/");
        for (int index = 0; index < segments.length; index++) {
            current = findChild(resolver, current, segments[index]);
            if (current == null) {
                return null;
            }
            if (index < segments.length - 1 && !isDirectory(resolver, current)) {
                throw new IOException("Working path component is not a directory: " + segments[index]);
            }
        }
        return current;
    }

    private static Uri findOrCreateDirectory(
            ContentResolver resolver,
            Uri parent,
            String name
    ) throws IOException {
        Uri existing = findChild(resolver, parent, name);
        if (existing != null) {
            if (!isDirectory(resolver, existing)) {
                throw new IOException("Workspace item blocks required directory: " + name);
            }
            return existing;
        }
        return createDirectoryExact(resolver, parent, name);
    }

    private static Uri requireChild(
            ContentResolver resolver,
            Uri parent,
            String name
    ) throws IOException {
        Uri child = findChild(resolver, parent, name);
        if (child == null) {
            throw new IOException("Required workspace item is missing: " + name);
        }
        return child;
    }

    private static Uri findChild(
            ContentResolver resolver,
            Uri parentUri,
            String name
    ) throws IOException {
        final String parentId;
        try {
            parentId = DocumentsContract.getDocumentId(parentUri);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid workspace document URI", error);
        }
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, parentId);
        String[] projection = new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        try (Cursor cursor = resolver.query(children, projection, null, null, null)) {
            if (cursor == null) {
                throw new IOException("Android returned no workspace child listing");
            }
            int idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            if (idIndex < 0 || nameIndex < 0) {
                throw new IOException("Document provider omitted workspace metadata");
            }
            while (cursor.moveToNext()) {
                if (name.equals(cursor.getString(nameIndex))) {
                    return DocumentsContract.buildDocumentUriUsingTree(
                            parentUri,
                            cursor.getString(idIndex)
                    );
                }
            }
            return null;
        } catch (SecurityException | IllegalArgumentException error) {
            throw new IOException("Could not inspect workspace folder", error);
        }
    }

    private static Uri createDirectoryExact(
            ContentResolver resolver,
            Uri parent,
            String name
    ) throws IOException {
        validateSimpleName(name);
        if (findChild(resolver, parent, name) != null) {
            throw new IOException("Workspace path already exists: " + name);
        }
        Uri created = DocumentsContract.createDocument(
                resolver,
                parent,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name
        );
        if (created == null) {
            throw new IOException("Could not create database edit directory: " + name);
        }
        Metadata metadata = queryMetadata(resolver, created);
        if (!name.equals(metadata.name)
                || !DocumentsContract.Document.MIME_TYPE_DIR.equals(metadata.mimeType)) {
            deleteQuietly(resolver, created);
            throw new IOException("Provider changed the required database edit directory name");
        }
        return created;
    }

    private static Uri createFileExact(
            ContentResolver resolver,
            Uri parent,
            String name
    ) throws IOException {
        validateSimpleName(name);
        if (findChild(resolver, parent, name) != null) {
            throw new IOException("Workspace file already exists: " + name);
        }
        Uri created = DocumentsContract.createDocument(
                resolver,
                parent,
                ExtractionRules.exactNameMimeType(),
                name
        );
        if (created == null) {
            throw new IOException("Could not create database edit file: " + name);
        }
        Metadata metadata = queryMetadata(resolver, created);
        if (!name.equals(metadata.name)
                || DocumentsContract.Document.MIME_TYPE_DIR.equals(metadata.mimeType)) {
            deleteQuietly(resolver, created);
            throw new IOException("Provider changed the required database edit filename: " + name);
        }
        return created;
    }

    private static Metadata queryMetadata(ContentResolver resolver, Uri uri) throws IOException {
        String[] projection = new String[]{
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        try (Cursor cursor = resolver.query(uri, projection, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                throw new IOException("Document metadata is unavailable");
            }
            int nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            String name = nameIndex >= 0 && !cursor.isNull(nameIndex) ? cursor.getString(nameIndex) : "";
            String mimeType = mimeIndex >= 0 && !cursor.isNull(mimeIndex)
                    ? cursor.getString(mimeIndex)
                    : "application/octet-stream";
            return new Metadata(name, mimeType);
        } catch (SecurityException | IllegalArgumentException error) {
            throw new IOException("Could not read document metadata", error);
        }
    }

    private static boolean isDirectory(ContentResolver resolver, Uri uri) throws IOException {
        return DocumentsContract.Document.MIME_TYPE_DIR.equals(queryMetadata(resolver, uri).mimeType);
    }

    private static void validateSimpleName(String name) throws IOException {
        if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            throw new IOException("Unsafe workspace name");
        }
        for (int index = 0; index < name.length(); index++) {
            char character = name.charAt(index);
            if (character == 0 || character < 0x20 || character == 0x7f) {
                throw new IOException("Workspace name contains a control character");
            }
        }
    }

    private static void deleteQuietly(ContentResolver resolver, Uri uri) {
        try {
            DocumentsContract.deleteDocument(resolver, uri);
        } catch (IOException | RuntimeException ignored) {
            // Best-effort cleanup only.
        }
    }

    private static String buildManifest(
            String editId,
            AssetRecord asset,
            String originalHash,
            String editedHash,
            String findText,
            String replacementText,
            int occurrenceNumber,
            DatabaseRules.PatchPlan plan
    ) {
        return "phase=1F\n"
                + "state=EDITED_COPY_VERIFIED\n"
                + "edit_id=" + editId + "\n"
                + "created_utc=" + utcTimestamp() + "\n"
                + "target_path_b64=" + b64(asset.getPath()) + "\n"
                + "target_name_b64=" + b64(asset.getName()) + "\n"
                + "original_size=" + asset.getSizeBytes() + "\n"
                + "original_sha256=" + originalHash + "\n"
                + "edited_sha256=" + editedHash + "\n"
                + "occurrence_number=" + occurrenceNumber + "\n"
                + "total_occurrences=" + plan.getTotalOccurrences() + "\n"
                + "byte_offset=" + plan.getOffset() + "\n"
                + "patched_length=" + plan.getPatchedLength() + "\n"
                + "find_text_b64=" + b64(findText) + "\n"
                + "replacement_text_b64=" + b64(replacementText) + "\n"
                + "working_database_changed=false\n";
    }

    private static String newEditId() {
        byte[] random = new byte[4];
        new SecureRandom().nextBytes(random);
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new java.util.Date()) + "_" + hex(random);
    }

    private static String utcTimestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new java.util.Date());
    }

    private static String b64(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (value == null ? "" : value).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static MessageDigest newDigest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }

    private static String sha256(byte[] bytes) throws IOException {
        MessageDigest digest = newDigest();
        digest.update(bytes);
        return hex(digest.digest());
    }

    private static String hex(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            output.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return output.toString();
    }

    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Database operation was interrupted");
        }
    }

    private static void notifyProgress(
            ReplacementEngine.ProgressListener listener,
            String stage,
            long completed,
            long total
    ) {
        if (listener != null) {
            listener.onProgress(stage, completed, total);
        }
    }

    private static ScanReport report(
            String title,
            String status,
            String summary,
            List<String> details,
            List<String> candidates
    ) {
        return new ScanReport(title, status, summary, details, candidates);
    }

    private static OperationResult failed(
            String title,
            String status,
            String summary,
            List<String> details
    ) {
        return new OperationResult(
                report(title, status, summary, details, Collections.emptyList()),
                false,
                null,
                ""
        );
    }

    private static String safeMessage(Throwable error) {
        if (error == null) {
            return "Unknown database error";
        }
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return error.getClass().getSimpleName();
        }
        return error.getClass().getSimpleName() + ": " + message.trim();
    }

    private static final class WorkspacePaths {
        final Uri workingRoot;
        final Uri patchImport;
        final Uri logs;

        WorkspacePaths(Uri workingRoot, Uri patchImport, Uri logs) {
            this.workingRoot = workingRoot;
            this.patchImport = patchImport;
            this.logs = logs;
        }
    }

    private static final class LoadedBytes {
        final byte[] bytes;
        final String sha256;

        LoadedBytes(byte[] bytes, String sha256) {
            this.bytes = bytes;
            this.sha256 = sha256;
        }
    }

    private static final class LoadedDatabase {
        final Uri uri;
        final byte[] bytes;
        final String sha256;

        LoadedDatabase(Uri uri, byte[] bytes, String sha256) {
            this.uri = uri;
            this.bytes = bytes;
            this.sha256 = sha256;
        }
    }

    private static final class Metadata {
        final String name;
        final String mimeType;

        Metadata(String name, String mimeType) {
            this.name = name == null ? "" : name;
            this.mimeType = mimeType == null ? "application/octet-stream" : mimeType;
        }
    }
}
