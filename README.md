# PPSSPP Mod Toolkit — Phase 1H

A native Android toolkit for safe, Android-only FIFA 14 PSP/PPSSPP modding.

## Working Phase 1H feature

Phase 1H turns the verified schema decoder into the first practical, read-only roster and team-data browser for the uploaded USA `ULUS-10655` FIFA 14 database profile.

It validates three real fixed-row sections before showing any record:

| Table | Verified rows in the current database | Record bytes | Proven fields |
|---|---:|---:|---|
| `teams` | 631 | 44 | `teamid`, absolute `teamname` pointer |
| `teamplayerlinks` | 14,669 | 12 | `teamid`, `playerid` |
| `players` | 27,708 | 64 | `playerid`, absolute `firstname` and `surname` pointers |

The row counts are read from the database, not blindly assumed. A table is accepted only when its header offsets, row count, fixed record width, calculated end boundary, verified schema block, unique IDs, string pointers, and cross-table references all validate.

## Row queries

Select `fifa.db`, enter one of these table names, then use the row browser:

```text
teams
players
teamplayerlinks
```

Supported query forms:

```text
index:0          zero-based row index
id:241           exact team/player ID
241              exact team/player ID; link tables treat a plain number as a row index
Arsenal          case-insensitive team-name search
Ryan Giggs       case-insensitive player-name search
team:1           first link for team ID 1
player:8473      first link for player ID 8473
```

Leaving the query empty opens row index 0. Previous and Next browse verified adjacent rows.

## Cross-linking

For a selected team, the report shows its validated linked players. For a selected player, it shows the validated linked team or teams. For a selected `teamplayerlinks` row, both IDs are resolved to names.

Every report also includes:

- Exact table header, row start, row end, row count, and record width.
- Selected row byte offset.
- Raw unsigned little-endian words and hexadecimal values.
- Descriptor-pattern statistics from the verified schema.
- Foreign-key and duplicate-pair validation totals.
- A SHA-256-verified text report saved under:

```text
90_logs/phase1h_row_browser_reports/
```

## Safety boundary

Phase 1H remains read-only. It does not claim a descriptor-to-field mapping for unresolved values and does not enable:

- Player-rating editing.
- Jersey number or position editing.
- Transfers or team assignment changes.
- Row insertion or deletion.
- Record-count changes.
- Packed-bitfield editing.

The selected ISO, verified backup, protected extraction, and working database are never modified by row browsing. Existing staged full-file replacement and rollback remain separate Phase 1D operations.

## Build through GitHub Actions

1. Put the complete Phase 1H replacement in the repository root.
2. Commit and push to `main` or `master`.
3. Open **Actions** and select **Build Android Debug APK**.
4. Wait for the green tick.
5. Download `PPSSPP-Mod-Toolkit-Phase1H-debug`.

Do not install an APK from a failed or cancelled workflow.

## First test

1. Search `ext:db` and select `PSP_GAME/USRDIR/data/cmn/fifa.db`.
2. Enter table `teams` and row query `Arsenal`.
3. Tap **Open verified row browser — read only**.
4. Confirm the report resolves `teamid=1`, the Arsenal name, and linked players.
5. Repeat with table `players` and query `Ryan Giggs`.
6. Repeat with table `teamplayerlinks` and query `player:8473`.
