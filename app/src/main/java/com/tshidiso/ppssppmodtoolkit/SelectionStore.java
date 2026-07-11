package com.tshidiso.ppssppmodtoolkit;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

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
                .apply();
    }

    public static void clearPhase1CRecords(Context context) {
        prefs(context).edit()
                .remove(KEY_LATEST_EXTRACTION_URI)
                .remove(KEY_LATEST_EXTRACTION_REFERENCE)
                .remove(KEY_LATEST_WORKING_URI)
                .remove(KEY_LATEST_WORKING_REFERENCE)
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
