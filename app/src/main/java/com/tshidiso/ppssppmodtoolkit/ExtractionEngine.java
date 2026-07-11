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
import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Phase 1C controlled extraction and verified working-copy engine.
 *
 * <p>All writes are limited to the app-created workspace folders. The selected source and verified
 * backup are opened read-only. Every extracted/copied file is hashed while written, reopened, hashed
 * again, and compared before the operation is marked successful.</p>
 */
public final class ExtractionEngine {
    private static final int COPY_BUFFER_BYTES = 1024 * 1024;
    private static final long PROGRESS_INTERVAL_BYTES = 8L * 1024L * 1024L;
    private static final int MAX_FOLDER_ENTRIES = 50_000;
    private static final int MAX_FOLDER_DEPTH = 48;
    private static final int MAX_TEXT_BYTES = 24 * 1024 * 1024;

    private static final String ORIGINAL_CONTAINER = "10_extracted_original";
    private static final String WORKING_CONTAINER = "20_working_files";
    private static final String LOG_CONTAINER = "90_logs";
    private static final String ORIGINAL_ROOT = "source_original";
    private static final String WORKING_ROOT = "source_working";

    private static final String EXTRACTION_MANIFEST = "extraction_manifest.csv";
    private static final String EXTRACTION_HASHES = "extraction_hashes.tsv";
    private static final String ASSET_INDEX = "asset_index.csv";
    private static final String EXTRACTION_SUMMARY = "extraction_summary.txt";
    private static final String EXTRACTION_MARKER = ".phase1c_extraction_incomplete.txt";

    private static final String WORKING_MANIFEST = "working_manifest.csv";
    private static final String WORKING_HASHES = "working_hashes.tsv";
    private static final String WORKING_ASSET_INDEX = "working_asset_index.csv";
    private static final String WORKING_SUMMARY = "working_summary.txt";
    private static final String WORKING_MARKER = ".phase1c_working_copy_incomplete.txt";

    public interface ProgressListener {
        void onProgress(String stage, long completedBytes, long totalBytes);
    }

    private ExtractionEngine() {
    }

    public static ScanReport checkExtractionReadiness(
            Context context,
            Uri sourceFileUri,
            Uri sourceFolderUri,
            Uri workspaceProjectUri,
            String verifiedBackupReference
    ) {
        List<String> details = new ArrayList<>();
        List<String> candidates = new ArrayList<>();

        if (sourceFileUri == null && sourceFolderUri == null) {
            return report(
                    "Extraction readiness",
                    "Game source missing",
                    "Choose the FIFA 14 source first.",
                    details,
                    candidates
            );
        }
        if (workspaceProjectUri == null) {
            return report(
                    "Extraction readiness",
                    "Prepared workspace missing",
                    "Prepare a Phase 1B workspace before extraction.",
                    details,
                    candidates
            );
        }
        if (verifiedBackupReference == null || verifiedBackupReference.trim().isEmpty()) {
            return report(
                    "Extraction readiness",
                    "Verified backup required",
                    "Create a verified backup before extracting the source.",
                    details,
                    candidates
            );
        }

        ContentResolver resolver = context.getContentResolver();
        try {
            WorkspacePaths workspace = requireWorkspace(resolver, workspaceProjectUri);
            SourcePlan plan = buildSourcePlan(resolver, sourceFileUri, sourceFolderUri);
            candidates.addAll(plan.candidates);

            Uri existingRoot = findChild(resolver, workspace.originalContainer, ORIGINAL_ROOT);
            Uri existingManifest = findChild(resolver, workspace.logs, EXTRACTION_MANIFEST);
            Uri existingHashes = findChild(resolver, workspace.logs, EXTRACTION_HASHES);
            Uri existingIndex = findChild(resolver, workspace.logs, ASSET_INDEX);
            Uri existingSummary = findChild(resolver, workspace.logs, EXTRACTION_SUMMARY);
            Uri incompleteMarker = findChild(resolver, workspace.logs, EXTRACTION_MARKER);
            if (existingRoot != null
                    && existingManifest != null
                    && existingHashes != null
                    && existingIndex != null
                    && existingSummary != null
                    && incompleteMarker == null) {
                details.add("Extraction root: " + ORIGINAL_CONTAINER + "/" + ORIGINAL_ROOT);
                details.add("Extraction manifest: " + LOG_CONTAINER + "/" + EXTRACTION_MANIFEST);
                details.add("Indexed modding candidates: " + plan.candidates.size());
                return report(
                        "Extraction readiness",
                        "Original already extracted",
                        "The verified original extraction already exists. Create the working copy next.",
                        details,
                        candidates
                );
            }

            DestinationProbe probe = probeDestination(resolver, workspace.originalContainer);
            if (!probe.writable) {
                details.add("Workspace write test: " + probe.message);
                return report(
                        "Extraction readiness",
                        "Workspace is not writable",
                        "Re-select the workspace destination and allow read/write access.",
                        details,
                        candidates
                );
            }

            long required = ExtractionRules.requiredForOriginalExtraction(plan.fileBytes);
            details.add("Source type: " + plan.sourceType);
            details.add("Source name: " + plan.sourceName);
            if (!plan.volumeId.isEmpty()) {
                details.add("ISO volume ID: " + plan.volumeId);
                details.add("Directory encoding: " + (plan.joliet ? "Joliet" : "ISO-9660"));
            }
            details.add("Files to extract: " + plan.fileCount);
            details.add("Directories to create: " + plan.directoryCount);
            details.add("Extracted file data: " + GameScanner.formatBytes(plan.fileBytes));
            details.add("Exact modding candidates indexed: " + plan.candidates.size());
            details.add("Workspace write test: passed");
            if (probe.availableBytes >= 0L) {
                details.add("Measured free space: " + GameScanner.formatBytes(probe.availableBytes));
            }
            if (required >= 0L) {
                details.add("Minimum for verified extraction: " + GameScanner.formatBytes(required));
            }

            if (!ExtractionRules.hasEnoughSpace(probe.availableBytes, required)) {
                return report(
                        "Extraction readiness",
                        "Not enough measured free space",
                        "The workspace needs room for the extracted files, verification reads, manifests, and a safety margin.",
                        details,
                        candidates
                );
            }

            details.add("Verification plan: hash source bytes while writing, then reopen and hash every extracted file");
            details.add("Original source modification: disabled");
            details.add("Verified backup modification: disabled");
            return report(
                    "Extraction readiness",
                    "Ready for controlled extraction",
                    "The source and prepared workspace passed the Phase 1C safety checks.",
                    details,
                    candidates
            );
        } catch (IOException | SecurityException | IllegalArgumentException error) {
            details.add(error.getClass().getSimpleName() + ": " + safeMessage(error));
            return report(
                    "Extraction readiness",
                    "Readiness check failed safely",
                    "Nothing was written to the game source or verified backup.",
                    details,
                    candidates
            );
        }
    }

