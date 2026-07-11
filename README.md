# PPSSPP Mod Toolkit — Phase 1E

A native Android toolkit for Android-only PSP game modding, initially focused on FIFA 14 PSP/PPSSPP.

## Phase 1E working features

Phase 1E keeps all Phase 1A–1D source scanning, verified backup, controlled workspace, ISO extraction, exact-path indexing, verified working copy, staged full-file replacement, and rollback features. It adds a conservative FIFA database lab for the selected `.db` working asset.

### Verified database inspection

- Requires a selected `.db` asset such as:

```text
PSP_GAME/USRDIR/data/cmn/fifa.db
```

- Resolves the exact file only inside:

```text
20_working_files/source_working
```

- Rechecks the file size and SHA-256 against the current working asset index before every operation.
- Detects SQLite 3 headers, known FIFA table-name markers, mostly-text files, and unknown binary formats without guessing a decoded schema.
- Reports:
  - exact path
  - file size
  - SHA-256
  - format fingerprint
  - printable-string count
  - known table-name markers
  - first little-endian and big-endian 32-bit values
  - first 64 header bytes in hexadecimal

### Exact database text search

- Searches exact case-sensitive UTF-8 byte sequences.
- Reports total occurrences.
- Reports up to 50 byte offsets in decimal and hexadecimal.
- Shows printable context around each occurrence.
- Never changes the database during inspection or search.

### Same-length database edit builder

- Accepts:
  - exact find text
  - replacement text
  - occurrence number
- Requires find and replacement text to use exactly the same number of UTF-8 bytes.
- Rejects empty text, identical replacements, line breaks, NUL bytes, missing matches, and invalid occurrence numbers.
- Replaces only the selected occurrence in memory.
- Creates a complete edited database copy at:

```text
30_patch_import/phase1e_database_edits/<edit_id>/<exact_database_filename>
```

- Reopens and SHA-256 verifies the generated database copy.
- Writes `database_edit_manifest.txt` beside it with the target path, byte offset, occurrence number, old hash, new hash, and Base64URL-encoded text values.
- Leaves the working database unchanged while the edited copy is being built.

### Automatic Phase 1D staging

After the edited database copy is verified, Phase 1E automatically sends it through the existing Phase 1D replacement validator:

- exact filename check
- stale-target size/hash check
- workspace-space check
- staged-copy SHA-256 verification
- rollback-copy requirement before apply

The user must still review the operation report and tap **Apply verified full-file replacement** before the working database changes.

### Existing verified rollback

- Apply changes only the selected exact file inside `20_working_files/source_working`.
- The ISO, verified backup, and protected original remain untouched.
- **Roll back latest applied replacement** restores the verified pre-replacement file and manifests.

## Important limitation

Phase 1E does not claim that every FIFA binary database table or field has been decoded. It exposes verified fingerprints, known table-name markers, exact text offsets, and a controlled same-length text editor. Arbitrary integer/record editing remains disabled until the real PSP database layout is proven from the user’s file and repeatable tests.

## Build through GitHub Actions

1. Put these files in the root of the repository.
2. Commit and push to `main` or `master`.
3. Open **Actions** and select **Build Android Debug APK**.
4. Wait for the green tick.
5. Download the artifact named `PPSSPP-Mod-Toolkit-Phase1E-debug`.

Do not install an APK from a failed or cancelled workflow.
