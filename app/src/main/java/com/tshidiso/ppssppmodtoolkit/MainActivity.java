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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQUEST_GAME_FILE = 1001;
    private static final int REQUEST_GAME_FOLDER = 1002;
    private static final int REQUEST_PATCH_ZIP = 1003;
    private static final int REQUEST_BACKUP_FOLDER = 1004;
    private static final int REQUEST_WORKSPACE_FOLDER = 1005;
    private static final int REQUEST_REPLACEMENT_FILE = 1006;

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
    private Uri replacementFileUri;

    private final List<AssetRecord> assetMatches = new ArrayList<>();
    private int assetMatchIndex = -1;
    private AssetRecord selectedAsset;

    private TextView sourceStatusView;
    private TextView backupStatusView;
    private TextView latestBackupStatusView;
    private TextView workspaceStatusView;
    private TextView latestWorkspaceStatusView;
    private TextView extractionStatusView;
    private TextView workingCopyStatusView;
    private TextView assetBrowserStatusView;
    private TextView selectedAssetStatusView;
    private TextView databaseLabStatusView;
    private TextView replacementStatusView;
    private TextView stagedReplacementStatusView;
    private TextView appliedReplacementStatusView;
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
    private Button checkExtractionButton;
    private Button extractOriginalButton;
    private Button createWorkingCopyButton;
    private EditText assetSearchInput;
    private Button searchAssetsButton;
    private Button previousAssetButton;
    private Button nextAssetButton;
    private EditText databaseSearchInput;
    private EditText databaseReplacementInput;
    private EditText databaseOccurrenceInput;
    private Button inspectDatabaseButton;
    private Button searchDatabaseButton;
    private Button buildDatabaseEditButton;
    private Button chooseReplacementButton;
    private Button validateReplacementButton;
    private Button applyReplacementButton;
    private Button rollbackReplacementButton;
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
        replacementFileUri = SelectionStore.loadReplacementFileUri(this);

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
                "PHASE 1E  •  FIFA DATABASE LAB",
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
                "FIFA 14 PSP scanning, verified backups, database fingerprinting, exact text-offset editing, staged full-file replacement, and verified rollback inside the working copy.",
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
                "A verified backup is mandatory. The app checks workspace storage and creates separate protected-original, working, patch-import, rebuilt-output, and log areas."
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

        LinearLayout extractionCard = createCard();
        extractionCard.addView(sectionTitle("4. Extract, index, and verify the game filesystem"));
        extractionCard.addView(sectionBody(
                "The app reads the ISO filesystem without changing it, extracts every file into the protected original area, records exact internal paths and SHA-256 hashes, then can create a second verified working copy."
        ));

        extractionStatusView = statusPanel();
        extractionCard.addView(extractionStatusView, statusPanelParams());

        workingCopyStatusView = statusPanel();
        extractionCard.addView(workingCopyStatusView, secondaryStatusPanelParams());

        checkExtractionButton = actionButton("Check extraction readiness", false);
        checkExtractionButton.setOnClickListener(view -> runExtractionReadinessCheck());
        extractionCard.addView(checkExtractionButton, buttonParams());

        extractOriginalButton = actionButton("Extract and verify protected original", true);
        extractOriginalButton.setOnClickListener(view -> runExtractOriginal());
        extractionCard.addView(extractOriginalButton, primaryButtonParams());

        createWorkingCopyButton = actionButton("Create verified working copy", true);
        createWorkingCopyButton.setOnClickListener(view -> runCreateWorkingCopy());
        extractionCard.addView(createWorkingCopyButton, primaryButtonParams());

        root.addView(extractionCard, cardParams());

        LinearLayout assetCard = createCard();
        assetCard.addView(sectionTitle("5. Browse and select a working asset"));
        assetCard.addView(sectionBody(
                "Search the verified working asset index by exact path, filename, category, extension, or size. Examples: fifa, ext:db, type:database, path:data, min:1MB, max:20MB."
        ));

        assetBrowserStatusView = statusPanel();
        assetCard.addView(assetBrowserStatusView, statusPanelParams());

        assetSearchInput = new EditText(this);
        assetSearchInput.setTextSize(14);
        assetSearchInput.setTextColor(COLOR_TEXT);
        assetSearchInput.setHintTextColor(COLOR_MUTED);
        assetSearchInput.setHint("Search working assets");
        assetSearchInput.setSingleLine(true);
        assetSearchInput.setPadding(dp(13), dp(12), dp(13), dp(12));
        assetSearchInput.setBackground(roundedBackground(COLOR_CARD_ALT, COLOR_BORDER, 13));
        LinearLayout.LayoutParams searchInputParams = matchWidthWrapHeight();
        searchInputParams.topMargin = dp(10);
        assetCard.addView(assetSearchInput, searchInputParams);

        searchAssetsButton = actionButton("Search working asset index", true);
        searchAssetsButton.setOnClickListener(view -> runAssetSearch());
        assetCard.addView(searchAssetsButton, primaryButtonParams());

        previousAssetButton = actionButton("Previous match", false);
        previousAssetButton.setOnClickListener(view -> selectPreviousAsset());
        assetCard.addView(previousAssetButton, buttonParams());

        nextAssetButton = actionButton("Next match", false);
        nextAssetButton.setOnClickListener(view -> selectNextAsset());
        assetCard.addView(nextAssetButton, buttonParams());

        selectedAssetStatusView = statusPanel();
        assetCard.addView(selectedAssetStatusView, statusPanelParams());

        root.addView(assetCard, cardParams());

        LinearLayout databaseCard = createCard();
        databaseCard.addView(sectionTitle("6. Inspect and build an edited FIFA database"));
        databaseCard.addView(sectionBody(
                "Select fifa.db or another .db asset above. Phase 1E verifies its current SHA-256, fingerprints the binary structure, searches exact case-sensitive text at byte offsets, and can build a complete edited copy by replacing one occurrence with text using exactly the same UTF-8 byte length."
        ));

        databaseLabStatusView = statusPanel();
        databaseCard.addView(databaseLabStatusView, statusPanelParams());

        inspectDatabaseButton = actionButton("Inspect selected database", false);
        inspectDatabaseButton.setOnClickListener(view -> runDatabaseInspection());
        databaseCard.addView(inspectDatabaseButton, buttonParams());

        databaseSearchInput = new EditText(this);
        databaseSearchInput.setTextSize(14);
        databaseSearchInput.setTextColor(COLOR_TEXT);
        databaseSearchInput.setHintTextColor(COLOR_MUTED);
        databaseSearchInput.setHint("Find exact database text or marker");
        databaseSearchInput.setSingleLine(true);
        databaseSearchInput.setPadding(dp(13), dp(12), dp(13), dp(12));
        databaseSearchInput.setBackground(roundedBackground(COLOR_CARD_ALT, COLOR_BORDER, 13));
        LinearLayout.LayoutParams databaseSearchParams = matchWidthWrapHeight();
        databaseSearchParams.topMargin = dp(10);
        databaseCard.addView(databaseSearchInput, databaseSearchParams);

        searchDatabaseButton = actionButton("Search exact text and byte offsets", false);
        searchDatabaseButton.setOnClickListener(view -> runDatabaseTextSearch());
        databaseCard.addView(searchDatabaseButton, buttonParams());

        databaseReplacementInput = new EditText(this);
        databaseReplacementInput.setTextSize(14);
        databaseReplacementInput.setTextColor(COLOR_TEXT);
        databaseReplacementInput.setHintTextColor(COLOR_MUTED);
        databaseReplacementInput.setHint("Replacement text — exact same UTF-8 byte length");
        databaseReplacementInput.setSingleLine(true);
        databaseReplacementInput.setPadding(dp(13), dp(12), dp(13), dp(12));
        databaseReplacementInput.setBackground(roundedBackground(COLOR_CARD_ALT, COLOR_BORDER, 13));
        LinearLayout.LayoutParams databaseReplacementParams = matchWidthWrapHeight();
        databaseReplacementParams.topMargin = dp(10);
        databaseCard.addView(databaseReplacementInput, databaseReplacementParams);

        databaseOccurrenceInput = new EditText(this);
        databaseOccurrenceInput.setTextSize(14);
        databaseOccurrenceInput.setTextColor(COLOR_TEXT);
        databaseOccurrenceInput.setHintTextColor(COLOR_MUTED);
        databaseOccurrenceInput.setHint("Occurrence number");
        databaseOccurrenceInput.setText("1");
        databaseOccurrenceInput.setSingleLine(true);
        databaseOccurrenceInput.setPadding(dp(13), dp(12), dp(13), dp(12));
        databaseOccurrenceInput.setBackground(roundedBackground(COLOR_CARD_ALT, COLOR_BORDER, 13));
        LinearLayout.LayoutParams databaseOccurrenceParams = matchWidthWrapHeight();
        databaseOccurrenceParams.topMargin = dp(10);
        databaseCard.addView(databaseOccurrenceInput, databaseOccurrenceParams);

        buildDatabaseEditButton = actionButton("Build and stage edited full database", true);
        buildDatabaseEditButton.setOnClickListener(view -> runBuildAndStageDatabaseEdit());
        databaseCard.addView(buildDatabaseEditButton, primaryButtonParams());

        root.addView(databaseCard, cardParams());

        LinearLayout replacementCard = createCard();
        replacementCard.addView(sectionTitle("7. Stage, apply, or roll back one complete file"));
        replacementCard.addView(sectionBody(
                "Use this section for an external complete replacement file or for the edited database automatically produced above. Validation copies it into 30_patch_import and verifies SHA-256 without changing the game. Apply first creates a verified rollback copy, then replaces only the matching file inside 20_working_files/source_working."
        ));

        replacementStatusView = statusPanel();
        replacementCard.addView(replacementStatusView, statusPanelParams());

        stagedReplacementStatusView = statusPanel();
        replacementCard.addView(stagedReplacementStatusView, secondaryStatusPanelParams());

        appliedReplacementStatusView = statusPanel();
        replacementCard.addView(appliedReplacementStatusView, secondaryStatusPanelParams());

        chooseReplacementButton = actionButton("Choose complete replacement file", false);
        chooseReplacementButton.setOnClickListener(view -> openReplacementPicker());
        replacementCard.addView(chooseReplacementButton, buttonParams());

        validateReplacementButton = actionButton("Validate and stage replacement", false);
        validateReplacementButton.setOnClickListener(view -> runValidateReplacement());
        replacementCard.addView(validateReplacementButton, buttonParams());

        applyReplacementButton = actionButton("Apply verified full-file replacement", true);
        applyReplacementButton.setOnClickListener(view -> runApplyReplacement());
        replacementCard.addView(applyReplacementButton, primaryButtonParams());

        rollbackReplacementButton = actionButton("Roll back latest applied replacement", false);
        rollbackReplacementButton.setOnClickListener(view -> runRollbackReplacement());
        replacementCard.addView(rollbackReplacementButton, buttonParams());

        root.addView(replacementCard, cardParams());

        LinearLayout patchCard = createCard();
        patchCard.addView(sectionTitle("8. Inspect a mod patch ZIP"));
        patchCard.addView(sectionBody(
                "The app checks the ZIP signature, lists likely modding assets, and blocks dangerous parent-folder paths. Phase 1E replacement remains deliberately one exact full file at a time."
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
                "No Phase 1E operation has been run yet.",
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
                "Phase 1E never edits the selected ISO, verified backup, or protected original. Database editing first creates a separate full file in 30_patch_import; apply and rollback still operate only on the verified working copy and transaction files inside the workspace.",
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

    private void openReplacementPicker() {
        if (selectedAsset == null && SelectionStore.loadSelectedAssetPath(this).trim().isEmpty()) {
            showToast("Search and select a target asset first.");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(intent, REQUEST_REPLACEMENT_FILE);
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
        } else if (requestCode == REQUEST_REPLACEMENT_FILE) {
            if (!urisEqual(replacementFileUri, selectedUri)) {
                releaseReadPermission(replacementFileUri);
            }
            replacementFileUri = selectedUri;
            SelectionStore.saveReplacementFileUri(this, replacementFileUri);
            SelectionStore.clearStagedReplacement(this);
            reportView.setText("Replacement file selected. Validate and stage it before applying any change.");
            operationStatusView.setText("Replacement selected — working file unchanged.");
        } else if (requestCode == REQUEST_BACKUP_FOLDER) {
            if (!urisEqual(backupTreeUri, selectedUri)) {
                releaseReadWritePermission(backupTreeUri);
            }
            backupTreeUri = selectedUri;
            SelectionStore.saveBackupTreeUri(this, backupTreeUri);
            reportView.setText("Backup destination selected. Run the readiness check before copying.");
            operationStatusView.setText("Backup destination selected.");
        } else if (requestCode == REQUEST_WORKSPACE_FOLDER) {
            boolean destinationChanged = !urisEqual(workspaceTreeUri, selectedUri);
            if (destinationChanged) {
                releaseReadWritePermission(workspaceTreeUri);
            }
            workspaceTreeUri = selectedUri;
            SelectionStore.saveWorkspaceTreeUri(this, workspaceTreeUri);
            if (destinationChanged) {
                SelectionStore.clearLatestWorkspace(this);
                SelectionStore.clearPhase1CRecords(this);
                clearLocalPhase1DState();
            }
            reportView.setText("Workspace destination selected. Prepare a workspace before extraction.");
            operationStatusView.setText(destinationChanged
                    ? "Workspace destination changed — prepare a new workspace."
                    : "The same workspace destination was reselected.");
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
                    this::showOperationProgress
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
                    SelectionStore.clearPhase1CRecords(this);
                    clearLocalPhase1DState();
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
                    SelectionStore.clearPhase1CRecords(this);
                    clearLocalPhase1DState();
                }
                reportView.setText(result.getReport().toDisplayText());
                setBusy(false, result.isSuccess()
                        ? "Workspace prepared."
                        : "Workspace preparation stopped safely.");
                updateSelectionViews();
            });
        });
    }

    private void runExtractionReadinessCheck() {
        Uri workspaceProject = SelectionStore.loadLatestWorkspaceUri(this);
        String backupReference = SelectionStore.loadLatestBackupReference(this);
        if (!hasSource()) {
            showToast("Choose the game source first.");
            return;
        }
        if (workspaceProject == null) {
            showToast("Prepare a workspace before checking extraction readiness.");
            return;
        }
        if (backupReference == null || backupReference.trim().isEmpty()) {
            showToast("Create a verified backup first.");
            return;
        }

        final Uri selectedFile = gameFileUri;
        final Uri selectedFolder = gameFolderUri;
        final Uri selectedWorkspace = workspaceProject;
        final String selectedBackupReference = backupReference;
        setBusy(true, "Reading the ISO filesystem and checking workspace capacity…");

        operationExecutor.execute(() -> {
            ScanReport report = ExtractionEngine.checkExtractionReadiness(
                    getApplicationContext(),
                    selectedFile,
                    selectedFolder,
                    selectedWorkspace,
                    selectedBackupReference
            );
            finishReport(report, "Extraction readiness check complete.");
        });
    }

    private void runExtractOriginal() {
        Uri workspaceProject = SelectionStore.loadLatestWorkspaceUri(this);
        String backupReference = SelectionStore.loadLatestBackupReference(this);
        if (!hasSource()) {
            showToast("Choose the game source first.");
            return;
        }
        if (workspaceProject == null) {
            showToast("Prepare a workspace before extraction.");
            return;
        }
        if (backupReference == null || backupReference.trim().isEmpty()) {
            showToast("Create a verified backup first.");
            return;
        }

        final Uri selectedFile = gameFileUri;
        final Uri selectedFolder = gameFolderUri;
        final Uri selectedWorkspace = workspaceProject;
        final String selectedBackupReference = backupReference;
        setBusy(true, "Extracting and verifying every file. Keep the app open and the phone charged…");

        operationExecutor.execute(() -> {
            OperationResult result = ExtractionEngine.extractOriginal(
                    getApplicationContext(),
                    selectedFile,
                    selectedFolder,
                    selectedWorkspace,
                    selectedBackupReference,
                    this::showOperationProgress
            );

            runOnUiThread(() -> {
                if (isActivityUnavailable()) {
                    return;
                }
                if (result.isSuccess()) {
                    SelectionStore.saveLatestExtraction(
                            this,
                            result.getCreatedUri(),
                            result.getReference()
                    );
                    SelectionStore.clearLatestWorkingCopy(this);
                    clearLocalPhase1DState();
                }
                reportView.setText(result.getReport().toDisplayText());
                setBusy(false, result.isSuccess()
                        ? "Protected original extraction verified."
                        : "Extraction stopped safely.");
                updateSelectionViews();
            });
        });
    }

    private void runCreateWorkingCopy() {
        Uri workspaceProject = SelectionStore.loadLatestWorkspaceUri(this);
        String backupReference = SelectionStore.loadLatestBackupReference(this);
        String extractionReference = SelectionStore.loadLatestExtractionReference(this);
        if (workspaceProject == null) {
            showToast("Prepare a workspace first.");
            return;
        }
        if (backupReference == null || backupReference.trim().isEmpty()) {
            showToast("Create a verified backup first.");
            return;
        }
        if (extractionReference == null || extractionReference.trim().isEmpty()) {
            showToast("Extract and verify the protected original first.");
            return;
        }

        final Uri selectedWorkspace = workspaceProject;
        final String selectedBackupReference = backupReference;
        final String selectedExtractionReference = extractionReference;
        setBusy(true, "Creating and verifying the complete working copy…");

        operationExecutor.execute(() -> {
            OperationResult result = ExtractionEngine.createVerifiedWorkingCopy(
                    getApplicationContext(),
                    selectedWorkspace,
                    selectedBackupReference,
                    selectedExtractionReference,
                    this::showOperationProgress
            );

            runOnUiThread(() -> {
                if (isActivityUnavailable()) {
                    return;
                }
                if (result.isSuccess()) {
                    SelectionStore.clearPhase1DRecords(this);
                    clearLocalPhase1DState();
                    SelectionStore.saveLatestWorkingCopy(
                            this,
                            result.getCreatedUri(),
                            result.getReference()
                    );
                }
                reportView.setText(result.getReport().toDisplayText());
                setBusy(false, result.isSuccess()
                        ? "Verified working copy ready."
                        : "Working copy stopped safely.");
                updateSelectionViews();
            });
        });
    }

    private void runAssetSearch() {
        Uri workspaceProject = SelectionStore.loadLatestWorkspaceUri(this);
        String workingReference = SelectionStore.loadLatestWorkingReference(this);
        if (workspaceProject == null || workingReference == null || workingReference.trim().isEmpty()) {
            showToast("Create the verified working copy first.");
            return;
        }
        final String query = assetSearchInput.getText() == null
                ? ""
                : assetSearchInput.getText().toString().trim();
        setBusy(true, "Searching the verified working asset index…");
        operationExecutor.execute(() -> {
            AssetSearchResult result = ReplacementEngine.searchAssets(
                    getApplicationContext(),
                    workspaceProject,
                    query
            );
            runOnUiThread(() -> {
                if (isActivityUnavailable()) {
                    return;
                }
                assetMatches.clear();
                assetMatches.addAll(result.getMatches());
                assetMatchIndex = assetMatches.isEmpty() ? -1 : 0;
                if (assetMatchIndex >= 0) {
                    selectAsset(assetMatches.get(assetMatchIndex), false);
                } else {
                    selectedAsset = null;
                    SelectionStore.saveSelectedAssetPath(this, null);
                    SelectionStore.clearStagedReplacement(this);
                }
                reportView.setText(result.getReport().toDisplayText());
                setBusy(false, assetMatches.isEmpty()
                        ? "No matching working assets."
                        : "Working asset matches loaded.");
                updateSelectionViews();
            });
        });
    }

    private void selectPreviousAsset() {
        if (assetMatches.isEmpty()) {
            showToast("Search the working asset index first.");
            return;
        }
        assetMatchIndex = (assetMatchIndex - 1 + assetMatches.size()) % assetMatches.size();
        selectAsset(assetMatches.get(assetMatchIndex), true);
    }

    private void selectNextAsset() {
        if (assetMatches.isEmpty()) {
            showToast("Search the working asset index first.");
            return;
        }
        assetMatchIndex = (assetMatchIndex + 1) % assetMatches.size();
        selectAsset(assetMatches.get(assetMatchIndex), true);
    }

    private void selectAsset(AssetRecord asset, boolean announce) {
        String previousPath = SelectionStore.loadSelectedAssetPath(this);
        selectedAsset = asset;
        SelectionStore.saveSelectedAssetPath(this, asset == null ? null : asset.getPath());
        if (asset != null && !asset.getPath().equals(previousPath)) {
            SelectionStore.clearStagedReplacement(this);
        }
        if (announce && asset != null) {
            reportView.setText(
                    "Selected working asset\n\n"
                            + "Path: " + asset.getPath() + "\n"
                            + "Category: " + asset.getCategory() + "\n"
                            + "Size: " + GameScanner.formatBytes(asset.getSizeBytes()) + "\n"
                            + "SHA-256: " + asset.getSha256() + "\n\n"
                            + "Choose a complete replacement file named exactly “" + asset.getName() + "”."
            );
            operationStatusView.setText("Working target selected — no file changed.");
        }
        updateSelectionViews();
    }

    private void runDatabaseInspection() {
        Uri workspaceProject = SelectionStore.loadLatestWorkspaceUri(this);
        String selectedPath = SelectionStore.loadSelectedAssetPath(this);
        String workingReference = SelectionStore.loadLatestWorkingReference(this);
        if (workspaceProject == null || workingReference == null || workingReference.trim().isEmpty()) {
            showToast("Create the verified working copy first.");
            return;
        }
        if (selectedPath == null || selectedPath.trim().isEmpty()) {
            showToast("Search and select fifa.db or another .db asset first.");
            return;
        }

        final Uri selectedWorkspace = workspaceProject;
        final String targetPath = selectedPath;
        setBusy(true, "Verifying and fingerprinting the selected database…");
        operationExecutor.execute(() -> {
            AssetRecord asset = ReplacementEngine.findAsset(
                    getApplicationContext(),
                    selectedWorkspace,
                    targetPath
            );
            ScanReport report = DatabaseLab.inspectDatabase(
                    getApplicationContext(),
                    selectedWorkspace,
                    asset
            );
            runOnUiThread(() -> {
                if (isActivityUnavailable()) {
                    return;
                }
                if (asset != null) {
                    selectedAsset = asset;
                    replaceAssetMatch(asset);
                }
                reportView.setText(report.toDisplayText());
                setBusy(false, "Database inspection complete.");
                updateSelectionViews();
            });
        });
    }

    private void runDatabaseTextSearch() {
        Uri workspaceProject = SelectionStore.loadLatestWorkspaceUri(this);
        String selectedPath = SelectionStore.loadSelectedAssetPath(this);
        String workingReference = SelectionStore.loadLatestWorkingReference(this);
        if (workspaceProject == null || workingReference == null || workingReference.trim().isEmpty()) {
            showToast("Create the verified working copy first.");
            return;
        }
        if (selectedPath == null || selectedPath.trim().isEmpty()) {
            showToast("Search and select fifa.db or another .db asset first.");
            return;
        }
        final String searchText = databaseSearchInput.getText() == null
                ? ""
                : databaseSearchInput.getText().toString();
        if (searchText.isEmpty()) {
            showToast("Enter exact case-sensitive database text to search.");
            return;
        }

        final Uri selectedWorkspace = workspaceProject;
        final String targetPath = selectedPath;
        setBusy(true, "Searching exact database bytes and offsets…");
        operationExecutor.execute(() -> {
            AssetRecord asset = ReplacementEngine.findAsset(
                    getApplicationContext(),
                    selectedWorkspace,
                    targetPath
            );
            ScanReport report = DatabaseLab.searchDatabaseText(
                    getApplicationContext(),
                    selectedWorkspace,
                    asset,
                    searchText
            );
            runOnUiThread(() -> {
                if (isActivityUnavailable()) {
                    return;
                }
                if (asset != null) {
                    selectedAsset = asset;
                    replaceAssetMatch(asset);
                }
                reportView.setText(report.toDisplayText());
                setBusy(false, "Database text search complete.");
                updateSelectionViews();
            });
        });
    }

    private void runBuildAndStageDatabaseEdit() {
        Uri workspaceProject = SelectionStore.loadLatestWorkspaceUri(this);
        String selectedPath = SelectionStore.loadSelectedAssetPath(this);
        String workingReference = SelectionStore.loadLatestWorkingReference(this);
        if (workspaceProject == null || workingReference == null || workingReference.trim().isEmpty()) {
            showToast("Create the verified working copy first.");
            return;
        }
        if (selectedPath == null || selectedPath.trim().isEmpty()) {
            showToast("Search and select fifa.db or another .db asset first.");
            return;
        }
        final String findText = databaseSearchInput.getText() == null
                ? ""
                : databaseSearchInput.getText().toString();
        final String replacementText = databaseReplacementInput.getText() == null
                ? ""
                : databaseReplacementInput.getText().toString();
        final String occurrenceText = databaseOccurrenceInput.getText() == null
                ? ""
                : databaseOccurrenceInput.getText().toString().trim();
        final int occurrenceNumber;
        try {
            occurrenceNumber = Integer.parseInt(occurrenceText);
        } catch (RuntimeException error) {
            showToast("Occurrence number must be a whole number starting at 1.");
            return;
        }

        final Uri selectedWorkspace = workspaceProject;
        final String targetPath = selectedPath;
        SelectionStore.clearStagedReplacement(this);
        setBusy(true, "Building a verified edited database copy, then staging it…");
        operationExecutor.execute(() -> {
            AssetRecord asset = ReplacementEngine.findAsset(
                    getApplicationContext(),
                    selectedWorkspace,
                    targetPath
            );
            OperationResult editResult = DatabaseLab.createEditedDatabaseCopy(
                    getApplicationContext(),
                    selectedWorkspace,
                    asset,
                    findText,
                    replacementText,
                    occurrenceNumber,
                    this::showOperationProgress
            );
            StagedReplacementResult stageResult = null;
            if (editResult.isSuccess()) {
                stageResult = ReplacementEngine.validateAndStageReplacement(
                        getApplicationContext(),
                        selectedWorkspace,
                        asset,
                        editResult.getCreatedUri(),
                        this::showOperationProgress
                );
            }
            final StagedReplacementResult completedStage = stageResult;
            runOnUiThread(() -> {
                if (isActivityUnavailable()) {
                    return;
                }
                StringBuilder combinedReport = new StringBuilder(
                        editResult.getReport().toDisplayText()
                );
                boolean staged = false;
                if (editResult.isSuccess()) {
                    replacementFileUri = editResult.getCreatedUri();
                    SelectionStore.saveReplacementFileUri(this, replacementFileUri);
                    if (asset != null) {
                        selectedAsset = asset;
                        replaceAssetMatch(asset);
                    }
                }
                if (completedStage != null) {
                    OperationResult stageOperation = completedStage.getOperationResult();
                    combinedReport.append("\n\n").append(stageOperation.getReport().toDisplayText());
                    if (stageOperation.isSuccess()) {
                        staged = true;
                        SelectionStore.saveStagedReplacement(
                                this,
                                completedStage.getTransactionUri(),
                                stageOperation.getReference(),
                                completedStage.getTargetPath(),
                                completedStage.getReplacementSha256(),
                                completedStage.getReplacementSize()
                        );
                    }
                }
                reportView.setText(combinedReport.toString());
                if (staged) {
                    setBusy(false, "Edited database built and staged. Review the report before applying.");
                } else if (editResult.isSuccess()) {
                    setBusy(false, "Edited database built, but staging stopped safely.");
                } else {
                    setBusy(false, "Database edit generation stopped safely.");
                }
                updateSelectionViews();
            });
        });
    }

    private void runValidateReplacement() {
        Uri workspaceProject = SelectionStore.loadLatestWorkspaceUri(this);
        String selectedPath = SelectionStore.loadSelectedAssetPath(this);
        String workingReference = SelectionStore.loadLatestWorkingReference(this);
        if (workspaceProject == null || workingReference == null || workingReference.trim().isEmpty()) {
            showToast("Create the verified working copy first.");
            return;
        }
        if (selectedPath == null || selectedPath.trim().isEmpty()) {
            showToast("Search and select a target asset first.");
            return;
        }
        if (replacementFileUri == null) {
            showToast("Choose the complete replacement file first.");
            return;
        }

        final Uri selectedWorkspace = workspaceProject;
        final Uri selectedReplacement = replacementFileUri;
        final String targetPath = selectedPath;
        setBusy(true, "Validating filename, target hash, replacement hash, and workspace space…");
        operationExecutor.execute(() -> {
            AssetRecord asset = ReplacementEngine.findAsset(
                    getApplicationContext(),
                    selectedWorkspace,
                    targetPath
            );
            StagedReplacementResult result = ReplacementEngine.validateAndStageReplacement(
                    getApplicationContext(),
                    selectedWorkspace,
                    asset,
                    selectedReplacement,
                    this::showOperationProgress
            );
            runOnUiThread(() -> {
                if (isActivityUnavailable()) {
                    return;
                }
                OperationResult operation = result.getOperationResult();
                if (operation.isSuccess()) {
                    selectedAsset = asset;
                    SelectionStore.saveStagedReplacement(
                            this,
                            result.getTransactionUri(),
                            operation.getReference(),
                            result.getTargetPath(),
                            result.getReplacementSha256(),
                            result.getReplacementSize()
                    );
                }
                reportView.setText(operation.getReport().toDisplayText());
                setBusy(false, operation.isSuccess()
                        ? "Replacement staged and verified."
                        : "Replacement validation stopped safely.");
                updateSelectionViews();
            });
        });
    }

    private void runApplyReplacement() {
        Uri workspaceProject = SelectionStore.loadLatestWorkspaceUri(this);
        Uri transactionUri = SelectionStore.loadStagedTransactionUri(this);
        String targetPath = SelectionStore.loadStagedTargetPath(this);
        String replacementHash = SelectionStore.loadStagedReplacementHash(this);
        long replacementSize = SelectionStore.loadStagedReplacementSize(this);
        if (workspaceProject == null || transactionUri == null || targetPath.trim().isEmpty()
                || replacementHash.trim().isEmpty() || replacementSize < 0L) {
            showToast("Validate and stage a replacement first.");
            return;
        }
        setBusy(true, "Creating the rollback copy, then applying and verifying the replacement…");
        operationExecutor.execute(() -> {
            OperationResult result = ReplacementEngine.applyStagedReplacement(
                    getApplicationContext(),
                    workspaceProject,
                    transactionUri,
                    targetPath,
                    replacementHash,
                    replacementSize,
                    this::showOperationProgress
            );
            AssetRecord refreshed = result.isSuccess()
                    ? ReplacementEngine.findAsset(getApplicationContext(), workspaceProject, targetPath)
                    : null;
            runOnUiThread(() -> {
                if (isActivityUnavailable()) {
                    return;
                }
                if (result.isSuccess()) {
                    SelectionStore.saveAppliedReplacement(
                            this,
                            transactionUri,
                            result.getReference(),
                            targetPath
                    );
                    SelectionStore.clearStagedReplacement(this);
                    selectedAsset = refreshed;
                    replaceAssetMatch(refreshed);
                }
                reportView.setText(result.getReport().toDisplayText());
                setBusy(false, result.isSuccess()
                        ? "Full-file replacement applied and verified."
                        : "Replacement stopped; see the safety report.");
                updateSelectionViews();
            });
        });
    }

    private void runRollbackReplacement() {
        Uri workspaceProject = SelectionStore.loadLatestWorkspaceUri(this);
        Uri transactionUri = SelectionStore.loadAppliedTransactionUri(this);
        String targetPath = SelectionStore.loadAppliedTargetPath(this);
        if (workspaceProject == null || transactionUri == null || targetPath.trim().isEmpty()) {
            showToast("No applied replacement is available to roll back.");
            return;
        }
        setBusy(true, "Restoring the verified pre-replacement working file…");
        operationExecutor.execute(() -> {
            OperationResult result = ReplacementEngine.rollbackReplacement(
                    getApplicationContext(),
                    workspaceProject,
                    transactionUri,
                    this::showOperationProgress
            );
            AssetRecord refreshed = result.isSuccess()
                    ? ReplacementEngine.findAsset(getApplicationContext(), workspaceProject, targetPath)
                    : null;
            runOnUiThread(() -> {
                if (isActivityUnavailable()) {
                    return;
                }
                if (result.isSuccess()) {
                    SelectionStore.popAppliedReplacement(this);
                    selectedAsset = refreshed;
                    replaceAssetMatch(refreshed);
                }
                reportView.setText(result.getReport().toDisplayText());
                setBusy(false, result.isSuccess()
                        ? "Latest replacement rolled back and verified."
                        : "Rollback stopped; see the recovery report.");
                updateSelectionViews();
            });
        });
    }

    private void replaceAssetMatch(AssetRecord refreshed) {
        if (refreshed == null) {
            return;
        }
        for (int index = 0; index < assetMatches.size(); index++) {
            if (assetMatches.get(index).getPath().equals(refreshed.getPath())) {
                assetMatches.set(index, refreshed);
                assetMatchIndex = index;
                return;
            }
        }
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

    private void showOperationProgress(String stage, long completedBytes, long totalBytes) {
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

    private void clearLocalPhase1DState() {
        releaseReadPermission(replacementFileUri);
        replacementFileUri = null;
        selectedAsset = null;
        assetMatches.clear();
        assetMatchIndex = -1;
    }

    private void clearSourceDependentRecords() {
        SelectionStore.clearLatestVerifiedBackup(this);
        SelectionStore.clearLatestWorkspace(this);
        SelectionStore.clearPhase1CRecords(this);
        clearLocalPhase1DState();
    }

    private void resetSelections() {
        releaseReadPermission(gameFileUri);
        releaseReadPermission(gameFolderUri);
        releaseReadPermission(patchZipUri);
        releaseReadPermission(replacementFileUri);
        releaseReadWritePermission(backupTreeUri);
        releaseReadWritePermission(workspaceTreeUri);

        gameFileUri = null;
        gameFolderUri = null;
        patchZipUri = null;
        backupTreeUri = null;
        workspaceTreeUri = null;
        replacementFileUri = null;
        selectedAsset = null;
        assetMatches.clear();
        assetMatchIndex = -1;
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

        String extractionReference = SelectionStore.loadLatestExtractionReference(this);
        extractionStatusView.setText(
                extractionReference == null || extractionReference.trim().isEmpty()
                        ? "Protected original extraction: none"
                        : "Latest protected original\n" + extractionReference
        );

        String workingReference = SelectionStore.loadLatestWorkingReference(this);
        workingCopyStatusView.setText(
                workingReference == null || workingReference.trim().isEmpty()
                        ? "Verified working copy: none"
                        : "Latest verified working copy\n" + workingReference
        );

        boolean hasWorkingCopy = workingReference != null && !workingReference.trim().isEmpty()
                && SelectionStore.loadLatestWorkingUri(this) != null;
        assetBrowserStatusView.setText(hasWorkingCopy
                ? (assetMatches.isEmpty()
                        ? "Working asset index ready\nEnter a query and tap Search working asset index"
                        : "Matches loaded: " + assetMatches.size() + "\nSelected position: " + (assetMatchIndex + 1))
                : "Verified working copy required");

        String selectedPath = SelectionStore.loadSelectedAssetPath(this);
        if (selectedAsset != null) {
            selectedAssetStatusView.setText(
                    "Selected working target\n"
                            + selectedAsset.getPath() + "\n"
                            + selectedAsset.getCategory() + " • "
                            + GameScanner.formatBytes(selectedAsset.getSizeBytes()) + "\n"
                            + "SHA-256: " + selectedAsset.getSha256()
            );
        } else if (selectedPath != null && !selectedPath.trim().isEmpty()) {
            selectedAssetStatusView.setText(
                    "Saved working target\n" + selectedPath
                            + "\nSearch again to refresh its current hash."
            );
        } else {
            selectedAssetStatusView.setText("No working asset selected");
        }

        boolean selectedDatabase = selectedAsset != null && DatabaseRules.isDatabaseAsset(selectedAsset);
        if (selectedDatabase) {
            databaseLabStatusView.setText(
                    "Database Lab target\n"
                            + selectedAsset.getPath() + "\n"
                            + GameScanner.formatBytes(selectedAsset.getSizeBytes())
                            + " • exact working SHA-256 verified before every operation"
            );
        } else if (selectedPath != null && selectedPath.toLowerCase(Locale.US).endsWith(".db")) {
            databaseLabStatusView.setText(
                    "Saved database target\n" + selectedPath
                            + "\nSearch the asset index again to refresh its current size and SHA-256."
            );
        } else {
            databaseLabStatusView.setText(
                    "Select fifa.db or another .db asset in Section 5 to activate the Database Lab."
            );
        }

        if (replacementFileUri != null) {
            replacementStatusView.setText(
                    "Selected replacement file\n" + GameScanner.describeUri(this, replacementFileUri)
            );
        } else {
            replacementStatusView.setText("No replacement file selected");
        }

        String stagedReference = SelectionStore.loadStagedReference(this);
        stagedReplacementStatusView.setText(
                stagedReference == null || stagedReference.trim().isEmpty()
                        ? "Validated staged replacement: none"
                        : "Validated staged replacement\n" + stagedReference
        );

        String appliedReference = SelectionStore.loadAppliedReference(this);
        appliedReplacementStatusView.setText(
                appliedReference == null || appliedReference.trim().isEmpty()
                        ? "Latest applied replacement: none"
                        : "Latest applied replacement\n" + appliedReference
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
        boolean hasPreparedWorkspace = workspaceReference != null && !workspaceReference.trim().isEmpty()
                && SelectionStore.loadLatestWorkspaceUri(this) != null;
        boolean hasExtraction = extractionReference != null && !extractionReference.trim().isEmpty();
        boolean hasSelectedAsset = selectedAsset != null
                || (selectedPath != null && !selectedPath.trim().isEmpty());
        boolean hasSelectedDatabase = (selectedAsset != null && DatabaseRules.isDatabaseAsset(selectedAsset))
                || (selectedAsset == null && selectedPath != null
                && selectedPath.toLowerCase(Locale.US).endsWith(".db"));
        boolean hasStagedReplacement = SelectionStore.loadStagedTransactionUri(this) != null
                && !SelectionStore.loadStagedTargetPath(this).trim().isEmpty();
        boolean hasAppliedReplacement = SelectionStore.loadAppliedTransactionUri(this) != null
                && !SelectionStore.loadAppliedTargetPath(this).trim().isEmpty();

        scanSourceButton.setEnabled(hasSource);
        checkBackupButton.setEnabled(hasSource && hasBackupDestination);
        createBackupButton.setEnabled(hasSource && hasBackupDestination);
        prepareWorkspaceButton.setEnabled(
                hasSource && hasWorkspaceDestination && hasVerifiedBackup
        );
        checkExtractionButton.setEnabled(hasSource && hasVerifiedBackup && hasPreparedWorkspace);
        extractOriginalButton.setEnabled(hasSource && hasVerifiedBackup && hasPreparedWorkspace);
        createWorkingCopyButton.setEnabled(hasVerifiedBackup && hasPreparedWorkspace && hasExtraction);
        searchAssetsButton.setEnabled(hasWorkingCopy);
        previousAssetButton.setEnabled(hasWorkingCopy && assetMatches.size() > 1);
        nextAssetButton.setEnabled(hasWorkingCopy && assetMatches.size() > 1);
        inspectDatabaseButton.setEnabled(hasWorkingCopy && hasSelectedDatabase);
        searchDatabaseButton.setEnabled(hasWorkingCopy && hasSelectedDatabase);
        buildDatabaseEditButton.setEnabled(hasWorkingCopy && hasSelectedDatabase);
        chooseReplacementButton.setEnabled(hasWorkingCopy && hasSelectedAsset);
        validateReplacementButton.setEnabled(
                hasWorkingCopy && hasSelectedAsset && replacementFileUri != null
        );
        applyReplacementButton.setEnabled(hasWorkingCopy && hasStagedReplacement);
        rollbackReplacementButton.setEnabled(hasWorkingCopy && hasAppliedReplacement);
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
        chooseReplacementButton.setEnabled(!busy);
        assetSearchInput.setEnabled(!busy);
        databaseSearchInput.setEnabled(!busy);
        databaseReplacementInput.setEnabled(!busy);
        databaseOccurrenceInput.setEnabled(!busy);
        resetButton.setEnabled(!busy);

        if (busy) {
            scanSourceButton.setEnabled(false);
            checkBackupButton.setEnabled(false);
            createBackupButton.setEnabled(false);
            prepareWorkspaceButton.setEnabled(false);
            checkExtractionButton.setEnabled(false);
            extractOriginalButton.setEnabled(false);
            createWorkingCopyButton.setEnabled(false);
            searchAssetsButton.setEnabled(false);
            previousAssetButton.setEnabled(false);
            nextAssetButton.setEnabled(false);
            inspectDatabaseButton.setEnabled(false);
            searchDatabaseButton.setEnabled(false);
            buildDatabaseEditButton.setEnabled(false);
            chooseReplacementButton.setEnabled(false);
            validateReplacementButton.setEnabled(false);
            applyReplacementButton.setEnabled(false);
            rollbackReplacementButton.setEnabled(false);
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
