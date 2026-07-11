package com.tshidiso.ppssppmodtoolkit;

import android.net.Uri;

public final class StagedReplacementResult {
    private final OperationResult operationResult;
    private final Uri transactionUri;
    private final String targetPath;
    private final String replacementName;
    private final long replacementSize;
    private final String replacementSha256;

    public StagedReplacementResult(
            OperationResult operationResult,
            Uri transactionUri,
            String targetPath,
            String replacementName,
            long replacementSize,
            String replacementSha256
    ) {
        this.operationResult = operationResult;
        this.transactionUri = transactionUri;
        this.targetPath = targetPath == null ? "" : targetPath;
        this.replacementName = replacementName == null ? "" : replacementName;
        this.replacementSize = replacementSize;
        this.replacementSha256 = replacementSha256 == null ? "" : replacementSha256;
    }

    public OperationResult getOperationResult() {
        return operationResult;
    }

    public Uri getTransactionUri() {
        return transactionUri;
    }

    public String getTargetPath() {
        return targetPath;
    }

    public String getReplacementName() {
        return replacementName;
    }

    public long getReplacementSize() {
        return replacementSize;
    }

    public String getReplacementSha256() {
        return replacementSha256;
    }
}
