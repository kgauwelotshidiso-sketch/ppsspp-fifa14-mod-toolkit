package com.tshidiso.ppssppmodtoolkit;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Conservative, read-only structural decoder for EA/FIFA binary databases.
 *
 * <p>The decoder does not claim that a table's record schema is known. It maps verified table-name
 * markers, decodes a plausible little-endian section-offset directory when the header supports it,
 * shows local aligned words and pointer targets, and ranks record-layout hypotheses. Every
 * hypothesis remains explicitly untrusted until it can be confirmed against repeatable database
 * samples.</p>
 */
public final class FifaTableDecoder {
    private static final int MAX_HEADER_BYTES = 4096;
    private static final int MAX_MARKER_OCCURRENCES = 512;
    private static final int MAX_NEARBY_STRINGS = 24;
    private static final int MAX_LOCAL_WORDS = 28;
    private static final int MAX_HYPOTHESES = 10;
    private static final int MAX_SAMPLE_RECORDS = 4;
    private static final int MAX_SAMPLE_WORDS = 8;
    private static final long MAX_PLAUSIBLE_RECORD_COUNT = 500_000L;
    private static final long MAX_PLAUSIBLE_RECORD_BYTES = 4096L;

    private FifaTableDecoder() {
    }

    public static DecodeResult decode(byte[] data, String requestedTable) {
        if (data == null || data.length < 16) {
            throw new IllegalArgumentException("Database is too small for structural decoding");
        }
        String table = DatabaseRules.requireKnownTableName(requestedTable);
        HeaderDirectory header = decodeHeaderDirectory(data);
        List<MarkerOccurrence> markers = findKnownMarkerOccurrences(data);
        List<MarkerOccurrence> selected = new ArrayList<>();
        for (MarkerOccurrence marker : markers) {
            if (table.equals(marker.name)) {
                selected.add(marker);
            }
        }
        FifaSchemaDecoder.SchemaResult schemaResult = FifaSchemaDecoder.decode(data, table);

        List<String> details = new ArrayList<>();
        List<String> findings = new ArrayList<>();
        details.add("Requested table: " + table);
        details.add("Database bytes examined: " + data.length);
        details.add("Known table-marker occurrences: " + markers.size());
        details.add("Unique known table names: " + uniqueMarkerNames(markers).size());
        appendSchemaEvidence(schemaResult, details, findings);

        if (header.valid) {
            details.add("Candidate little-endian header size: " + header.headerBytes
                    + " bytes (" + (header.headerBytes / 4) + " words)");
            details.add("Candidate section offsets decoded: " + header.sectionStarts.size());
            details.add("Header confidence: " + header.confidence);
            int sectionLimit = Math.min(header.sections.size(), 28);
            for (int index = 0; index < sectionLimit; index++) {
                SectionRange section = header.sections.get(index);
                findings.add("Candidate section " + section.index + ": "
                        + formatOffset(section.start) + " → " + formatOffset(section.end)
                        + " (" + (section.end - section.start) + " bytes)");
            }
            if (header.sections.size() > sectionLimit) {
                findings.add("…and " + (header.sections.size() - sectionLimit)
                        + " more candidate sections in the saved decoder report");
            }
        } else {
            details.add("Candidate section directory: not proven from the header");
            details.add("Header confidence: none");
        }

        if (markers.isEmpty()) {
            return new DecodeResult(
                    table,
                    false,
                    "No known FIFA table markers were found. No table schema was inferred.",
                    details,
                    findings,
                    buildFullReport(data, table, header, markers, selected, details, findings)
            );
        }

        MarkerCluster cluster = describeMarkerCluster(markers);
        details.add("Marker directory span: " + formatOffset(cluster.start) + " → "
                + formatOffset(cluster.end) + " (" + (cluster.end - cluster.start) + " bytes)");
        details.add("Median marker gap: " + cluster.medianGap + " bytes");

        for (MarkerOccurrence marker : markers) {
            String sectionLabel = header.sectionFor(marker.offset);
            findings.add("Table marker map: " + marker.name + " at "
                    + formatOffset(marker.offset) + " | " + sectionLabel);
        }

        if (selected.isEmpty()) {
            return new DecodeResult(
                    table,
                    false,
                    "The requested known table marker was not present. The database remained read-only.",
                    details,
                    findings,
                    buildFullReport(data, table, header, markers, selected, details, findings)
            );
        }

        details.add("Requested marker occurrences: " + selected.size());
        for (int occurrenceIndex = 0; occurrenceIndex < selected.size(); occurrenceIndex++) {
            MarkerOccurrence marker = selected.get(occurrenceIndex);
            analyzeOccurrence(
                    data,
                    table,
                    occurrenceIndex + 1,
                    marker,
                    markers,
                    header,
                    details,
                    findings
            );
        }

        String summary = schemaResult.isVerified()
                ? "The table schema block was structurally verified read-only against the uploaded "
                + "ULUS-10655 database profile: table hashes, descriptor-word array, aligned field-name "
                + "list, and the exact successor-table boundary were decoded separately. Descriptor "
                + "semantics, descriptor-to-field mapping, and row-data boundaries remain unconfirmed, "
                + "so numeric editing stays disabled."
                : "The table marker and surrounding binary structures were mapped read-only. Candidate "
                + "section boundaries, aligned words, pointer targets, nearby strings, and record-layout "
                + "hypotheses remain evidence for reverse engineering—not permission to edit records.";
        return new DecodeResult(
                table,
                true,
                summary,
                details,
                findings,
                buildFullReport(data, table, header, markers, selected, details, findings)
        );
    }