    public static OperationResult extractOriginal(
            Context context,
            Uri sourceFileUri,
            Uri sourceFolderUri,
            Uri workspaceProjectUri,
            String verifiedBackupReference,
            ProgressListener listener
    ) {
        List<String> details = new ArrayList<>();
        List<String> candidates = new ArrayList<>();
        ContentResolver resolver = context.getContentResolver();
        Uri originalRootUri = null;

        if (sourceFileUri == null && sourceFolderUri == null) {
            return failed("Controlled extraction", "Game source missing", "Choose a game source first.", details);
        }
        if (workspaceProjectUri == null) {
            return failed("Controlled extraction", "Prepared workspace missing", "Prepare a workspace first.", details);
        }
        if (verifiedBackupReference == null || verifiedBackupReference.trim().isEmpty()) {
            return failed(
                    "Controlled extraction",
                    "Verified backup required",
                    "Create a verified backup before extracting the source.",
                    details
            );
        }

        try {
            checkInterrupted();
            WorkspacePaths workspace = requireWorkspace(resolver, workspaceProjectUri);
            Uri existingRoot = findChild(resolver, workspace.originalContainer, ORIGINAL_ROOT);
            Uri existingManifest = findChild(resolver, workspace.logs, EXTRACTION_MANIFEST);
            Uri existingHashes = findChild(resolver, workspace.logs, EXTRACTION_HASHES);
            Uri existingIndex = findChild(resolver, workspace.logs, ASSET_INDEX);
            Uri existingSummary = findChild(resolver, workspace.logs, EXTRACTION_SUMMARY);
            Uri incompleteMarker = findChild(resolver, workspace.logs, EXTRACTION_MARKER);
            if (existingRoot != null
                    && existingManifest != null
                    && existingHashes != null
                    && existingIndex != null
                    && existingSummary != null
                    && incompleteMarker == null) {
                String reference = readExtractionReference(resolver, workspace.logs);
                details.add("Extraction root already exists: " + ORIGINAL_CONTAINER + "/" + ORIGINAL_ROOT);
                details.add("Existing extraction manifest was retained");
                return new OperationResult(
                        new ScanReport(
                                "Controlled extraction",
                                "Original already extracted",
                                "No duplicate extraction was created. Continue with the verified working copy.",
                                details,
                                new ArrayList<>()
                        ),
                        true,
                        existingRoot,
                        reference
                );
            }

            cleanupPartialExtraction(resolver, workspace);
            SourcePlan plan = buildSourcePlan(resolver, sourceFileUri, sourceFolderUri);
            candidates.addAll(plan.candidates);

            DestinationProbe probe = probeDestination(resolver, workspace.originalContainer);
            if (!probe.writable) {
                details.add("Workspace write test: " + probe.message);
                return failed(
                        "Controlled extraction",
                        "Workspace is not writable",
                        "No extraction folder was created.",
                        details
                );
            }
            long required = ExtractionRules.requiredForOriginalExtraction(plan.fileBytes);
            if (!ExtractionRules.hasEnoughSpace(probe.availableBytes, required)) {
                details.add("Measured free space: " + GameScanner.formatBytes(probe.availableBytes));
                details.add("Minimum required: " + GameScanner.formatBytes(required));
                return failed(
                        "Controlled extraction",
                        "Not enough free space",
                        "No extraction was started.",
                        details
                );
            }

            writeOrReplaceText(
                    resolver,
                    workspace.logs,
                    EXTRACTION_MARKER,
                    "state=INCOMPLETE\nstarted_utc=" + utcTimestamp() + "\n"
            );
            originalRootUri = createDirectoryExact(
                    resolver,
                    workspace.originalContainer,
                    ORIGINAL_ROOT
            );

            ExtractionOutput output;
            if (sourceFileUri != null) {
                output = extractIso(
                        resolver,
                        sourceFileUri,
                        originalRootUri,
                        listener
                );
            } else {
                output = extractFolder(
                        resolver,
                        sourceFolderUri,
                        originalRootUri,
                        listener
                );
            }

            writeOrReplaceText(
                    resolver,
                    workspace.logs,
                    EXTRACTION_MANIFEST,
                    output.csvManifest
            );
            writeOrReplaceText(
                    resolver,
                    workspace.logs,
                    EXTRACTION_HASHES,
                    output.machineHashes
            );
            writeOrReplaceText(
                    resolver,
                    workspace.logs,
                    ASSET_INDEX,
                    output.assetIndex
            );

            String reference = "source_original | "
                    + output.fileCount
                    + " files | "
                    + GameScanner.formatBytes(output.fileBytes)
                    + " | SHA-256 verified";
            String summary = buildExtractionSummary(
                    output,
                    verifiedBackupReference,
                    reference
            );
            writeOrReplaceText(
                    resolver,
                    workspace.logs,
                    EXTRACTION_SUMMARY,
                    summary
            );
            updateWorkspaceManifest(
                    resolver,
                    workspaceProjectUri,
                    "ORIGINAL_EXTRACTED",
                    verifiedBackupReference,
                    reference,
                    "",
                    output
            );
            deleteChildQuietly(resolver, workspace.logs, EXTRACTION_MARKER);

            details.add("Extraction root: " + ORIGINAL_CONTAINER + "/" + ORIGINAL_ROOT);
            details.add("Files extracted and verified: " + output.fileCount);
            details.add("Directories created: " + output.directoryCount);
            details.add("Extracted file data: " + GameScanner.formatBytes(output.fileBytes));
            details.add("Exact modding candidates indexed: " + output.candidates.size());
            details.add("Manifest: " + LOG_CONTAINER + "/" + EXTRACTION_MANIFEST);
            details.add("Asset index: " + LOG_CONTAINER + "/" + ASSET_INDEX);
            details.add("Per-file verification: every extracted file matched its source SHA-256");
            details.add("Original source changed: no");
            details.add("Verified backup changed: no");

            return new OperationResult(
                    new ScanReport(
                            "Controlled extraction",
                            "Original extraction verified",
                            "The game filesystem was extracted into the protected original area, indexed by exact path, and verified file by file.",
                            details,
                            output.candidates
                    ),
                    true,
                    originalRootUri,
                    reference
            );
        } catch (IOException | SecurityException | IllegalArgumentException error) {
            if (originalRootUri != null) {
                deleteQuietly(resolver, originalRootUri);
            }
            try {
                WorkspacePaths workspace = requireWorkspace(resolver, workspaceProjectUri);
                deleteChildQuietly(resolver, workspace.logs, EXTRACTION_MANIFEST);
                deleteChildQuietly(resolver, workspace.logs, EXTRACTION_HASHES);
                deleteChildQuietly(resolver, workspace.logs, ASSET_INDEX);
                deleteChildQuietly(resolver, workspace.logs, EXTRACTION_SUMMARY);
                deleteChildQuietly(resolver, workspace.logs, EXTRACTION_MARKER);
            } catch (IOException | RuntimeException ignored) {
                // Best-effort cleanup only.
            }
            details.add(error.getClass().getSimpleName() + ": " + safeMessage(error));
            details.add("Partial app-created extraction cleanup was requested");
            details.add("Original source changed: no");
            details.add("Verified backup changed: no");
            return failed(
                    "Controlled extraction",
                    "Extraction failed safely",
                    "The source and verified backup were not modified. The incomplete workspace extraction was removed where the provider allowed it.",
                    details
            );
        }
    }

