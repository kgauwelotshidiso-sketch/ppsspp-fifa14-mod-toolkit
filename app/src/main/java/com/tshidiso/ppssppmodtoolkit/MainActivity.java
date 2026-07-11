package com.tshidiso.ppssppmodtoolkit;

import android.app.Activity;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQUEST_GAME_FILE = 1001;
    private static final int REQUEST_GAME_FOLDER = 1002;
    private static final int REQUEST_PATCH_ZIP = 1003;

    private static final int COLOR_BACKGROUND = Color.rgb(10, 17, 28);
    private static final int COLOR_CARD = Color.rgb(18, 29, 44);
    private static final int COLOR_CARD_ALT = Color.rgb(14, 24, 37);
    private static final int COLOR_PRIMARY = Color.rgb(54, 211, 153);
    private static final int COLOR_PRIMARY_DARK = Color.rgb(9, 73, 58);
    private static final int COLOR_TEXT = Color.rgb(241, 245, 249);
    private static final int COLOR_MUTED = Color.rgb(159, 176, 195);
    private static final int COLOR_BORDER = Color.rgb(47, 65, 85);
    private static final int COLOR_WARNING = Color.rgb(251, 191, 36);

    private final ExecutorService scannerExecutor = Executors.newSingleThreadExecutor();

    private Uri gameFileUri;
    private Uri gameFolderUri;
    private Uri patchZipUri;

    private TextView sourceStatusView;
    private TextView patchStatusView;
    private TextView operationStatusView;
    private TextView reportView;

    private Button chooseGameFileButton;
    private Button chooseGameFolderButton;
    private Button scanSourceButton;
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
                "PHASE 1A  •  READ-ONLY SCANNER",
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
                "FIFA 14 PSP source detection, asset discovery, and safe patch inspection — built for Android-only modding.",
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
        sourceCard.addView(sectionTitle("1. Choose the FIFA 14 game source"));
        sourceCard.addView(sectionBody(
                "Select either the ISO/CSO/PBP file used by PPSSPP or an already extracted PSP game folder. Selecting one source clears the other."
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

        LinearLayout patchCard = createCard();
        patchCard.addView(sectionTitle("2. Inspect a mod patch ZIP"));
        patchCard.addView(sectionBody(
                "The app checks the ZIP signature, lists likely modding assets, and blocks dangerous parent-folder paths. It does not install anything in this phase."
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
        reportCard.addView(sectionTitle("Scanner report"));

        operationStatusView = textView(
                "Ready. Select a source, then run the scanner.",
                13,
                COLOR_WARNING,
                Typeface.BOLD
        );
        LinearLayout.LayoutParams operationParams = matchWidthWrapHeight();
        operationParams.topMargin = dp(8);
        operationParams.bottomMargin = dp(12);
        reportCard.addView(operationStatusView, operationParams);

        reportView = textView(
                "No scan has been run yet.",
                14,
                COLOR_TEXT,
                Typeface.NORMAL
        );
        reportView.setTextIsSelectable(true);
        reportView.setLineSpacing(0f, 1.18f);
        reportView.setPadding(dp(14), dp(14), dp(14), dp(14));
        reportView.setBackground(roundedBackground(COLOR_CARD_ALT, COLOR_BORDER, 14));
        reportCard.addView(reportView, matchWidthWrapHeight());

        resetButton = actionButton("Reset selected files", false);
        resetButton.setOnClickListener(view -> resetSelections());
        LinearLayout.LayoutParams resetParams = buttonParams();
        resetParams.topMargin = dp(16);
        reportCard.addView(resetButton, resetParams);

        root.addView(reportCard, cardParams());

        TextView footer = textView(
                "Phase 1A never edits, extracts, or replaces your game. Backup and verified full-file replacement are the next Phase 1 sprint.",
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }

        Uri selectedUri = data.getData();
        persistGrantedPermission(selectedUri, data);

        if (requestCode == REQUEST_GAME_FILE) {
            releasePersistedPermission(gameFolderUri);
            gameFileUri = selectedUri;
            gameFolderUri = null;
            SelectionStore.saveGameUri(this, gameFileUri);
            SelectionStore.saveFolderUri(this, null);
            reportView.setText("Game image selected. Tap “Run source scan” to inspect it.");
            operationStatusView.setText("Source selected — scan not yet run.");
        } else if (requestCode == REQUEST_GAME_FOLDER) {
            releasePersistedPermission(gameFileUri);
            gameFolderUri = selectedUri;
            gameFileUri = null;
            SelectionStore.saveFolderUri(this, gameFolderUri);
            SelectionStore.saveGameUri(this, null);
            reportView.setText("Extracted folder selected. Tap “Run source scan” to inspect it.");
            operationStatusView.setText("Source selected — scan not yet run.");
        } else if (requestCode == REQUEST_PATCH_ZIP) {
            patchZipUri = selectedUri;
            SelectionStore.savePatchUri(this, patchZipUri);
            reportView.setText("Patch package selected. Tap “Inspect patch package” to scan it.");
            operationStatusView.setText("Patch selected — inspection not yet run.");
        }

        updateSelectionViews();
    }

    private void persistGrantedPermission(Uri uri, Intent resultData) {
        boolean readPermissionGranted = (resultData.getFlags()
                & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0;
        if (!readPermissionGranted) {
            return;
        }

        try {
            getContentResolver().takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
            );
        } catch (SecurityException ignored) {
            // Some providers grant access only for the current app session.
        }
    }

    private void releasePersistedPermission(Uri uri) {
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

    private void runSourceScan() {
        if (gameFileUri == null && gameFolderUri == null) {
            showToast("Choose a game file or extracted folder first.");
            return;
        }

        final Uri selectedFile = gameFileUri;
        final Uri selectedFolder = gameFolderUri;
        setBusy(true, "Scanning the selected game source…");

        scannerExecutor.execute(() -> {
            ScanReport report = selectedFolder != null
                    ? GameScanner.scanFolder(getApplicationContext(), selectedFolder)
                    : GameScanner.scanGameFile(getApplicationContext(), selectedFile);

            runOnUiThread(() -> {
                if (isActivityUnavailable()) {
                    return;
                }
                reportView.setText(report.toDisplayText());
                setBusy(false, "Source scan complete.");
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

        scannerExecutor.execute(() -> {
            ScanReport report = GameScanner.scanPatchZip(
                    getApplicationContext(),
                    selectedPatch
            );

            runOnUiThread(() -> {
                if (isActivityUnavailable()) {
                    return;
                }
                reportView.setText(report.toDisplayText());
                setBusy(false, "Patch inspection complete.");
            });
        });
    }

    private void resetSelections() {
        releasePersistedPermission(gameFileUri);
        releasePersistedPermission(gameFolderUri);
        releasePersistedPermission(patchZipUri);

        gameFileUri = null;
        gameFolderUri = null;
        patchZipUri = null;
        SelectionStore.clearAll(this);

        reportView.setText("Selections cleared. No scan has been run.");
        operationStatusView.setText("Ready. Select a source, then run the scanner.");
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

        if (patchZipUri != null) {
            patchStatusView.setText(
                    "Selected patch package\n" + GameScanner.describeUri(this, patchZipUri)
            );
        } else {
            patchStatusView.setText("No patch ZIP selected");
        }

        scanSourceButton.setEnabled(gameFileUri != null || gameFolderUri != null);
        scanPatchButton.setEnabled(patchZipUri != null);
    }

    private void setBusy(boolean busy, String message) {
        operationStatusView.setText(message);
        chooseGameFileButton.setEnabled(!busy);
        chooseGameFolderButton.setEnabled(!busy);
        choosePatchButton.setEnabled(!busy);
        resetButton.setEnabled(!busy);
        scanSourceButton.setEnabled(!busy && (gameFileUri != null || gameFolderUri != null));
        scanPatchButton.setEnabled(!busy && patchZipUri != null);
    }

    private boolean isActivityUnavailable() {
        return isFinishing() || (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed());
    }

    @Override
    protected void onDestroy() {
        scannerExecutor.shutdownNow();
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