    private static void appendSchemaEvidence(
            FifaSchemaDecoder.SchemaResult schemaResult,
            List<String> details,
            List<String> findings
    ) {
        details.add("Verified schema block: " + (schemaResult.isVerified() ? "yes" : "no"));
        details.add("Exact table-name occurrences examined for schema: "
                + schemaResult.getOccurrencesExamined());
        for (String rejected : schemaResult.getRejectedOccurrences()) {
            findings.add("Rejected non-schema table-name occurrence: " + rejected);
        }
        if (!schemaResult.isVerified()) {
            findings.add("Schema field list: unavailable because no occurrence passed every verified "
                    + "length-prefix, descriptor-array, field-name-count, first/last-field, successor-"
                    + "boundary, and alignment check");
            return;
        }

        FifaSchemaDecoder.TableSchema schema = schemaResult.getSchema();
        details.add("Structural schema marker offset: " + formatOffset(schema.getMarkerOffset()));
        details.add("Table-name length-prefix offset: "
                + formatOffset(schema.getLengthPrefixOffset()));
        details.add("Aligned schema header offset: " + formatOffset(schema.getHeaderOffset()));
        details.add("Table hash A: " + schema.hashAHex()
                + " (" + schema.getHashA() + ")");
        details.add("Table hash B: " + schema.hashBHex()
                + " (" + schema.getHashB() + ")");
        details.add("Verified descriptor-word count: " + schema.getDescriptorCount());
        details.add("Verified field-name count: " + schema.getFieldCount());
        details.add("Descriptor array: " + formatOffset(schema.getDescriptorOffset())
                + " → " + formatOffset(schema.getDescriptorEndOffset()));
        details.add("Verified schema block end: " + formatOffset(schema.getSchemaEndOffset()));
        details.add("Verified successor table: " + schema.getSuccessorTableName()
                + " at " + formatOffset(schema.getSuccessorMarkerOffset()));

        int zero = 0;
        int positive = 0;
        int negative = 0;
        for (int descriptor : schema.getDescriptors()) {
            if (descriptor == 0) {
                zero++;
            } else if (descriptor > 0) {
                positive++;
            } else {
                negative++;
            }
        }
        findings.add("Descriptor-word distribution: zero=" + zero
                + ", positive=" + positive + ", negative=" + negative
                + " | descriptor semantics and field mapping remain UNCONFIRMED");
        for (int index = 0; index < schema.getDescriptors().size(); index++) {
            int descriptor = schema.getDescriptors().get(index);
            findings.add("Descriptor word " + (index + 1) + "/"
                    + schema.getDescriptorCount() + ": " + descriptor
                    + " (" + String.format(Locale.US, "0x%08x",
                    Integer.toUnsignedLong(descriptor)) + ")");
        }
        for (FifaSchemaDecoder.FieldDefinition field : schema.getFields()) {
            findings.add("Schema field name " + (field.getIndex() + 1) + "/"
                    + schema.getFieldCount() + ": " + field.getName()
                    + " | lengthPrefixOffset="
                    + formatOffset(field.getLengthPrefixOffset())
                    + " | nameOffset=" + formatOffset(field.getNameOffset()));
        }
    }

