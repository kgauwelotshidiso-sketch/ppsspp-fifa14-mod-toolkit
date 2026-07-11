package com.tshidiso.ppssppmodtoolkit;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStatVfs;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

public final class BackupEngine {
    private static final int COPY_BUFFER_BYTES = 1024 * 1024;
    private static final int MAX_FOLDER_ENTRIES = 20_000;
    private static final int MAX_FOLDER_DEPTH = 32;
    private static final long PROGRESS_INTERVAL_BYTES = 8L * 1024L * 1024L;

    public interface ProgressListener {
        void onProgress(String stage, long completedBytes, long totalBytes);
    }

    private BackupEngine() {
    }

    public static String describeTree(Context context, Uri treeUri) {
        if (treeUri == null) {
            return "Not selected";
        }
        try {
            Uri rootUri = rootDocumentUri(treeUri);
            Metadata metadata = queryMetadata(context.getContentResolver(), rootUri);
            return metadata.name == null || metadata.name.trim().isEmpty()
                    ? "Selected folder"
                    : metadata.name;
        } catch (RuntimeException error) {
            return "Selected folder";
        }
    }

    public static ScanReport checkBackupReadiness(
            Context context,
            Uri sourceFileUri,
            Uri sourceFolderUri,
            Uri backupTreeUri
    ) {
        List<String> details = new ArrayList<>();
        List<String> empty = new ArrayList<>();

        if (sourceFileUri == null && sourceFolderUri == null) {
            return report(
                    "Backup readiness",
                    "Game source missing",
                    "Choose the FIFA 14 game image or extracted folder first.",
                    details
            );
        }
        if (backupTreeUri == null) {
            return report(
                    "Backup readiness",
                    "Backup folder missing",
                    "Choose a writable backup destination folder first.",
                    details
            );
        }
        if (sourceFolderUri != null && isSameOrDescendantTree(sourceFolderUri, backupTreeUri)) {
            details.add("The backup destination is the source folder or is located inside it");
            return report(
                    "Backup readiness",
                    "Unsafe backup destination",
                    "Choose a backup folder outside the extracted game source to prevent recursive self-copying.",
                    details
            );
        }

        ContentResolver resolver = context.getContentResolver();
        long sourceBytes;
        int sourceFiles;
        try {
            if (sourceFolderUri != null) {
                Inventory inventory = inventoryFolder(resolver, sourceFolderUri);
                if (inventory.truncated) {
                    details.add("Folder entries discovered: " + inventory.entries.size());
                    return new ScanReport(
                            "Backup readiness",
                            "Folder scan limit reached",
                            "The extracted folder is too large to back up safely in this build. Nothing was copied.",
                            details,
                            empty
                    );
                }
                sourceBytes = inventory.unknownSizeFiles == 0 ? inventory.knownBytes : -1L;
                sourceFiles = inventory.fileCount;
                details.add("Source type: extracted PSP game folder");
                details.add("Files to back up: " + sourceFiles);
                details.add("Folders to reproduce: " + inventory.directoryCount);
                if (inventory.unknownSizeFiles > 0) {
                    details.add("Files with unknown reported size: " + inventory.unknownSizeFiles);
                }
            } else {
                Metadata source = queryMetadata(resolver, sourceFileUri);
                sourceBytes = resolveSize(resolver, sourceFileUri, source.size);
                sourceFiles = 1;
                details.add("Source type: game image file");
                details.add("Source file: " + safeName(source.name, "selected_game_image"));
            }
        } catch (IOException | SecurityException | IllegalArgumentException error) {
            details.add(error.getClass().getSimpleName() + ": " + safeMessage(error));
            return new ScanReport(
                    "Backup readiness",
                    "Source check failed",
                    "The selected source could not be fully read. Re-select it and grant access.",
                    details,
                    empty
            );
        }

        if (sourceBytes >= 0L) {
            details.add("Source data size: " + GameScanner.formatBytes(sourceBytes));
        } else {
            details.add("Source data size: provider did not report a reliable size");
        }

        DestinationProbe probe = probeDestination(resolver, backupTreeUri);
        if (!probe.writable) {
            details.add("Destination check: " + probe.message);
            return new ScanReport(
                    "Backup readiness",
                    "Destination is not writable",
                    "Choose a different folder and allow read/write access.",
                    details,
                    empty
            );
        }

        details.add("Destination write test: passed");
        if (probe.availableBytes >= 0L) {
            details.add("Destination free space: " + GameScanner.formatBytes(probe.availableBytes));
        } else {
            details.add("Destination free space: provider does not expose a reliable value");
        }

        long required = BackupRules.requiredBackupBytes(sourceBytes);
        if (required >= 0L) {
            details.add("Minimum recommended for backup: " + GameScanner.formatBytes(required));
        }

        if (required >= 0L && probe.availableBytes >= 0L && probe.availableBytes < required) {
            return new ScanReport(
                    "Backup readiness",
                    "Not enough free space",
                    "The destination does not have enough measured space for a verified copy plus the safety margin.",
                    details,
                    empty
            );
        }

        details.add("Planned verification: SHA-256 source hash compared with a second read of the backup");
        details.add("Planned output: timestamped backup folder plus verification manifest");
        details.add("Files scheduled: " + sourceFiles);
        return new ScanReport(
                "Backup readiness",
                "Ready for verified backup",
                "The source is readable and the selected destination passed its write check.",
                details,
                empty
        );
    }