    public static OperationResult createVerifiedWorkingCopy(
            Context context,
            Uri workspaceProjectUri,
            String verifiedBackupReference,
            String extractionReference,
            ProgressListener listener
    ) {
        List<String> details = new ArrayList<>();
        ContentResolver resolver = context.getContentResolver();
        Uri workingRootUri = null;

        if (workspaceProjectUri == null) {
            return failed("Verified working copy", "Prepared workspace missing", "Prepare a workspace first.", details);
        }
        if (verifiedBackupReference == null || verifiedBackupReference.trim().isEmpty()) {
            return failed("Verified working copy", "Verified backup required", "Create a verified backup first.", details);
        }
        if (extractionReference == null || extractionReference.trim().isEmpty()) {
            return failed(
                    "Verified working copy",
                    "Original extraction required",
                    "Extract and verify the original game filesystem first.",
                    details
            );
        }

        try {
            checkInterrupted();
            WorkspacePaths workspace = requireWorkspace(resolver, workspaceProjectUri);
            Uri originalRoot = findChild(resolver, workspace.originalContainer, ORIGINAL_ROOT);
            Uri extractionHashes = findChild(resolver, workspace.logs, EXTRACTION_HASHES);
            if (originalRoot == null || extractionHashes == null) {
                return failed(
                        "Verified working copy",
                        "Original extraction record missing",
                        "Run the controlled extraction again before creating a working copy.",
                        details
                );
            }

            Uri existingWorking = findChild(resolver, workspace.workingContainer, WORKING_ROOT);
            Uri existingManifest = findChild(resolver, workspace.logs, WORKING_MANIFEST);
            Uri existingHashes = findChild(resolver, workspace.logs, WORKING_HASHES);
            Uri existingIndex = findChild(resolver, workspace.logs, WORKING_ASSET_INDEX);
            Uri existingSummary = findChild(resolver, workspace.logs, WORKING_SUMMARY);
            Uri incompleteMarker = findChild(resolver, workspace.logs, WORKING_MARKER);
            if (existingWorking != null
                    && existingManifest != null
                    && existingHashes != null
                    && existingIndex != null
                    && existingSummary != null
                    && incompleteMarker == null) {
                String reference = readWorkingReference(resolver, workspace.logs);
                details.add("Working root already exists: " + WORKING_CONTAINER + "/" + WORKING_ROOT);
                details.add("Existing verified working copy was retained");
                return new OperationResult(
                        new ScanReport(
                                "Verified working copy",
                                "Working copy already verified",
                                "No duplicate working copy was created.",
                                details,
                                new ArrayList<>()
                        ),
                        true,
                        existingWorking,
                        reference
                );
            }

            cleanupPartialWorkingCopy(resolver, workspace);
            FolderInventory inventory = inventoryDocumentFolder(resolver, originalRoot);
            Map<String, ExpectedHash> expected = readMachineHashes(resolver, extractionHashes);
            if (inventory.fileCount != expected.size()) {
                throw new IOException(
                        "Protected original file count no longer matches the extraction manifest"
                );
            }

            DestinationProbe probe = probeDestination(resolver, workspace.workingContainer);
            if (!probe.writable) {
                details.add("Workspace write test: " + probe.message);
                return failed(
                        "Verified working copy",
                        "Working area is not writable",
                        "No working copy was created.",
                        details
                );
            }
            long required = ExtractionRules.requiredForWorkingCopy(inventory.knownBytes);
            if (!ExtractionRules.hasEnoughSpace(probe.availableBytes, required)) {
                details.add("Measured free space: " + GameScanner.formatBytes(probe.availableBytes));
                details.add("Minimum required: " + GameScanner.formatBytes(required));
                return failed(
                        "Verified working copy",
                        "Not enough free space",
                        "The verified original was retained and no working copy was created.",
                        details
                );
            }

            writeOrReplaceText(
                    resolver,
                    workspace.logs,
                    WORKING_MARKER,
                    "state=INCOMPLETE\nstarted_utc=" + utcTimestamp() + "\n"
            );
            workingRootUri = createDirectoryExact(
                    resolver,
                    workspace.workingContainer,
                    WORKING_ROOT
            );

            ExtractionOutput output = copyFolderToWorking(
                    resolver,
                    inventory,
                    expected,
                    workingRootUri,
                    listener
            );
            writeOrReplaceText(resolver, workspace.logs, WORKING_MANIFEST, output.csvManifest);
            writeOrReplaceText(resolver, workspace.logs, WORKING_HASHES, output.machineHashes);
            writeOrReplaceText(resolver, workspace.logs, WORKING_ASSET_INDEX, output.assetIndex);

            String reference = "source_working | "
                    + output.fileCount
                    + " files | "
                    + GameScanner.formatBytes(output.fileBytes)
                    + " | SHA-256 verified";
            writeOrReplaceText(
                    resolver,
                    workspace.logs,
                    WORKING_SUMMARY,
                    buildWorkingSummary(output, verifiedBackupReference, extractionReference, reference)
            );
            updateWorkspaceManifest(
                    resolver,
                    workspaceProjectUri,
                    "WORKING_COPY_READY",
                    verifiedBackupReference,
                    extractionReference,
                    reference,
                    output
            );
            deleteChildQuietly(resolver, workspace.logs, WORKING_MARKER);

            details.add("Working root: " + WORKING_CONTAINER + "/" + WORKING_ROOT);
            details.add("Files copied and verified: " + output.fileCount);
            details.add("Working file data: " + GameScanner.formatBytes(output.fileBytes));
            details.add("Original extraction revalidated against its saved hash manifest: passed");
            details.add("Working copy verified against protected original: passed");
            details.add("Working asset index: " + LOG_CONTAINER + "/" + WORKING_ASSET_INDEX);
            details.add("Protected original changed: no");
            details.add("Selected source changed: no");
            details.add("Full-file replacement remains disabled until the verified working copy is created");

            return new OperationResult(
                    new ScanReport(
                            "Verified working copy",
                            "Working copy ready",
                            "A complete verified working filesystem is ready for controlled full-file replacement in the next phase.",
                            details,
                            output.candidates
                    ),
                    true,
                    workingRootUri,
                    reference
            );
        } catch (IOException | SecurityException | IllegalArgumentException error) {
            if (workingRootUri != null) {
                deleteQuietly(resolver, workingRootUri);
            }
            try {
                WorkspacePaths workspace = requireWorkspace(resolver, workspaceProjectUri);
                deleteChildQuietly(resolver, workspace.logs, WORKING_MANIFEST);
                deleteChildQuietly(resolver, workspace.logs, WORKING_HASHES);
                deleteChildQuietly(resolver, workspace.logs, WORKING_ASSET_INDEX);
                deleteChildQuietly(resolver, workspace.logs, WORKING_SUMMARY);
                deleteChildQuietly(resolver, workspace.logs, WORKING_MARKER);
            } catch (IOException | RuntimeException ignored) {
                // Best-effort cleanup only.
            }
            details.add(error.getClass().getSimpleName() + ": " + safeMessage(error));
            details.add("Partial app-created working copy cleanup was requested");
            details.add("Protected original changed: no");
            return failed(
                    "Verified working copy",
                    "Working copy failed safely",
                    "The protected original extraction, source ISO, and verified backup were not modified.",
                    details
            );
        }
    }

    private static ExtractionOutput extractIso(
            ContentResolver resolver,
            Uri sourceUri,
            Uri targetRoot,
            ProgressListener listener
    ) throws IOException {
        try (AndroidRandomAccessSource source = new AndroidRandomAccessSource(resolver, sourceUri)) {
            Iso9660Reader.Volume volume = Iso9660Reader.read(source);
            Map<String, Uri> directories = new HashMap<>();
            directories.put("", targetRoot);

            StringBuilder csv = manifestHeader("iso_image", volume.getVolumeId());
            StringBuilder machine = machineHeader();
            StringBuilder assetIndex = assetHeader();
            List<String> candidates = new ArrayList<>();
            long totalWork = safeMultiplyForProgress(volume.getFileBytes(), 2L);
            long completedWork = 0L;

            for (Iso9660Reader.Entry entry : volume.getEntries()) {
                checkInterrupted();
                String parentPath = parentPath(entry.getPath());
                Uri parent = directories.get(parentPath);
                if (parent == null) {
                    throw new IOException("Missing extracted parent directory for " + entry.getPath());
                }

                if (entry.isDirectory()) {
                    Uri created = createDirectoryExact(resolver, parent, entry.getName());
                    directories.put(entry.getPath(), created);
                    continue;
                }

                Uri target = createFileExact(
                        resolver,
                        parent,
                        entry.getName()
                );
                FileVerification verification = copyIsoExtentAndVerify(
                        resolver,
                        source,
                        volume.getLogicalBlockSize(),
                        entry,
                        target,
                        completedWork,
                        totalWork,
                        listener
                );
                completedWork = safeAddProgress(
                        completedWork,
                        safeMultiplyForProgress(verification.bytes, 2L)
                );

                boolean candidate = GameScanner.isModdingCandidate(entry.getPath());
                appendManifestRow(
                        csv,
                        entry.getPath(),
                        verification.bytes,
                        verification.sha256,
                        candidate,
                        "iso",
                        safeMultiplyLong(entry.getExtentBlock(), volume.getLogicalBlockSize())
                );
                appendMachineRow(machine, entry.getPath(), verification.bytes, verification.sha256);
                if (candidate) {
                    candidates.add(entry.getPath());
                    appendAssetRow(assetIndex, entry.getPath(), verification.bytes, verification.sha256);
                }
            }

            return new ExtractionOutput(
                    "iso_image",
                    volume.getVolumeId(),
                    volume.isJoliet(),
                    volume.getFileCount(),
                    volume.getDirectoryCount(),
                    volume.getFileBytes(),
                    candidates,
                    csv.toString(),
                    machine.toString(),
                    assetIndex.toString()
            );
        }
    }