    private static void analyzeOccurrence(
            byte[] data,
            String table,
            int occurrenceNumber,
            MarkerOccurrence marker,
            List<MarkerOccurrence> allMarkers,
            HeaderDirectory header,
            List<String> details,
            List<String> findings
    ) {
        details.add(table + " occurrence " + occurrenceNumber + " marker offset: "
                + formatOffset(marker.offset));
        details.add(table + " occurrence " + occurrenceNumber + " section: "
                + header.sectionFor(marker.offset));
        details.add(table + " occurrence " + occurrenceNumber + " token boundaries: "
                + marker.boundaryDescription);

        MarkerOccurrence previous = previousMarker(allMarkers, marker.offset);
        MarkerOccurrence next = nextMarker(allMarkers, marker.offset);
        if (previous != null) {
            details.add("Previous known marker: " + previous.name + " at "
                    + formatOffset(previous.offset) + " (gap "
                    + (marker.offset - previous.offset) + " bytes)");
        }
        if (next != null) {
            details.add("Next known marker: " + next.name + " at "
                    + formatOffset(next.offset) + " (gap "
                    + (next.offset - marker.offset) + " bytes)");
        }

        findings.add("Selected marker context: " + formatOffset(marker.offset) + " | …"
                + printableContext(data, marker.offset, marker.name.length(), 48) + "…");
        findings.add("Selected marker hex window:\n"
                + hexWindow(data, marker.offset, 32, 80));

        List<AsciiString> nearbyStrings = findAsciiStrings(
                data,
                Math.max(0, marker.offset - 384),
                Math.min(data.length, marker.offset + marker.name.length() + 384),
                3,
                MAX_NEARBY_STRINGS
        );
        for (AsciiString value : nearbyStrings) {
            findings.add("Nearby ASCII at " + formatOffset(value.offset) + ": “"
                    + value.text + "”");
        }

        List<WordObservation> observations = inspectAlignedWords(
                data,
                marker.offset,
                header,
                allMarkers
        );
        for (WordObservation observation : observations) {
            findings.add("LE32 " + formatOffset(observation.wordOffset) + " = "
                    + observation.unsignedValue + " (0x"
                    + Long.toHexString(observation.unsignedValue) + ") | "
                    + observation.classification);
        }

        List<LayoutHypothesis> hypotheses = inferLayoutHypotheses(data, marker.offset, header);
        if (hypotheses.isEmpty()) {
            findings.add("Record-layout hypotheses: none met the conservative bounds near this marker");
            return;
        }
        for (int index = 0; index < hypotheses.size(); index++) {
            LayoutHypothesis hypothesis = hypotheses.get(index);
            findings.add("Layout hypothesis " + (index + 1)
                    + " [score " + hypothesis.score + "]: count=" + hypothesis.count
                    + ", recordBytes=" + hypothesis.recordBytes
                    + ", dataOffset=" + formatOffset(hypothesis.dataOffset)
                    + ", calculatedEnd=" + formatOffset(hypothesis.endOffset)
                    + ", sourceWords=" + formatOffset(hypothesis.originOffset)
                    + " order=" + hypothesis.orderLabel
                    + " | UNCONFIRMED");
        }

        LayoutHypothesis top = hypotheses.get(0);
        List<String> samples = sampleNumericRecords(data, top);
        for (String sample : samples) {
            findings.add(sample + " | values are raw unsigned LE32 words, fields unconfirmed");
        }
    }

