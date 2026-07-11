# PPSSPP Mod Toolkit — Phase 1C

A native Android toolkit for Android-only PSP game modding, initially focused on FIFA 14 PSP/PPSSPP.

## Phase 1C working features

- Keeps the Phase 1A source scanner and patch ZIP safety inspection.
- Keeps Phase 1B full-source backup, SHA-256 verification, storage checks, and controlled workspace creation.
- Reads uncompressed ISO-9660 and Joliet filesystems directly from the selected Android document URI.
- Extracts the complete ISO filesystem into `10_extracted_original/source_original` without changing the ISO.
- Supports an already-extracted source folder as an alternative source.
- Preserves exact internal paths and refuses unsafe, duplicate, malformed, out-of-range, or multi-extent records.
- Hashes every source file while writing it, reopens the extracted file, hashes it again, and requires a size/SHA-256 match.
- Creates:
  - `90_logs/extraction_manifest.csv`
  - `90_logs/extraction_hashes.tsv`
  - `90_logs/asset_index.csv`
  - `90_logs/extraction_summary.txt`
- Indexes exact paths for likely modding assets such as `fifa.db`, `.big`, `.bh`, `.rx3`, `.fsh`, `.loc`, and configuration files.
- Creates a complete second copy at `20_working_files/source_working`.
- Revalidates the protected original against its saved hash manifest while making the working copy.
- Reopens and hashes every working file before marking the working copy ready.
- Keeps full-file replacement disabled until Phase 1D.

## Safety boundary

Phase 1C only opens the selected source and verified backup read-only. It writes inside the backup/workspace folders selected by the user. Failed extraction or working-copy operations request cleanup of only the app-created incomplete folders and logs.

Compressed CSO/ZSO/DAX and PBP extraction are deliberately rejected in Phase 1C. Source scanning and backup still recognize them, but controlled extraction currently requires an uncompressed ISO or an extracted game folder.

## Build through GitHub Actions

1. Put these files in the root of the repository.
2. Commit and push to `main` or `master`.
3. Open **Actions** and select **Build Android Debug APK**.
4. Wait for the green tick.
5. Download the artifact named `PPSSPP-Mod-Toolkit-Phase1C-debug`.

Do not install an APK from a failed or cancelled workflow.