    public static OperationResult createVerifiedBackup(
            Context context,
            Uri sourceFileUri,
            Uri sourceFolderUri,
            Uri backupTreeUri,
            ProgressListener listener
    ) {
        List<String> details = new ArrayList<>();
        List<String> empty = new ArrayList<>();
        Uri backupFolderUri = null;

        if (sourceFileUri == null && sourceFolderUri == null) {
            return failed("Verified backup", "Game source missing", "Choose a game source first.", details);
        }
        if (backupTreeUri == null) {
            return failed("Verified backup", "Backup folder missing", "Choose a backup destination first.", details);
        }
        if (sourceFolderUri != null && isSameOrDescendantTree(sourceFolderUri, backupTreeUri)) {
            details.add("The backup destination is the source folder or is located inside it");
            return failed(
                    "Verified backup",
                    "Unsafe backup destination",
                    "Choose a backup folder outside the extracted game source to prevent recursive self-copying.",
                    details
            );
        }

        ContentResolver resolver = context.getContentResolver();
        try {
            checkInterrupted();
            DestinationProbe probe = probeDestination(resolver, backupTreeUri);
            if (!probe.writable) {
                details.add("Destination check: " + probe.message);
                return failed(
                        "Verified backup",
                        "Destination is not writable",
                        "No backup was created.",
                        details
                );
            }

            Inventory sourceInventory = null;
            Metadata sourceFileMetadata = null;
            Metadata sourceFolderMetadata = null;
            long plannedSourceBytes;
            if (sourceFolderUri != null) {
                sourceInventory = inventoryFolder(resolver, sourceFolderUri);
                if (sourceInventory.truncated) {
                    throw new IOException(
                            "Source folder exceeds the safe scan limit of "
                                    + MAX_FOLDER_ENTRIES
                                    + " entries"
                    );
                }
                plannedSourceBytes = sourceInventory.unknownSizeFiles == 0
                        ? sourceInventory.knownBytes
                        : -1L;
                sourceFolderMetadata = queryMetadata(
                        resolver,
                        rootDocumentUri(sourceFolderUri)
                );
            } else {
                sourceFileMetadata = queryMetadata(resolver, sourceFileUri);
                plannedSourceBytes = resolveSize(
                        resolver,
                        sourceFileUri,
                        sourceFileMetadata.size
                );
            }
            enforceSpaceIfKnown(
                    probe.availableBytes,
                    BackupRules.requiredBackupBytes(plannedSourceBytes)
            );

            String timestamp = timestamp();
            String folderName = "FIFA14_Backup_" + timestamp;
            backupFolderUri = createDirectory(resolver, rootDocumentUri(backupTreeUri), folderName);

            StringBuilder manifest = new StringBuilder();
            manifest.append("PPSSPP Mod Toolkit verified backup\n");
            manifest.append("manifest_version=1\n");
            manifest.append("toolkit_phase=1C\n");
            manifest.append("created_utc=").append(utcTimestamp()).append('\n');
            manifest.append("verification=SHA-256\n");

            long totalBytes;
            int fileCount;
            String reference;

            if (sourceFolderUri != null) {
                Inventory inventory = sourceInventory;
                if (inventory == null) {
                    throw new IOException("Source folder inventory was not created");
                }
                totalBytes = plannedSourceBytes;
                fileCount = inventory.fileCount;

                String sourceRootName = BackupRules.sanitizeDocumentName(
                        sourceFolderMetadata == null ? null : sourceFolderMetadata.name,
                        "extracted_game"
                );
                Uri copiedRoot = createDirectory(resolver, backupFolderUri, sourceRootName);
                if (copiedRoot == null) {
                    throw new IOException("Could not create the extracted-game backup folder");
                }

                manifest.append("source_type=extracted_folder\n");
                manifest.append("source_name=").append(manifestSafe(sourceRootName)).append('\n');
                manifest.append("known_source_bytes=").append(inventory.knownBytes).append('\n');
                manifest.append("unknown_size_files=").append(inventory.unknownSizeFiles).append('\n');
                manifest.append("file_count=").append(fileCount).append('\n');
                manifest.append("directory_count=").append(inventory.directoryCount).append('\n');
                manifest.append("\nfiles\n");

                Map<String, Uri> targetDirectories = new HashMap<>();
                targetDirectories.put("", copiedRoot);
                long completed = 0L;

                Collections.sort(inventory.entries, Comparator
                        .comparingInt((Entry entry) -> entry.depth)
                        .thenComparing(entry -> entry.directory ? 0 : 1)
                        .thenComparing(entry -> entry.relativePath));

                for (Entry entry : inventory.entries) {
                    checkInterrupted();
                    String parentPath = parentPath(entry.relativePath);
                    Uri targetParent = targetDirectories.get(parentPath);
                    if (targetParent == null) {
                        throw new IOException("Backup directory mapping is missing for " + parentPath);
                    }

                    if (entry.directory) {
                        Uri targetDirectory = createDirectory(
                                resolver,
                                targetParent,
                                BackupRules.sanitizeDocumentName(entry.name, "folder")
                        );
                        if (targetDirectory == null) {
                            throw new IOException("Could not create folder: " + entry.relativePath);
                        }
                        targetDirectories.put(entry.relativePath, targetDirectory);
                        continue;
                    }

                    Uri targetFile = createFile(
                            resolver,
                            targetParent,
                            safeMime(entry.mimeType),
                            BackupRules.sanitizeDocumentName(entry.name, "file.bin")
                    );
                    if (targetFile == null) {
                        throw new IOException("Could not create backup file: " + entry.relativePath);
                    }

                    CopyResult copied = copyAndVerify(
                            resolver,
                            entry.uri,
                            targetFile,
                            entry.relativePath,
                            completed,
                            totalBytes,
                            listener
                    );
                    completed += copied.bytes;
                    manifest.append(manifestSafe(entry.relativePath))
                            .append('\t').append(copied.bytes)
                            .append('\t').append(copied.sourceSha256)
                            .append('\n');
                }

                manifest.append("verified=true\n");
                manifest.append("verified_file_count=").append(fileCount).append('\n');
                reference = folderName + " / " + sourceRootName;
                details.add("Backup type: extracted game folder");
                details.add("Files verified: " + fileCount);
                details.add("Known bytes copied: " + GameScanner.formatBytes(completed));
            } else {
                Metadata source = sourceFileMetadata;
                if (source == null) {
                    throw new IOException("Source file metadata was not created");
                }
                String sourceName = BackupRules.sanitizeDocumentName(
                        source.name,
                        "fifa14_game_image.iso"
                );
                totalBytes = plannedSourceBytes;
                fileCount = 1;

                Uri targetFile = createFile(
                        resolver,
                        backupFolderUri,
                        safeMime(source.mimeType),
                        sourceName
                );
                if (targetFile == null) {
                    throw new IOException("The document provider did not create the backup file");
                }

                manifest.append("source_type=game_image\n");
                manifest.append("source_name=").append(manifestSafe(sourceName)).append('\n');
                manifest.append("reported_source_bytes=").append(totalBytes).append('\n');
                manifest.append("file_count=1\n");
                manifest.append("\nfiles\n");

                CopyResult copied = copyAndVerify(
                        resolver,
                        sourceFileUri,
                        targetFile,
                        sourceName,
                        0L,
                        totalBytes,
                        listener
                );
                manifest.append(manifestSafe(sourceName))
                        .append('\t').append(copied.bytes)
                        .append('\t').append(copied.sourceSha256)
                        .append('\n');
                manifest.append("verified=true\n");
                manifest.append("verified_file_count=1\n");
                reference = folderName + " / " + sourceName;

                details.add("Backup type: game image file");
                details.add("File copied: " + sourceName);
                details.add("Verified size: " + GameScanner.formatBytes(copied.bytes));
                details.add("SHA-256: " + copied.sourceSha256);
            }

            Uri manifestUri = createFile(
                    resolver,
                    backupFolderUri,
                    "text/plain",
                    "verification_manifest.txt"
            );
            if (manifestUri == null) {
                throw new IOException("The verification manifest could not be created");
            }
            writeText(resolver, manifestUri, manifest.toString());

            details.add("Backup folder: " + folderName);
            details.add("Manifest: verification_manifest.txt");
            details.add("Verification result: every copied file matched its source SHA-256");
            details.add("Original source changed: no");

            ScanReport report = new ScanReport(
                    "Verified backup",
                    "Backup verified successfully",
                    "The backup was copied, reopened, hashed again, and matched against the original source.",
                    details,
                    empty
            );
            return new OperationResult(report, true, backupFolderUri, reference);
        } catch (IOException | SecurityException | IllegalArgumentException error) {
            if (backupFolderUri != null) {
                deleteQuietly(resolver, backupFolderUri);
            }
            details.add(error.getClass().getSimpleName() + ": " + safeMessage(error));
            details.add("Incomplete backup folder cleanup was requested");
            return failed(
                    "Verified backup",
                    "Backup failed safely",
                    "The original game source was not modified. Any incomplete destination folder was deleted where the provider allowed it.",
                    details
            );
        }
    }