    private static HeaderDirectory decodeHeaderDirectory(byte[] data) {
        if (data.length < 12) {
            return HeaderDirectory.invalid();
        }
        long headerValue = readU32Le(data, 8);
        if (headerValue < 12L || headerValue > Math.min(data.length, MAX_HEADER_BYTES)
                || (headerValue & 3L) != 0L) {
            return HeaderDirectory.invalid();
        }
        int headerBytes = (int) headerValue;
        int wordCount = headerBytes / 4;
        if (wordCount < 4 || wordCount > MAX_HEADER_BYTES / 4) {
            return HeaderDirectory.invalid();
        }

        List<Long> rawWords = new ArrayList<>();
        for (int index = 0; index < wordCount; index++) {
            rawWords.add(readU32Le(data, index * 4));
        }

        List<Integer> starts = new ArrayList<>();
        long previous = -1L;
        int monotonicCount = 0;
        for (int index = 3; index < rawWords.size(); index++) {
            long value = rawWords.get(index);
            if (value >= headerBytes && value <= data.length) {
                if (previous < 0L || value >= previous) {
                    monotonicCount++;
                }
                previous = value;
                int intValue = (int) value;
                if (starts.isEmpty() || starts.get(starts.size() - 1) != intValue) {
                    starts.add(intValue);
                }
            }
        }
        if (starts.size() < 3 || monotonicCount < 3) {
            return HeaderDirectory.invalid();
        }
        Collections.sort(starts);
        List<Integer> unique = new ArrayList<>();
        for (Integer start : starts) {
            if (unique.isEmpty() || !unique.get(unique.size() - 1).equals(start)) {
                unique.add(start);
            }
        }
        if (unique.get(0) < headerBytes) {
            return HeaderDirectory.invalid();
        }

        List<SectionRange> sections = new ArrayList<>();
        for (int index = 0; index < unique.size(); index++) {
            int start = unique.get(index);
            int end = index + 1 < unique.size() ? unique.get(index + 1) : data.length;
            if (end < start) {
                return HeaderDirectory.invalid();
            }
            sections.add(new SectionRange(index, start, end));
        }
        String confidence = monotonicCount == rawWords.size() - 3
                ? "strong structural candidate (all in-range header words are monotonic)"
                : "moderate structural candidate (usable monotonic offsets found)";
        return new HeaderDirectory(true, headerBytes, rawWords, unique, sections, confidence);
    }

    private static List<MarkerOccurrence> findKnownMarkerOccurrences(byte[] data) {
        List<MarkerOccurrence> output = new ArrayList<>();
        byte[] lower = asciiLower(data);
        for (String name : DatabaseRules.knownTableMarkers()) {
            byte[] needle = name.getBytes(StandardCharsets.US_ASCII);
            int from = 0;
            while (from <= lower.length - needle.length && output.size() < MAX_MARKER_OCCURRENCES) {
                int found = indexOf(lower, needle, from);
                if (found < 0) {
                    break;
                }
                if (isTokenBoundary(data, found - 1)
                        && isTokenBoundary(data, found + needle.length)) {
                    output.add(new MarkerOccurrence(
                            name,
                            found,
                            describeBoundaries(data, found, needle.length)
                    ));
                }
                from = found + Math.max(1, needle.length);
            }
        }
        output.sort(Comparator.comparingInt(value -> value.offset));
        return output;
    }

    private static List<WordObservation> inspectAlignedWords(
            byte[] data,
            int markerOffset,
            HeaderDirectory header,
            List<MarkerOccurrence> markers
    ) {
        List<WordObservation> output = new ArrayList<>();
        int start = alignDown(Math.max(0, markerOffset - 64), 4);
        int end = Math.min(data.length - 4, markerOffset + 64);
        Map<Integer, String> markerByOffset = new HashMap<>();
        for (MarkerOccurrence marker : markers) {
            markerByOffset.put(marker.offset, marker.name);
        }
        Set<Integer> sectionStarts = new LinkedHashSet<>(header.sectionStarts);
        for (int offset = start; offset <= end && output.size() < MAX_LOCAL_WORDS; offset += 4) {
            long value = readU32Le(data, offset);
            String classification;
            if (value == 0L) {
                classification = "zero";
            } else if (value <= Integer.MAX_VALUE && sectionStarts.contains((int) value)) {
                classification = "matches candidate section start";
            } else if (value < data.length) {
                int target = (int) value;
                String markerName = markerByOffset.get(target);
                if (markerName != null) {
                    classification = "in-file pointer to table marker “" + markerName + "”";
                } else {
                    String stringAtTarget = printableStringAt(data, target, 32);
                    if (!stringAtTarget.isEmpty()) {
                        classification = "in-file pointer to ASCII “" + stringAtTarget + "”";
                    } else {
                        classification = "in-file offset candidate; " + header.sectionFor(target);
                    }
                }
            } else if (value <= MAX_PLAUSIBLE_RECORD_COUNT) {
                classification = "small integer/count candidate";
            } else {
                classification = "raw unsigned value";
            }
            output.add(new WordObservation(offset, value, classification));
        }
        return output;
    }

