> 🌐 [한국어](README.md) · **English** — translation of `commission-system/README.md` at commit `48cc90f`. The Korean original is canonical; where the two differ, the Korean text prevails.
>
> English documents: [design document](docs/en/design.md) · [design notes](docs/en/design-notes.md) · [build & run (commission-system)](README.en.md) · [contributing](../CONTRIBUTING.en.md) · [DB migrations](docs/en/db-migrations.md) · [third-party](docs/en/third-party.md)

# GA Agent Commission Calculation System

Implementation of [`docs/설계서.md`](docs/en/design.md) (v1.2.1, the single source). Rules are data, not code (effective-dated versions); calculation results are an immutable ledger (corrected only through reversal entries); the 1200% limit is a gate in the payout pipeline. **Phase 0–16 complete — from the rule repository to a runnable Spring Boot app.**

## Build/Test

```bash
./gradlew build          # build all modules + tests + Oracle integration tests (Docker required)
./gradlew :commission-limit:test                    # single module
./gradlew :commission-infra:integrationTest         # Oracle (Testcontainers) only
./gradlew build -x :commission-infra:integrationTest  # workaround for environments without Docker
```

- Domain and engine modules are pure Java with no Spring/DB dependency — unit tests run within seconds.
- commission-infra `test` validates the schema on H2 (MODE=Oracle); `integrationTest` uses
  **Testcontainers (gvenzl/oracle-free:23-slim-faststart)** to verify contract tests, locks and
  concurrency contention on real Oracle (§8.6–8.7). Same command locally and in CI.
- Flyway locations: common `db/migration` + Oracle-only `db/vendor/oracle` (partitioning, IS JSON,
  ACTIVE function-based unique index).

## Modules (dependency direction: app → infra/api/batch → engines → rule → domain)

| Module | Content |
|---|---|
| commission-domain | Money (BigDecimal, scale 0)/Rate/RoundingPolicy, value objects, PolicyEvent |
| commission-rule | effective-dated RuleRepository (reference-date-required convention), rate approval workflow (DRAFT→ACTIVE→SUPERSEDED), incentive master (INCENTIVE_MST) · SpEL condition-expression engine (SimpleEvaluationContext sandbox) · incentive approval workflow (diff audit isomorphic to rates) |
| commission-calc | Calculation pipeline (Step chain; the Step list is also effective-dated configuration), COMM_CALC immutable ledger port, Reversal&Rebook, replay |
| commission-limit | 1200% limit ledger (LIMIT_LEDGER+DTL), LimitGateStep, recalculation on reduction, reversal-entry restore hook |
| commission-clawback | Clawback Step (clawback rate by elapsed-installment band), re-payout on reinstatement, conversion to receivables · automatic offset |
| commission-deferral | Deferral curve (data) split Step (2027–), RELEASE batch for due items, extinguishment on surrender |
| commission-settlement | Monthly close checklist gate, 3.3% withholding tax, ADJUSTMENT (attributed to the following month), close-month immutability guard |
| commission-inbound | Insurer statement adapter (pilot: SAMLIFE CSV), idempotent save |
| commission-recon | Reconciliation (statement vs. own calculation) — difference typing (MATCH/rate difference/missing/unrecognized) |
| commission-shadow | Shadow run (transition verification) — imports external settlement CSV, compares against own calculation (NetAmountCalculator net amount) at the won level per recipient × type × close month, difference typing (rounding/rate difference/missing/unrecognized/timing). Real Oracle path |
| commission-api | Commission simulation (expected commission, limit consumption, immediate deferral portion), query facade, REST controller slice (query/simulation/approval — net amounts via NetAmountCalculator, amounts serialized as long and dates as ISO, fail-fast exceptions mapped to 4xx, real approver required for approval), sales commission comparative disclosure in two layers (extraction `DisclosureService` · form mapping `DisclosureFormat` separated, no hardcoded forms) + large-GA comparative-explanation rank and grade (`RankingService`). Full boot assembly deferred to the 'production assembly' Phase |
| commission-infra | Flyway schema (V1–V11 common + Oracle-only V100/V101/V102), MyBatis Oracle adapter (9 transactional Stores + rule/incentive query/approval/agent directory + close report), limit ledger SELECT FOR UPDATE serialization + posting_seq numbering under the lock, rate approval concurrency runner (index violation → retry/reject, §6.6) + incentive ACTIVE unique index `ux_incentive_active` (V102, violation → explicit translation to 409, runner deferred), trimming audit history (COMM_RATE · INCENTIVE_CHANGE_HIST), close completeness report (rate and incentive overlaps), OraclePersistence (transaction boundary §4.2 [3.5]–[5.5]) |
| commission-batch | Spring Batch 5 job wiring — 4 jobs: monthly close/payout run/deferral release/recalculation. JobInstance-based request dedup, per-item transactions (Resourceless step TM + a business transaction per iteration so that calculation and payout jobs do not hold the limit lock across the whole chunk), failure isolation (RuntimeException = isolate · Error = abort all), BATCH_* meta tables via Flyway (V101) |
| commission-app | Runnable `@SpringBootApplication` (Phase 16) — explicit wiring of all store, service, batch and REST beans (code without stereotypes as @Bean), Spring Security roles (ADMIN/OPERATOR/VIEWER) · principal as the real approver, externalized configuration (zero secrets · Oracle profile · Flyway validate-only), no automatic batch execution + protected endpoints, Actuator health/readiness, startup smoke E2E (real Oracle) |
| commission-localstack | (E2 ⑤, outside the production jar) Local stack scenario runner — runs the second pass of A-1 (calculation → close job → payout run job → surrender → provisional statement job) on real Oracle with the app assembly as is + a step clock · sequential eventIds. Feed comparison `verifyFeed`. See "Local run" below |