    public static OperationResult prepareWorkspace(
            Context context,
            Uri sourceFileUri,
            Uri sourceFolderUri,
            Uri workspaceTreeUri,
            String verifiedBackupReference
    ) {
        List<String> details = new ArrayList<>();
        Uri projectUri = null;

        if (sourceFileUri == null && sourceFolderUri == null) {
            return failed("Workspace preparation", "Game source missing", "Choose a game source first.", details);
        }
        if (workspaceTreeUri == null) {
            return failed("Workspace preparation", "Workspace folder missing", "Choose a writable workspace destination first.", details);
        }
        if (verifiedBackupReference == null || verifiedBackupReference.trim().isEmpty()) {
            return failed(
                    "Workspace preparation",
                    "Verified backup required",
                    "Create a verified backup before preparing a modding workspace.",
                    details
            );
        }
        if (sourceFolderUri != null && isSameOrDescendantTree(sourceFolderUri, workspaceTreeUri)) {
            details.add("The workspace destination is the source folder or is located inside it");
            return failed(
                    "Workspace preparation",
                    "Unsafe workspace destination",
                    "Choose a workspace folder outside the extracted game source.",
                    details
            );
        }

        ContentResolver resolver = context.getContentResolver();
        try {
            DestinationProbe probe = probeDestination(resolver, workspaceTreeUri);
            if (!probe.writable) {
                details.add("Workspace check: " + probe.message);
                return failed(
                        "Workspace preparation",
                        "Workspace is not writable",
                        "Choose a different workspace folder and allow read/write access.",
                        details
                );
            }

            long sourceBytes;
            String sourceName;
            String sourceType;
            if (sourceFolderUri != null) {
                Inventory inventory = inventoryFolder(resolver, sourceFolderUri);
                if (inventory.truncated) {
                    throw new IOException("Source folder exceeds the safe scan limit");
                }
                sourceBytes = inventory.unknownSizeFiles == 0 ? inventory.knownBytes : -1L;
                Metadata metadata = queryMetadata(resolver, rootDocumentUri(sourceFolderUri));
                sourceName = safeName(metadata.name, "extracted_game");
                sourceType = "extracted_folder";
            } else {
                Metadata metadata = queryMetadata(resolver, sourceFileUri);
                sourceBytes = resolveSize(resolver, sourceFileUri, metadata.size);
                sourceName = safeName(metadata.name, "fifa14_game_image");
                sourceType = "game_image";
            }

            long recommended = BackupRules.recommendedWorkspaceBytes(sourceBytes);
            if (recommended >= 0L && probe.availableBytes >= 0L && probe.availableBytes < recommended) {
                details.add("Measured free space: " + GameScanner.formatBytes(probe.availableBytes));
                details.add("Recommended workspace space: " + GameScanner.formatBytes(recommended));
                return failed(
                        "Workspace preparation",
                        "Not enough measured workspace space",
                        "Extraction plus rebuilding can require roughly two copies of the game and a safety margin.",
                        details
                );
            }

            String projectName = "FIFA14_Workspace_" + timestamp();
            projectUri = createDirectory(resolver, rootDocumentUri(workspaceTreeUri), projectName);
            if (projectUri == null) {
                throw new IOException("Could not create the workspace project folder");
            }

            String[] folders = new String[]{
                    "00_backup_reference",
                    "10_extracted_original",
                    "20_working_files",
                    "30_patch_import",
                    "40_rebuilt_output",
                    "90_logs"
            };
            for (String folder : folders) {
                if (createDirectory(resolver, projectUri, folder) == null) {
                    throw new IOException("Could not create workspace folder: " + folder);
                }
            }

            StringBuilder manifest = new StringBuilder();
            manifest.append("PPSSPP Mod Toolkit workspace\n");
            manifest.append("manifest_version=1\n");
            manifest.append("toolkit_phase=1C\n");
            manifest.append("state=PREPARED_NOT_EXTRACTED\n");
            manifest.append("created_utc=").append(utcTimestamp()).append('\n');
            manifest.append("source_type=").append(sourceType).append('\n');
            manifest.append("source_name=").append(manifestSafe(sourceName)).append('\n');
            manifest.append("reported_source_bytes=").append(sourceBytes).append('\n');
            manifest.append("verified_backup_reference=")
                    .append(manifestSafe(verifiedBackupReference)).append('\n');
            manifest.append("measured_free_bytes=").append(probe.availableBytes).append('\n');
            manifest.append("recommended_workspace_bytes=").append(recommended).append('\n');
            manifest.append("replacement_enabled=false\n");
            manifest.append("next_step=extract source into 10_extracted_original with per-file manifest\n");

            Uri manifestUri = createFile(
                    resolver,
                    projectUri,
                    "text/plain",
                    "workspace_manifest.txt"
            );
            if (manifestUri == null) {
                throw new IOException("Could not create workspace_manifest.txt");
            }
            writeText(resolver, manifestUri, manifest.toString());

            details.add("Workspace project: " + projectName);
            details.add("Workspace write test: passed");
            if (probe.availableBytes >= 0L) {
                details.add("Measured free space: " + GameScanner.formatBytes(probe.availableBytes));
            }
            if (recommended >= 0L) {
                details.add("Recommended extraction/rebuild space: " + GameScanner.formatBytes(recommended));
            }
            details.add("Verified backup linked: " + verifiedBackupReference);
            details.add("Workspace state: prepared, not extracted");
            details.add("Game replacement remains disabled until Phase 1D");

            ScanReport report = new ScanReport(
                    "Workspace preparation",
                    "Workspace prepared",
                    "A controlled folder structure and manifest were created for Phase 1C extraction.",
                    details,
                    new ArrayList<>()
            );
            return new OperationResult(report, true, projectUri, projectName);
        } catch (IOException | SecurityException | IllegalArgumentException error) {
            if (projectUri != null) {
                deleteQuietly(resolver, projectUri);
            }
            details.add(error.getClass().getSimpleName() + ": " + safeMessage(error));
            return failed(
                    "Workspace preparation",
                    "Workspace preparation failed safely",
                    "No game file was changed. Partial workspace cleanup was requested.",
                    details
            );
        }
    }