    private static List<LayoutHypothesis> inferLayoutHypotheses(
            byte[] data,
            int markerOffset,
            HeaderDirectory header
    ) {
        int start = alignDown(Math.max(0, markerOffset - 160), 4);
        int end = Math.min(data.length - 12, markerOffset + 160);
        Map<String, LayoutHypothesis> unique = new LinkedHashMap<>();
        int[][] permutations = new int[][]{
                {0, 1, 2}, {0, 2, 1}, {1, 0, 2},
                {1, 2, 0}, {2, 0, 1}, {2, 1, 0}
        };
        for (int offset = start; offset <= end; offset += 4) {
            long[] words = new long[]{
                    readU32Le(data, offset),
                    readU32Le(data, offset + 4),
                    readU32Le(data, offset + 8)
            };
            for (int[] permutation : permutations) {
                long count = words[permutation[0]];
                long recordBytes = words[permutation[1]];
                long dataOffset = words[permutation[2]];
                if (count < 1L || count > MAX_PLAUSIBLE_RECORD_COUNT
                        || recordBytes < 1L || recordBytes > MAX_PLAUSIBLE_RECORD_BYTES
                        || dataOffset < 0L || dataOffset >= data.length) {
                    continue;
                }
                long payloadBytes;
                try {
                    payloadBytes = Math.multiplyExact(count, recordBytes);
                } catch (ArithmeticException ignored) {
                    continue;
                }
                long calculatedEnd = dataOffset + payloadBytes;
                if (calculatedEnd < dataOffset || calculatedEnd > data.length) {
                    continue;
                }
                int score = 2;
                if ((recordBytes & 3L) == 0L) {
                    score += 2;
                }
                if ((dataOffset & 3L) == 0L) {
                    score += 1;
                }
                if (count >= 10L) {
                    score += 1;
                }
                if (recordBytes >= 4L) {
                    score += 1;
                }
                if (Math.abs(offset - markerOffset) <= 64) {
                    score += 1;
                }
                if (header.containsSectionStart((int) dataOffset)) {
                    score += 2;
                } else if (header.valid && !"outside candidate sections".equals(header.sectionFor((int) dataOffset))) {
                    score += 1;
                }
                String order = "w" + permutation[0] + "=count,w" + permutation[1]
                        + "=recordBytes,w" + permutation[2] + "=dataOffset";
                LayoutHypothesis hypothesis = new LayoutHypothesis(
                        count,
                        recordBytes,
                        (int) dataOffset,
                        (int) calculatedEnd,
                        offset,
                        score,
                        order
                );
                String key = count + ":" + recordBytes + ":" + dataOffset;
                LayoutHypothesis previous = unique.get(key);
                if (previous == null || hypothesis.score > previous.score) {
                    unique.put(key, hypothesis);
                }
            }
        }
        List<LayoutHypothesis> output = new ArrayList<>(unique.values());
        output.sort((first, second) -> {
            int scoreCompare = Integer.compare(second.score, first.score);
            if (scoreCompare != 0) {
                return scoreCompare;
            }
            int distanceFirst = Math.abs(first.originOffset - markerOffset);
            int distanceSecond = Math.abs(second.originOffset - markerOffset);
            return Integer.compare(distanceFirst, distanceSecond);
        });
        if (output.size() > MAX_HYPOTHESES) {
            return new ArrayList<>(output.subList(0, MAX_HYPOTHESES));
        }
        return output;
    }

    private static List<String> sampleNumericRecords(byte[] data, LayoutHypothesis hypothesis) {
        List<String> output = new ArrayList<>();
        int records = (int) Math.min(hypothesis.count, MAX_SAMPLE_RECORDS);
        int words = (int) Math.min(hypothesis.recordBytes / 4L, MAX_SAMPLE_WORDS);
        if (words < 1) {
            return output;
        }
        for (int record = 0; record < records; record++) {
            long startLong = hypothesis.dataOffset + record * hypothesis.recordBytes;
            if (startLong < 0L || startLong + words * 4L > data.length) {
                break;
            }
            int start = (int) startLong;
            StringBuilder line = new StringBuilder();
            line.append("Hypothesis sample record ").append(record).append(" at ")
                    .append(formatOffset(start)).append(": ");
            for (int word = 0; word < words; word++) {
                if (word > 0) {
                    line.append(", ");
                }
                line.append(readU32Le(data, start + word * 4));
            }
            output.add(line.toString());
        }
        return output;
    }

