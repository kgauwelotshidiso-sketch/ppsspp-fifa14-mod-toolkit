# PPSSPP Mod Toolkit — Phase 1D

A native Android toolkit for Android-only PSP game modding, initially focused on FIFA 14 PSP/PPSSPP.

## Phase 1D working features

Phase 1D keeps all Phase 1A–1C source scanning, ZIP inspection, verified backup, workspace, ISO extraction, exact-path indexing, and verified working-copy features. It adds the first controlled full-file replacement workflow.

### Searchable working asset browser

- Reads `90_logs/working_asset_index.csv` from the verified working copy.
- Searches by ordinary path text or structured filters:
  - `ext:db`
  - `name:fifa`
  - `path:data`
  - `type:database`
  - `min:1MB`
  - `max:20MB`
- Displays exact internal path, category, size, and SHA-256.
- Prioritizes `fifa.db`, other databases, `.bh`, `.big`, graphics/model assets, and localization files.

### Verified replacement staging

- Requires the replacement filename to exactly match the selected working asset filename.
- Re-hashes the selected working target and rejects a stale asset-index selection.
- Hashes the replacement before copying it.
- Measures workspace free space and reserves room for staging, rollback, and a safety margin.
- Copies the replacement into:

```text
30_patch_import/phase1d_staging/<transaction_id>/replacement/
```

- Reopens the staged file and requires a size/SHA-256 match.
- Does not change the working file during validation.

### Controlled full-file replacement

- Revalidates the staged replacement and current working target.
- Creates and verifies a complete pre-replacement rollback copy at:

```text
30_patch_import/phase1d_staging/<transaction_id>/rollback_original/<exact_internal_path>
```

- Replaces only the selected exact path inside:

```text
20_working_files/source_working
```

- Reopens and hashes the applied file.
- Updates:
  - `90_logs/working_manifest.csv`
  - `90_logs/working_hashes.tsv`
  - `90_logs/working_asset_index.csv`
  - `90_logs/replacement_history.csv`
  - `90_logs/latest_replacement.txt`
- Records a transaction manifest beside the staged and rollback files.
- Attempts an immediate verified restore if an apply operation fails after the target is touched.

### LIFO rollback

- Rolls back the most recently applied replacement first.
- Verifies that the current target still matches the applied transaction before restoring anything.
- Revalidates the rollback original, restores it, reopens it, and checks SHA-256.
- Restores all working manifests to the pre-replacement size/hash.
- Keeps a stack of applied transactions so repeated replacements can be rolled back in reverse order.

## Safety boundary

Phase 1D never writes to the selected ISO/CSO/PBP, verified backup, or `10_extracted_original/source_original`. It modifies only the exact selected file inside the verified working copy. Staging and rollback files remain inside the app-created workspace.

Phase 1D does not rebuild an ISO yet. ISO rebuild and output verification are reserved for the next phase.

## Build through GitHub Actions

1. Put these files in the root of the repository.
2. Commit and push to `main` or `master`.
3. Open **Actions** and select **Build Android Debug APK**.
4. Wait for the green tick.
5. Download the artifact named `PPSSPP-Mod-Toolkit-Phase1D-debug`.

Do not install an APK from a failed or cancelled workflow.
