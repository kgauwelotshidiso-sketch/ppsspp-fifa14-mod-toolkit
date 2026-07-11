# PPSSPP Mod Toolkit — Phase 1B

A native Android toolkit for Android-only PSP game modding, initially focused on FIFA 14 PSP/PPSSPP.

## Working features

### Source scanner retained from Phase 1A
- Select an ISO, CSO, ZSO, PBP, or extracted PSP game folder through Android's system picker.
- Persist source access without broad storage permission.
- Confirm FIFA 14 from internal title IDs and PSP structure markers.
- Discover likely modding assets such as `fifa.db`, `.big`, `.bh`, `.rx3`, `.fsh`, `.loc`, and configuration files.
- Inspect patch ZIPs and reject unsafe archive paths.

### Phase 1B verified backup
- Select a writable backup destination using Android's Storage Access Framework.
- Perform a real write test before backup.
- Measure free space where the document provider exposes it.
- Copy the complete selected game image or extracted game folder.
- Calculate SHA-256 while reading each source file.
- Reopen each destination file and calculate SHA-256 again.
- Reject and clean up incomplete backups when size or hash verification fails.
- Create a timestamped backup directory and `verification_manifest.txt`.
- Never modify the selected source.

### Controlled workspace preparation
- Require a verified backup record first.
- Check measurable free space against an extraction/rebuild recommendation.
- Create isolated folders for backup reference, extracted originals, working files, patch imports, rebuilt output, and logs.
- Create `workspace_manifest.txt` with state `PREPARED_NOT_EXTRACTED`.
- Keep replacement and ISO/CSO rebuilding disabled until the extraction engine is added.

## Stable development signing

Phase 1B introduces a repository development keystore so APKs built from this project use a stable debug signature. This key is intentionally for development only and must never be used for a production store release.

Because Phase 1A used GitHub runner-generated debug signing, the first Phase 1B installation may require uninstalling Phase 1A once. Phase 1B and later development APKs can then install as updates as long as this keystore is retained.

## Safety boundary

The game source is always opened read-only. Phase 1B writes only inside the backup and workspace folders explicitly selected by the user. It does not patch, replace, rebuild, or overwrite files inside an ISO/CSO.

## Build through GitHub Actions

1. Replace the project files with the Phase 1B package.
2. Commit and push to `main` or `master`.
3. Open **Actions** and select **Build Android Debug APK**.
4. Wait for tests, lint, and APK compilation to receive a green tick.
5. Download the artifact named `PPSSPP-Mod-Toolkit-Phase1B-debug`.

Do not install an APK from a failed or cancelled workflow.
