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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;

/**
 * Phase 1D controlled full-file replacement engine.
 *
 * <p>Only the verified working copy is changed. A selected replacement is first copied into the
 * patch-import staging area and verified. Before the working target is touched, the current working
 * file is copied into the transaction rollback vault and verified. A failed apply attempts an
 * immediate verified restore from that rollback copy.</p>
 */
public final class ReplacementEngine {
    private static final int COPY_BUFFER_BYTES = 1024 * 1024;
    private static final long PROGRESS_INTERVAL_BYTES = 8L * 1024L * 1024L;
    private static final int MAX_TEXT_BYTES = 24 * 1024 * 1024;
    private static final int MAX_HISTORY_BYTES = 8 * 1024 * 1024;

    private static final String WORKING_CONTAINER = "20_working_files";
    private static final String WORKING_ROOT = "source_working";
    private static final String PATCH_CONTAINER = "30_patch_import";
    private static final String LOG_CONTAINER = "90_logs";
    private static final String STAGING_ROOT = "phase1d_staging";
    private static final String STAGED_REPLACEMENT_DIR = "replacement";
    private static final String ROLLBACK_DIR = "rollback_original";
    private static final String TRANSACTION_MANIFEST = "transaction_manifest.txt";
    private static final String WORKING_MANIFEST = "working_manifest.csv";
    private static final String WORKING_HASHES = "working_hashes.tsv";
    private static final String WORKING_ASSET_INDEX = "working_asset_index.csv";
    private static final String REPLACEMENT_HISTORY = "replacement_history.csv";
    private static final String LATEST_REPLACEMENT = "latest_replacement.txt";

    public interface ProgressListener {
        void onProgress(String stage, long completedBytes, long totalBytes);
    }

    private ReplacementEngine() {
    }

    public static AssetSearchResult searchAssets(
            Context context,
            Uri workspaceProjectUri,
            String query
    ) {
        List<String> details = new ArrayList<>();
        List<String> candidates = new ArrayList<>();
        if (workspaceProjectUri == null) {
            ScanReport report = report(
                    "Working asset browser",
                    "Prepared workspace missing",
                    "Prepare a workspace and create the verified working copy first.",
                    details,
                    candidates
            );
            return new AssetSearchResult(Collections.emptyList(), report);
        }
        try {
            ContentResolver resolver = context.getContentResolver();
            WorkspacePaths workspace = requireWorkspace(resolver, workspaceProjectUri);
            Uri indexUri = requireChild(resolver, workspace.logs, WORKING_ASSET_INDEX);
            List<AssetRecord> all = ReplacementRules.parseAssetIndex(
                    readText(resolver, indexUri, MAX_TEXT_BYTES)
            );
            List<AssetRecord> matches = ReplacementRules.filterAssets(all, query);
            details.add("Indexed working assets: " + all.size());
            details.add("Matching assets: " + matches.size());
            details.add("Query: " + ((query == null || query.trim().isEmpty()) ? "all assets" : query.trim()));
            details.add("Filters: ext:, name:, path:, type:, min:, max:");
            for (AssetRecord record : matches) {
                candidates.add(
                        record.getPath() + " | " + record.getCategory() + " | "
                                + GameScanner.formatBytes(record.getSizeBytes())
                );
            }
            ScanReport report = report(
                    "Working asset browser",
                    matches.isEmpty() ? "No matching assets" : "Asset index loaded",
                    matches.isEmpty()
                            ? "Try a broader query such as fifa, ext:db, ext:big, or type:localization."
                            : "Select a match with Previous/Next, then choose a complete replacement file with the exact same filename.",
                    details,
                    candidates
            );
            return new AssetSearchResult(matches, report);
        } catch (IOException | IllegalArgumentException | SecurityException error) {
            details.add(safeMessage(error));
            ScanReport report = report(
                    "Working asset browser",
                    "Asset index unavailable",
                    "The verified working copy or its Phase 1C asset index could not be read.",
                    details,
                    candidates
            );
            return new AssetSearchResult(Collections.emptyList(), report);
        }
    }

    public static AssetRecord findAsset(
            Context context,
            Uri workspaceProjectUri,
            String targetPath
    ) {
        if (workspaceProjectUri == null || targetPath == null || targetPath.trim().isEmpty()) {
            return null;
        }
        try {
            ContentResolver resolver = context.getContentResolver();
            WorkspacePaths workspace = requireWorkspace(resolver, workspaceProjectUri);
            Uri indexUri = requireChild(resolver, workspace.logs, WORKING_ASSET_INDEX);
            String safePath = ReplacementRules.requireSafeRelativePath(targetPath);
            for (AssetRecord record : ReplacementRules.parseAssetIndex(
                    readText(resolver, indexUri, MAX_TEXT_BYTES)
            )) {
                if (safePath.equals(record.getPath())) {
                    return record;
                }
            }
        } catch (IOException | IllegalArgumentException | SecurityException ignored) {
            return null;
        }
        return null;
    }

