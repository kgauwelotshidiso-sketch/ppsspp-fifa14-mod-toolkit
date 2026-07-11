package com.tshidiso.ppssppmodtoolkit;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQUEST_GAME_FILE = 1001;
    private static final int REQUEST_GAME_FOLDER = 1002;
    private static final int REQUEST_PATCH_ZIP = 1003;
    private static final int REQUEST_BACKUP_FOLDER = 1004;
    private static final int REQUEST_WORKSPACE_FOLDER = 1005;

    private static final int COLOR_BACKGROUND = Color.rgb(10, 17, 28);
    private static final int COLOR_CARD = Color.rgb(18, 29, 44);
    private static final int COLOR_CARD_ALT = Color.rgb(14, 24, 37);
    private static final int COLOR_PRIMARY = Color.rgb(54, 211, 153);
    private static final int COLOR_PRIMARY_DARK = Color.rgb(9, 73, 58);
    private static final int COLOR_TEXT = Color.rgb(241, 245, 249);
    private static final int COLOR_MUTED = Color.rgb(159, 176, 195);
    private static final int COLOR_BORDER = Color.rgb(47, 65, 85);
    private static final int COLOR_WARNING = Color.rgb(251, 191, 36);

    private final ExecutorService operationExecutor = Executors.newSingleThreadExecutor();

    private Uri gameFileUri;
    private Uri gameFolderUri;
    private Uri patchZipUri;
    private Uri backupTreeUri;
    private Uri workspaceTreeUri;

    private TextView sourceStatusView;
    private TextView backupStatusView;
    private TextView latestBackupStatusView;
    private TextView workspaceStatusView;
    private TextView latestWorkspaceStatusView;
    private TextView patchStatusView;
    private TextView operationStatusView;
    private TextView reportView;

    private Button chooseGameFileButton;
    private Button chooseGameFolderButton;
    private Button scanSourceButton;
    private Button chooseBackupFolderButton;
    private Button checkBackupButton;
    private Button createBackupButton;
    private Button chooseWorkspaceFolderButton;
    private Button prepareWorkspaceButton;
    private Button choosePatchButton;
    private Button scanPatchButton;
    private Button resetButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().setStatusBarColor(COLOR_BACKGROUND);
        getWindow().setNavigationBarColor(COLOR_BACKGROUND);

        gameFileUri = SelectionStore.loadGameUri(this);
        gameFolderUri = SelectionStore.loadFolderUri(this);
        patchZipUri = SelectionStore.loadPatchUri(this);
        backupTreeUri = SelectionStore.loadBackupTreeUri(this);
        workspaceTreeUri = SelectionStore.loadWorkspaceTreeUri(this);

        if (gameFileUri != null && gameFolderUri != null) {
            gameFolderUri = null;
            SelectionStore.saveFolderUri(this, null);
        }

        setContentView(buildScreen());
        updateSelectionViews();
    }

    private View buildScreen() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(COLOR_BACKGROUND);
        scrollView.setClipToPadding(false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(32));
        root.setBackgroundColor(COLOR_BACKGROUND);

        final int baseLeft = dp(18);
        final int baseTop = dp(24);
        final int baseRight = dp(18);
        final int baseBottom = dp(32);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            view.setPadding(baseLeft, baseTop + top, baseRight, baseBottom + bottom);
            return insets;
        });

        TextView badge = textView(
                "PHASE 1B  •  VERIFIED BACKUP & WORKSPACE",
                12,
                COLOR_PRIMARY,
                Typeface.BOLD
        );
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(12), dp(8), dp(12), dp(8));
        badge.setBackground(roundedBackground(COLOR_PRIMARY_DARK, COLOR_PRIMARY, 999));
        root.addView(badge, wrapContentCentered());

        TextView title = textView("PPSSPP Mod Toolkit", 30, COLOR_TEXT, Typeface.BOLD);
        title.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams titleParams = matchWidthWrapHeight();
        titleParams.topMargin = dp(18);
        root.addView(title, titleParams);

        TextView subtitle = textView(
                "FIFA 14 PSP source scanning, SHA-256 verified backups, storage checks, and controlled modding workspaces.",
                15,
                COLOR_MUTED,
                Typeface.NORMAL
        );
        subtitle.setGravity(Gravity.CENTER_HORIZONTAL);
        subtitle.setLineSpacing(0f, 1.18f);
        LinearLayout.LayoutParams subtitleParams = matchWidthWrapHeight();
        subtitleParams.topMargin = dp(10);
        subtitleParams.bottomMargin = dp(22);
        root.addView(subtitle, subtitleParams);

        LinearLayout sourceCard = createCard();
        sourceCard.addView(sectionTitle("1. Choose and scan the FIFA 14 source"));
        sourceCard.addView(sectionBody(
                "Select either the ISO/CSO/PBP used by PPSSPP or an extracted PSP game folder. Changing the source clears the saved backup/workspace link because it may belong to another game."
        ));

        sourceStatusView = statusPanel();
        sourceCard.addView(sourceStatusView, statusPanelParams());

        chooseGameFileButton = actionButton("Choose ISO / CSO / PBP", false);
        chooseGameFileButton.setOnClickListener(view -> openGameFilePicker());
        sourceCard.addView(chooseGameFileButton, buttonParams());

        chooseGameFolderButton = actionButton("Choose extracted game folder", false);
        chooseGameFolderButton.setOnClickListener(view -> openGameFolderPicker());
        sourceCard.addView(chooseGameFolderButton, buttonParams());

        scanSourceButton = actionButton("Run source scan", true);
        scanSourceButton.setOnClickListener(view -> runSourceScan());
        sourceCard.addView(scanSourceButton, primaryButtonParams());

        root.addView(sourceCard, cardParams());

        LinearLayout backupCard = createCard();
        backupCard.addView(sectionTitle("2. Create a verified backup"));
        backupCard.addView(sectionBody(
                "Choose a destination folder. The app performs a write test, checks measurable free space, copies the complete source, then reopens the backup and compares SHA-256 hashes."
        ));

        backupStatusView = statusPanel();
        backupCard.addView(backupStatusView, statusPanelParams());

        latestBackupStatusView = statusPanel();
        backupCard.addView(latestBackupStatusView, secondaryStatusPanelParams());

        chooseBackupFolderButton = actionButton("Choose backup destination", false);
        chooseBackupFolderButton.setOnClickListener(view -> openWritableTreePicker(REQUEST_BACKUP_FOLDER));
        backupCard.addView(chooseBackupFolderButton, buttonParams());

        checkBackupButton = actionButton("Check backup readiness", false);
        checkBackupButton.setOnClickListener(view -> runBackupReadinessCheck());
        backupCard.addView(checkBackupButton, buttonParams());

        createBackupButton = actionButton("Create and verify backup", true);
        createBackupButton.setOnClickListener(view -> runCreateBackup());
        backupCard.addView(createBackupButton, primaryButtonParams());

        root.addView(backupCard, cardParams());

        LinearLayout workspaceCard = createCard();
        workspaceCard.addView(sectionTitle("3. Prepare the controlled workspace"));
        workspaceCard.addView(sectionBody(
                "A verified backup is mandatory. The app checks workspace storage and creates separate original, working, patch-import, rebuilt-output, and log folders. Source extraction is deliberately reserved for the next sprint."
        ));

        workspaceStatusView = statusPanel();
        workspaceCard.addView(workspaceStatusView, statusPanelParams());

        latestWorkspaceStatusView = statusPanel();
        workspaceCard.addView(latestWorkspaceStatusView, secondaryStatusPanelParams());

        chooseWorkspaceFolderButton = actionButton("Choose workspace destination", false);
        chooseWorkspaceFolderButton.setOnClickListener(view -> openWritableTreePicker(REQUEST_WORKSPACE_FOLDER));
        workspaceCard.addView(chooseWorkspaceFolderButton, buttonParams());

        prepareWorkspaceButton = actionButton("Prepare workspace", true);
        prepareWorkspaceButton.setOnClickListener(view -> runPrepareWorkspace());
        workspaceCard.addView(prepareWorkspaceButton, primaryButtonParams());

        root.addView(workspaceCard, cardParams());

        LinearLayout patchCard = createCard();
        patchCard.addView(sectionTitle("4. Inspect a mod patch ZIP"));
        patchCard.addView(sectionBody(
                "The app checks the ZIP signature, lists likely modding assets, and blocks dangerous parent-folder paths. Patch installation remains disabled in Phase 1B."
        ));

        patchStatusView = statusPanel();
        patchCard.addView(patchStatusView, statusPanelParams());

        choosePatchButton = actionButton("Choose patch ZIP", false);
        choosePatchButton.setOnClickListener(view -> openPatchPicker());
        patchCard.addView(choosePatchButton, buttonParams());

        scanPatchButton = actionButton("Inspect patch package", true);
        scanPatchButton.setOnClickListener(view -> runPatchScan());
        patchCard.addView(scanPatchButton, primaryButtonParams());

        root.addView(patchCard, cardParams());

        LinearLayout reportCard = createCard();
        reportCard.addView(sectionTitle("Operation report"));

        operationStatusView = textView(
                "Ready. Start with a source scan or backup readiness check.",
                13,
                COLOR_WARNING,
                Typeface.BOLD
        );
        LinearLayout.LayoutParams operationParams = matchWidthWrapHeight();
        operationParams.topMargin = dp(8);
        operationParams.bottomMargin = dp(12);
        reportCard.addView(operationStatusView, operationParams);

        reportView = textView(
                "No Phase 1B operation has been run yet.",
                14,
                COLOR_TEXT,
                Typeface.NORMAL
        );
        reportView.setTextIsSelectable(true);
        reportView.setLineSpacing(0f, 1.18f);
        reportView.setPadding(dp(14), dp(14), dp(14), dp(14));
        reportView.setBackground(roundedBackground(COLOR_CARD_ALT, COLOR_BORDER, 14));
        reportCard.addView(reportView, matchWidthWrapHeight());

        resetButton = actionButton("Reset all selections and records", false);
        resetButton.setOnClickListener(view -> resetSelections());
        LinearLayout.LayoutParams resetParams = buttonParams();
        resetParams.topMargin = dp(16);
        reportCard.addView(resetButton, resetParams);

        root.addView(reportCard, cardParams());

        TextView footer = textView(
                "Phase 1B may write only inside the backup and workspace folders you choose. It never edits the selected game source and does not yet replace files inside an ISO/CSO.",
                12,
                COLOR_MUTED,
                Typeface.NORMAL
        );
        footer.setGravity(Gravity.CENTER_HORIZONTAL);
        footer.setLineSpacing(0f, 1.15f);
        LinearLayout.LayoutParams footerParams = matchWidthWrapHeight();
        footerParams.topMargin = dp(4);
        root.addView(footer, footerParams);

        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        return scrollView;
    }

    private void openGameFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_GAME_FILE);
    }

    private void openGameFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_GAME_FOLDER);
    }

    private void openPatchPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_PATCH_ZIP);
    }

    private void openWritableTreePicker(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        startActivityForResult(intent, requestCode);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Uri selectedUri = data.getData();
        if (requestCode == REQUEST_BACKUP_FOLDER || requestCode == REQUEST_WORKSPACE_FOLDER) {
            persistReadWritePermission(selectedUri, data);
        } else {
            persistReadPermission(selectedUri, data);
        }

        if (requestCode == REQUEST_GAME_FILE) {
            boolean sourceChanged = gameFolderUri != null || !urisEqual(gameFileUri, selectedUri);
            releaseReadPermission(gameFolderUri);
            gameFileUri = selectedUri;
            gameFolderUri = null;
            SelectionStore.saveGameUri(this, gameFileUri);
            SelectionStore.saveFolderUri(this, null);
            if (sourceChanged) {
                clearSourceDependentRecords();
            }
            reportView.setText("Game image selected. Run the source scan, then check backup readiness.");
            operationStatusView.setText(sourceChanged
                    ? "Source changed — a new verified backup is required."
                    : "The same game image was reselected.");
        } else if (requestCode == REQUEST_GAME_FOLDER) {
            boolean sourceChanged = gameFileUri != null || !urisEqual(gameFolderUri, selectedUri);
            releaseReadPermission(gameFileUri);
            gameFolderUri = selectedUri;
            gameFileUri = null;
            SelectionStore.saveFolderUri(this, gameFolderUri);
            SelectionStore.saveGameUri(this, null);
            if (sourceChanged) {
                clearSourceDependentRecords();
            }
            reportView.setText("Extracted folder selected. Run the source scan, then check backup readiness.");
            operationStatusView.setText(sourceChanged
                    ? "Source changed — a new verified backup is required."
                    : "The same extracted folder was reselected.");
        } else if (requestCode == REQUEST_PATCH_ZIP) {
            if (!urisEqual(patchZipUri, selectedUri)) {
                releaseReadPermission(patchZipUri);
            }
            patchZipUri = selectedUri;
            SelectionStore.savePatchUri(this, patchZipUri);
            reportView.setText("Patch package selected. Tap “Inspect patch package” to scan it.");
            operationStatusView.setText("Patch selected — inspection not yet run.");
        } else if (requestCode == REQUEST_BACKUP_FOLDER) {
            if (!urisEqual(backupTreeUri, selectedUri)) {
                releaseReadWritePermission(backupTreeUri);
            }
            backupTreeUri = selectedUri;
            SelectionStore.saveBackupTreeUri(this, backupTreeUri);
            reportView.setText("Backup destination selected. Run the readiness check before copying.");
            operationStatusView.setText("Backup destination selected.");
        } else if (requestCode == REQUEST_WORKSPACE_FOLDER) {
            if (!urisEqual(workspaceTreeUri, selectedUri)) {
                releaseReadWritePermission(workspaceTreeUri);
            }
            workspaceTreeUri = selectedUri;
            SelectionStore.saveWorkspaceTreeUri(this, workspaceTreeUri);
            reportView.setText("Workspace destination selected. A verified backup is still required before preparation.");
            operationStatusView.setText("Workspace destination selected.");
        }

        updateSelectionViews();
    }

    private void persistReadPermission(Uri uri, Intent resultData) {
        boolean readGranted = (resultData.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0;
        if (!readGranted) {
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (SecurityException ignored) {
            // Some document providers grant only session access.
        }
    }

    private void persistReadWritePermission(Uri uri, Intent resultData) {
        boolean readGranted = (resultData.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0;
        boolean writeGranted = (resultData.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0;
        if (!readGranted || !writeGranted) {
            showToast("This folder did not grant both read and write access.");
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            );
        } catch (SecurityException ignored) {
            // The readiness write test will detect providers that do not preserve access.
        }
    }

    private void releaseReadPermission(Uri uri) {
        if (uri == null) {
            return;
        }
        try {
            getContentResolver().releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (SecurityException ignored) {
            // Permission may be session-only, already released, or provider-managed.
        }
    }

    private void releaseReadWritePermission(Uri uri) {
        if (uri == null) {
            return;
        }
        try {
            getContentResolver().releasePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            );
        } catch (SecurityException ignored) {
            // Permission may be session-only, already released, or provider-managed.
        }
    }

    private void runSourceScan() {
        if (!hasSource()) {
            showToast("Choose a game file or extracted folder first.");
            return;
        }

        final Uri selectedFile = gameFileUri;
        final Uri selectedFolder = gameFolderUri;
        setBusy(true, "Scanning the selected game source…");

        operationExecutor.execute(() -> {
            ScanReport report = selectedFolder != null
                    ? GameScanner.scanFolder(getApplicationContext(), selectedFolder)
                    : GameScanner.scanGameFile(getApplicationContext(), selectedFile);
            finishReport(report, "Source scan complete.");
        });
    }

    private void runBackupReadinessCheck() {
        if (!hasSource()) {
            showToast("Choose a game source first.");
            return;
        }
        if (backupTreeUri == null) {
            showToast("Choose a backup destination first.");
            return;
        }

        final Uri selectedFile = gameFileUri;
        final Uri selectedFolder = gameFolderUri;
        final Uri selectedBackupTree = backupTreeUri;
        setBusy(true, "Checking source size, write access, and destination space…");

        operationExecutor.execute(() -> {
            ScanReport report = BackupEngine.checkBackupReadiness(
                    getApplicationContext(),
                    selectedFile,
                    selectedFolder,
                    selectedBackupTree
            );
            finishReport(report, "Backup readiness check complete.");
        });
    }

    private void runCreateBackup() {
        if (!hasSource()) {
            showToast("Choose a game source first.");
            return;
        }
        if (backupTreeUri == null) {
            showToast("Choose a backup destination first.");
            return;
        }

        final Uri selectedFile = gameFileUri;
        final Uri selectedFolder = gameFolderUri;
        final Uri selectedBackupTree = backupTreeUri;
        setBusy(true, "Creating the complete backup. Keep the app open and the phone charged…");

        operationExecutor.execute(() -> {
            OperationResult result = BackupEngine.createVerifiedBackup(
                    getApplicationContext(),
                    selectedFile,
                    selectedFolder,
                    selectedBackupTree,
                    this::showBackupProgress
            );

            runOnUiThread(() -> {
                if (isActivityUnavailable()) {
                    return;
                }
                if (result.isSuccess()) {
                    SelectionStore.saveLatestVerifiedBackup(
                            this,
                            result.getCreatedUri(),
                            result.getReference()
                    );
                    SelectionStore.clearLatestWorkspace(this);
                }
                reportView.setText(result.getReport().toDisplayText());
                setBusy(false, result.isSuccess()
                        ? "Verified backup complete."
                        : "Backup stopped safely.");
                updateSelectionViews();
            });
        });
    }

    private void runPrepareWorkspace() {
        if (!hasSource()) {
            showToast("Choose a game source first.");
            return;
        }
        if (workspaceTreeUri == null) {
            showToast("Choose a workspace destination first.");
            return;
        }

        String backupReference = SelectionStore.loadLatestBackupReference(this);
        if (backupReference == null || backupReference.trim().isEmpty()) {
            showToast("Create a verified backup before preparing the workspace.");
            return;
        }

        final Uri selectedFile = gameFileUri;
        final Uri selectedFolder = gameFolderUri;
        final Uri selectedWorkspaceTree = workspaceTreeUri;
        final String selectedBackupReference = backupReference;
        setBusy(true, "Checking workspace storage and creating the controlled folder structure…");

        operationExecutor.execute(() -> {
            OperationResult result = BackupEngine.prepareWorkspace(
                    getApplicationContext(),
                    selectedFile,
                    selectedFolder,
                    selectedWorkspaceTree,
                    selectedBackupReference
            );

            runOnUiThread(() -> {
                if (isActivityUnavailable()) {
                    return;
                }
                if (result.isSuccess()) {
                    SelectionStore.saveLatestWorkspace(
                            this,
                            result.getCreatedUri(),
                            result.getReference()
                    );
                }
                reportView.setText(result.getReport().toDisplayText());
                setBusy(false, result.isSuccess()
                        ? "Workspace prepared."
                        : "Workspace preparation stopped safely.");
                updateSelectionViews();
            });
        });
    }

    private void runPatchScan() {
        if (patchZipUri == null) {
            showToast("Choose a patch ZIP first.");
            return;
        }

        final Uri selectedPatch = patchZipUri;
        setBusy(true, "Inspecting the patch package…");

        operationExecutor.execute(() -> {
            ScanReport report = GameScanner.scanPatchZip(
                    getApplicationContext(),
                    selectedPatch
            );
            finishReport(report, "Patch inspection complete.");
        });
    }

    private void finishReport(ScanReport report, String status) {
        runOnUiThread(() -> {
            if (isActivityUnavailable()) {
                return;
            }
            reportView.setText(report.toDisplayText());
            setBusy(false, status);
            updateSelectionViews();
        });
    }

    private void showBackupProgress(String stage, long completedBytes, long totalBytes) {
        runOnUiThread(() -> {
            if (isActivityUnavailable()) {
                return;
            }
            String progress;
            if (totalBytes > 0L) {
                double percent = Math.min(100.0, (completedBytes * 100.0) / totalBytes);
                progress = String.format(
                        Locale.US,
                        "%s — %.1f%% (%s / %s)",
                        stage,
                        percent,
                        GameScanner.formatBytes(completedBytes),
                        GameScanner.formatBytes(totalBytes)
                );
            } else {
                progress = stage + " — " + GameScanner.formatBytes(completedBytes);
            }
            operationStatusView.setText(progress);
        });
    }

    private void clearSourceDependentRecords() {
        SelectionStore.clearLatestVerifiedBackup(this);
        SelectionStore.clearLatestWorkspace(this);
    }

    private void resetSelections() {
        releaseReadPermission(gameFileUri);
        releaseReadPermission(gameFolderUri);
        releaseReadPermission(patchZipUri);
        releaseReadWritePermission(backupTreeUri);
        releaseReadWritePermission(workspaceTreeUri);

        gameFileUri = null;
        gameFolderUri = null;
        patchZipUri = null;
        backupTreeUri = null;
        workspaceTreeUri = null;
        SelectionStore.clearAll(this);

        reportView.setText("Selections and app records cleared. Existing backup/workspace folders on storage were not deleted.");
        operationStatusView.setText("Ready. Choose the FIFA 14 source again.");
        updateSelectionViews();
    }

    private void updateSelectionViews() {
        if (gameFileUri != null) {
            sourceStatusView.setText(
                    "Selected game image\n" + GameScanner.describeUri(this, gameFileUri)
            );
        } else if (gameFolderUri != null) {
            sourceStatusView.setText(
                    "Selected extracted folder\n" + GameScanner.describeUri(this, gameFolderUri)
            );
        } else {
            sourceStatusView.setText("No game source selected");
        }

        if (backupTreeUri != null) {
            backupStatusView.setText(
                    "Backup destination\n" + BackupEngine.describeTree(this, backupTreeUri)
            );
        } else {
            backupStatusView.setText("No backup destination selected");
        }

        String backupReference = SelectionStore.loadLatestBackupReference(this);
        latestBackupStatusView.setText(
                backupReference == null || backupReference.trim().isEmpty()
                        ? "Verified backup record: none"
                        : "Latest verified backup\n" + backupReference
        );

        if (workspaceTreeUri != null) {
            workspaceStatusView.setText(
                    "Workspace destination\n" + BackupEngine.describeTree(this, workspaceTreeUri)
            );
        } else {
            workspaceStatusView.setText("No workspace destination selected");
        }

        String workspaceReference = SelectionStore.loadLatestWorkspaceReference(this);
        latestWorkspaceStatusView.setText(
                workspaceReference == null || workspaceReference.trim().isEmpty()
                        ? "Prepared workspace record: none"
                        : "Latest prepared workspace\n" + workspaceReference
        );

        if (patchZipUri != null) {
            patchStatusView.setText(
                    "Selected patch package\n" + GameScanner.describeUri(this, patchZipUri)
            );
        } else {
            patchStatusView.setText("No patch ZIP selected");
        }

        boolean hasSource = hasSource();
        boolean hasBackupDestination = backupTreeUri != null;
        boolean hasWorkspaceDestination = workspaceTreeUri != null;
        boolean hasVerifiedBackup = backupReference != null && !backupReference.trim().isEmpty();

        scanSourceButton.setEnabled(hasSource);
        checkBackupButton.setEnabled(hasSource && hasBackupDestination);
        createBackupButton.setEnabled(hasSource && hasBackupDestination);
        prepareWorkspaceButton.setEnabled(
                hasSource && hasWorkspaceDestination && hasVerifiedBackup
        );
        scanPatchButton.setEnabled(patchZipUri != null);
    }

    private boolean hasSource() {
        return gameFileUri != null || gameFolderUri != null;
    }

    private boolean urisEqual(Uri first, Uri second) {
        if (first == null || second == null) {
            return first == second;
        }
        return first.toString().equals(second.toString());
    }

    private void setBusy(boolean busy, String message) {
        operationStatusView.setText(message);
        if (busy) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }

        chooseGameFileButton.setEnabled(!busy);
        chooseGameFolderButton.setEnabled(!busy);
        chooseBackupFolderButton.setEnabled(!busy);
        chooseWorkspaceFolderButton.setEnabled(!busy);
        choosePatchButton.setEnabled(!busy);
        resetButton.setEnabled(!busy);

        if (busy) {
            scanSourceButton.setEnabled(false);
            checkBackupButton.setEnabled(false);
            createBackupButton.setEnabled(false);
            prepareWorkspaceButton.setEnabled(false);
            scanPatchButton.setEnabled(false);
        } else {
            updateSelectionViews();
        }
    }

    private boolean isActivityUnavailable() {
        return isFinishing() || isDestroyed();
    }

    @Override
    protected void onDestroy() {
        operationExecutor.shutdownNow();
        super.onDestroy();
    }

    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        card.setBackground(roundedBackground(COLOR_CARD, COLOR_BORDER, 18));
        return card;
    }

    private TextView sectionTitle(String text) {
        return textView(text, 19, COLOR_TEXT, Typeface.BOLD);
    }

    private TextView sectionBody(String text) {
        TextView view = textView(text, 14, COLOR_MUTED, Typeface.NORMAL);
        view.setLineSpacing(0f, 1.16f);
        LinearLayout.LayoutParams params = matchWidthWrapHeight();
        params.topMargin = dp(8);
        view.setLayoutParams(params);
        return view;
    }

    private TextView statusPanel() {
        TextView status = textView("Not selected", 14, COLOR_TEXT, Typeface.BOLD);
        status.setLineSpacing(0f, 1.12f);
        status.setPadding(dp(13), dp(12), dp(13), dp(12));
        status.setBackground(roundedBackground(COLOR_CARD_ALT, COLOR_BORDER, 13));
        return status;
    }

    private LinearLayout.LayoutParams secondaryStatusPanelParams() {
        LinearLayout.LayoutParams params = matchWidthWrapHeight();
        params.topMargin = dp(8);
        params.bottomMargin = dp(4);
        return params;
    }

    private Button actionButton(String text, boolean primary) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(50));
        button.setPadding(dp(14), dp(10), dp(14), dp(10));
        button.setStateListAnimator(null);
        button.setTextColor(primary ? COLOR_BACKGROUND : COLOR_TEXT);
        button.setBackgroundTintList(ColorStateList.valueOf(
                primary ? COLOR_PRIMARY : COLOR_CARD_ALT
        ));
        return button;
    }

    private TextView textView(String text, int sp, int color, int style) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setTypeface(Typeface.DEFAULT, style);
        return view;
    }

    private GradientDrawable roundedBackground(int fill, int stroke, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams cardParams() {
        LinearLayout.LayoutParams params = matchWidthWrapHeight();
        params.bottomMargin = dp(16);
        return params;
    }

    private LinearLayout.LayoutParams statusPanelParams() {
        LinearLayout.LayoutParams params = matchWidthWrapHeight();
        params.topMargin = dp(14);
        params.bottomMargin = dp(4);
        return params;
    }

    private LinearLayout.LayoutParams buttonParams() {
        LinearLayout.LayoutParams params = matchWidthWrapHeight();
        params.topMargin = dp(10);
        return params;
    }

    private LinearLayout.LayoutParams primaryButtonParams() {
        LinearLayout.LayoutParams params = matchWidthWrapHeight();
        params.topMargin = dp(14);
        return params;
    }

    private LinearLayout.LayoutParams matchWidthWrapHeight() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private LinearLayout.LayoutParams wrapContentCentered() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        params.gravity = Gravity.CENTER_HORIZONTAL;
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showToast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }
}
