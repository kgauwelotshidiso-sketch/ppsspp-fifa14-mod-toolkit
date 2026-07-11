package com.tshidiso.ppssppmodtoolkit;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class SelectionStore {
    private static final String PREFS = "toolkit_selection_state";
    private static final String KEY_GAME_URI = "game_uri";
    private static final String KEY_FOLDER_URI = "folder_uri";
    private static final String KEY_PATCH_URI = "patch_uri";
    private static final String KEY_BACKUP_TREE_URI = "backup_tree_uri";
    private static final String KEY_WORKSPACE_TREE_URI = "workspace_tree_uri";
    private static final String KEY_LATEST_BACKUP_URI = "latest_backup_uri";
    private static final String KEY_LATEST_BACKUP_REFERENCE = "latest_backup_reference";
    private static final String KEY_LATEST_WORKSPACE_URI = "latest_workspace_uri";
    private static final String KEY_LATEST_WORKSPACE_REFERENCE = "latest_workspace_reference";
    private static final String KEY_LATEST_EXTRACTION_URI = "latest_extraction_uri";
    private static final String KEY_LATEST_EXTRACTION_REFERENCE = "latest_extraction_reference";
    private static final String KEY_LATEST_WORKING_URI = "latest_working_uri";
    private static final String KEY_LATEST_WORKING_REFERENCE = "latest_working_reference";
    private static final String KEY_SELECTED_ASSET_PATH = "selected_asset_path";
    private static final String KEY_REPLACEMENT_FILE_URI = "replacement_file_uri";
    private static final String KEY_STAGED_TRANSACTION_URI = "staged_transaction_uri";
    private static final String KEY_STAGED_REFERENCE = "staged_reference";
    private static final String KEY_STAGED_TARGET_PATH = "staged_target_path";
    private static final String KEY_STAGED_REPLACEMENT_HASH = "staged_replacement_hash";
    private static final String KEY_STAGED_REPLACEMENT_SIZE = "staged_replacement_size";
    private static final String KEY_APPLIED_TRANSACTION_URI = "applied_transaction_uri";
    private static final String KEY_APPLIED_REFERENCE = "applied_reference";
    private static final String KEY_APPLIED_TARGET_PATH = "applied_target_path";
    private static final String KEY_APPLIED_STACK = "applied_stack";

    private SelectionStore() {
    }

    public static void saveGameUri(Context context, Uri uri) {
        saveUri(context, KEY_GAME_URI, uri);
    }

    public static void saveFolderUri(Context context, Uri uri) {
        saveUri(context, KEY_FOLDER_URI, uri);
    }

    public static void savePatchUri(Context context, Uri uri) {
        saveUri(context, KEY_PATCH_URI, uri);
    }

    public static void saveBackupTreeUri(Context context, Uri uri) {
        saveUri(context, KEY_BACKUP_TREE_URI, uri);
    }

    public static void saveWorkspaceTreeUri(Context context, Uri uri) {
        saveUri(context, KEY_WORKSPACE_TREE_URI, uri);
    }

    public static Uri loadGameUri(Context context) {
        return loadUri(context, KEY_GAME_URI);
    }

    public static Uri loadFolderUri(Context context) {
        return loadUri(context, KEY_FOLDER_URI);
    }

    public static Uri loadPatchUri(Context context) {
        return loadUri(context, KEY_PATCH_URI);
    }

    public static Uri loadBackupTreeUri(Context context) {
        return loadUri(context, KEY_BACKUP_TREE_URI);
    }

    public static Uri loadWorkspaceTreeUri(Context context) {
        return loadUri(context, KEY_WORKSPACE_TREE_URI);
    }

    public static void saveLatestVerifiedBackup(Context context, Uri uri, String reference) {
        SharedPreferences.Editor editor = prefs(context).edit();
        putUri(editor, KEY_LATEST_BACKUP_URI, uri);
        putString(editor, KEY_LATEST_BACKUP_REFERENCE, reference);
        editor.apply();
    }

    public static Uri loadLatestBackupUri(Context context) {
        return loadUri(context, KEY_LATEST_BACKUP_URI);
    }

    public static String loadLatestBackupReference(Context context) {
        return prefs(context).getString(KEY_LATEST_BACKUP_REFERENCE, "");
    }

    public static void clearLatestVerifiedBackup(Context context) {
        prefs(context).edit()
                .remove(KEY_LATEST_BACKUP_URI)
                .remove(KEY_LATEST_BACKUP_REFERENCE)
                .apply();
    }

    public static void saveLatestWorkspace(Context context, Uri uri, String reference) {
        SharedPreferences.Editor editor = prefs(context).edit();
        putUri(editor, KEY_LATEST_WORKSPACE_URI, uri);
        putString(editor, KEY_LATEST_WORKSPACE_REFERENCE, reference);
        editor.apply();
    }

    public static Uri loadLatestWorkspaceUri(Context context) {
        return loadUri(context, KEY_LATEST_WORKSPACE_URI);
    }

    public static String loadLatestWorkspaceReference(Context context) {
        return prefs(context).getString(KEY_LATEST_WORKSPACE_REFERENCE, "");
    }

    public static void clearLatestWorkspace(Context context) {
        prefs(context).edit()
                .remove(KEY_LATEST_WORKSPACE_URI)
                .remove(KEY_LATEST_WORKSPACE_REFERENCE)
                .apply();
    }

    public static void saveLatestExtraction(Context context, Uri uri, String reference) {
        SharedPreferences.Editor editor = prefs(context).edit();
        putUri(editor, KEY_LATEST_EXTRACTION_URI, uri);
        putString(editor, KEY_LATEST_EXTRACTION_REFERENCE, reference);
        editor.apply();
    }

    public static Uri loadLatestExtractionUri(Context context) {
        return loadUri(context, KEY_LATEST_EXTRACTION_URI);
    }

    public static String loadLatestExtractionReference(Context context) {
        return prefs(context).getString(KEY_LATEST_EXTRACTION_REFERENCE, "");
    }

    public static void clearLatestExtraction(Context context) {
        prefs(context).edit()
                .remove(KEY_LATEST_EXTRACTION_URI)
                .remove(KEY_LATEST_EXTRACTION_REFERENCE)
                .apply();
    }

    public static void saveLatestWorkingCopy(Context context, Uri uri, String reference) {
        SharedPreferences.Editor editor = prefs(context).edit();
        putUri(editor, KEY_LATEST_WORKING_URI, uri);
        putString(editor, KEY_LATEST_WORKING_REFERENCE, reference);
        editor.apply();
    }

    public static Uri loadLatestWorkingUri(Context context) {
        return loadUri(context, KEY_LATEST_WORKING_URI);
    }

    public static String loadLatestWorkingReference(Context context) {
        return prefs(context).getString(KEY_LATEST_WORKING_REFERENCE, "");
    }

    public static void clearLatestWorkingCopy(Context context) {
        prefs(context).edit()
                .remove(KEY_LATEST_WORKING_URI)
                .remove(KEY_LATEST_WORKING_REFERENCE)
                .remove(KEY_SELECTED_ASSET_PATH)
                .remove(KEY_REPLACEMENT_FILE_URI)
                .remove(KEY_STAGED_TRANSACTION_URI)
                .remove(KEY_STAGED_REFERENCE)
                .remove(KEY_STAGED_TARGET_PATH)
                .remove(KEY_STAGED_REPLACEMENT_HASH)
                .remove(KEY_STAGED_REPLACEMENT_SIZE)
                .remove(KEY_APPLIED_TRANSACTION_URI)
                .remove(KEY_APPLIED_REFERENCE)
                .remove(KEY_APPLIED_TARGET_PATH)
                .remove(KEY_APPLIED_STACK)
                .apply();
    }


    public static void saveSelectedAssetPath(Context context, String path) {
        SharedPreferences.Editor editor = prefs(context).edit();
        putString(editor, KEY_SELECTED_ASSET_PATH, path);
        editor.apply();
    }

    public static String loadSelectedAssetPath(Context context) {
        return prefs(context).getString(KEY_SELECTED_ASSET_PATH, "");
    }

    public static void saveReplacementFileUri(Context context, Uri uri) {
        saveUri(context, KEY_REPLACEMENT_FILE_URI, uri);
    }

    public static Uri loadReplacementFileUri(Context context) {
        return loadUri(context, KEY_REPLACEMENT_FILE_URI);
    }

    public static void saveStagedReplacement(
            Context context,
            Uri transactionUri,
            String reference,
            String targetPath,
            String replacementHash,
            long replacementSize
    ) {
        SharedPreferences.Editor editor = prefs(context).edit();
        putUri(editor, KEY_STAGED_TRANSACTION_URI, transactionUri);
        putString(editor, KEY_STAGED_REFERENCE, reference);
        putString(editor, KEY_STAGED_TARGET_PATH, targetPath);
        putString(editor, KEY_STAGED_REPLACEMENT_HASH, replacementHash);
        if (replacementSize < 0L) {
            editor.remove(KEY_STAGED_REPLACEMENT_SIZE);
        } else {
            editor.putString(KEY_STAGED_REPLACEMENT_SIZE, Long.toString(replacementSize));
        }
        editor.apply();
    }

    public static Uri loadStagedTransactionUri(Context context) {
        return loadUri(context, KEY_STAGED_TRANSACTION_URI);
    }

    public static String loadStagedReference(Context context) {
        return prefs(context).getString(KEY_STAGED_REFERENCE, "");
    }

    public static String loadStagedTargetPath(Context context) {
        return prefs(context).getString(KEY_STAGED_TARGET_PATH, "");
    }

    public static String loadStagedReplacementHash(Context context) {
        return prefs(context).getString(KEY_STAGED_REPLACEMENT_HASH, "");
    }

    public static long loadStagedReplacementSize(Context context) {
        String value = prefs(context).getString(KEY_STAGED_REPLACEMENT_SIZE, "");
        try {
            return Long.parseLong(value);
        } catch (RuntimeException ignored) {
            return -1L;
        }
    }

    public static void clearStagedReplacement(Context context) {
        prefs(context).edit()
                .remove(KEY_STAGED_TRANSACTION_URI)
                .remove(KEY_STAGED_REFERENCE)
                .remove(KEY_STAGED_TARGET_PATH)
                .remove(KEY_STAGED_REPLACEMENT_HASH)
                .remove(KEY_STAGED_REPLACEMENT_SIZE)
                .apply();
    }

    public static void saveAppliedReplacement(
            Context context,
            Uri transactionUri,
            String reference,
            String targetPath
    ) {
        SharedPreferences preferences = prefs(context);
        String existingStack = preferences.getString(KEY_APPLIED_STACK, "");
        String entry = encode(transactionUri == null ? "" : transactionUri.toString())
                + "\t" + encode(reference)
                + "\t" + encode(targetPath);
        String nextStack = existingStack == null || existingStack.isEmpty()
                ? entry
                : existingStack + "\n" + entry;
        SharedPreferences.Editor editor = preferences.edit();
        putUri(editor, KEY_APPLIED_TRANSACTION_URI, transactionUri);
        putString(editor, KEY_APPLIED_REFERENCE, reference);
        putString(editor, KEY_APPLIED_TARGET_PATH, targetPath);
        editor.putString(KEY_APPLIED_STACK, nextStack);
        editor.apply();
    }

    public static Uri loadAppliedTransactionUri(Context context) {
        return loadUri(context, KEY_APPLIED_TRANSACTION_URI);
    }

    public static String loadAppliedReference(Context context) {
        return prefs(context).getString(KEY_APPLIED_REFERENCE, "");
    }

    public static String loadAppliedTargetPath(Context context) {
        return prefs(context).getString(KEY_APPLIED_TARGET_PATH, "");
    }

    public static void popAppliedReplacement(Context context) {
        SharedPreferences preferences = prefs(context);
        String stack = preferences.getString(KEY_APPLIED_STACK, "");
        if (stack == null || stack.isEmpty()) {
            clearAppliedReplacement(context);
            return;
        }
        String[] entries = stack.split("\n", -1);
        int remaining = Math.max(0, entries.length - 1);
        SharedPreferences.Editor editor = preferences.edit();
        if (remaining == 0) {
            editor.remove(KEY_APPLIED_STACK)
                    .remove(KEY_APPLIED_TRANSACTION_URI)
                    .remove(KEY_APPLIED_REFERENCE)
                    .remove(KEY_APPLIED_TARGET_PATH)
                    .apply();
            return;
        }
        StringBuilder nextStack = new StringBuilder();
        for (int index = 0; index < remaining; index++) {
            if (index > 0) {
                nextStack.append('\n');
            }
            nextStack.append(entries[index]);
        }
        String[] latest = entries[remaining - 1].split("\t", -1);
        if (latest.length != 3) {
            editor.remove(KEY_APPLIED_STACK)
                    .remove(KEY_APPLIED_TRANSACTION_URI)
                    .remove(KEY_APPLIED_REFERENCE)
                    .remove(KEY_APPLIED_TARGET_PATH)
                    .apply();
            return;
        }
        String uriValue = decode(latest[0]);
        String reference = decode(latest[1]);
        String targetPath = decode(latest[2]);
        editor.putString(KEY_APPLIED_STACK, nextStack.toString());
        if (uriValue.isEmpty()) {
            editor.remove(KEY_APPLIED_TRANSACTION_URI);
        } else {
            editor.putString(KEY_APPLIED_TRANSACTION_URI, uriValue);
        }
        putString(editor, KEY_APPLIED_REFERENCE, reference);
        putString(editor, KEY_APPLIED_TARGET_PATH, targetPath);
        editor.apply();
    }

    public static void clearAppliedReplacement(Context context) {
        prefs(context).edit()
                .remove(KEY_APPLIED_TRANSACTION_URI)
                .remove(KEY_APPLIED_REFERENCE)
                .remove(KEY_APPLIED_TARGET_PATH)
                .remove(KEY_APPLIED_STACK)
                .apply();
    }

    public static void clearPhase1DRecords(Context context) {
        prefs(context).edit()
                .remove(KEY_SELECTED_ASSET_PATH)
                .remove(KEY_REPLACEMENT_FILE_URI)
                .remove(KEY_STAGED_TRANSACTION_URI)
                .remove(KEY_STAGED_REFERENCE)
                .remove(KEY_STAGED_TARGET_PATH)
                .remove(KEY_STAGED_REPLACEMENT_HASH)
                .remove(KEY_STAGED_REPLACEMENT_SIZE)
                .remove(KEY_APPLIED_TRANSACTION_URI)
                .remove(KEY_APPLIED_REFERENCE)
                .remove(KEY_APPLIED_TARGET_PATH)
                .remove(KEY_APPLIED_STACK)
                .apply();
    }

    public static void clearPhase1CRecords(Context context) {
        prefs(context).edit()
                .remove(KEY_LATEST_EXTRACTION_URI)
                .remove(KEY_LATEST_EXTRACTION_REFERENCE)
                .remove(KEY_LATEST_WORKING_URI)
                .remove(KEY_LATEST_WORKING_REFERENCE)
                .remove(KEY_SELECTED_ASSET_PATH)
                .remove(KEY_REPLACEMENT_FILE_URI)
                .remove(KEY_STAGED_TRANSACTION_URI)
                .remove(KEY_STAGED_REFERENCE)
                .remove(KEY_STAGED_TARGET_PATH)
                .remove(KEY_STAGED_REPLACEMENT_HASH)
                .remove(KEY_STAGED_REPLACEMENT_SIZE)
                .remove(KEY_APPLIED_TRANSACTION_URI)
                .remove(KEY_APPLIED_REFERENCE)
                .remove(KEY_APPLIED_TARGET_PATH)
                .remove(KEY_APPLIED_STACK)
                .apply();
    }

    public static void clearAll(Context context) {
        prefs(context).edit().clear().apply();
    }

    private static void saveUri(Context context, String key, Uri uri) {
        SharedPreferences.Editor editor = prefs(context).edit();
        putUri(editor, key, uri);
        editor.apply();
    }

    private static void putUri(SharedPreferences.Editor editor, String key, Uri uri) {
        if (uri == null) {
            editor.remove(key);
        } else {
            editor.putString(key, uri.toString());
        }
    }

    private static void putString(SharedPreferences.Editor editor, String key, String value) {
        if (value == null || value.trim().isEmpty()) {
            editor.remove(key);
        } else {
            editor.putString(key, value);
        }
    }

    private static Uri loadUri(Context context, String key) {
        return parse(prefs(context).getString(key, null));
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                (value == null ? "" : value).getBytes(StandardCharsets.UTF_8)
        );
    }

    private static String decode(String value) {
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    private static Uri parse(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        try {
            return Uri.parse(value);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