    private static ExtractionOutput extractFolder(
            ContentResolver resolver,
            Uri sourceTreeUri,
            Uri targetRoot,
            ProgressListener listener
    ) throws IOException {
        Uri sourceRoot = rootDocumentUri(sourceTreeUri);
        FolderInventory inventory = inventoryDocumentFolder(resolver, sourceRoot);
        Map<String, Uri> directories = new HashMap<>();
        directories.put("", targetRoot);

        StringBuilder csv = manifestHeader("extracted_folder", "");
        StringBuilder machine = machineHeader();
        StringBuilder assetIndex = assetHeader();
        List<String> candidates = new ArrayList<>();
        long totalWork = safeMultiplyForProgress(inventory.knownBytes, 2L);
        long completedWork = 0L;

        for (DocumentEntry entry : inventory.entries) {
            checkInterrupted();
            String parentPath = parentPath(entry.path);
            Uri parent = directories.get(parentPath);
            if (parent == null) {
                throw new IOException("Missing extracted parent directory for " + entry.path);
            }

            if (entry.directory) {
                Uri created = createDirectoryExact(resolver, parent, entry.name);
                directories.put(entry.path, created);
                continue;
            }

            Uri target = createFileExact(
                    resolver,
                    parent,
                    entry.name
            );
            FileVerification verification = copyDocumentAndVerify(
                    resolver,
                    entry.uri,
                    target,
                    entry.path,
                    completedWork,
                    totalWork,
                    listener,
                    null
            );
            completedWork = safeAddProgress(
                    completedWork,
                    safeMultiplyForProgress(verification.bytes, 2L)
            );

            boolean candidate = GameScanner.isModdingCandidate(entry.path);
            appendManifestRow(
                    csv,
                    entry.path,
                    verification.bytes,
                    verification.sha256,
                    candidate,
                    "folder",
                    -1L
            );
            appendMachineRow(machine, entry.path, verification.bytes, verification.sha256);
            if (candidate) {
                candidates.add(entry.path);
                appendAssetRow(assetIndex, entry.path, verification.bytes, verification.sha256);
            }
        }

        return new ExtractionOutput(
                "extracted_folder",
                "",
                false,
                inventory.fileCount,
                inventory.directoryCount,
                inventory.knownBytes,
                candidates,
                csv.toString(),
                machine.toString(),
                assetIndex.toString()
        );
    }

    private static ExtractionOutput copyFolderToWorking(
            ContentResolver resolver,
            FolderInventory inventory,
            Map<String, ExpectedHash> expected,
            Uri targetRoot,
            ProgressListener listener
    ) throws IOException {
        Map<String, Uri> directories = new HashMap<>();
        directories.put("", targetRoot);

        StringBuilder csv = manifestHeader("working_copy", "");
        StringBuilder machine = machineHeader();
        StringBuilder assetIndex = assetHeader();
        List<String> candidates = new ArrayList<>();
        long totalWork = safeMultiplyForProgress(inventory.knownBytes, 2L);
        long completedWork = 0L;
        int copiedFiles = 0;

        for (DocumentEntry entry : inventory.entries) {
            checkInterrupted();
            String parentPath = parentPath(entry.path);
            Uri parent = directories.get(parentPath);
            if (parent == null) {
                throw new IOException("Missing working parent directory for " + entry.path);
            }

            if (entry.directory) {
                Uri created = createDirectoryExact(resolver, parent, entry.name);
                directories.put(entry.path, created);
                continue;
            }

            ExpectedHash expectedHash = expected.get(entry.path);
            if (expectedHash == null) {
                throw new IOException("Protected original contains an unmanifested file: " + entry.path);
            }
            Uri target = createFileExact(
                    resolver,
                    parent,
                    entry.name
            );
            FileVerification verification = copyDocumentAndVerify(
                    resolver,
                    entry.uri,
                    target,
                    entry.path,
                    completedWork,
                    totalWork,
                    listener,
                    expectedHash
            );
            completedWork = safeAddProgress(
                    completedWork,
                    safeMultiplyForProgress(verification.bytes, 2L)
            );
            copiedFiles++;

            boolean candidate = GameScanner.isModdingCandidate(entry.path);
            appendManifestRow(
                    csv,
                    entry.path,
                    verification.bytes,
                    verification.sha256,
                    candidate,
                    "protected_original",
                    -1L
            );
            appendMachineRow(machine, entry.path, verification.bytes, verification.sha256);
            if (candidate) {
                candidates.add(entry.path);
                appendAssetRow(assetIndex, entry.path, verification.bytes, verification.sha256);
            }
        }

        if (copiedFiles != expected.size()) {
            throw new IOException("Working copy did not reproduce every manifest file");
        }

        return new ExtractionOutput(
                "working_copy",
                "",
                false,
                inventory.fileCount,
                inventory.directoryCount,
                inventory.knownBytes,
                candidates,
                csv.toString(),
                machine.toString(),
                assetIndex.toString()
        );
    }

    private static FileVerification copyIsoExtentAndVerify(
            ContentResolver resolver,
            AndroidRandomAccessSource source,
            int blockSize,
            Iso9660Reader.Entry entry,
            Uri target,
            long baseCompleted,
            long totalWork,
            ProgressListener listener
    ) throws IOException {
        MessageDigest sourceDigest = newDigest();
        long sourceOffset = safeMultiplyLong(entry.getExtentBlock(), blockSize);
        long remaining = entry.getSize();
        long copied = 0L;
        long nextProgress = PROGRESS_INTERVAL_BYTES;
        byte[] buffer = new byte[COPY_BUFFER_BYTES];

        try (OutputStream rawOut = resolver.openOutputStream(target, "w")) {
            if (rawOut == null) {
                throw new IOException("Android returned no output stream for " + entry.getPath());
            }
            try (BufferedOutputStream output = new BufferedOutputStream(rawOut, COPY_BUFFER_BYTES)) {
                while (remaining > 0L) {
                    checkInterrupted();
                    int requested = (int) Math.min(buffer.length, remaining);
                    int read = source.read(sourceOffset + copied, buffer, 0, requested);
                    if (read < 0) {
                        throw new IOException("Unexpected end of ISO while extracting " + entry.getPath());
                    }
                    if (read == 0) {
                        throw new IOException("ISO returned zero bytes while extracting " + entry.getPath());
                    }
                    output.write(buffer, 0, read);
                    sourceDigest.update(buffer, 0, read);
                    copied += read;
                    remaining -= read;
                    if (copied >= nextProgress) {
                        notifyProgress(listener, "Extracting original: " + entry.getPath(),
                                safeAddProgress(baseCompleted, copied), totalWork);
                        nextProgress = safeAddProgress(nextProgress, PROGRESS_INTERVAL_BYTES);
                    }
                }
                output.flush();
            }
        }
        if (copied != entry.getSize()) {
            throw new IOException("Extracted size mismatch for " + entry.getPath());
        }

        String sourceHash = hex(sourceDigest.digest());
        HashResult targetHash = hashDocument(
                resolver,
                target,
                "Verifying extracted file: " + entry.getPath(),
                safeAddProgress(baseCompleted, copied),
                totalWork,
                listener
        );
        if (targetHash.bytes != copied || !sourceHash.equals(targetHash.sha256)) {
            throw new IOException("SHA-256 verification failed for " + entry.getPath());
        }
        notifyProgress(listener, "Verified extracted file: " + entry.getPath(),
                safeAddProgress(safeAddProgress(baseCompleted, copied), copied), totalWork);
        return new FileVerification(copied, sourceHash);
    }

    private static FileVerification copyDocumentAndVerify(
            ContentResolver resolver,
            Uri source,
            Uri target,
            String path,
            long baseCompleted,
            long totalWork,
            ProgressListener listener,
            ExpectedHash expectedHash
    ) throws IOException {
        MessageDigest sourceDigest = newDigest();
        long copied = 0L;
        long nextProgress = PROGRESS_INTERVAL_BYTES;

        try (InputStream rawIn = resolver.openInputStream(source);
             OutputStream rawOut = resolver.openOutputStream(target, "w")) {
            if (rawIn == null) {
                throw new IOException("Android returned no input stream for " + path);
            }
            if (rawOut == null) {
                throw new IOException("Android returned no output stream for " + path);
            }
            try (BufferedInputStream input = new BufferedInputStream(rawIn, COPY_BUFFER_BYTES);
                 BufferedOutputStream output = new BufferedOutputStream(rawOut, COPY_BUFFER_BYTES)) {
                byte[] buffer = new byte[COPY_BUFFER_BYTES];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    checkInterrupted();
                    if (read == 0) {
                        continue;
                    }
                    output.write(buffer, 0, read);
                    sourceDigest.update(buffer, 0, read);
                    copied = safeAddLong(copied, read);
                    if (copied >= nextProgress) {
                        notifyProgress(listener, "Copying: " + path,
                                safeAddProgress(baseCompleted, copied), totalWork);
                        nextProgress = safeAddProgress(nextProgress, PROGRESS_INTERVAL_BYTES);
                    }
                }
                output.flush();
            }
        }