    public static StagedReplacementResult validateAndStageReplacement(
            Context context,
            Uri workspaceProjectUri,
            AssetRecord selectedAsset,
            Uri replacementSourceUri,
            ProgressListener listener
    ) {
        List<String> details = new ArrayList<>();
        if (workspaceProjectUri == null) {
            return stageFailure("Prepared workspace missing", "Prepare the workspace first.", details);
        }
        if (selectedAsset == null) {
            return stageFailure("Target asset missing", "Search the asset index and select one exact target path.", details);
        }
        if (replacementSourceUri == null) {
            return stageFailure("Replacement file missing", "Choose the full replacement file first.", details);
        }

        ContentResolver resolver = context.getContentResolver();
        Uri transactionUri = null;
        try {
            WorkspacePaths workspace = requireWorkspace(resolver, workspaceProjectUri);
            String targetPath = ReplacementRules.requireSafeRelativePath(selectedAsset.getPath());
            String targetName = ReplacementRules.fileName(targetPath);
            String replacementName = queryName(resolver, replacementSourceUri, "");
            if (!targetName.equals(replacementName)) {
                details.add("Required filename: " + targetName);
                details.add("Selected filename: " + (replacementName.isEmpty() ? "unknown" : replacementName));
                return stageFailure(
                        "Filename mismatch",
                        "Choose a replacement whose filename exactly matches the selected working asset.",
                        details
                );
            }

            Uri targetUri = resolvePath(resolver, workspace.workingRoot, targetPath, false);
            if (targetUri == null || isDirectory(resolver, targetUri)) {
                throw new IOException("The selected working target no longer exists: " + targetPath);
            }
            HashResult currentTarget = hashDocument(
                    resolver,
                    targetUri,
                    "Checking current working target",
                    0L,
                    0L,
                    listener
            );
            if (currentTarget.bytes != selectedAsset.getSizeBytes()
                    || !currentTarget.sha256.equals(selectedAsset.getSha256())) {
                details.add("Asset index size/hash no longer matches the working file");
                details.add("Reload the asset index before staging a replacement");
                return stageFailure(
                        "Stale target selection",
                        "The working file changed after it was selected. Search the index again.",
                        details
                );
            }

            HashResult sourceHash = hashDocument(
                    resolver,
                    replacementSourceUri,
                    "Hashing selected replacement",
                    0L,
                    0L,
                    listener
            );
            if (sourceHash.bytes <= 0L) {
                return stageFailure("Empty replacement rejected", "The replacement file contains no data.", details);
            }
            if (sourceHash.bytes == currentTarget.bytes && sourceHash.sha256.equals(currentTarget.sha256)) {
                return stageFailure(
                        "No content change",
                        "The selected replacement is byte-for-byte identical to the current working file.",
                        details
                );
            }

            long required = ReplacementRules.requiredWorkspaceBytes(
                    currentTarget.bytes,
                    sourceHash.bytes
            );
            long available = probeAvailableBytes(resolver, workspace.patchImport);
            details.add("Current target: " + GameScanner.formatBytes(currentTarget.bytes));
            details.add("Replacement: " + GameScanner.formatBytes(sourceHash.bytes));
            details.add("Required workspace headroom: " + GameScanner.formatBytes(required));
            details.add("Measured workspace free space: " + GameScanner.formatBytes(available));
            if (!ReplacementRules.hasEnoughSpace(available, required)) {
                return stageFailure(
                        "Insufficient measurable workspace space",
                        "Free more storage before staging. Space is needed for the staged file, verified rollback copy, and safety margin.",
                        details
                );
            }

            Uri stagingRoot = findOrCreateDirectory(resolver, workspace.patchImport, STAGING_ROOT);
            String transactionId = newTransactionId();
            transactionUri = createDirectoryExact(resolver, stagingRoot, transactionId);
            Uri replacementDir = createDirectoryExact(
                    resolver,
                    transactionUri,
                    STAGED_REPLACEMENT_DIR
            );
            Uri stagedFile = createFileExact(resolver, replacementDir, replacementName);
            long totalWork = safeMultiply(sourceHash.bytes, 2L);
            HashResult stagedHash = copyAndVerify(
                    resolver,
                    replacementSourceUri,
                    stagedFile,
                    "Staging replacement: " + replacementName,
                    0L,
                    totalWork,
                    listener,
                    sourceHash
            );

            TransactionData transaction = new TransactionData();
            transaction.state = "STAGED";
            transaction.transactionId = transactionId;
            transaction.targetPath = targetPath;
            transaction.targetName = targetName;
            transaction.oldSize = currentTarget.bytes;
            transaction.oldSha256 = currentTarget.sha256;
            transaction.replacementSize = stagedHash.bytes;
            transaction.replacementSha256 = stagedHash.sha256;
            transaction.createdUtc = utcTimestamp();
            writeOrReplaceText(
                    resolver,
                    transactionUri,
                    TRANSACTION_MANIFEST,
                    buildTransactionManifest(transaction)
            );

            details.add("Target path: " + targetPath);
            details.add("Target category: " + selectedAsset.getCategory());
            details.add("Original working SHA-256: " + currentTarget.sha256);
            details.add("Staged replacement SHA-256: " + stagedHash.sha256);
            details.add("Transaction: " + transactionId);
            details.add("Working file changed: no");
            ScanReport report = report(
                    "Replacement validation",
                    "Replacement staged and verified",
                    "The replacement was copied into 30_patch_import and verified. The working file has not been changed. You may now apply it.",
                    details,
                    Collections.emptyList()
            );
            OperationResult operation = new OperationResult(
                    report,
                    true,
                    transactionUri,
                    transactionId + " | " + targetPath
            );
            return new StagedReplacementResult(
                    operation,
                    transactionUri,
                    targetPath,
                    replacementName,
                    stagedHash.bytes,
                    stagedHash.sha256
            );
        } catch (IOException | IllegalArgumentException | SecurityException error) {
            if (transactionUri != null) {
                deleteQuietly(resolver, transactionUri);
            }
            details.add(safeMessage(error));
            details.add("Working file changed: no");
            return stageFailure(
                    "Replacement staging stopped safely",
                    "No working file was replaced.",
                    details
            );
        }
    }