## Running (production assembly, Phase 16)

```bash
# run: all configuration and secrets are injected externally (not in the repository). TZ/encoding set explicitly for determinism.
DB_URL=jdbc:oracle:thin:@//host:1521/svc DB_USERNAME=... DB_PASSWORD=... \
  java -Dspring.profiles.active=prod -Duser.timezone=Asia/Seoul -Dfile.encoding=UTF-8 \
       -jar commission-app/build/libs/commission-app.jar
```
- **Zero secrets**: DB connection, credentials and authenticated users (`app.security.users`) come only from environment variables/external configuration. If no users are configured, nobody can authenticate (**no deployment without authentication**). Corporate SSO integration is an operations transition item.
- **Schema**: production (prod) runs Flyway **validate only** at startup — migrate is a controlled deployment procedure (`flywayMigrate`).
- **Batch**: jobs do not run automatically on web startup. They start only via `POST /api/batch/{job}/run` (OPERATOR/ADMIN, explicit parameters). Jobs: `monthCloseJob`/`payoutRunJob`/`deferralReleaseJob`/`recalcJob`.
- **Approval**: `POST /api/rates/{id}/approve` (ADMIN) — the real approver is the authenticated principal, not the request body.
- What remains outside the system (production seed, additional INBOUND adapters, shadow parallel run, SSO, etc.) is in the design document §12 operations transition checklist.

## Local run (E2 ⑤ — one command, engine + A-1 feed)

```bash
localstack/up.sh          # new Oracle Free container (digest pinned) → Flyway · seed · A-1 scenario → engine app :8080 (about 40 s when the image is present)
localstack/up.sh verify   # GET /internal/v1/events?afterSeq=0 … to the end = commission-outbox/src/test/resources/outbox-golden/a1_portal_150000_e2.jsonl (bytes)
localstack/up.sh down     # stop the app + remove container and volumes
```
- Requirements: Docker (compose v2), JDK 21, `openssl`, `curl`. Change ports with `ORACLE_PORT` (default 1521) · `APP_PORT` (default 8080).
- **Secrets**: `up.sh` generates the service token and DB password randomly each time and writes them only to `localstack/.state/` (mode 700, excluded from git) — the token file is `localstack/.state/engine-token` (mode 600). Only the token's SHA-256 is passed to the app as `ENGINE_SERVICE_TOKEN_SHA256`, and the script never prints the token or password. The portal's `e2e/engine-feed/` reads this token file.
- Tenant `GA01`, default profile (not prod — Flyway migrate, placeholder retention determination allowed). The image reference lives in one place, `oracle-free-image` in `gradle/libs.versions.toml` (the same value as the two Testcontainers ITs).
- Seed `localstack/seed/a1.sql` = test fixtures `RuleFixtures.standardRules()` + `CalcTestHarness.standardDirectory()` (fictional data). The match is proven by `LocalStackSeedIT` (query grid) and the CI job `로컬 스택(A-1 피드)` (feed bytes).
- Since the engine has no scheduler for the provisional statement, in operations an external cron calls `POST /api/batch/statementEstimateJob/run` (parameter `request_id`, `close_ym` optional) once nightly (design document §6.8).

