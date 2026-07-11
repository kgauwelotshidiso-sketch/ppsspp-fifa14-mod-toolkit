package com.tshidiso.ppssppmodtoolkit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class ScanReport {
    private final String title;
    private final String status;
    private final String summary;
    private final List<String> details;
    private final List<String> candidates;

    public ScanReport(
            String title,
            String status,
            String summary,
            List<String> details,
            List<String> candidates
    ) {
        this.title = title == null ? "Scan report" : title;
        this.status = status == null ? "Unknown" : status;
        this.summary = summary == null ? "" : summary;
        this.details = Collections.unmodifiableList(
                details == null ? new ArrayList<>() : new ArrayList<>(details)
        );
        this.candidates = Collections.unmodifiableList(
                candidates == null ? new ArrayList<>() : new ArrayList<>(candidates)
        );
    }

    public String toDisplayText() {
        StringBuilder out = new StringBuilder();
        out.append(title).append('\n');
        out.append("Status: ").append(status).append('\n');

        if (!summary.trim().isEmpty()) {
            out.append('\n').append(summary).append('\n');
        }

        if (!details.isEmpty()) {
            out.append("\nDetails\n");
            for (String detail : details) {
                out.append("• ").append(detail).append('\n');
            }
        }

        if (!candidates.isEmpty()) {
            out.append("\nDetected modding candidates\n");
            int shown = Math.min(candidates.size(), 80);
            for (int i = 0; i < shown; i++) {
                out.append("• ").append(candidates.get(i)).append('\n');
            }
            if (candidates.size() > shown) {
                out.append("• …and ")
                        .append(candidates.size() - shown)
                        .append(" more\n");
            }
        }

        return out.toString().trim();
    }
}