    private static List<AsciiString> findAsciiStrings(
            byte[] data,
            int start,
            int end,
            int minimumLength,
            int maximum
    ) {
        List<AsciiString> output = new ArrayList<>();
        int index = Math.max(0, start);
        int safeEnd = Math.min(data.length, end);
        Set<String> duplicateGuard = new LinkedHashSet<>();
        while (index < safeEnd && output.size() < maximum) {
            if (!isPrintableAscii(data[index] & 0xff)) {
                index++;
                continue;
            }
            int runStart = index;
            while (index < safeEnd && isPrintableAscii(data[index] & 0xff)) {
                index++;
            }
            int length = index - runStart;
            if (length >= minimumLength) {
                int clipped = Math.min(length, 80);
                String text = new String(data, runStart, clipped, StandardCharsets.US_ASCII);
                String key = runStart + ":" + text;
                if (duplicateGuard.add(key)) {
                    output.add(new AsciiString(runStart, text + (length > clipped ? "…" : "")));
                }
            }
        }
        return output;
    }

    private static String buildFullReport(
            byte[] data,
            String table,
            HeaderDirectory header,
            List<MarkerOccurrence> markers,
            List<MarkerOccurrence> selected,
            List<String> details,
            List<String> findings
    ) {
        StringBuilder output = new StringBuilder();
        output.append("PPSSPP Mod Toolkit — Phase 1G Hotfix 1 verified schema decoder report\n");
        output.append("mode=READ_ONLY\n");
        output.append("requested_table=").append(table).append('\n');
        output.append("database_size=").append(data.length).append('\n');
        output.append("requested_marker_occurrences=").append(selected.size()).append('\n');
        output.append("known_marker_occurrences=").append(markers.size()).append('\n');
        output.append("header_candidate_valid=").append(header.valid).append('\n');
        if (header.valid) {
            output.append("header_candidate_bytes=").append(header.headerBytes).append('\n');
            output.append("header_candidate_confidence=").append(header.confidence).append('\n');
            output.append("header_words_le32=");
            for (int index = 0; index < header.rawWords.size(); index++) {
                if (index > 0) {
                    output.append(',');
                }
                output.append(header.rawWords.get(index));
            }
            output.append('\n');
        }
        output.append("database_changed=false\n\n");
        output.append("DETAILS\n");
        for (String detail : details) {
            output.append("- ").append(detail).append('\n');
        }
        output.append("\nFINDINGS\n");
        for (String finding : findings) {
            output.append("- ").append(finding).append('\n');
        }
        output.append("\nSAFETY\n");
        output.append("- Candidate sections are inferred from monotonic little-endian offsets only.\n");
        output.append("- Field names and one-descriptor-per-field boundaries are structurally verified when reported.\n- Descriptor meanings, record counts, record widths, and row-data sections remain unconfirmed.\n");
        output.append("- No numeric record editing is enabled by this report.\n");
        return output.toString();
    }

    private static MarkerCluster describeMarkerCluster(List<MarkerOccurrence> markers) {
        if (markers.isEmpty()) {
            return new MarkerCluster(0, 0, 0);
        }
        List<Integer> gaps = new ArrayList<>();
        for (int index = 1; index < markers.size(); index++) {
            gaps.add(markers.get(index).offset - markers.get(index - 1).offset);
        }
        Collections.sort(gaps);
        int median = gaps.isEmpty() ? 0 : gaps.get(gaps.size() / 2);
        return new MarkerCluster(
                markers.get(0).offset,
                markers.get(markers.size() - 1).offset,
                median
        );
    }

    private static Set<String> uniqueMarkerNames(List<MarkerOccurrence> markers) {
        Set<String> output = new LinkedHashSet<>();
        for (MarkerOccurrence marker : markers) {
            output.add(marker.name);
        }
        return output;
    }

