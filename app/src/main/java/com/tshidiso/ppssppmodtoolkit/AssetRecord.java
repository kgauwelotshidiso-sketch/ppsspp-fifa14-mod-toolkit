package com.tshidiso.ppssppmodtoolkit;

import java.util.Locale;

public final class AssetRecord {
    private final String path;
    private final long sizeBytes;
    private final String sha256;

    public AssetRecord(String path, long sizeBytes, String sha256) {
        this.path = path == null ? "" : path;
        this.sizeBytes = sizeBytes;
        this.sha256 = sha256 == null ? "" : sha256.toLowerCase(Locale.US);
    }

    public String getPath() {
        return path;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public String getSha256() {
        return sha256;
    }

    public String getName() {
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    public String getExtension() {
        String name = getName();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.US);
    }

    public String getCategory() {
        return ReplacementRules.categoryForPath(path);
    }
}
