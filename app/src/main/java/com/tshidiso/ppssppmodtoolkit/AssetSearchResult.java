package com.tshidiso.ppssppmodtoolkit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class AssetSearchResult {
    private final List<AssetRecord> matches;
    private final ScanReport report;

    public AssetSearchResult(List<AssetRecord> matches, ScanReport report) {
        this.matches = Collections.unmodifiableList(
                matches == null ? new ArrayList<>() : new ArrayList<>(matches)
        );
        this.report = report;
    }

    public List<AssetRecord> getMatches() {
        return matches;
    }

    public ScanReport getReport() {
        return report;
    }
}
