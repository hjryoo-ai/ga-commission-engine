> 🌐 [한국어](CONTRIBUTING.md) · **English** — translation of `CONTRIBUTING.md` at commit `48cc90f`. The Korean original is canonical; where the two differ, the Korean text prevails.

# Contributing guide (repository collaboration conventions)

These conventions settle into the repository the principles kept so far through conversation. These conventions come before the code.

## Single repository (no splitting)

The density of this project comes from three things — **① the canonical document (`commission-system/docs/설계서.md`)
moves in the same commit as the code, ② contract tests (testFixtures `contract/`) verify the in-memory
reference and the Oracle adapter at the same time across modules, ③ changes are committed in atomic units.** Splitting
repositories per module or per document breaks all three (the 12 Gradle modules are already one build, and sharing the
contract suites would require publishing artifacts). Documents, golden-set CSVs and even `gradle/verification-metadata.xml` all live in one repository. When
separation of permissions is needed (e.g. settlement staff editing golden-set CSVs), solve it with **path-level review permissions (CODEOWNERS)**, not with repositories.

## Branches — trunk-based

- **`main` protected**: no direct pushes, PR required, CI green required.
- Work happens on short-lived branches (`feature/inbound-samsung`, `fix/…`). Create a PR even when working alone —
  diff review naturally becomes PR review, and the Phase report becomes the PR description.
- Commit in small units of work; **merge with a merge commit** (no squash, v1.2.2 — Phase reports cite commit hashes as evidence, so the hashes must stay alive on main). Keep "PR = one logical change" as the PR scope. Branches are `work/phase-<id>` (so the name does not clash with the tag `phase-<id>`).
- **Merge PRs only after the acceptance review reply.** Tags may be placed first (same convention as ga-disclosure, E3.2 directive item 4).
  One exception: when a directive explicitly names a **notation change** (no change in meaning) to a shared contract, that contract PR may be merged after its own CI passes — because the other repository's
  `UPSTREAM` must point to that merge commit. All other PRs are merged after review (E3.2 acceptance review §2).

## Versions and tags (two tracks)

- **`phase-N` tags**: construction history. Frozen as they are (phase-12 ~ phase-16-final).
- **Release tags (semver)**: history of deployment artifacts. `v0.x.y` until go-live; **passing 1–2 shadow-run close
  cycles (the go-live gate) is `v1.0.0`**. Release notes state "based on design document vX.Y" to link them.
- **Document version (`docs/설계서.md` v1.2.x)**: document change history. Separate from release versions.

## CI (two tiers, `.github/workflows/ci.yml`)

- **Every push/PR**: fast tier — unit/H2 + architecture/golden set (no Docker needed).
- **PR + merge to main**: full build — Oracle Testcontainers IT + startup smoke (Docker).
- **Dependency verification is always active in CI too** (enforced by the wrapper). For PRs that add dependencies or change metadata,
  review of the regenerated diff (zero unintended artifacts) is a **PR checklist item**.

## Document-code synchronization (merge gate)

Every PR merged into main answers for itself — **"Should this change be reflected in the design document? If not, why?"**
(a required item in the PR template). When a design decision changes, update the canonical source in the same PR and record the rationale in `설계고찰.md`.

## DB migrations

Migration numbers are **globally monotonic in a single number space** combining common (`db/migration`) and Oracle-only (`db/vendor/oracle`) — no per-directory
restarts or ranges, a new number is the overall maximum + 1, no number reuse. **Committed migrations are not modified** (corrections use a new number). Gaps in numbering and the history of
ordering inversions under the old rule are in `commission-system/docs/db-migrations.md`; the test is `MigrationNumberingTest` (ga-disclosure 3A acceptance review §4-①, E3.2).

## Rule-file corrections

**Factual corrections** to this file, `CLAUDE.md` and design document Appendix B (typos that do not change meaning, such as paths, names, versions and citations) are fixed directly and noted in the PR description and report.
**Meaning changes** (adding, removing, widening or narrowing a rule) are reported with the reason first and fixed after approval (same convention as ga-disclosure, E3.2 directive item 4).

## Appendix B core conventions (all modules)

No double/float · net amounts via `NetAmountCalculator` (§3.0) · rule lookup requires a reference date · new regulation is a new Step ·
fail-fast (no silent defaults) · zero secrets (external configuration injection). Details in `commission-system/docs/설계서.md` Appendix B.
