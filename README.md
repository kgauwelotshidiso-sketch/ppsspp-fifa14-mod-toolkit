# PPSSPP Mod Toolkit — Phase 1F

A native Android toolkit for Android-only PSP game modding, initially focused on FIFA 14 PSP/PPSSPP.

## Phase 1F working features

Phase 1F keeps all Phase 1A–1E features: source scanning, verified backup, controlled workspace, ISO extraction, exact-path asset indexing, verified working copy, staged full-file replacement, rollback, database fingerprinting, exact text search, and same-length edited-copy generation.

It adds a conservative **read-only FIFA binary table decoder** for the selected working `.db` file.

### Read-only table decoder

The decoder starts with verified FIFA tables such as:

```text
players
teams
teamplayerlinks
```

It also accepts the other known table markers already recognized by the Database Lab.

Before decoding, the app:

- resolves the exact selected file only inside `20_working_files/source_working`
- rechecks file size and SHA-256 against the current working asset index
- refuses stale asset records
- keeps the ISO, verified backup, protected original, and working database unchanged

### Candidate header and section map

For compatible EA/FIFA binary databases, Phase 1F:

- reads the third little-endian 32-bit header word as a **candidate header-size value** only when it is aligned and within conservative bounds
- reads the candidate header words without changing the file
- identifies monotonic in-file offsets
- maps those offsets into candidate section ranges
- reports confidence as a structural candidate, not a proven semantic schema

### Table-marker map

The decoder:

- searches known table names case-insensitively
- requires token boundaries to reduce false substring matches
- sorts table markers by exact byte offset
- reports the previous and next known marker
- maps each marker to the candidate section containing it
- reports nearby printable strings and a bounded hexadecimal window

### Aligned numeric observations

Around the requested marker, the app reports:

- aligned little-endian 32-bit words
- zero values
- values matching candidate section starts
- possible in-file pointers
- pointers landing on known markers or printable ASCII
- small integer/count candidates

These are observations only. No field name or record meaning is claimed without proof.

### Record-layout hypotheses

Phase 1F conservatively tests nearby groups of three aligned 32-bit words as possible:

```text
record count
record size in bytes
data offset
```

A hypothesis is retained only when:

- count and record size stay within safety limits
- multiplication does not overflow
- calculated record data remains inside the database
- the candidate data offset is valid

Hypotheses are ranked and explicitly labeled **UNCONFIRMED**. The top candidate may show a few raw unsigned 32-bit sample records for comparison. Numeric editing remains disabled.

### Verified decoder report

Each decoder run creates and reopens a report inside:

```text
90_logs/phase1f_table_decoder_reports/
```

The report contains:

- selected database path and verified SHA-256
- requested table
- candidate header words and sections
- complete known-marker map
- local aligned values and pointer classifications
- record-layout hypotheses
- explicit read-only safety statements

The saved report is SHA-256 verified after writing.

### Existing Database Lab editing

Phase 1E same-length text editing remains available, but structural names such as `players`, `teams`, and `teamplayerlinks` must not be edited. A generated edited database still goes through the Phase 1D staging, verified rollback-copy, apply, and rollback pipeline.

## Important limitation

Phase 1F is a structural reverse-engineering step. Candidate section ranges and layout hypotheses are not yet a proven FIFA PSP table schema. The app does not expose numeric field editing, transfers, ratings, IDs, or row insertion until record boundaries and field descriptors are repeatably verified from the user’s real database.

## Build through GitHub Actions

1. Put these files in the root of the repository.
2. Commit and push to `main` or `master`.
3. Open **Actions** and select **Build Android Debug APK**.
4. Wait for the green tick.
5. Download the artifact named `PPSSPP-Mod-Toolkit-Phase1F-debug`.

Do not install an APK from a failed or cancelled workflow.