        String sourceHash = hex(sourceDigest.digest());
        if (expectedHash != null) {
            if (expectedHash.size != copied || !expectedHash.sha256.equals(sourceHash)) {
                throw new IOException("Protected original no longer matches its manifest: " + path);
            }
        }

        HashResult targetHash = hashDocument(
                resolver,
                target,
                "Verifying copied file: " + path,
                safeAddProgress(baseCompleted, copied),
                totalWork,
                listener
        );
        if (targetHash.bytes != copied || !sourceHash.equals(targetHash.sha256)) {
            throw new IOException("SHA-256 verification failed for " + path);
        }
        notifyProgress(listener, "Verified: " + path,
                safeAddProgress(safeAddProgress(baseCompleted, copied), copied), totalWork);
        return new FileVerification(copied, sourceHash);
    }

    private static HashResult hashDocument(
            ContentResolver resolver,
            Uri uri,
            String stage,
            long baseCompleted,
            long totalWork,
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
                while ((read = input.read(buffer)) >= 0) {
                    checkInterrupted();
                    if (read == 0) {
                        continue;
                    }
                    digest.update(buffer, 0, read);
                    bytes = safeAddLong(bytes, read);
                    if (bytes >= nextProgress) {
                        notifyProgress(listener, stage,
                                safeAddProgress(baseCompleted, bytes), totalWork);
                        nextProgress = safeAddProgress(nextProgress, PROGRESS_INTERVAL_BYTES);
                    }
                }
            }
        }
        return new HashResult(bytes, hex(digest.digest()));
    }

    private static SourcePlan buildSourcePlan(
            ContentResolver resolver,
            Uri sourceFileUri,
            Uri sourceFolderUri
    ) throws IOException {
        if (sourceFileUri != null) {
            String sourceName = queryName(resolver, sourceFileUri, "selected_game.iso");
            String lower = sourceName.toLowerCase(Locale.ROOT);
            if (lower.endsWith(".cso") || lower.endsWith(".zso") || lower.endsWith(".dax")) {
                throw new IOException(
                        "Phase 1C extraction supports uncompressed ISO files. Convert or select the verified ISO source; compressed CSO/ZSO/DAX extraction is not enabled yet."
                );
            }
            if (lower.endsWith(".pbp")) {
                throw new IOException("PBP extraction is not enabled in Phase 1C");
            }

            try (AndroidRandomAccessSource source = new AndroidRandomAccessSource(resolver, sourceFileUri)) {
                Iso9660Reader.Volume volume = Iso9660Reader.read(source);
                List<String> candidates = new ArrayList<>();
                for (Iso9660Reader.Entry entry : volume.getEntries()) {
                    if (!entry.isDirectory() && GameScanner.isModdingCandidate(entry.getPath())) {
                        candidates.add(entry.getPath());
                    }
                }
                return new SourcePlan(
                        "ISO game image",
                        sourceName,
                        volume.getVolumeId(),
                        volume.isJoliet(),
                        volume.getFileCount(),
                        volume.getDirectoryCount(),
                        volume.getFileBytes(),
                        candidates
                );
            }
        }

        Uri root = rootDocumentUri(sourceFolderUri);
        FolderInventory inventory = inventoryDocumentFolder(resolver, root);
        String sourceName = queryDocumentMetadata(resolver, root).name;
        List<String> candidates = new ArrayList<>();
        for (DocumentEntry entry : inventory.entries) {
            if (!entry.directory && GameScanner.isModdingCandidate(entry.path)) {
                candidates.add(entry.path);
            }
        }
        return new SourcePlan(
                "Extracted game folder",
                safeName(sourceName, "extracted_game"),
                "",
                false,
                inventory.fileCount,
                inventory.directoryCount,
                inventory.knownBytes,
                candidates
        );
    }

    private static WorkspacePaths requireWorkspace(
            ContentResolver resolver,
            Uri projectUri
    ) throws IOException {
        Metadata project = queryDocumentMetadata(resolver, projectUri);
        if (!DocumentsContract.Document.MIME_TYPE_DIR.equals(project.mimeType)) {
            throw new IOException("Saved workspace project is no longer a directory");
        }
        Uri original = findChild(resolver, projectUri, ORIGINAL_CONTAINER);
        Uri working = findChild(resolver, projectUri, WORKING_CONTAINER);
        Uri logs = findChild(resolver, projectUri, LOG_CONTAINER);
        if (original == null || working == null || logs == null) {
            throw new IOException("Prepared workspace folders are missing; prepare a new workspace");
        }
        if (!isDirectory(resolver, original)
                || !isDirectory(resolver, working)
                || !isDirectory(resolver, logs)) {
            throw new IOException("A required workspace path is not a directory");
        }
        return new WorkspacePaths(projectUri, original, working, logs);
    }

    private static FolderInventory inventoryDocumentFolder(
            ContentResolver resolver,
            Uri rootDocumentUri
    ) throws IOException {
        FolderInventory inventory = new FolderInventory();
        ArrayDeque<FolderNode> queue = new ArrayDeque<>();
        queue.add(new FolderNode(rootDocumentUri, "", 0));

        while (!queue.isEmpty()) {
            checkInterrupted();
            FolderNode folder = queue.removeFirst();
            if (folder.depth > MAX_FOLDER_DEPTH) {
                throw new IOException("Folder nesting exceeds the safe depth limit");
            }

            String parentId = DocumentsContract.getDocumentId(folder.uri);
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    folder.uri,
                    parentId
            );
            String[] projection = new String[]{
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE
            };

            try (Cursor cursor = resolver.query(childrenUri, projection, null, null, null)) {
                if (cursor == null) {
                    throw new IOException("Android returned no folder listing for " + folder.path);
                }
                int idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
                int nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
                int mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
                int sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
                if (idIndex < 0 || nameIndex < 0 || mimeIndex < 0) {
                    throw new IOException("Document provider omitted required folder metadata");
                }

                while (cursor.moveToNext()) {
                    if (inventory.entries.size() >= MAX_FOLDER_ENTRIES) {
                        throw new IOException("Folder entry count exceeds the safe limit");
                    }
                    String documentId = cursor.getString(idIndex);
                    String name = cursor.getString(nameIndex);
                    String mime = cursor.getString(mimeIndex);
                    long size = sizeIndex >= 0 && !cursor.isNull(sizeIndex)
                            ? cursor.getLong(sizeIndex)
                            : -1L;
                    validateDocumentName(name);
                    String path = folder.path.isEmpty() ? name : folder.path + "/" + name;
                    Uri child = DocumentsContract.buildDocumentUriUsingTree(
                            folder.uri,
                            documentId
                    );
                    boolean directory = DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);
                    DocumentEntry entry = new DocumentEntry(
                            child,
                            path,
                            name,
                            mime,
                            size,
                            directory
                    );
                    inventory.entries.add(entry);
                    if (directory) {
                        inventory.directoryCount++;
                        queue.addLast(new FolderNode(child, path, folder.depth + 1));
                    } else {
                        inventory.fileCount++;
                        if (size >= 0L) {
                            inventory.knownBytes = safeAddLong(inventory.knownBytes, size);
                        } else {
                            inventory.unknownSizeFiles++;
                        }
                    }
                }
            } catch (SecurityException | IllegalArgumentException error) {
                throw new IOException("Could not list folder " + folder.path, error);
            }
        }

        if (inventory.unknownSizeFiles > 0) {
            long measured = 0L;
            for (DocumentEntry entry : inventory.entries) {
                if (!entry.directory) {
                    long size = resolveSize(resolver, entry.uri, entry.size);
                    if (size < 0L) {
                        throw new IOException("Provider did not expose a reliable size for " + entry.path);
                    }
                    entry.size = size;
                    measured = safeAddLong(measured, size);
                }
            }
            inventory.knownBytes = measured;
            inventory.unknownSizeFiles = 0;
        }

        inventory.entries.sort(Comparator
                .comparingInt((DocumentEntry entry) -> pathDepth(entry.path))
                .thenComparing(entry -> entry.path.toLowerCase(Locale.ROOT))
                .thenComparing(entry -> entry.path));
        return inventory;
    }

    private static Map<String, ExpectedHash> readMachineHashes(
            ContentResolver resolver,
            Uri hashesUri
    ) throws IOException {
        String text = readText(resolver, hashesUri, MAX_TEXT_BYTES);
        Map<String, ExpectedHash> result = new LinkedHashMap<>();
        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            if (line.trim().isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] parts = line.split("\\t", -1);
            if (parts.length != 3) {
                throw new IOException("Malformed extraction hash manifest");
            }
            String path;
            long size;
            try {
                path = new String(
                        Base64.getUrlDecoder().decode(parts[0]),
                        StandardCharsets.UTF_8
                );
                size = Long.parseLong(parts[1]);
            } catch (IllegalArgumentException error) {
                throw new IOException("Malformed extraction hash manifest value", error);
            }
            String hash = parts[2].trim().toLowerCase(Locale.ROOT);
            if (!hash.matches("[0-9a-f]{64}")) {
                throw new IOException("Malformed SHA-256 in extraction manifest");
            }
            if (result.put(path, new ExpectedHash(size, hash)) != null) {
                throw new IOException("Duplicate path in extraction hash manifest: " + path);
            }
        }
        if (result.isEmpty()) {
            throw new IOException("Extraction hash manifest is empty");
        }
        return result;
    }

    private static StringBuilder manifestHeader(String sourceKind, String volumeId) {
        StringBuilder builder = new StringBuilder();
        builder.append("path,size_bytes,sha256,modding_candidate,source_kind,source_offset_bytes\n");
        builder.append("# phase=1C source_kind=")
                .append(ExtractionRules.csv(sourceKind))
                .append(" volume_id=")
                .append(ExtractionRules.csv(volumeId))
                .append(" created_utc=")
                .append(utcTimestamp())
                .append('\n');
        return builder;
    }

    private static StringBuilder machineHeader() {
        return new StringBuilder("# base64url_path\\tsize_bytes\\tsha256\n");
    }

    private static StringBuilder assetHeader() {
        return new StringBuilder("path,size_bytes,sha256\n");
    }

    private static void appendManifestRow(
            StringBuilder builder,
            String path,
            long bytes,
            String sha256,
            boolean candidate,
            String sourceKind,
            long sourceOffset
    ) {
        builder.append(ExtractionRules.csv(path)).append(',')
                .append(bytes).append(',')
                .append(sha256).append(',')
                .append(candidate).append(',')
                .append(ExtractionRules.csv(sourceKind)).append(',')
                .append(sourceOffset)
                .append('\n');
    }

    private static void appendMachineRow(
            StringBuilder builder,
            String path,
            long bytes,
            String sha256
    ) {
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(
                path.getBytes(StandardCharsets.UTF_8)
        );
        builder.append(encoded).append('\t')
                .append(bytes).append('\t')
                .append(sha256).append('\n');
    }

    private static void appendAssetRow(
            StringBuilder builder,
            String path,
            long bytes,
            String sha256
    ) {
        builder.append(ExtractionRules.csv(path)).append(',')
                .append(bytes).append(',')
                .append(sha256).append('\n');
    }

    private static String buildExtractionSummary(
            ExtractionOutput output,
            String backupReference,
            String extractionReference
    ) {
        StringBuilder text = new StringBuilder();
        text.append("PPSSPP Mod Toolkit Phase 1C extraction summary\n");
        text.append("state=ORIGINAL_EXTRACTED\n");
        text.append("created_utc=").append(utcTimestamp()).append('\n');
        text.append("source_kind=").append(output.sourceKind).append('\n');
        text.append("volume_id=").append(manifestSafe(output.volumeId)).append('\n');
        text.append("directory_encoding=").append(output.joliet ? "Joliet" : "ISO-9660_or_folder").append('\n');
        text.append("file_count=").append(output.fileCount).append('\n');
        text.append("directory_count=").append(output.directoryCount).append('\n');
        text.append("file_bytes=").append(output.fileBytes).append('\n');
        text.append("candidate_count=").append(output.candidates.size()).append('\n');
        text.append("verified_backup_reference=").append(manifestSafe(backupReference)).append('\n');
        text.append("extraction_reference=").append(manifestSafe(extractionReference)).append('\n');
        text.append("source_changed=false\n");
        text.append("replacement_enabled=false\n");
        return text.toString();
    }

    private static String buildWorkingSummary(
            ExtractionOutput output,
            String backupReference,
            String extractionReference,
            String workingReference
    ) {
        StringBuilder text = new StringBuilder();
        text.append("PPSSPP Mod Toolkit Phase 1C working copy summary\n");
        text.append("state=WORKING_COPY_READY\n");
        text.append("created_utc=").append(utcTimestamp()).append('\n');
        text.append("file_count=").append(output.fileCount).append('\n');
        text.append("directory_count=").append(output.directoryCount).append('\n');
        text.append("file_bytes=").append(output.fileBytes).append('\n');
        text.append("candidate_count=").append(output.candidates.size()).append('\n');
        text.append("verified_backup_reference=").append(manifestSafe(backupReference)).append('\n');
        text.append("extraction_reference=").append(manifestSafe(extractionReference)).append('\n');
        text.append("working_reference=").append(manifestSafe(workingReference)).append('\n');
        text.append("protected_original_changed=false\n");
        text.append("replacement_enabled=false\n");
        return text.toString();
    }

    private static void updateWorkspaceManifest(
            ContentResolver resolver,
            Uri projectUri,
            String state,
            String backupReference,
            String extractionReference,
            String workingReference,
            ExtractionOutput output
    ) throws IOException {
        StringBuilder manifest = new StringBuilder();
        manifest.append("PPSSPP Mod Toolkit workspace\n");
        manifest.append("manifest_version=2\n");
        manifest.append("toolkit_phase=1C\n");
        manifest.append("state=").append(state).append('\n');
        manifest.append("updated_utc=").append(utcTimestamp()).append('\n');
        manifest.append("verified_backup_reference=")
                .append(manifestSafe(backupReference)).append('\n');
        manifest.append("extraction_reference=")
                .append(manifestSafe(extractionReference)).append('\n');
        manifest.append("working_reference=")
                .append(manifestSafe(workingReference)).append('\n');
        manifest.append("original_root=")
                .append(ORIGINAL_CONTAINER).append('/').append(ORIGINAL_ROOT).append('\n');
        manifest.append("working_root=")
                .append(WORKING_CONTAINER).append('/').append(WORKING_ROOT).append('\n');
        manifest.append("file_count=").append(output.fileCount).append('\n');
        manifest.append("file_bytes=").append(output.fileBytes).append('\n');
        manifest.append("candidate_count=").append(output.candidates.size()).append('\n');
        manifest.append("replacement_enabled=false\n");
        manifest.append("next_step=Phase 1E database inspection and controlled replacement inside source_working\n");
        writeOrReplaceText(resolver, projectUri, "workspace_manifest.txt", manifest.toString());
    }

    private static String readExtractionReference(ContentResolver resolver, Uri logs)
            throws IOException {
        Uri summary = findChild(resolver, logs, EXTRACTION_SUMMARY);
        if (summary == null) {
            return "source_original | existing verified extraction";
        }
        return findProperty(readText(resolver, summary, 1024 * 1024), "extraction_reference");
    }

    private static String readWorkingReference(ContentResolver resolver, Uri logs)
            throws IOException {
        Uri summary = findChild(resolver, logs, WORKING_SUMMARY);
        if (summary == null) {
            return "source_working | existing verified working copy";
        }
        return findProperty(readText(resolver, summary, 1024 * 1024), "working_reference");
    }

    private static String findProperty(String text, String key) {
        String prefix = key + "=";
        for (String line : text.split("\\r?\\n")) {
            if (line.startsWith(prefix)) {
                String value = line.substring(prefix.length()).trim();
                return value.isEmpty() ? key : value;
            }
        }
        return key;
    }

    private static void cleanupPartialExtraction(
            ContentResolver resolver,
            WorkspacePaths workspace
    ) throws IOException {
        Uri root = findChild(resolver, workspace.originalContainer, ORIGINAL_ROOT);
        Uri manifest = findChild(resolver, workspace.logs, EXTRACTION_MANIFEST);
        Uri hashes = findChild(resolver, workspace.logs, EXTRACTION_HASHES);
        Uri index = findChild(resolver, workspace.logs, ASSET_INDEX);
        Uri summary = findChild(resolver, workspace.logs, EXTRACTION_SUMMARY);
        Uri marker = findChild(resolver, workspace.logs, EXTRACTION_MARKER);
        boolean complete = root != null
                && manifest != null
                && hashes != null
                && index != null
                && summary != null
                && marker == null;
        if (complete) {
            return;
        }
        if (root != null) {
            deleteDocument(resolver, root);
        }
        deleteChildQuietly(resolver, workspace.logs, EXTRACTION_MANIFEST);
        deleteChildQuietly(resolver, workspace.logs, EXTRACTION_HASHES);
        deleteChildQuietly(resolver, workspace.logs, ASSET_INDEX);
        deleteChildQuietly(resolver, workspace.logs, EXTRACTION_SUMMARY);
        deleteChildQuietly(resolver, workspace.logs, EXTRACTION_MARKER);
    }

    private static void cleanupPartialWorkingCopy(
            ContentResolver resolver,
            WorkspacePaths workspace
    ) throws IOException {
        Uri root = findChild(resolver, workspace.workingContainer, WORKING_ROOT);
        Uri manifest = findChild(resolver, workspace.logs, WORKING_MANIFEST);
        Uri hashes = findChild(resolver, workspace.logs, WORKING_HASHES);
        Uri index = findChild(resolver, workspace.logs, WORKING_ASSET_INDEX);
        Uri summary = findChild(resolver, workspace.logs, WORKING_SUMMARY);
        Uri marker = findChild(resolver, workspace.logs, WORKING_MARKER);
        boolean complete = root != null
                && manifest != null
                && hashes != null
                && index != null
                && summary != null
                && marker == null;
        if (complete) {
            return;
        }
        if (root != null) {
            deleteDocument(resolver, root);
        }
        deleteChildQuietly(resolver, workspace.logs, WORKING_MANIFEST);
        deleteChildQuietly(resolver, workspace.logs, WORKING_HASHES);
        deleteChildQuietly(resolver, workspace.logs, WORKING_ASSET_INDEX);
        deleteChildQuietly(resolver, workspace.logs, WORKING_SUMMARY);
        deleteChildQuietly(resolver, workspace.logs, WORKING_MARKER);
    }

    private static DestinationProbe probeDestination(ContentResolver resolver, Uri parentUri) {
        Uri probe = null;
        try {
            probe = createFileExact(
                    resolver,
                    parentUri,
                    ".ppsspp_phase1c_probe_" + System.currentTimeMillis() + ".tmp"
            );
            try (OutputStream output = resolver.openOutputStream(probe, "w")) {
                if (output == null) {
                    return new DestinationProbe(false, -1L, "Provider returned no write stream");
                }
                output.write(0);
                output.flush();
            }

            long available = -1L;
            try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(probe, "r")) {
                if (descriptor != null) {
                    try {
                        StructStatVfs stat = Os.fstatvfs(descriptor.getFileDescriptor());
                        available = safeMultiplyLong(stat.f_bavail, stat.f_frsize);
                    } catch (ErrnoException | RuntimeException ignored) {
                        available = -1L;
                    }
                }
            }
            return new DestinationProbe(true, available, "Write test passed");
        } catch (IOException | SecurityException | IllegalArgumentException error) {
            return new DestinationProbe(false, -1L, safeMessage(error));
        } finally {
            if (probe != null) {
                deleteQuietly(resolver, probe);
            }
        }
    }

    private static Uri findChild(ContentResolver resolver, Uri parentUri, String name)
            throws IOException {
        String parentId;
        try {
            parentId = DocumentsContract.getDocumentId(parentUri);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid workspace document URI", error);
        }
        Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(parentUri, parentId);
        String[] projection = new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
        };
        try (Cursor cursor = resolver.query(childrenUri, projection, null, null, null)) {
            if (cursor == null) {
                throw new IOException("Android returned no child listing");
            }
            int idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            if (idIndex < 0 || nameIndex < 0) {
                throw new IOException("Document provider omitted child metadata");
            }
            while (cursor.moveToNext()) {
                String displayName = cursor.getString(nameIndex);
                if (name.equals(displayName)) {
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
            Uri parentUri,
            String name
    ) throws IOException {
        if (findChild(resolver, parentUri, name) != null) {
            throw new IOException("Workspace path already exists: " + name);
        }
        Uri created = DocumentsContract.createDocument(
                resolver,
                parentUri,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name
        );
        if (created == null) {
            throw new IOException("Could not create directory: " + name);
        }
        Metadata metadata = queryDocumentMetadata(resolver, created);
        if (!name.equals(metadata.name)
                || !DocumentsContract.Document.MIME_TYPE_DIR.equals(metadata.mimeType)) {
            deleteQuietly(resolver, created);
            throw new IOException("Provider changed the required directory name: " + name);
        }
        return created;
    }

    private static Uri createFileExact(
            ContentResolver resolver,
            Uri parentUri,
            String name
    ) throws IOException {
        if (findChild(resolver, parentUri, name) != null) {
            throw new IOException("Workspace file already exists: " + name);
        }
        Uri created = DocumentsContract.createDocument(
                resolver,
                parentUri,
                ExtractionRules.exactNameMimeType(),
                name
        );
        if (created == null) {
            throw new IOException("Could not create file: " + name);
        }
        Metadata metadata = queryDocumentMetadata(resolver, created);
        if (!name.equals(metadata.name)
                || DocumentsContract.Document.MIME_TYPE_DIR.equals(metadata.mimeType)) {
            deleteQuietly(resolver, created);
            throw new IOException("Provider changed the required file name: " + name);
        }
        return created;
    }

    private static void writeOrReplaceText(
            ContentResolver resolver,
            Uri parentUri,
            String name,
            String text
    ) throws IOException {
        Uri existing = findChild(resolver, parentUri, name);
        if (existing != null) {
            deleteDocument(resolver, existing);
        }
        Uri file = createFileExact(resolver, parentUri, name);
        try (OutputStream output = resolver.openOutputStream(file, "w")) {
            if (output == null) {
                throw new IOException("Android returned no output stream for " + name);
            }
            output.write(text.getBytes(StandardCharsets.UTF_8));
            output.flush();
        }
    }

    private static String readText(
            ContentResolver resolver,
            Uri uri,
            int maximumBytes
    ) throws IOException {
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) {
                throw new IOException("Android returned no input stream for a workspace manifest");
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[32 * 1024];
            int read;
            int total = 0;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                total += read;
                if (total > maximumBytes) {
                    throw new IOException("Workspace manifest exceeds the safe size limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static Metadata queryDocumentMetadata(ContentResolver resolver, Uri uri)
            throws IOException {
        String[] projection = new String[]{
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
        };
        try (Cursor cursor = resolver.query(uri, projection, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                throw new IOException("Document metadata is unavailable");
            }
            int nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
            String name = nameIndex >= 0 && !cursor.isNull(nameIndex)
                    ? cursor.getString(nameIndex)
                    : "";
            String mime = mimeIndex >= 0 && !cursor.isNull(mimeIndex)
                    ? cursor.getString(mimeIndex)
                    : "application/octet-stream";
            long size = sizeIndex >= 0 && !cursor.isNull(sizeIndex)
                    ? cursor.getLong(sizeIndex)
                    : -1L;
            return new Metadata(name, mime, size);
        } catch (SecurityException | IllegalArgumentException error) {
            throw new IOException("Could not read document metadata", error);
        }
    }

    private static String queryName(
            ContentResolver resolver,
            Uri uri,
            String fallback
    ) {
        try (Cursor cursor = resolver.query(
                uri,
                new String[]{OpenableColumns.DISPLAY_NAME},
                null,
                null,
                null
        )) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) {
                String name = cursor.getString(0);
                if (name != null && !name.trim().isEmpty()) {
                    return name.trim();
                }
            }
        } catch (RuntimeException ignored) {
            // Fallback below.
        }
        return fallback;
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
            // Unknown size is handled by the caller.
        }
        return -1L;
    }

    private static Uri rootDocumentUri(Uri treeUri) throws IOException {
        try {
            String treeId = DocumentsContract.getTreeDocumentId(treeUri);
            return DocumentsContract.buildDocumentUriUsingTree(treeUri, treeId);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid source folder URI", error);
        }
    }

    private static boolean isDirectory(ContentResolver resolver, Uri uri) throws IOException {
        return DocumentsContract.Document.MIME_TYPE_DIR.equals(
                queryDocumentMetadata(resolver, uri).mimeType
        );
    }

    private static void deleteChildQuietly(
            ContentResolver resolver,
            Uri parent,
            String childName
    ) {
        try {
            Uri child = findChild(resolver, parent, childName);
            if (child != null) {
                deleteDocument(resolver, child);
            }
        } catch (IOException | RuntimeException ignored) {
            // Best-effort cleanup only.
        }
    }

    private static void deleteQuietly(ContentResolver resolver, Uri uri) {
        try {
            deleteDocument(resolver, uri);
        } catch (IOException | RuntimeException ignored) {
            // Best-effort cleanup only.
        }
    }

    private static void deleteDocument(ContentResolver resolver, Uri uri) throws IOException {
        if (!DocumentsContract.deleteDocument(resolver, uri)) {
            throw new IOException("Document provider refused to delete an incomplete workspace item");
        }
    }

    private static void validateDocumentName(String name) throws IOException {
        if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            throw new IOException("Source folder contains an empty or unsafe name");
        }
        if (name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) {
            throw new IOException("Source folder contains an unsafe path separator");
        }
        for (int index = 0; index < name.length(); index++) {
            if (name.charAt(index) == 0) {
                throw new IOException("Source folder contains a NUL character");
            }
        }
    }

    private static String parentPath(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    private static int pathDepth(String path) {
        int depth = 0;
        for (int index = 0; index < path.length(); index++) {
            if (path.charAt(index) == '/') {
                depth++;
            }
        }
        return depth;
    }

    private static String safeName(String value, String fallback) {
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }

    private static String manifestSafe(String value) {
        if (value == null) {
            return "";
        }
        return value.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ');
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty()
                ? "No additional details"
                : message;
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) {
            out.append(String.format(Locale.US, "%02x", value & 0xff));
        }
        return out.toString();
    }

    private static String utcTimestamp() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(System.currentTimeMillis());
    }

    private static void checkInterrupted() throws IOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new IOException("Operation cancelled");
        }
    }

    private static void notifyProgress(
            ProgressListener listener,
            String stage,
            long completed,
            long total
    ) {
        if (listener != null) {
            listener.onProgress(stage, completed, total);
        }
    }

    private static long safeAddLong(long left, long right) throws IOException {
        if (left < 0L || right < 0L || left > Long.MAX_VALUE - right) {
            throw new IOException("File size arithmetic overflow");
        }
        return left + right;
    }

    private static long safeMultiplyLong(long left, long right) throws IOException {
        if (left < 0L || right < 0L || (right != 0L && left > Long.MAX_VALUE / right)) {
            throw new IOException("File offset arithmetic overflow");
        }
        return left * right;
    }

    private static long safeMultiplyForProgress(long left, long right) {
        if (left < 0L || right < 0L || (right != 0L && left > Long.MAX_VALUE / right)) {
            return -1L;
        }
        return left * right;
    }

    private static long safeAddProgress(long left, long right) {
        if (left < 0L || right < 0L || left > Long.MAX_VALUE - right) {
            return -1L;
        }
        return left + right;
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
                new ScanReport(title, status, summary, details, new ArrayList<>()),
                false,
                null,
                ""
        );
    }

    private static final class AndroidRandomAccessSource
            implements Iso9660Reader.RandomAccessSource {
        private final ParcelFileDescriptor descriptor;
        private final FileInputStream input;
        private final FileChannel channel;
        private final long size;

        private AndroidRandomAccessSource(ContentResolver resolver, Uri uri) throws IOException {
            descriptor = resolver.openFileDescriptor(uri, "r");
            if (descriptor == null) {
                throw new IOException("Android returned no file descriptor for the selected ISO");
            }
            input = new ParcelFileDescriptor.AutoCloseInputStream(descriptor);
            channel = input.getChannel();
            long statSize = descriptor.getStatSize();
            long channelSize;
            try {
                channelSize = channel.size();
            } catch (IOException error) {
                channelSize = -1L;
            }
            if (statSize > 0L && channelSize > 0L) {
                size = Math.max(statSize, channelSize);
            } else if (statSize >= 0L) {
                size = statSize;
            } else {
                size = channelSize;
            }
            if (size < 0L) {
                close();
                throw new IOException("The source provider did not expose a seekable ISO size");
            }
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public int read(long position, byte[] buffer, int offset, int length)
                throws IOException {
            if (position < 0L || offset < 0 || length < 0 || offset + length > buffer.length) {
                throw new IndexOutOfBoundsException("Invalid ISO read range");
            }
            try {
                return channel.read(ByteBuffer.wrap(buffer, offset, length), position);
            } catch (IOException error) {
                throw new IOException(
                        "The selected provider does not support reliable seekable ISO access. Move the ISO to normal device storage and select it again.",
                        error
                );
            }
        }

        @Override
        public void close() throws IOException {
            input.close();
        }
    }

    private static final class SourcePlan {
        private final String sourceType;
        private final String sourceName;
        private final String volumeId;
        private final boolean joliet;
        private final int fileCount;
        private final int directoryCount;
        private final long fileBytes;
        private final List<String> candidates;

        private SourcePlan(
                String sourceType,
                String sourceName,
                String volumeId,
                boolean joliet,
                int fileCount,
                int directoryCount,
                long fileBytes,
                List<String> candidates
        ) {
            this.sourceType = sourceType;
            this.sourceName = sourceName;
            this.volumeId = volumeId;
            this.joliet = joliet;
            this.fileCount = fileCount;
            this.directoryCount = directoryCount;
            this.fileBytes = fileBytes;
            this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
        }
    }

    private static final class WorkspacePaths {
        private final Uri project;
        private final Uri originalContainer;
        private final Uri workingContainer;
        private final Uri logs;

        private WorkspacePaths(
                Uri project,
                Uri originalContainer,
                Uri workingContainer,
                Uri logs
        ) {
            this.project = project;
            this.originalContainer = originalContainer;
            this.workingContainer = workingContainer;
            this.logs = logs;
        }
    }

    private static final class FolderInventory {
        private final List<DocumentEntry> entries = new ArrayList<>();
        private int fileCount;
        private int directoryCount;
        private int unknownSizeFiles;
        private long knownBytes;
    }

    private static final class DocumentEntry {
        private final Uri uri;
        private final String path;
        private final String name;
        private final String mimeType;
        private long size;
        private final boolean directory;

        private DocumentEntry(
                Uri uri,
                String path,
                String name,
                String mimeType,
                long size,
                boolean directory
        ) {
            this.uri = uri;
            this.path = path;
            this.name = name;
            this.mimeType = mimeType;
            this.size = size;
            this.directory = directory;
        }
    }

    private static final class FolderNode {
        private final Uri uri;
        private final String path;
        private final int depth;

        private FolderNode(Uri uri, String path, int depth) {
            this.uri = uri;
            this.path = path;
            this.depth = depth;
        }
    }

    private static final class ExtractionOutput {
        private final String sourceKind;
        private final String volumeId;
        private final boolean joliet;
        private final int fileCount;
        private final int directoryCount;
        private final long fileBytes;
        private final List<String> candidates;
        private final String csvManifest;
        private final String machineHashes;
        private final String assetIndex;

        private ExtractionOutput(
                String sourceKind,
                String volumeId,
                boolean joliet,
                int fileCount,
                int directoryCount,
                long fileBytes,
                List<String> candidates,
                String csvManifest,
                String machineHashes,
                String assetIndex
        ) {
            this.sourceKind = sourceKind;
            this.volumeId = volumeId;
            this.joliet = joliet;
            this.fileCount = fileCount;
            this.directoryCount = directoryCount;
            this.fileBytes = fileBytes;
            this.candidates = Collections.unmodifiableList(new ArrayList<>(candidates));
            this.csvManifest = csvManifest;
            this.machineHashes = machineHashes;
            this.assetIndex = assetIndex;
        }
    }

    private static final class FileVerification {
        private final long bytes;
        private final String sha256;

        private FileVerification(long bytes, String sha256) {
            this.bytes = bytes;
            this.sha256 = sha256;
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

    private static final class ExpectedHash {
        private final long size;
        private final String sha256;

        private ExpectedHash(long size, String sha256) {
            this.size = size;
            this.sha256 = sha256;
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
}