    private static CopyResult copyAndVerify(
            ContentResolver resolver,
            Uri sourceUri,
            Uri targetUri,
            String label,
            long baseCompleted,
            long totalBytes,
            ProgressListener listener
    ) throws IOException {
        MessageDigest sourceDigest = newDigest();
        long bytesCopied = 0L;
        long nextProgress = PROGRESS_INTERVAL_BYTES;

        try (InputStream rawIn = resolver.openInputStream(sourceUri);
             OutputStream rawOut = resolver.openOutputStream(targetUri, "w")) {
            if (rawIn == null) {
                throw new IOException("Android returned no source input stream for " + label);
            }
            if (rawOut == null) {
                throw new IOException("Android returned no backup output stream for " + label);
            }

            try (BufferedInputStream input = new BufferedInputStream(rawIn, COPY_BUFFER_BYTES);
                 BufferedOutputStream output = new BufferedOutputStream(rawOut, COPY_BUFFER_BYTES)) {
                byte[] buffer = new byte[COPY_BUFFER_BYTES];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    checkInterrupted();
                    output.write(buffer, 0, read);
                    sourceDigest.update(buffer, 0, read);
                    bytesCopied += read;
                    if (listener != null && bytesCopied >= nextProgress) {
                        listener.onProgress(
                                "Copying and hashing " + label,
                                baseCompleted + bytesCopied,
                                totalBytes
                        );
                        nextProgress = bytesCopied + PROGRESS_INTERVAL_BYTES;
                    }
                }
                output.flush();
            }
        }