    public static OperationResult applyStagedReplacement(
            Context context,
            Uri workspaceProjectUri,
            Uri transactionUri,
            String expectedTargetPath,
            String expectedReplacementSha256,
            long expectedReplacementSize,
            ProgressListener listener
    ) {
        List<String> details = new ArrayList<>();
        if (workspaceProjectUri == null || transactionUri == null) {
            return failed("Full-file replacement", "Validated stage missing", "Validate and stage a replacement first.", details);
        }

        ContentResolver resolver = context.getContentResolver();
        Uri targetParent = null;
        Uri rollbackFile = null;
        Uri stagedFile = null;
        String oldAssetIndex = null;
        String oldWorkingManifest = null;
        String oldWorkingHashes = null;
        String oldHistory = null;
        String oldLatest = null;
        String targetName = null;
        TransactionData transaction = null;
        boolean destructiveStarted = false;
        boolean restored = false;

        try {
            WorkspacePaths workspace = requireWorkspace(resolver, workspaceProjectUri);
            transaction = readTransaction(resolver, transactionUri);
            requireState(transaction, "STAGED");
            String targetPath = ReplacementRules.requireSafeRelativePath(expectedTargetPath);
            if (!targetPath.equals(transaction.targetPath)) {
                throw new IOException("The staged transaction belongs to a different target path");
            }
            if (expectedReplacementSize != transaction.replacementSize
                    || expectedReplacementSha256 == null
                    || !expectedReplacementSha256.equalsIgnoreCase(transaction.replacementSha256)) {
                throw new IOException("The saved staged replacement record does not match its transaction");
            }

            Uri replacementDir = requireChild(resolver, transactionUri, STAGED_REPLACEMENT_DIR);
            stagedFile = requireChild(resolver, replacementDir, transaction.targetName);
            HashResult stagedHash = hashDocument(
                    resolver,
                    stagedFile,
                    "Revalidating staged replacement",
                    0L,
                    0L,
                    listener
            );
            requireHash(stagedHash, transaction.replacementSize, transaction.replacementSha256,
                    "Staged replacement changed after validation");

            targetParent = resolveParentPath(resolver, workspace.workingRoot, targetPath);
            targetName = transaction.targetName;
            Uri targetUri = requireChild(resolver, targetParent, targetName);
            HashResult targetHash = hashDocument(
                    resolver,
                    targetUri,
                    "Revalidating current working file",
                    0L,
                    0L,
                    listener
            );
            requireHash(targetHash, transaction.oldSize, transaction.oldSha256,
                    "Working target changed after validation");

            Uri assetIndexUri = requireChild(resolver, workspace.logs, WORKING_ASSET_INDEX);
            Uri manifestUri = requireChild(resolver, workspace.logs, WORKING_MANIFEST);
            Uri hashesUri = requireChild(resolver, workspace.logs, WORKING_HASHES);
            oldAssetIndex = readText(resolver, assetIndexUri, MAX_TEXT_BYTES);
            oldWorkingManifest = readText(resolver, manifestUri, MAX_TEXT_BYTES);
            oldWorkingHashes = readText(resolver, hashesUri, MAX_TEXT_BYTES);
            Uri historyUri = findChild(resolver, workspace.logs, REPLACEMENT_HISTORY);
            oldHistory = historyUri == null ? "" : readText(resolver, historyUri, MAX_HISTORY_BYTES);
            Uri latestUri = findChild(resolver, workspace.logs, LATEST_REPLACEMENT);
            oldLatest = latestUri == null ? "" : readText(resolver, latestUri, 1024 * 1024);

            Uri rollbackRoot = createDirectoryExact(resolver, transactionUri, ROLLBACK_DIR);
            rollbackFile = createPathAndFile(
                    resolver,
                    rollbackRoot,
                    targetPath
            );
            long totalWork = safeAdd(
                    safeMultiply(transaction.oldSize, 2L),
                    safeMultiply(transaction.replacementSize, 2L)
            );
            HashResult rollbackHash = copyAndVerify(
                    resolver,
                    targetUri,
                    rollbackFile,
                    "Creating verified rollback copy",
                    0L,
                    totalWork,
                    listener,
                    targetHash
            );
            requireHash(rollbackHash, transaction.oldSize, transaction.oldSha256,
                    "Rollback copy verification failed");
            transaction.state = "BACKUP_VERIFIED";
            writeOrReplaceText(
                    resolver,
                    transactionUri,
                    TRANSACTION_MANIFEST,
                    buildTransactionManifest(transaction)
            );

            destructiveStarted = true;
            deleteDocument(resolver, targetUri);
            Uri replacementTarget = createFileExact(resolver, targetParent, targetName);
            HashResult appliedHash = copyAndVerify(
                    resolver,
                    stagedFile,
                    replacementTarget,
                    "Applying replacement to working copy",
                    safeMultiply(transaction.oldSize, 2L),
                    totalWork,
                    listener,
                    stagedHash
            );
            requireHash(appliedHash, transaction.replacementSize, transaction.replacementSha256,
                    "Applied replacement verification failed");

            String newAssetIndex = ReplacementRules.updateAssetIndex(
                    oldAssetIndex,
                    targetPath,
                    transaction.replacementSize,
                    transaction.replacementSha256
            );
            String newWorkingManifest = ReplacementRules.updateWorkingManifest(
                    oldWorkingManifest,
                    targetPath,
                    transaction.replacementSize,
                    transaction.replacementSha256
            );
            String newWorkingHashes = ReplacementRules.updateWorkingHashes(
                    oldWorkingHashes,
                    targetPath,
                    transaction.replacementSize,
                    transaction.replacementSha256
            );
            writeOrReplaceText(resolver, workspace.logs, WORKING_ASSET_INDEX, newAssetIndex);
            writeOrReplaceText(resolver, workspace.logs, WORKING_MANIFEST, newWorkingManifest);
            writeOrReplaceText(resolver, workspace.logs, WORKING_HASHES, newWorkingHashes);

            transaction.state = "APPLIED";
            transaction.appliedUtc = utcTimestamp();
            writeOrReplaceText(
                    resolver,
                    transactionUri,
                    TRANSACTION_MANIFEST,
                    buildTransactionManifest(transaction)
            );
            String history = appendHistory(oldHistory, transaction, "APPLIED");
            writeOrReplaceText(resolver, workspace.logs, REPLACEMENT_HISTORY, history);
            writeOrReplaceText(
                    resolver,
                    workspace.logs,
                    LATEST_REPLACEMENT,
                    buildLatestReplacement(transaction)
            );

            details.add("Transaction: " + transaction.transactionId);
            details.add("Target path: " + targetPath);
            details.add("Rollback original: verified and retained");
            details.add("Old size: " + GameScanner.formatBytes(transaction.oldSize));
            details.add("New size: " + GameScanner.formatBytes(transaction.replacementSize));
            details.add("Old SHA-256: " + transaction.oldSha256);
            details.add("New SHA-256: " + transaction.replacementSha256);
            details.add("Working manifests updated: yes");
            details.add("Protected original changed: no");
            details.add("Verified backup changed: no");
            ScanReport report = report(
                    "Full-file replacement",
                    "Replacement applied and verified",
                    "Only the selected file inside 20_working_files/source_working was replaced. A verified rollback copy remains available.",
                    details,
                    Collections.emptyList()
            );
            return new OperationResult(
                    report,
                    true,
                    transactionUri,
                    transaction.transactionId + " | " + targetPath
            );
        } catch (IOException | IllegalArgumentException | SecurityException error) {
            details.add("Failure: " + safeMessage(error));
            if (destructiveStarted && targetParent != null && targetName != null && rollbackFile != null) {
                try {
                    restoreFileFromVerifiedSource(
                            resolver,
                            targetParent,
                            targetName,
                            rollbackFile,
                            "Restoring original after failed apply",
                            listener
                    );
                    restoreLogs(
                            resolver,
                            workspaceProjectUri,
                            oldAssetIndex,
                            oldWorkingManifest,
                            oldWorkingHashes,
                            oldHistory,
                            oldLatest
                    );
                    restored = true;
                } catch (IOException | RuntimeException restoreError) {
                    details.add("Automatic restore also failed: " + safeMessage(restoreError));
                }
            }
            if ((restored || !destructiveStarted) && transaction != null) {
                try {
                    Uri rollbackRoot = findChild(resolver, transactionUri, ROLLBACK_DIR);
                    if (rollbackRoot != null) {
                        deleteDocument(resolver, rollbackRoot);
                    }
                    transaction.state = "STAGED";
                    transaction.appliedUtc = "";
                    transaction.rolledBackUtc = "";
                    writeOrReplaceText(
                            resolver,
                            transactionUri,
                            TRANSACTION_MANIFEST,
                            buildTransactionManifest(transaction)
                    );
                    details.add("Transaction reset for a safe retry: yes");
                } catch (IOException | RuntimeException resetError) {
                    details.add("Transaction reset failed: " + safeMessage(resetError));
                }
            }
            details.add("Automatic working-file restore: " + (restored ? "verified" : (destructiveStarted ? "not verified" : "not required")));
            details.add("Protected original changed: no");
            details.add("Verified backup changed: no");
            String summary = destructiveStarted && !restored
                    ? "The transaction rollback copy remains in 30_patch_import. Do not rebuild an ISO until the working target is restored."
                    : "The replacement did not leave an unverified change in the working copy.";
            return failed(
                    "Full-file replacement",
                    restored || !destructiveStarted ? "Replacement stopped safely" : "Manual restore required",
                    summary,
                    details
            );
        }
    }

