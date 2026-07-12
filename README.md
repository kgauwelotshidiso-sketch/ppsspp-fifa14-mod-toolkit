# PPSSPP Mod Toolkit — Phase 1G

A native Android toolkit for safe, Android-only FIFA 14 PSP/PPSSPP modding.

## Phase 1G working features

Phase 1G retains the complete Phase 1A–1F pipeline:

- Select and verify a PSP ISO or extracted game folder.
- Create a full SHA-256-verified backup.
- Prepare a controlled workspace.
- Extract and verify the protected original.
- Create a second verified working copy.
- Search the exact-path working asset index.
- Stage, validate, apply, and roll back full-file replacements.
- Inspect `fifa.db`, search exact text offsets, and build same-length edited copies.
- Decode conservative section and marker evidence read-only.

Phase 1G adds a verified table-schema parser based on the real ULUS-10655 reports for:

- `players`
- `teams`
- `teamplayerlinks`

## Verified schema structure

A table occurrence is accepted as a structural schema block only when every boundary validates:

1. The table name has a NUL byte immediately before it.
2. The table name is NUL terminated.
3. Any alignment bytes before the schema header are zero.
4. Two 32-bit table hashes are readable.
5. The 32-bit field count is within a conservative bound.
6. Exactly one signed 32-bit descriptor exists for every field.
7. Exactly the declared number of field names can be read.
8. Every field name is 16-bit length-prefixed, ASCII-safe, and padded with zeroes to the next four-byte boundary.

This rejects ordinary user-facing strings such as “FIFA Career Saved Teams” instead of confusing them with the structural `teams` table.

For a verified schema, the report includes:

- Structural marker offset.
- Aligned schema-header offset.
- Both table hashes.
- Exact field count.
- Descriptor-array range.
- Exact schema-block end.
- Every field name, descriptor value, hexadecimal descriptor, and name offset.
- Zero/positive/negative descriptor distribution.
- Existing candidate sections, nearby words, strings, and record-layout hypotheses.

Reports are written and SHA-256 verified under:

```text
90_logs/phase1g_schema_decoder_reports/
```

## Confirmed from the supplied FIFA 14 USA database reports

The user-supplied Phase 1F reports establish a repeatable schema pattern:

- `teams` declares **40** fields.
- `teamplayerlinks` declares **7** fields.
- `players` declares **121** fields.

The schema parser verifies these values from the database itself rather than hard-coding them. It also pairs each field name with the corresponding raw signed descriptor.

## Safety boundary

Phase 1G does not interpret descriptor semantics yet. It does not claim that a descriptor is a rating offset, bit width, type code, row position, or foreign key until that meaning is proven against the table data sections.

The following remain disabled:

- Numeric player-rating editing.
- Transfers and team assignment changes.
- Row insertion or deletion.
- Record-count changes.
- Automatic database rebuilding from decoded rows.

The selected ISO, verified backup, and protected original remain read-only. Existing full-file apply and rollback operations still target only `20_working_files/source_working`.

## Build through GitHub Actions

1. Put the replacement files in the repository root.
2. Commit and push to `main` or `master`.
3. Open **Actions** and select **Build Android Debug APK**.
4. Wait for the green tick.
5. Download the artifact named `PPSSPP-Mod-Toolkit-Phase1G-debug`.

Do not install an APK from a failed or cancelled workflow.

## First Phase 1G test

1. Search `ext:db` in Section 5.
2. Select `PSP_GAME/USRDIR/data/cmn/fifa.db`.
3. Enter `teams` in the Section 6 table field.
4. Tap **Parse verified table schema — read only**.
5. Confirm the report says `Verified schema block: yes` and `Verified field count: 40`.
6. Repeat with `teamplayerlinks` and `players`.

The complete reports will contain all field names even when the on-screen report truncates the displayed candidate list.
