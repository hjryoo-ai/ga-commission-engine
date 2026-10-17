> 🌐 [한국어](../db-migrations.md) · **English** — translation of `commission-system/docs/db-migrations.md` at commit `48cc90f`. The Korean original is canonical; where the two differ, the Korean text prevails.

# DB migration numbering rules

> Documented in E3.2 (2026-10-09). Source of the decision: ga-disclosure `docs/phase-03A-수용심사.md` §4-① (approval of engine E3.1 question 1). Test: `commission-infra` `MigrationNumberingTest` reads the gap table in this document and compares it with the actual files.

## Locations

| Directory | Applies to | Content |
|---|---|---|
| `commission-infra/src/main/resources/db/migration` | H2 (MODE=Oracle, fast tier) and Oracle | Common DDL and seeds |
| `commission-infra/src/main/resources/db/vendor/oracle` | Oracle only (app `spring.flyway.locations`, `OracleTestSupport`) | Elements H2 cannot handle — `IS JSON`, function-based unique indexes, partitioning, triggers |

Flyway merges the versions of both directories into **a single order** and applies them. The directories do not split the number space.

## Rules

1. **Numbers are globally monotonic.** The number of a new migration is **the maximum across both directories + 1**, regardless of directory. Numbers are not counted per directory (no per-directory ranges).
2. **Numbers are not reused.** A number once used is not given to another file — the number of a deleted migration is also left empty (deleting itself falls under rule 4 below).
3. **Do not create gaps.** A new number is only maximum + 1. Existing gaps (table below) are not filled — filling one would make it "an unapplied lower version" on a DB where higher versions are already applied.
4. **Committed migrations are not modified.** Corrections are made with a migration under a new number (same principle as ga-disclosure). If the content of an applied file changes, Flyway `validate` (production startup, design document §12-8) fails with a checksum mismatch.
5. **H2 compatibility is the default.** Files in the common directory must run on both H2 (MODE=Oracle) and Oracle 19c+. Statements that work only on Oracle go in the Oracle-only directory, but their numbers follow rule 1.
6. **Naming**: `V{number}__{lowercase_underscore_description}.sql`. The header comment states the Phase, the basis (review or directive section) and portability.

## Gaps

<!-- numbering:gaps — MigrationNumberingTest reads this table. Row format: | V{from}–V{to} | reason | -->
| Gap | Reason |
|---|---|
| V15–V99 | The old rule (Phase 10–E3.1) counted the Oracle-only directory separately from V100. When 3A acceptance review §4-① made it a single number space, V103 was followed by V104, and this range stays empty permanently (rule 3). |
<!-- /numbering:gaps -->

## History — ordering inversions under the old rule

Under the old rule, common numbers were always lower than Oracle-only numbers (V100–), so common files added after the Oracle-only files existed were "lower versions than higher versions already applied". Since this was a period with no production DB (before go-live), there was no actual harm, and the files are not modified (rule 4).

| Order added | File | Commit that added it | Oracle-only maximum at that time |
|---|---|---|---|
| 1 | V1–V10, V100·V101 | `5fc45d9` (Phase 0–12) | — |
| 2 | V11 | `f2e375b` (Phase 13) | V101 |
| 3 | V102 | `987d0c0` (Phase 14) | — |
| 4 | V12·V103 | `a4c4585` (E3) | V102 |
| 5 | V14, V13 | `ed00f37`·`c0142e0` (E3.1 — V14 was committed first, same PR) | V103 |
| 6 | V104 | E3.2 | V103 — rule 1 applies from this file |
| 7 | V105 (common — outbox)·V106 (Oracle-only — outbox trigger · IS JSON)·V107 (common — payout statement) | E1 | V106 — continues in one number space per rule 1 |

Applying V13 and V14 to a DB where up to V103 was already applied required Flyway `outOfOrder` (E3.1 report §5-6). From V104 on it is not needed.