    public static OperationResult rollbackReplacement(
            Context context,
            Uri workspaceProjectUri,
            Uri transactionUri,
            ProgressListener listener
    ) {
        List<String> details = new ArrayList<>();
        if (workspaceProjectUri == null || transactionUri == null) {
            return failed("Replacement rollback", "Applied transaction missing", "No applied replacement is available to roll back.", details);
        }

        ContentResolver resolver = context.getContentResolver();
        Uri targetParent = null;
        Uri stagedFile = null;
        String targetName = null;
        String currentAssetIndex = null;
        String currentWorkingManifest = null;
        String currentWorkingHashes = null;
        String currentHistory = null;
        String currentLatest = null;
        TransactionData transaction = null;
        boolean destructiveStarted = false;
        boolean reapplied = false;

        try {
            WorkspacePaths workspace = requireWorkspace(resolver, workspaceProjectUri);
            transaction = readTransaction(resolver, transactionUri);
            requireState(transaction, "APPLIED");
            targetName = transaction.targetName;
            targetParent = resolveParentPath(resolver, workspace.workingRoot, transaction.targetPath);
            Uri targetUri = requireChild(resolver, targetParent, targetName);
            HashResult current = hashDocument(
                    resolver,
                    targetUri,
                    "Checking applied working replacement",
                    0L,
                    0L,
                    listener
            );
            requireHash(current, transaction.replacementSize, transaction.replacementSha256,
                    "The working target changed after this transaction was applied");

            Uri rollbackRoot = requireChild(resolver, transactionUri, ROLLBACK_DIR);
            Uri rollbackFile = resolvePath(resolver, rollbackRoot, transaction.targetPath, false);
            if (rollbackFile == null) {
                throw new IOException("Verified rollback file is missing");
            }
            HashResult rollbackHash = hashDocument(
                    resolver,
                    rollbackFile,
                    "Revalidating rollback original",
                    0L,
                    0L,
                    listener
            );
            requireHash(rollbackHash, transaction.oldSize, transaction.oldSha256,
                    "Rollback original no longer matches its transaction");

            Uri replacementDir = requireChild(resolver, transactionUri, STAGED_REPLACEMENT_DIR);
            stagedFile = requireChild(resolver, replacementDir, targetName);
            HashResult stagedHash = hashDocument(
                    resolver,
                    stagedFile,
                    "Revalidating applied replacement source",
                    0L,
                    0L,
                    listener
            );
            requireHash(stagedHash, transaction.replacementSize, transaction.replacementSha256,
                    "Staged replacement no longer matches its transaction");

            Uri assetIndexUri = requireChild(resolver, workspace.logs, WORKING_ASSET_INDEX);
            Uri manifestUri = requireChild(resolver, workspace.logs, WORKING_MANIFEST);
            Uri hashesUri = requireChild(resolver, workspace.logs, WORKING_HASHES);
            currentAssetIndex = readText(resolver, assetIndexUri, MAX_TEXT_BYTES);
            currentWorkingManifest = readText(resolver, manifestUri, MAX_TEXT_BYTES);
            currentWorkingHashes = readText(resolver, hashesUri, MAX_TEXT_BYTES);
            Uri historyUri = findChild(resolver, workspace.logs, REPLACEMENT_HISTORY);
            currentHistory = historyUri == null ? "" : readText(resolver, historyUri, MAX_HISTORY_BYTES);
            Uri latestUri = findChild(resolver, workspace.logs, LATEST_REPLACEMENT);
            currentLatest = latestUri == null ? "" : readText(resolver, latestUri, 1024 * 1024);

            destructiveStarted = true;
            deleteDocument(resolver, targetUri);
            Uri restoredTarget = createFileExact(resolver, targetParent, targetName);
            HashResult restoredHash = copyAndVerify(
                    resolver,
                    rollbackFile,
                    restoredTarget,
                    "Restoring verified original working file",
                    0L,
                    safeMultiply(transaction.oldSize, 2L),
                    listener,
                    rollbackHash
            );
            requireHash(restoredHash, transaction.oldSize, transaction.oldSha256,
                    "Rollback target verification failed");

            writeOrReplaceText(
                    resolver,
                    workspace.logs,
                    WORKING_ASSET_INDEX,
                    ReplacementRules.updateAssetIndex(
                            currentAssetIndex,
                            transaction.targetPath,
                            transaction.oldSize,
                            transaction.oldSha256
                    )
            );
            writeOrReplaceText(
                    resolver,
                    workspace.logs,
                    WORKING_MANIFEST,
                    ReplacementRules.updateWorkingManifest(
                            currentWorkingManifest,
                            transaction.targetPath,
                            transaction.oldSize,
                            transaction.oldSha256
                    )
            );
            writeOrReplaceText(
                    resolver,
                    workspace.logs,
                    WORKING_HASHES,
                    ReplacementRules.updateWorkingHashes(
                            currentWorkingHashes,
                            transaction.targetPath,
                            transaction.oldSize,
                            transaction.oldSha256
                    )
            );

            transaction.state = "ROLLED_BACK";
            transaction.rolledBackUtc = utcTimestamp();
            writeOrReplaceText(
                    resolver,
                    transactionUri,
                    TRANSACTION_MANIFEST,
                    buildTransactionManifest(transaction)
            );
            writeOrReplaceText(
                    resolver,
                    workspace.logs,
                    REPLACEMENT_HISTORY,
                    appendHistory(currentHistory, transaction, "ROLLED_BACK")
            );
            writeOrReplaceText(
                    resolver,
                    workspace.logs,
                    LATEST_REPLACEMENT,
                    buildLatestReplacement(transaction)
            );

            details.add("Transaction: " + transaction.transactionId);
            details.add("Restored target: " + transaction.targetPath);
            details.add("Restored size: " + GameScanner.formatBytes(transaction.oldSize));
            details.add("Restored SHA-256: " + transaction.oldSha256);
            details.add("Working manifests restored: yes");
            details.add("Protected original changed: no");
            details.add("Verified backup changed: no");
            ScanReport report = report(
                    "Replacement rollback",
                    "Rollback completed and verified",
                    "The latest replacement was removed from the working copy and the verified pre-replacement file was restored.",
                    details,
                    Collections.emptyList()
            );
            return new OperationResult(report, true, transactionUri,
                    transaction.transactionId + " | rolled back | " + transaction.targetPath);
        } catch (IOException | IllegalArgumentException | SecurityException error) {
            details.add("Failure: " + safeMessage(error));
            if (destructiveStarted && targetParent != null && targetName != null && stagedFile != null) {
                try {
                    restoreFileFromVerifiedSource(
                            resolver,
                            targetParent,
                            targetName,
                            stagedFile,
                            "Reapplying replacement after failed rollback",
                            listener
                    );
                    restoreLogs(
                            resolver,
                            workspaceProjectUri,
                            currentAssetIndex,
                            currentWorkingManifest,
                            currentWorkingHashes,
                            currentHistory,
                            currentLatest
                    );
                    if (transaction != null) {
                        transaction.state = "APPLIED";
                        transaction.rolledBackUtc = "";
                        writeOrReplaceText(
                                resolver,
                                transactionUri,
                                TRANSACTION_MANIFEST,
                                buildTransactionManifest(transaction)
                        );
                    }
                    reapplied = true;
                } catch (IOException | RuntimeException restoreError) {
                    details.add("Replacement reapply also failed: " + safeMessage(restoreError));
                }
            }
            details.add("Previous replacement re-applied: " + (reapplied ? "verified" : (destructiveStarted ? "not verified" : "not required")));
            details.add("Protected original changed: no");
            details.add("Verified backup changed: no");
            return failed(
                    "Replacement rollback",
                    reapplied || !destructiveStarted ? "Rollback stopped safely" : "Manual recovery required",
                    reapplied || !destructiveStarted
                            ? "The working copy remains in its pre-rollback state."
                            : "Do not rebuild an ISO until the target is recovered from the transaction files.",
                    details
            );
        }
    }