        String sourceHash = BackupRules.toHex(sourceDigest.digest());
        if (listener != null) {
            listener.onProgress(
                    "Re-reading backup for SHA-256 verification: " + label,
                    0L,
                    bytesCopied
            );
        }
        HashResult targetHash = hashUri(resolver, targetUri, label, bytesCopied, listener);
        if (targetHash.bytes != bytesCopied) {
            throw new IOException(
                    "Size mismatch after copy for " + label
                            + " (source " + bytesCopied + ", backup " + targetHash.bytes + ")"
            );
        }
        if (!BackupRules.hashesMatch(sourceHash, targetHash.sha256)) {
            throw new IOException("SHA-256 mismatch after copy for " + label);
        }
        return new CopyResult(bytesCopied, sourceHash, targetHash.sha256);
    }

    private static HashResult hashUri(
            ContentResolver resolver,
            Uri uri,
            String label,
            long expectedBytes,
            ProgressListener listener
    ) throws IOException {
        MessageDigest digest = newDigest();
        long bytes = 0L;
        long nextProgress = PROGRESS_INTERVAL_BYTES;
        try (InputStream raw = resolver.openInputStream(uri)) {
            if (raw == null) {
                throw new IOException("Android returned no verification input stream");
            }
            try (BufferedInputStream input = new BufferedInputStream(raw, COPY_BUFFER_BYTES)) {
                byte[] buffer = new byte[COPY_BUFFER_BYTES];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    checkInterrupted();
                    digest.update(buffer, 0, read);
                    bytes += read;
                    if (listener != null && bytes >= nextProgress) {
                        listener.onProgress(
                                "Verifying backup SHA-256: " + label,
                                bytes,
                                expectedBytes
                        );
                        nextProgress = bytes + PROGRESS_INTERVAL_BYTES;
                    }
                }
            }
        }
        if (listener != null) {
            listener.onProgress(
                    "Backup SHA-256 verification complete: " + label,
                    bytes,
                    expectedBytes
            );
        }
        return new HashResult(bytes, BackupRules.toHex(digest.digest()));
    }

    private static Inventory inventoryFolder(ContentResolver resolver, Uri treeUri) throws IOException {
        Uri rootUri = rootDocumentUri(treeUri);
        String rootId = DocumentsContract.getTreeDocumentId(treeUri);
        ArrayDeque<FolderNode> queue = new ArrayDeque<>();
        queue.add(new FolderNode(rootId, "", 0));

        Inventory inventory = new Inventory();
        while (!queue.isEmpty()) {
            checkInterrupted();
            FolderNode folder = queue.removeFirst();
            if (folder.depth > MAX_FOLDER_DEPTH) {
                inventory.truncated = true;
                break;
            }

            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri,
                    folder.documentId
            );
            try (Cursor cursor = resolver.query(
                    childrenUri,
                    new String[]{
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE,
                            DocumentsContract.Document.COLUMN_SIZE
                    },
                    null,
                    null,
                    null
            )) {
                if (cursor == null) {
                    throw new IOException("The document provider returned no folder listing");
                }

                int idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
                int sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);

                while (cursor.moveToNext()) {
                    if (inventory.entries.size() >= MAX_FOLDER_ENTRIES) {
                        inventory.truncated = true;
                        return inventory;
                    }

                    String documentId = cursor.getString(idIndex);
                    String name = nameIndex >= 0 && !cursor.isNull(nameIndex)
                            ? cursor.getString(nameIndex)
                            : "unnamed";
                    String mime = mimeIndex >= 0 && !cursor.isNull(mimeIndex)
                            ? cursor.getString(mimeIndex)
                            : "application/octet-stream";
                    long size = sizeIndex >= 0 && !cursor.isNull(sizeIndex)
                            ? cursor.getLong(sizeIndex)
                            : -1L;
                    boolean directory = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                    String safeName = BackupRules.sanitizeDocumentName(
                            name,
                            directory ? "folder" : "file.bin"
                    );
                    String relativePath = folder.relativePath.isEmpty()
                            ? safeName
                            : folder.relativePath + "/" + safeName;
                    Uri documentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId);
                    Entry entry = new Entry(
                            documentUri,
                            documentId,
                            safeName,
                            relativePath,
                            mime,
                            size,
                            directory,
                            folder.depth + 1
                    );
                    inventory.entries.add(entry);

                    if (directory) {
                        inventory.directoryCount++;
                        queue.addLast(new FolderNode(
                                documentId,
                                relativePath,
                                folder.depth + 1
                        ));
                    } else {
                        inventory.fileCount++;
                        if (size >= 0L) {
                            inventory.knownBytes = safeAdd(inventory.knownBytes, size);
                        } else {
                            inventory.unknownSizeFiles++;
                        }
                    }
                }
            }
        }
        return inventory;
    }

    private static DestinationProbe probeDestination(ContentResolver resolver, Uri treeUri) {
        Uri probeUri = null;
        try {
            Uri root = rootDocumentUri(treeUri);
            String name = ".ppsspp_toolkit_probe_" + System.currentTimeMillis() + ".tmp";
            probeUri = createFile(resolver, root, "application/octet-stream", name);
            if (probeUri == null) {
                return new DestinationProbe(false, -1L, "The provider did not create the write-test file");
            }

            try (OutputStream output = resolver.openOutputStream(probeUri, "w")) {
                if (output == null) {
                    return new DestinationProbe(false, -1L, "The provider returned no write-test output stream");
                }
                output.write(0);
                output.flush();
            }

            long available = -1L;
            try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(probeUri, "r")) {
                if (descriptor != null) {
                    try {
                        StructStatVfs stats = Os.fstatvfs(descriptor.getFileDescriptor());
                        available = safeMultiply(stats.f_bavail, stats.f_frsize);
                    } catch (ErrnoException | RuntimeException ignored) {
                        available = -1L;
                    }
                }
            }
            return new DestinationProbe(true, available, "Write test passed");
        } catch (IOException | SecurityException | IllegalArgumentException error) {
            return new DestinationProbe(false, -1L, safeMessage(error));
        } finally {
            if (probeUri != null) {
                deleteQuietly(resolver, probeUri);
            }
        }
    }

    private static boolean isSameOrDescendantTree(Uri sourceTree, Uri destinationTree) {
        if (sourceTree == null || destinationTree == null) {
            return false;
        }
        try {
            String sourceAuthority = sourceTree.getAuthority();
            String destinationAuthority = destinationTree.getAuthority();
            if (sourceAuthority == null || !sourceAuthority.equals(destinationAuthority)) {
                return false;
            }
            String sourceId = DocumentsContract.getTreeDocumentId(sourceTree);
            String destinationId = DocumentsContract.getTreeDocumentId(destinationTree);
            if (sourceId == null || destinationId == null) {
                return false;
            }
            return BackupRules.isSameOrDescendantDocumentId(sourceId, destinationId);
        } catch (IllegalArgumentException error) {
            return sourceTree.toString().equals(destinationTree.toString());
        }
    }

    private static Uri rootDocumentUri(Uri treeUri) {
        String treeId = DocumentsContract.getTreeDocumentId(treeUri);
        return DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId);
    }

    private static Uri createDirectory(ContentResolver resolver, Uri parent, String name)
            throws IOException {
        Uri created = DocumentsContract.createDocument(
                resolver,
                parent,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name
        );
        if (created == null) {
            throw new IOException("Could not create directory: " + name);
        }
        return created;
    }

    private static Uri createFile(ContentResolver resolver, Uri parent, String mime, String name)
            throws IOException {
        Uri created = DocumentsContract.createDocument(resolver, parent, mime, name);
        if (created == null) {
            throw new IOException("Could not create file: " + name);
        }
        return created;
    }

    private static void writeText(ContentResolver resolver, Uri uri, String text) throws IOException {
        try (OutputStream raw = resolver.openOutputStream(uri, "w")) {
            if (raw == null) {
                throw new IOException("Android returned no output stream for the manifest");
            }
            try (BufferedOutputStream output = new BufferedOutputStream(raw)) {
                output.write(text.getBytes(StandardCharsets.UTF_8));
                output.flush();
            }
        }
    }

    private static Metadata queryMetadata(ContentResolver resolver, Uri uri) {
        String name = null;
        String mime = resolver.getType(uri);
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
            // Fallbacks below handle providers with limited metadata support.
        }
        return new Metadata(name, mime, size);
    }

    private static long resolveSize(ContentResolver resolver, Uri uri, long reported) {
        if (reported >= 0L) {
            return reported;
        }
        try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "r")) {
            if (descriptor != null && descriptor.getStatSize() >= 0L) {
                return descriptor.getStatSize();
            }
        } catch (IOException | SecurityException ignored) {
            // Unknown size is handled safely by callers.
        }
        return -1L;
    }

    private static void enforceSpaceIfKnown(long available, long required) throws IOException {
        if (available >= 0L && required >= 0L && available < required) {
            throw new IOException(
                    "Insufficient destination space: available "
                            + GameScanner.formatBytes(available)
                            + ", recommended "
                            + GameScanner.formatBytes(required)
            );
        }
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static ScanReport report(String title, String status, String summary, List<String> details) {
        return new ScanReport(title, status, summary, details, new ArrayList<>());
    }

    private static OperationResult failed(
            String title,
            String status,
            String summary,
            List<String> details
    ) {
        return new OperationResult(
                new ScanReport(title, status, summary, details, new ArrayList<>()),
                false,
                null,
                ""
        );
    }

    private static String timestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        return format.format(System.currentTimeMillis());
    }

    private static String utcTimestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(System.currentTimeMillis());
    }

    private static String safeName(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String safeMime(String value) {
        return value == null || value.trim().isEmpty() || DocumentsContract.Document.MIME_TYPE_DIR.equals(value)
                ? "application/octet-stream"
                : value;
    }

    private static String manifestSafe(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    private static String parentPath(String relativePath) {
        int slash = relativePath.lastIndexOf('/');
        return slash < 0 ? "" : relativePath.substring(0, slash);
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? "No additional details"
                : message;
    }

    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Operation cancelled");
        }
    }

    private static void deleteQuietly(ContentResolver resolver, Uri uri) {
        try {
            DocumentsContract.deleteDocument(resolver, uri);
        } catch (IOException | RuntimeException ignored) {
            // Best-effort cleanup only. The provider may report a checked
            // FileNotFoundException when the document was already removed.
        }
    }

    private static long safeAdd(long left, long right) {
        return left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static long safeMultiply(long left, long right) {
        if (left < 0L || right < 0L) {
            return -1L;
        }
        if (left == 0L || right == 0L) {
            return 0L;
        }
        return left > Long.MAX_VALUE / right ? Long.MAX_VALUE : left * right;
    }

    private static final class Metadata {
        private final String name;
        private final String mimeType;
        private final long size;

        private Metadata(String name, String mimeType, long size) {
            this.name = name;
            this.mimeType = mimeType;
            this.size = size;
        }
    }

    private static final class DestinationProbe {
        private final boolean writable;
        private final long availableBytes;
        private final String message;

        private DestinationProbe(boolean writable, long availableBytes, String message) {
            this.writable = writable;
            this.availableBytes = availableBytes;
            this.message = message;
        }
    }

    private static final class CopyResult {
        private final long bytes;
        private final String sourceSha256;
        private final String backupSha256;

        private CopyResult(long bytes, String sourceSha256, String backupSha256) {
            this.bytes = bytes;
            this.sourceSha256 = sourceSha256;
            this.backupSha256 = backupSha256;
        }
    }

    private static final class HashResult {
        private final long bytes;
        private final String sha256;

        private HashResult(long bytes, String sha256) {
            this.bytes = bytes;
            this.sha256 = sha256;
        }
    }

    private static final class Inventory {
        private final List<Entry> entries = new ArrayList<>();
        private long knownBytes;
        private int fileCount;
        private int directoryCount;
        private int unknownSizeFiles;
        private boolean truncated;
    }

    private static final class Entry {
        private final Uri uri;
        private final String documentId;
        private final String name;
        private final String relativePath;
        private final String mimeType;
        private final long size;
        private final boolean directory;
        private final int depth;

        private Entry(
                Uri uri,
                String documentId,
                String name,
                String relativePath,
                String mimeType,
                long size,
                boolean directory,
                int depth
        ) {
            this.uri = uri;
            this.documentId = documentId;
            this.name = name;
            this.relativePath = relativePath;
            this.mimeType = mimeType;
            this.size = size;
            this.directory = directory;
            this.depth = depth;
        }
    }

    private static final class FolderNode {
        private final String documentId;
        private final String relativePath;
        private final int depth;

        private FolderNode(String documentId, String relativePath, int depth) {
            this.documentId = documentId;
            this.relativePath = relativePath;
            this.depth = depth;
        }
    }
}