## Core conventions (common to all modules)

1. **No double/float.** Use only `Money` for amount arithmetic and only the `RoundingPolicy` enum for rounding.
2. **Rule lookup requires a reference date.** There is no "currently effective" lookup API. Installment-type rules = business event date; policy-type rules (limit/deferral/clawback) = policy conclusion date.
3. **COMM_CALC is insert + status transition only.** Corrections are a negative reversal (referencing the original) + rebook. The REVERSED original stays in the sum and the reversal cancels it.
   - **Summation convention (§3.0)**: net amount = **sum over all statuses**, no status-filtered SUM, only via `NetAmountCalculator`. (A status-filtered SUM misses the original and reversal on correction before close and causes double deduction — locked in with a component regression test.)
4. **No new attribution to a closed (CLOSED) month** — automatically attributed to the next OPEN month. Corrections after close are ADJUSTMENT.
5. **New regulation = new Step** (version in the stepId) + effective-period registration. Existing Steps are not modified.
6. **Limit lock convention (§6.1.6)**: acquire the ledger `SELECT FOR UPDATE` before the gate decision and hold it through the post-save hook posting and commit. posting_seq is numbered only while holding the lock; ordering is by posting_seq only (not posted_at).
7. **Fail-fast (Appendix B-13)**: when required rule data (over_limit_action, etc.) is missing, reject the calculation instead of using a silent default. Do not embed policy defaults in code.

## Tests (356 = unit/H2 · app smoke 254 + Oracle integration 102)