    private static MarkerOccurrence previousMarker(List<MarkerOccurrence> markers, int offset) {
        MarkerOccurrence previous = null;
        for (MarkerOccurrence marker : markers) {
            if (marker.offset >= offset) {
                break;
            }
            previous = marker;
        }
        return previous;
    }

    private static MarkerOccurrence nextMarker(List<MarkerOccurrence> markers, int offset) {
        for (MarkerOccurrence marker : markers) {
            if (marker.offset > offset) {
                return marker;
            }
        }
        return null;
    }

    private static String describeBoundaries(byte[] data, int offset, int length) {
        String before = offset <= 0 ? "start-of-file" : describeByte(data[offset - 1] & 0xff);
        int afterOffset = offset + length;
        String after = afterOffset >= data.length
                ? "end-of-file"
                : describeByte(data[afterOffset] & 0xff);
        return "before=" + before + ", after=" + after;
    }

    private static String describeByte(int value) {
        if (value == 0) {
            return "NUL";
        }
        if (value >= 0x20 && value <= 0x7e) {
            return "ASCII '" + (char) value + "'";
        }
        return String.format(Locale.US, "0x%02x", value);
    }

    private static boolean isTokenBoundary(byte[] data, int offset) {
        if (offset < 0 || offset >= data.length) {
            return true;
        }
        int value = data[offset] & 0xff;
        return !((value >= 'a' && value <= 'z')
                || (value >= 'A' && value <= 'Z')
                || (value >= '0' && value <= '9')
                || value == '_');
    }

    private static String printableStringAt(byte[] data, int offset, int maximumLength) {
        if (offset < 0 || offset >= data.length || !isPrintableAscii(data[offset] & 0xff)) {
            return "";
        }
        int end = offset;
        while (end < data.length && end - offset < maximumLength
                && isPrintableAscii(data[end] & 0xff)) {
            end++;
        }
        if (end - offset < 3) {
            return "";
        }
        String text = new String(data, offset, end - offset, StandardCharsets.US_ASCII);
        return end < data.length && isPrintableAscii(data[end] & 0xff) ? text + "…" : text;
    }

    private static boolean isPrintableAscii(int value) {
        return value >= 0x20 && value <= 0x7e;
    }

    private static String printableContext(byte[] data, int offset, int length, int radius) {
        int start = Math.max(0, offset - radius);
        int end = Math.min(data.length, offset + length + radius);
        StringBuilder output = new StringBuilder(end - start);
        for (int index = start; index < end; index++) {
            int value = data[index] & 0xff;
            output.append(isPrintableAscii(value) ? (char) value : '.');
        }
        return output.toString();
    }

    private static String hexWindow(byte[] data, int center, int before, int after) {
        int start = Math.max(0, center - before);
        int end = Math.min(data.length, center + after);
        StringBuilder output = new StringBuilder((end - start) * 3);
        for (int index = start; index < end; index++) {
            if (index > start) {
                output.append((index - start) % 16 == 0 ? '\n' : ' ');
            }
            output.append(String.format(Locale.US, "%02x", data[index] & 0xff));
        }
        return output.toString();
    }

    private static byte[] asciiLower(byte[] data) {
        byte[] output = Arrays.copyOf(data, data.length);
        for (int index = 0; index < output.length; index++) {
            int value = output[index] & 0xff;
            if (value >= 'A' && value <= 'Z') {
                output[index] = (byte) (value + ('a' - 'A'));
            }
        }
        return output;
    }

    private static int indexOf(byte[] haystack, byte[] needle, int from) {
        if (needle.length == 0) {
            return Math.max(0, from);
        }
        for (int index = Math.max(0, from); index <= haystack.length - needle.length; index++) {
            boolean match = true;
            for (int needleIndex = 0; needleIndex < needle.length; needleIndex++) {
                if (haystack[index + needleIndex] != needle[needleIndex]) {
                    match = false;
                    break;
                }
            }
            if (match) {
                return index;
            }
        }
        return -1;
    }

    private static long readU32Le(byte[] data, int offset) {
        if (offset < 0 || offset + 4 > data.length) {
            return 0L;
        }
        return ((long) data[offset] & 0xffL)
                | (((long) data[offset + 1] & 0xffL) << 8)
                | (((long) data[offset + 2] & 0xffL) << 16)
                | (((long) data[offset + 3] & 0xffL) << 24);
    }

