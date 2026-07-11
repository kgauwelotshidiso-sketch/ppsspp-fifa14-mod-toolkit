package com.tshidiso.ppssppmodtoolkit;

import android.net.Uri;

public final class OperationResult {
    private final ScanReport report;
    private final boolean success;
    private final Uri createdUri;
    private final String reference;

    public OperationResult(ScanReport report, boolean success, Uri createdUri, String reference) {
        this.report = report;
        this.success = success;
        this.createdUri = createdUri;
        this.reference = reference == null ? "" : reference;
    }

    public ScanReport getReport() {
        return report;
    }

    public boolean isSuccess() {
        return success;
    }

    public Uri getCreatedUri() {
        return createdUri;
    }

    public String getReference() {
        return reference;
    }
}