- Golden cases: new business/installment/incentive, 2026-06-30↔07-01 limit boundary, limit near/over · clawback restore, surrender, withdrawal and reinstatement before/after installment 13, retroactive rate change reversal&rebook, close→payout cycle, 4-year→7-year deferral data replacement.
- Property (seed-fixed JUnit parameterized `SeededCases` — jqwik removed · `net.jqwik` blocked by the build): Money arithmetic, limit invariants (accum ≤ limit, accum = ΣDTL), deferral schedule sum = deferred principal, comparative-explanation rank ↔ grade monotonicity (500 sets).
- replay: reconstruct the ledger at calculation time and recompute the same amount; detect retroactive changes.
- **Contract tests (§8.7)**: the abstract suite of each Store port (module testFixtures `contract/`) is inherited and passed identically by the in-memory reference and the Oracle adapter — immutable ledger, idempotency keys, status transitions, posting order (posting_seq), empty string = NULL semantics.
- **Concurrency contention (§8.6, Oracle only)**: two concurrent calculations on the same ledger — the trailing transaction sees the updated accumulation and is reduced (accum ≤ limit holds under multiple threads); concurrent creation of a new ledger retries on uq_limit; full rollback on post-save hook failure (atomicity); FOR UPDATE waits until commit.
- **Rule master contract (Phase 10b)**: effective-period boundaries locked in (on the rate's apply_from day = new version; on the day of a grade/affiliation change = new version), overlapping ACTIVE → Ambiguous fail-fast, clawback table per-product → common fallback, absence of APIs violating "reference date required" (reflection check), trimming audit history (before/after values, approver, timestamp).
- **Approval concurrency (§6.6, Oracle only)**: two concurrent approvals for the same key — even if both pass the app-layer check, ux_comm_rate_active is the final arbiter; the loser retries (re-fetch latest, re-validate) and converges to SUPERSEDE; zero overlapping ACTIVE. Two kinds: deterministic interleaving + barrier contention.
- **Batch jobs (Phase 11)**: H2 flows (commission-batch) verify dedup, gate, forced close, isolation and restart semantics; real Oracle ITs (commission-infra) verify the completion criteria.
  - **Value idempotency on abort and restart (real Oracle)**: kill with `Error` at the 3rd of 4 recalculations → job FAILED; the aborted item rolls back [3.5]–[5.5] entirely (no partial reversal). Resubmitting the same request_id = restart → resumes from the progress offset; net amounts identical across all policies, exactly one generation (12 records per recipient), ledger invariants hold; resubmission after completion is a no-op.
  - **Two repeated payout runs (offset · withholding tax)**: run 1 injects a settlement failure for A → rollback to the point after offset restores the receivable balance; only the organization is settled. Run 2 recovers A (full net amount offset against the receivable → payout 0). The next month's run offsets the remaining receivable; withholding tax is computed per run on the net payout after offset (3.3% truncated, totals verified via the step context).
  - **Close gate · forced close · 3 reports (real Oracle)**: job FAILED if PENDING exists (month stays OPEN); for a forced close the reason persists in BATCH_JOB_EXECUTION_CONTEXT. Reports = rule completeness (overlapping ACTIVE · missing masters) / MAXVALUE partition loading / approval contention (SUPERSEDE right after ACTIVATE) detected for real (non-blocking).

- **REST controller slice (Phase 12)**: MockMvc standalone verifies thin controllers over the facade. **The net-amount API is correct in the reversal scenario (three pre-close corrections coexisting)** (via NetAmountCalculator → the status-filter double deduction of −135,000 is not reproduced), amounts serialized as long · close month as yyyyMM · dates as ISO, fail-fast exceptions mapped to 4xx (no internal details exposed), the approval API requires a real approver · rejects "system". The absence of status-filtered SUM is pinned by a source-scanning architecture test (§3.0).

- **Incentive condition-expression engine (Phase 13)**: SpEL condition expressions are evaluated only in a **SimpleEvaluationContext (data binding only) sandbox** — rejection of `T()`, beans, constructors, method calls and assignment is pinned by 20 forbidden-syntax tests (admin input does not become a path to code execution). New incentives take effect by registering a condition expression alone (no redeploy), reusing the rate approval pattern (trimming/SUPERSEDE · diff audit · overlap invariant, in-memory + Oracle contract), included in the limit in the order IncentiveV2(30)<LimitGate(50), the incentive versions used are recorded in rule_versions · decisions recorded in calc_trace (replay), fail-fast on registration and evaluation.

- **Golden-set CSV externalization (Phase 14)**: the §8.1 representative-axis golden cases are externalized as **self-contained CSV bundles** (`commission-settlement/src/test/resources/golden/`, authoring conventions in `golden/README.md`). One case = one folder (rule seed + timeline + expectations), no dependency on shared seeds (regulatory change = add a new case). `GoldenCsvRunnerTest` scans the CSVs and compares **through the full pipeline** (no bypassing Steps), prints mismatches as diffs readable by settlement staff, **joins CI by adding a CSV alone (zero code)** · failure = build failure. 24 cases (new business · installment · incentive · limit · reduction · clawback · deferral · retroactive correction) migrated; the original JUnit golden values removed (behavior · property · contract · concurrency tests retained).
- **Final arbiter for incentive approval concurrency (prerequisite to Phase 14)**: `ux_incentive_active` (V102 Oracle) — isomorphic to rates; incentive filters are nullable so they are wrapped in `NVL('*')`. Index violation → explicit translation to `IncentiveApprovalConflictException` (→409); the close completeness report extended to detect incentive overlaps (retry runner · interleaving IT deferred).

- **Regulatory add-ons and transition (Phase 15)**: ① **Ambiguous fail-fast in rule resolution** (prerequisite consistency plug) — in single-result resolution at the reference date, the calculation is rejected if 2 or more applicable ACTIVE entries exist for the same key (rate)/`incentive_cd` (incentive) (in-memory + real Oracle). It also closes overlaps with different start dates that the index cannot block, cutting off overpayment at the source (extension of Appendix B-13). ② **Comparative disclosure** in two layers (all extraction via NetAmountCalculator + separated form mapping, demonstrated by swapping 2 dummy forms, reversal NET without double deduction) + comparative-explanation rank and grade. ③ **Shadow run** — deliberately injected differences are classified exactly by type (MATCH/rounding/rate difference/missing/unrecognized/timing), TIMING 2-pass, real Oracle path.

- **Production assembly (Phase 16, final)**: **startup smoke E2E** (real Oracle) — after a full-context boot, migration → rule seed → calculation → REST net amount (1,890,000) → close job COMPLETED detects wiring defects (missing beans · duplicates · missing scans · TM mix-ups) that unit/contract tests do not reveal. **Verification that jobs do not run automatically** (0 BATCH_JOB_EXECUTION rows after startup), **role separation** (VIEWER → approval API 403), **principal approval** (approvedBy removed), incentive approval **interleaving IT** (contention convergence). Externalized configuration · zero secrets · determinism (TZ/encoding) are verified by the run configuration.

For the rationale of design decisions and remaining tasks see `../설계고찰.md` (Phase 11 completion record in §8, Phase 13 in §10, Phase 14 in §11, Phase 15 in §12, Phase 16 in §13).
