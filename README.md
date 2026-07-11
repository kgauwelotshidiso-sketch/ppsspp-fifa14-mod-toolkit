# PPSSPP Mod Toolkit — Phase 1A

A native Android foundation for Android-only PSP game modding, initially focused on FIFA 14 PSP/PPSSPP.

## Working features

- Select an ISO, CSO, ZSO, PBP, or extracted PSP game folder through Android's system file picker.
- Persist file/folder access without requesting broad storage permission.
- Detect ISO 9660, CSO, ZSO, DAX, PBP, and ZIP signatures.
- Search readable ISO/folder content for FIFA 14 text and known PSP title IDs.
- Detect PSP structure markers such as `PSP_GAME`, `PARAM.SFO`, and `EBOOT.BIN`.
- List likely modding assets such as `fifa.db`, `.db`, `.big`, `.bh`, `.rx3`, `.fsh`, and configuration files.
- Inspect patch ZIP structure and reject parent-folder traversal paths.
- Restore the user's selections after the app restarts.

## Safety boundary

Phase 1A is intentionally read-only. It does not extract, install, edit, or replace game files. The next Phase 1 sprint adds backup creation, backup verification, and controlled full-file replacement.

## Build through GitHub Actions

1. Put these files in the root of a GitHub repository.
2. Commit and push them to `main` or `master`.
3. Open **Actions** and select **Build Android Debug APK**.
4. Wait for the green tick.
5. Open the completed workflow run and download the artifact named `PPSSPP-Mod-Toolkit-Phase1A-debug`.

Do not install an APK from a failed or cancelled workflow.
