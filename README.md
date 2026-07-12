# PPSSPP Mod Toolkit — Phase 1G Hotfix 1

A native Android toolkit for safe, Android-only FIFA 14 PSP/PPSSPP modding.

## Why this hotfix exists

The first Phase 1G parser assumed that the 32-bit value following the two table hashes was a field count and that one descriptor word belonged to every field name. Testing against the uploaded 2.80 MB ULUS-10655 `fifa.db` proved that assumption false.

The real database uses two separate arrays:

- A signed 32-bit descriptor-word array.
- A later aligned field-name array.

Their lengths are not equal.

## Verified ULUS-10655 schema profiles

The hotfix verifies these exact read-only profiles:

| Table | Descriptor words | Field names | First field | Last field | Structural successor |
|---|---:|---:|---|---|---|
| `teams` | 40 | 35 | `teamid` | `defdefenderline` | `refereecountrylinks` |
| `teamplayerlinks` | 7 | 6 | `teamid` | `transferdone` | `jerseynames` |
| `players` | 121 | 96 | `playerid` | `exportfromdb` | `leagueteamlinks` |

A table is accepted only when all of the following validate:

1. The table name has the correct 16-bit little-endian length prefix.
2. The table-name bytes and four-byte alignment padding are exact.
3. Both 32-bit table hashes are readable.
4. The descriptor-word count matches the verified profile.
5. The complete descriptor array is in range.
6. The exact verified number of aligned field names can be read.
7. The first and last field names match the real database profile.
8. The next aligned string is the expected structural successor table.
9. The successor has valid hashes, descriptor count, and first field.

This rejects ordinary display text such as “FIFA Career Saved Teams” and prevents field names from being mistaken for table boundaries.

## Report output

The Phase 1G report now shows descriptor words and field names separately:

- Verified descriptor-word count.
- Verified field-name count.
- Descriptor-array range.
- Exact schema end.
- Verified successor table and byte offset.
- Every raw signed descriptor word.
- Every field name and exact byte offset.

No descriptor is paired with a field until that mapping is independently proven.

Reports remain under:

```text
90_logs/phase1g_schema_decoder_reports/
```

## Safety boundary

This hotfix remains read-only. It does not enable:

- Numeric player-rating editing.
- Transfers or team assignment changes.
- Row insertion or deletion.
- Record-count changes.
- Descriptor-to-field mapping.
- Automatic database rebuilding from decoded rows.

The selected ISO, verified backup, protected extraction, and working database remain unchanged by schema inspection.

## Build through GitHub Actions

1. Overlay the hotfix files in the repository root.
2. Commit and push to `main` or `master`.
3. Open **Actions** and select **Build Android Debug APK**.
4. Wait for the green tick.
5. Download `PPSSPP-Mod-Toolkit-Phase1G-Hotfix1-debug`.

Do not install an APK from a failed or cancelled workflow.

## First retest

Select `fifa.db`, then parse these tables separately:

```text
teams
teamplayerlinks
players
```

Expected verified counts:

```text
teams: descriptor words 40, field names 35
teamplayerlinks: descriptor words 7, field names 6
players: descriptor words 121, field names 96
```