    private static int alignDown(int value, int alignment) {
        return value - Math.floorMod(value, alignment);
    }

    private static String formatOffset(int offset) {
        return offset + " (0x" + Integer.toHexString(offset) + ")";
    }

    public static final class DecodeResult {
        private final String tableName;
        private final boolean markerFound;
        private final String summary;
        private final List<String> details;
        private final List<String> findings;
        private final String fullReportText;

        DecodeResult(
                String tableName,
                boolean markerFound,
                String summary,
                List<String> details,
                List<String> findings,
                String fullReportText
        ) {
            this.tableName = tableName;
            this.markerFound = markerFound;
            this.summary = summary;
            this.details = Collections.unmodifiableList(new ArrayList<>(details));
            this.findings = Collections.unmodifiableList(new ArrayList<>(findings));
            this.fullReportText = fullReportText;
        }

        public String getTableName() {
            return tableName;
        }

        public boolean isMarkerFound() {
            return markerFound;
        }

        public String getSummary() {
            return summary;
        }

        public List<String> getDetails() {
            return details;
        }

        public List<String> getFindings() {
            return findings;
        }

        public String getFullReportText() {
            return fullReportText;
        }
    }

    private static final class HeaderDirectory {
        final boolean valid;
        final int headerBytes;
        final List<Long> rawWords;
        final List<Integer> sectionStarts;
        final List<SectionRange> sections;
        final String confidence;

        HeaderDirectory(
                boolean valid,
                int headerBytes,
                List<Long> rawWords,
                List<Integer> sectionStarts,
                List<SectionRange> sections,
                String confidence
        ) {
            this.valid = valid;
            this.headerBytes = headerBytes;
            this.rawWords = rawWords;
            this.sectionStarts = sectionStarts;
            this.sections = sections;
            this.confidence = confidence;
        }

        static HeaderDirectory invalid() {
            return new HeaderDirectory(
                    false,
                    0,
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    "none"
            );
        }

        boolean containsSectionStart(int offset) {
            return sectionStarts.contains(offset);
        }

        String sectionFor(int offset) {
            if (!valid) {
                return "section unknown";
            }
            for (SectionRange section : sections) {
                if (offset >= section.start && offset < section.end) {
                    return "candidate section " + section.index + " ["
                            + formatOffset(section.start) + " → "
                            + formatOffset(section.end) + ")";
                }
            }
            return "outside candidate sections";
        }
    }

    private static final class SectionRange {
        final int index;
        final int start;
        final int end;

        SectionRange(int index, int start, int end) {
            this.index = index;
            this.start = start;
            this.end = end;
        }
    }

    private static final class MarkerOccurrence {
        final String name;
        final int offset;
        final String boundaryDescription;

        MarkerOccurrence(String name, int offset, String boundaryDescription) {
            this.name = name;
            this.offset = offset;
            this.boundaryDescription = boundaryDescription;
        }
    }

    private static final class MarkerCluster {
        final int start;
        final int end;
        final int medianGap;

        MarkerCluster(int start, int end, int medianGap) {
            this.start = start;
            this.end = end;
            this.medianGap = medianGap;
        }
    }

    private static final class AsciiString {
        final int offset;
        final String text;

        AsciiString(int offset, String text) {
            this.offset = offset;
            this.text = text;
        }
    }

    private static final class WordObservation {
        final int wordOffset;
        final long unsignedValue;
        final String classification;

        WordObservation(int wordOffset, long unsignedValue, String classification) {
            this.wordOffset = wordOffset;
            this.unsignedValue = unsignedValue;
            this.classification = classification;
        }
    }

    private static final class LayoutHypothesis {
        final long count;
        final long recordBytes;
        final int dataOffset;
        final int endOffset;
        final int originOffset;
        final int score;
        final String orderLabel;

        LayoutHypothesis(
                long count,
                long recordBytes,
                int dataOffset,
                int endOffset,
                int originOffset,
                int score,
                String orderLabel
        ) {
            this.count = count;
            this.recordBytes = recordBytes;
            this.dataOffset = dataOffset;
            this.endOffset = endOffset;
            this.originOffset = originOffset;
            this.score = score;
            this.orderLabel = orderLabel;
        }
    }
}