    private static StagedReplacementResult stageFailure(
            String status,
            String summary,
            List<String> details
    ) {
        OperationResult operation = failed(
                "Replacement validation",
                status,
                summary,
                details
        );
        return new StagedReplacementResult(operation, null, "", "", -1L, "");
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

    private static ScanReport report(
            String title,
            String status,
            String summary,
            List<String> details,
            List<String> candidates
    ) {
        return new ScanReport(title, status, summary, details, candidates);
    }

    private static WorkspacePaths requireWorkspace(
            ContentResolver resolver,
            Uri projectUri
    ) throws IOException {
        WorkspacePaths workspace = requireWorkspaceStructure(resolver, projectUri);
        requireChild(resolver, workspace.logs, WORKING_MANIFEST);
        requireChild(resolver, workspace.logs, WORKING_HASHES);
        requireChild(resolver, workspace.logs, WORKING_ASSET_INDEX);
        return workspace;
    }

    private static WorkspacePaths requireWorkspaceStructure(
            ContentResolver resolver,
            Uri projectUri
    ) throws IOException {
        Uri workingContainer = requireChild(resolver, projectUri, WORKING_CONTAINER);
        Uri workingRoot = requireChild(resolver, workingContainer, WORKING_ROOT);
        Uri patchImport = requireChild(resolver, projectUri, PATCH_CONTAINER);
        Uri logs = requireChild(resolver, projectUri, LOG_CONTAINER);
        if (!isDirectory(resolver, workingContainer)
                || !isDirectory(resolver, workingRoot)
                || !isDirectory(resolver, patchImport)
                || !isDirectory(resolver, logs)) {
            throw new IOException("Workspace structure is incomplete");
        }
        return new WorkspacePaths(projectUri, workingRoot, patchImport, logs);
    }

    private static Uri resolveParentPath(
            ContentResolver resolver,
            Uri root,
            String targetPath
    ) throws IOException {
        String safePath = ReplacementRules.requireSafeRelativePath(targetPath);
        int slash = safePath.lastIndexOf('/');
        if (slash < 0) {
            return root;
        }
        String parentPath = safePath.substring(0, slash);
        Uri parent = resolvePath(resolver, root, parentPath, true);
        if (parent == null) {
            throw new IOException("Working target parent directory is missing: " + parentPath);
        }
        return parent;
    }

    private static Uri resolvePath(
            ContentResolver resolver,
            Uri root,
            String path,
            boolean requireDirectory
    ) throws IOException {
        String safePath = ReplacementRules.requireSafeRelativePath(path);
        Uri current = root;
        String[] segments = safePath.split("/");
        for (int index = 0; index < segments.length; index++) {
            current = findChild(resolver, current, segments[index]);
            if (current == null) {
                return null;
            }
            if ((index < segments.length - 1 || requireDirectory) && !isDirectory(resolver, current)) {
                throw new IOException("Path component is not a directory: " + segments[index]);
            }
        }
        return current;
    }

    private static Uri createPathAndFile(
            ContentResolver resolver,
            Uri root,
            String path
    ) throws IOException {
        String safePath = ReplacementRules.requireSafeRelativePath(path);
        String[] segments = safePath.split("/");
        Uri parent = root;
        for (int index = 0; index < segments.length - 1; index++) {
            parent = createDirectoryExact(resolver, parent, segments[index]);
        }
        return createFileExact(resolver, parent, segments[segments.length - 1]);
    }

    private static HashResult copyAndVerify(
            ContentResolver resolver,
            Uri source,
            Uri target,
            String stage,
            long baseCompleted,
            long totalWork,
            ProgressListener listener,
            HashResult expectedSource
    ) throws IOException {
        MessageDigest digest = newDigest();
        long bytes = 0L;
        long nextProgress = PROGRESS_INTERVAL_BYTES;
        try (InputStream rawInput = resolver.openInputStream(source);
             OutputStream rawOutput = resolver.openOutputStream(target, "w")) {
            if (rawInput == null || rawOutput == null) {
                throw new IOException("Android did not provide a required document stream");
            }
            try (BufferedInputStream input = new BufferedInputStream(rawInput, COPY_BUFFER_BYTES);
                 BufferedOutputStream output = new BufferedOutputStream(rawOutput, COPY_BUFFER_BYTES)) {
                byte[] buffer = new byte[COPY_BUFFER_BYTES];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    checkInterrupted();
                    if (read == 0) {
                        continue;
                    }
                    output.write(buffer, 0, read);
                    digest.update(buffer, 0, read);
                    bytes = safeAdd(bytes, read);
                    if (bytes >= nextProgress) {
                        notifyProgress(listener, stage,
                                safeAdd(baseCompleted, bytes), totalWork);
                        nextProgress = safeAdd(nextProgress, PROGRESS_INTERVAL_BYTES);
                    }
                }
                output.flush();
            }
        }
        HashResult sourceHash = new HashResult(bytes, hex(digest.digest()));
        if (expectedSource != null) {
            requireHash(sourceHash, expectedSource.bytes, expectedSource.sha256,
                    "Source file changed while it was being copied");
        }
        HashResult targetHash = hashDocument(
                resolver,
                target,
                "Verifying " + stage,
                safeAdd(baseCompleted, bytes),
                totalWork,
                listener
        );
        requireHash(targetHash, sourceHash.bytes, sourceHash.sha256,
                "Copied file SHA-256 verification failed");
        notifyProgress(listener, "Verified " + stage,
                safeAdd(safeAdd(baseCompleted, bytes), bytes), totalWork);
        return sourceHash;
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
        try (InputStream rawInput = resolver.openInputStream(uri)) {
            if (rawInput == null) {
                throw new IOException("Android did not provide a document input stream");
            }
            try (BufferedInputStream input = new BufferedInputStream(rawInput, COPY_BUFFER_BYTES)) {
                byte[] buffer = new byte[COPY_BUFFER_BYTES];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    checkInterrupted();
                    if (read == 0) {
                        continue;
                    }
                    digest.update(buffer, 0, read);
                    bytes = safeAdd(bytes, read);
                    if (bytes >= nextProgress) {
                        notifyProgress(listener, stage, safeAdd(baseCompleted, bytes), totalWork);
                        nextProgress = safeAdd(nextProgress, PROGRESS_INTERVAL_BYTES);
                    }
                }
            }
        }
        notifyProgress(listener, stage, safeAdd(baseCompleted, bytes), totalWork);
        return new HashResult(bytes, hex(digest.digest()));
    }

    private static void restoreFileFromVerifiedSource(
            ContentResolver resolver,
            Uri targetParent,
            String targetName,
            Uri verifiedSource,
            String stage,
            ProgressListener listener
    ) throws IOException {
        HashResult sourceHash = hashDocument(resolver, verifiedSource, stage, 0L, 0L, listener);
        Uri existing = findChild(resolver, targetParent, targetName);
        if (existing != null) {
            deleteDocument(resolver, existing);
        }
        Uri target = createFileExact(resolver, targetParent, targetName);
        copyAndVerify(
                resolver,
                verifiedSource,
                target,
                stage,
                0L,
                safeMultiply(sourceHash.bytes, 2L),
                listener,
                sourceHash
        );
    }

    private static void restoreLogs(
            ContentResolver resolver,
            Uri workspaceProjectUri,
            String assetIndex,
            String workingManifest,
            String workingHashes,
            String history,
            String latest
    ) throws IOException {
        if (assetIndex == null || workingManifest == null || workingHashes == null) {
            return;
        }
        WorkspacePaths workspace = requireWorkspaceStructure(resolver, workspaceProjectUri);
        writeOrReplaceText(resolver, workspace.logs, WORKING_ASSET_INDEX, assetIndex);
        writeOrReplaceText(resolver, workspace.logs, WORKING_MANIFEST, workingManifest);
        writeOrReplaceText(resolver, workspace.logs, WORKING_HASHES, workingHashes);
        if (history != null) {
            if (history.isEmpty()) {
                Uri currentHistory = findChild(resolver, workspace.logs, REPLACEMENT_HISTORY);
                if (currentHistory != null) {
                    deleteDocument(resolver, currentHistory);
                }
            } else {
                writeOrReplaceText(resolver, workspace.logs, REPLACEMENT_HISTORY, history);
            }
        }
        if (latest != null) {
            if (latest.isEmpty()) {
                Uri currentLatest = findChild(resolver, workspace.logs, LATEST_REPLACEMENT);
                if (currentLatest != null) {
                    deleteDocument(resolver, currentLatest);
                }
            } else {
                writeOrReplaceText(resolver, workspace.logs, LATEST_REPLACEMENT, latest);
            }
        }
    }

    private static String appendHistory(
            String existing,
            TransactionData transaction,
            String event
    ) {
        StringBuilder output = new StringBuilder();
        if (existing == null || existing.trim().isEmpty()) {
            output.append("event_utc,event,transaction_id,target_path,old_size,old_sha256,new_size,new_sha256\n");
        } else {
            output.append(existing);
            if (!existing.endsWith("\n")) {
                output.append('\n');
            }
        }
        output.append(ReplacementRules.csv(utcTimestamp())).append(',')
                .append(ReplacementRules.csv(event)).append(',')
                .append(ReplacementRules.csv(transaction.transactionId)).append(',')
                .append(ReplacementRules.csv(transaction.targetPath)).append(',')
                .append(transaction.oldSize).append(',')
                .append(transaction.oldSha256).append(',')
                .append(transaction.replacementSize).append(',')
                .append(transaction.replacementSha256).append('\n');
        return output.toString();
    }

    private static String buildLatestReplacement(TransactionData transaction) {
        StringBuilder output = new StringBuilder();
        output.append("phase=1D\n");
        output.append("state=").append(transaction.state).append('\n');
        output.append("transaction_id=").append(transaction.transactionId).append('\n');
        output.append("target_path_base64=").append(encode(transaction.targetPath)).append('\n');
        output.append("old_size=").append(transaction.oldSize).append('\n');
        output.append("old_sha256=").append(transaction.oldSha256).append('\n');
        output.append("replacement_size=").append(transaction.replacementSize).append('\n');
        output.append("replacement_sha256=").append(transaction.replacementSha256).append('\n');
        return output.toString();
    }

    private static TransactionData readTransaction(
            ContentResolver resolver,
            Uri transactionUri
    ) throws IOException {
        Uri manifestUri = requireChild(resolver, transactionUri, TRANSACTION_MANIFEST);
        Map<String, String> values = parseProperties(readText(resolver, manifestUri, 1024 * 1024));
        TransactionData transaction = new TransactionData();
        transaction.state = requireProperty(values, "state");
        transaction.transactionId = requireProperty(values, "transaction_id");
        transaction.targetPath = ReplacementRules.requireSafeRelativePath(
                decode(requireProperty(values, "target_path_base64"))
        );
        transaction.targetName = decode(requireProperty(values, "target_name_base64"));
        if (!transaction.targetName.equals(ReplacementRules.fileName(transaction.targetPath))) {
            throw new IOException("Transaction filename does not match its target path");
        }
        transaction.oldSize = parseLongProperty(values, "old_size");
        transaction.oldSha256 = requireSha256Property(values, "old_sha256");
        transaction.replacementSize = parseLongProperty(values, "replacement_size");
        transaction.replacementSha256 = requireSha256Property(values, "replacement_sha256");
        transaction.createdUtc = values.getOrDefault("created_utc", "");
        transaction.appliedUtc = values.getOrDefault("applied_utc", "");
        transaction.rolledBackUtc = values.getOrDefault("rolled_back_utc", "");
        return transaction;
    }

    private static String buildTransactionManifest(TransactionData transaction) {
        StringBuilder output = new StringBuilder();
        output.append("phase=1D\n");
        output.append("state=").append(transaction.state).append('\n');
        output.append("transaction_id=").append(transaction.transactionId).append('\n');
        output.append("target_path_base64=").append(encode(transaction.targetPath)).append('\n');
        output.append("target_name_base64=").append(encode(transaction.targetName)).append('\n');
        output.append("old_size=").append(transaction.oldSize).append('\n');
        output.append("old_sha256=").append(transaction.oldSha256).append('\n');
        output.append("replacement_size=").append(transaction.replacementSize).append('\n');
        output.append("replacement_sha256=").append(transaction.replacementSha256).append('\n');
        output.append("created_utc=").append(transaction.createdUtc == null ? "" : transaction.createdUtc).append('\n');
        output.append("applied_utc=").append(transaction.appliedUtc == null ? "" : transaction.appliedUtc).append('\n');
        output.append("rolled_back_utc=").append(transaction.rolledBackUtc == null ? "" : transaction.rolledBackUtc).append('\n');
        output.append("rollback_location=30_patch_import/phase1d_staging/")
                .append(transaction.transactionId).append('/').append(ROLLBACK_DIR).append('\n');
        return output.toString();
    }

    private static Map<String, String> parseProperties(String text) {
        Map<String, String> values = new HashMap<>();
        if (text == null) {
            return values;
        }
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n");
        for (String line : lines) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            int equals = line.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            values.put(line.substring(0, equals), line.substring(equals + 1));
        }
        return values;
    }

    private static String requireProperty(Map<String, String> values, String key) throws IOException {
        String value = values.get(key);
        if (value == null || value.trim().isEmpty()) {
            throw new IOException("Transaction property is missing: " + key);
        }
        return value.trim();
    }

    private static long parseLongProperty(Map<String, String> values, String key) throws IOException {
        try {
            long parsed = Long.parseLong(requireProperty(values, key));
            if (parsed < 0L) {
                throw new NumberFormatException("negative");
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw new IOException("Invalid transaction number: " + key, error);
        }
    }

    private static String requireSha256Property(Map<String, String> values, String key)
            throws IOException {
        String hash = requireProperty(values, key).toLowerCase(Locale.US);
        if (hash.length() != 64) {
            throw new IOException("Invalid transaction SHA-256: " + key);
        }
        for (int index = 0; index < hash.length(); index++) {
            char character = hash.charAt(index);
            if (!((character >= '0' && character <= '9') || (character >= 'a' && character <= 'f'))) {
                throw new IOException("Invalid transaction SHA-256: " + key);
            }
        }
        return hash;
    }

    private static void requireState(TransactionData transaction, String state) throws IOException {
        if (!state.equals(transaction.state)) {
            throw new IOException("Transaction state is " + transaction.state + ", expected " + state);
        }
    }

    private static void requireHash(
            HashResult actual,
            long expectedBytes,
            String expectedSha256,
            String message
    ) throws IOException {
        if (actual == null || actual.bytes != expectedBytes
                || expectedSha256 == null
                || !expectedSha256.equalsIgnoreCase(actual.sha256)) {
            throw new IOException(message);
        }
    }

    private static long probeAvailableBytes(ContentResolver resolver, Uri parent) throws IOException {
        String name = ".phase1d_space_probe_" + Long.toHexString(System.nanoTime());
        Uri probe = null;
        try {
            probe = createFileExact(resolver, parent, name);
            try (OutputStream output = resolver.openOutputStream(probe, "w")) {
                if (output == null) {
                    throw new IOException("Workspace write test returned no output stream");
                }
                output.write(1);
                output.flush();
            }
            try (ParcelFileDescriptor descriptor = resolver.openFileDescriptor(probe, "r")) {
                if (descriptor == null) {
                    throw new IOException("Workspace free-space measurement is unavailable");
                }
                try {
                    StructStatVfs stat = Os.fstatvfs(descriptor.getFileDescriptor());
                    return safeMultiply(stat.f_bavail, stat.f_frsize);
                } catch (ErrnoException | RuntimeException error) {
                    throw new IOException("Workspace free-space measurement failed", error);
                }
            }
        } finally {
            if (probe != null) {
                deleteQuietly(resolver, probe);
            }
        }
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
            throw new IOException("Could not create directory: " + name);
        }
        Metadata metadata = queryMetadata(resolver, created);
        if (!name.equals(metadata.name)
                || !DocumentsContract.Document.MIME_TYPE_DIR.equals(metadata.mimeType)) {
            deleteQuietly(resolver, created);
            throw new IOException("Provider changed the required directory name: " + name);
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
            throw new IOException("Could not create file: " + name);
        }
        Metadata metadata = queryMetadata(resolver, created);
        if (!name.equals(metadata.name)
                || DocumentsContract.Document.MIME_TYPE_DIR.equals(metadata.mimeType)) {
            deleteQuietly(resolver, created);
            throw new IOException("Provider changed the required file name: " + name);
        }
        return created;
    }

    private static void writeOrReplaceText(
            ContentResolver resolver,
            Uri parent,
            String name,
            String text
    ) throws IOException {
        Uri existing = findChild(resolver, parent, name);
        if (existing != null) {
            deleteDocument(resolver, existing);
        }
        Uri file = createFileExact(resolver, parent, name);
        try (OutputStream output = resolver.openOutputStream(file, "w")) {
            if (output == null) {
                throw new IOException("Android returned no output stream for " + name);
            }
            output.write((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
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
                throw new IOException("Android returned no input stream for a workspace record");
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[32 * 1024];
            int total = 0;
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) {
                    continue;
                }
                total += read;
                if (total > maximumBytes) {
                    throw new IOException("Workspace record exceeds its safe size limit");
                }
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        }
    }

    private static Metadata queryMetadata(ContentResolver resolver, Uri uri) throws IOException {
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
            String name = nameIndex >= 0 && !cursor.isNull(nameIndex) ? cursor.getString(nameIndex) : "";
            String mimeType = mimeIndex >= 0 && !cursor.isNull(mimeIndex)
                    ? cursor.getString(mimeIndex)
                    : "application/octet-stream";
            long size = sizeIndex >= 0 && !cursor.isNull(sizeIndex) ? cursor.getLong(sizeIndex) : -1L;
            return new Metadata(name, mimeType, size);
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
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0 && !cursor.isNull(index)) {
                    String name = cursor.getString(index);
                    if (name != null && !name.trim().isEmpty()) {
                        return name.trim();
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Fallback below.
        }
        return fallback;
    }

    private static boolean isDirectory(ContentResolver resolver, Uri uri) throws IOException {
        return DocumentsContract.Document.MIME_TYPE_DIR.equals(queryMetadata(resolver, uri).mimeType);
    }

    private static void deleteDocument(ContentResolver resolver, Uri uri) throws IOException {
        if (!DocumentsContract.deleteDocument(resolver, uri)) {
            throw new IOException("Document provider refused to delete a workspace item");
        }
    }

    private static void deleteQuietly(ContentResolver resolver, Uri uri) {
        try {
            DocumentsContract.deleteDocument(resolver, uri);
        } catch (IOException | RuntimeException ignored) {
            // Best-effort cleanup only.
        }
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

    private static String newTransactionId() {
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

    private static MessageDigest newDigest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException error) {
            throw new IOException("SHA-256 is unavailable", error);
        }
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (value == null ? "" : value).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String decode(String value) throws IOException {
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid transaction text encoding", error);
        }
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
            throw new IOException("Operation interrupted");
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

    private static long safeAdd(long first, long second) {
        if (second > 0L && first > Long.MAX_VALUE - second) {
            return Long.MAX_VALUE;
        }
        return first + second;
    }

    private static long safeMultiply(long first, long second) {
        if (first <= 0L || second <= 0L) {
            return 0L;
        }
        if (first > Long.MAX_VALUE / second) {
            return Long.MAX_VALUE;
        }
        return first * second;
    }

    private static String safeMessage(Throwable error) {
        String message = error == null ? "Unknown error" : error.getMessage();
        return message == null || message.trim().isEmpty()
                ? (error == null ? "Unknown error" : error.getClass().getSimpleName())
                : message.trim();
    }

    private static final class WorkspacePaths {
        private final Uri project;
        private final Uri workingRoot;
        private final Uri patchImport;
        private final Uri logs;

        private WorkspacePaths(Uri project, Uri workingRoot, Uri patchImport, Uri logs) {
            this.project = project;
            this.workingRoot = workingRoot;
            this.patchImport = patchImport;
            this.logs = logs;
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

    private static final class HashResult {
        private final long bytes;
        private final String sha256;

        private HashResult(long bytes, String sha256) {
            this.bytes = bytes;
            this.sha256 = sha256;
        }
    }

    private static final class TransactionData {
        private String state = "";
        private String transactionId = "";
        private String targetPath = "";
        private String targetName = "";
        private long oldSize = -1L;
        private String oldSha256 = "";
        private long replacementSize = -1L;
        private String replacementSha256 = "";
        private String createdUtc = "";
        private String appliedUtc = "";
        private String rolledBackUtc = "";
    }
}
