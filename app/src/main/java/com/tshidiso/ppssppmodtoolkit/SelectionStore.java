package com.tshidiso.ppssppmodtoolkit;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

public final class SelectionStore {
    private static final String PREFS = "toolkit_selection_state";
    private static final String KEY_GAME_URI = "game_uri";
    private static final String KEY_FOLDER_URI = "folder_uri";
    private static final String KEY_PATCH_URI = "patch_uri";

    private SelectionStore() {
    }

    public static void saveGameUri(Context context, Uri uri) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (uri == null) {
            editor.remove(KEY_GAME_URI);
        } else {
            editor.putString(KEY_GAME_URI, uri.toString());
        }
        editor.apply();
    }

    public static void saveFolderUri(Context context, Uri uri) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (uri == null) {
            editor.remove(KEY_FOLDER_URI);
        } else {
            editor.putString(KEY_FOLDER_URI, uri.toString());
        }
        editor.apply();
    }

    public static void savePatchUri(Context context, Uri uri) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (uri == null) {
            editor.remove(KEY_PATCH_URI);
        } else {
            editor.putString(KEY_PATCH_URI, uri.toString());
        }
        editor.apply();
    }

    public static Uri loadGameUri(Context context) {
        return parse(prefs(context).getString(KEY_GAME_URI, null));
    }

    public static Uri loadFolderUri(Context context) {
        return parse(prefs(context).getString(KEY_FOLDER_URI, null));
    }

    public static Uri loadPatchUri(Context context) {
        return parse(prefs(context).getString(KEY_PATCH_URI, null));
    }

    public static void clearAll(Context context) {
        prefs(context).edit().clear().apply();
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
