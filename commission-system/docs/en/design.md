> 🌐 [한국어](../설계서.md) · **English** — translation of `commission-system/docs/설계서.md` at commit `48cc90f`. The Korean original is canonical; where the two differ, the Korean text prevails.

# GA Agent Commission Calculation System Design Document — v1.2.6

> Purpose: Design of the core system that calculates, pays out, claws back and settles agent commissions at a GA (general agency).
> This document is the high-level design document used to instruct Claude Code on implementation, and is the **version synchronized** with the current codebase (`commission-system/`, **Phase 0–16 + E3 complete — from the rule store to a runnable app and the comparative explanation grade API** · 9,111 tests passing: unit/H2 · app smoke 8,994 + Oracle integration 117 — since v1.2.2 the cases of seed-fixed property tests are counted individually, 356 as of Phase 16).
> **Document management convention (v1.1.5)**: The single original of this design document is the **code repository** (`commission-system/docs/설계서.md`), and it is updated and reviewed together with the code when each Phase completes. Copies outside the repository (including copies delivered in conversation) are derivatives. A "proposed change" in `설계고찰.md` is marked as applied as soon as it is folded into the design document.
> Technical premise: Java 21 + Spring Boot + Oracle (the domain core has no DB dependency).
>
> **Disclaimer (learning/portfolio)**: The regulatory content in this document (the 1200% rule, deferral, comparative disclosure, etc.) is an illustrative summary based on publicly available supervisory regulations, and effective dates, detailed criteria and authoritative interpretations keep being updated — **actual application must follow the original texts and authoritative interpretations of the Financial Services Commission (금융위원회) and the Financial Supervisory Service (금융감독원).** All rates, amounts, figures, insurers, products and agents appearing in this document are **fictional example values**.

## Change history

| Version | Date | Description |
|---|---|---|
| v1.0 | 2026-07 | Initial design |
| v1.1 | 2026-07-16 | Implementation completion reflected. Main changes: ① Netting Convention promoted to a core principle (§3.0, Appendix B), ② new "post-save hook" stage in the calculation pipeline, with limit ledger posting moved there (§4.2, §6.1), ③ limit lock scope convention specified (§6.1.6), ④ `LIMIT_LEDGER_DTL.posting_seq` added and the replay reconstruction procedure codified (§5.6, §6.5), ⑤ limit restoration on a reversal entry = DTL sign inversion (§6.1.7), ⑥ deferral × limit interaction and deferral rounding finalized (§6.2), ⑦ offset moved from a pipeline Step to a payout-run-level service, and withholding tax moved to the payout stage (§4.2, §6.3, §7), ⑧ rule version replacement semantics finalized (§6.6), ⑨ two-layer definition of recalculation idempotency (§6.4), ⑩ contract tests and concurrency race tests added to the test strategy, Testcontainers-first principle (§8), ⑪ roadmap reorganized around the remaining work (§10), ⑫ tax and payout-cycle items added to §11, with a mapping of parameter locations |
| v1.1.1 | 2026-07-16 | Code consistency verification reflected (patch): ① `over_limit_action` has **no code default (intentional)** — it follows the rule data entirely, and it is clarified that what must be checked before deployment is not the code but the **operational seed data** (§6.1.4, §11.3), ② new **fail-fast principle** — when required rule data is missing, the calculation is rejected instead of falling back to a silent default (§6.1.4, Appendix B-13), ③ `posting_seq` numbering convention specified (numbered while holding the ledger lock) (§5.6), ④ posting_seq DDL and fail-fast tests added to the Phase 10 completion criteria (§10) |
| v1.1.2 | 2026-07-17 | Phase 10 completion reflected: ① `COMM_CALC` partitioning **finalized as RANGE + MAXVALUE** (INTERVAL is not possible on a VARCHAR2 key — including the alternatives reviewed and the reasons for rejecting them, §5.5), with detection of rows landing in MAXVALUE added as a close-report requirement, ② `posting_seq` numbering implementation finalized (MAX+1 under the lock, incremental save of the aggregate tail), ③ new rule approval concurrency convention — a function-based unique index as the final arbiter, retry/reject on violation (§6.6), change-audit requirement for trimming UPDATEs, ④ roadmap update (Phase 10 completion incorporated, new Phase 10b for the rule master and organization adapter) |
| v1.1.3 | 2026-07-17 | Phase 10b completion reflected: ① `AGENT_ORG_HIST` PK corrected to (agent_id, org_id, apply_from) — accommodating the 3-row team+branch+headquarters chain (§5 supplementary tables), ② approval workflow numbering uses a dedicated sequence separate from identity (blocks collisions between the seed and manual ranges), ③ automatic derivation of audit records from the before/after diff of the repository replace (omission structurally impossible) promoted to the standard, and an approver real-name requirement ("system" is recorded only on seed/test paths) and a recommendation to alert on approval races added (§6.6), ④ the consistency criterion for approval races = equivalence to serial execution specified (§6.6), ⑤ roadmap update (Phase 10b completion incorporated) |
| v1.1.4 | 2026-07-17 | Phase 11 interim reflected: ① the status-filtered SUM defect in the settlement net amount calculation (double deduction on a correction before close) found and fixed — recorded as an empirical case for the §3.0 Netting Convention, ② **extraction of `NetAmountCalculator` moved forward from Phase 12 to Phase 11** (settlement is the first consumer — the convention is enforced by structure, not by a spot fix), ③ hard coupling of offset and settlement-row creation to close removed — MonthCloseService does only the gate + confirmation, and the rest is separated into payout runs (run_seq), ④ specified that withholding tax is in principle calculated on the net payout amount of each payout run (withholding is based on the time of payout; linked to the §11.11 tax confirmation) |
| v1.1.5 | 2026-07-17 | Phase 11 completion reflected: ① roadmap update (Phase 11 completion incorporated, batch transaction pattern recorded — tasklet + one business transaction per item, minimizing the span during which the limit lock is held), ② **requirement to persist forced-close reasons in business tables** — facts subject to audit are not kept only in the batch metadata tables (BATCH_*) but are also persisted in `SETTLE_CLOSE.force_reason` (§5.9, §7), ③ **new document single-source convention** — the original of the design document lives in the code repository and is updated together with the code when a Phase completes (header), ④ recommendation to introduce dependency verification (Gradle dependency verification) — prompted by the discovery of unexpected directives inside a third-party artifact (§9 remarks) |
| v1.1.7 | 2026-07-17 | Phase 13 completion reflected: ① **incentive condition expression engine** — INCENTIVE_MST promoted to the §5 body (§5.10). Condition expressions are evaluated with SpEL only inside a **SimpleEvaluationContext (data-binding only) sandbox** (T() type references, beans, constructors, method calls and assignment are all rejected), fail-fast on registration and evaluation, determinism (the only inputs are snapshots of the event/agent/pre-aggregated performance — time, randomness and external lookups are blocked; the incentive versions used are pinned in rule_versions and condition evaluations are recorded in calc_trace), ② **reuse of the rate approval pattern** (fulfilling §6.6 v1.1.3 "apply the same to other masters") — trimming/SUPERSEDE, diff-based change audit (INCENTIVE_CHANGE_HIST), overlap invariant (same incentive_cd), ③ **new IncentiveV2 Step registered** (the existing IncentiveStep is retained, Appendix B-12), with the order IncentiveV2(30) &lt; LimitGate(50) so that incentives are included in the limit sum, ④ **new Appendix B-16** — sandbox convention for administrator-entered expressions (this system's first "execution of user input" surface), ⑤ repository put under git management (tags phase-12/13, one commit per Phase) |
| v1.1.6 | 2026-07-17 | Phase 12 completion reflected: ① **web layer scope finalized as a thin controller slice** — query/simulation/approval APIs wired on top of the facades with @RestController + MockMvc slices. The net amount API goes through `NetAmountCalculator` (the ban on status-filtered SUM is fixed by an architecture test), serialization fixed as long (won) for amount DTOs, ISO (yyyy-MM-dd) for dates and a yyyyMM string for the close month (an extension of Appendix B-1), 4xx mapping of fail-fast domain exceptions (internal details not exposed), a real approver required for the approval API (§6.6), ② **full boot assembly (runnable app + Oracle profile + externalized configuration + startup smoke) deferred to an 'operational assembly' Phase** — separating API correctness from operational assembly (newly added to the §10 remaining items) |
| v1.1.8 | 2026-07-17 | Phase 14 completion reflected + preliminary sub-tasks. **Preliminary (breaking down the deferred incentive approval concurrency)**: ① `INCENTIVE_MST` ACTIVE **function-based unique index** (V102, `ux_incentive_active`) — the final arbiter of "no overlapping ACTIVE", isomorphic to the rate index `ux_comm_rate_active`. However, because the incentive target filters (insurer/product/channel) are nullable, each filter is wrapped in `NVL('*')` (handling that rates did not need because they are NOT NULL — it stops duplicate all-target ACTIVE rows from slipping through Oracle's uniqueness comparison, which treats NULLs as distinct from one another), ② the adapter **translates** index violations into `IncentiveApprovalConflictException` (extends IllegalStateException) **→ an explicit 409 (race rejection)**, blocking raw 500 leaks, ③ **close rule completeness report extended to detect incentive overlaps (Ambiguous)**. **The retry runner + deterministic interleaving IT remain deferred** (a follow-up isomorphic to rates in §6.6 — since the index exists, the worst outcome of a race is a "noisy rejection", not an overpayment). **Phase 14 — externalizing the golden set to CSV (§8.1 implementation finalized)**: ④ golden cases promoted to **self-contained CSV bundles** (one case = one folder: it contains the rule seed + the event timeline + all expected results, with no dependency on shared seeds — the effective-dating philosophy that a regulation change means adding a new case rather than modifying existing ones), with authoring and verification ownership moved from developers to settlement staff (authoring conventions in `golden/README.md`: UTF-8 BOM, integer won, ISO dates, yyyyMM close month), ⑤ **CSV-scanning parameterized runner** — **runs through the full pipeline (bypassing Steps is forbidden)** with an in-memory store, mismatches are output as a diff readable by settlement staff (coordinates, expected vs actual, candidate causes), **joins CI just by adding a CSV (zero code changes)**, failure = build failure, summary log per case/axis, ⑥ **24 cases migrated** for the §8.1 representative axes (new business, installments, incentives, limit boundary/near-limit/over-limit/window, reduction, clawback (surrender/lapse/withdrawal/reinstatement/13th installment/duplicate), deferral (4-year/7-year/due/extinguished/rounding), retroactive correction before close NET) + **JUnit golden-value originals removed** (behavioral, property, contract and concurrency tests are all retained) |
| v1.1.9 | 2026-07-17 | Phase 15 completion reflected + preliminary sub-tasks. **Preliminary (consistency plug)**: ① **rule resolution Ambiguous fail-fast** — in single-record resolution by reference date, if two or more applicable ACTIVE records exist for the same key (rates) / `incentive_cd` (incentives), the calculation is rejected (Appendix B-13 extension, "if what must be one is two, fail loudly"). Rates were already fail-fast through the existing `unique()` (contract tests retained); for incentives a grouping check was added to `IncentiveV2Step` (pinned in-memory + on real Oracle). **This plug is the core of the consistency guarantee** — `ux_incentive_active` (v1.1.8) only arbitrates (key + start date) collisions, so concurrent approvals with different start dates can leave overlapping ACTIVE records, and the close report is non-blocking and runs once a month, so there is a payout window before detection. The Ambiguous plug closes that window at calculation time (however an overlap arises, no money moves). The spec of the deferred approval runner now states the **lock-then-revalidate** order (§5.10, §10 remaining items) — the runner is now a pure convenience improvement. **Phase 15 — regulatory add-ons and transition**: ② **comparative disclosure (§1)** in two layers — source data extraction (`DisclosureService`, all net amounts go through `NetAmountCalculator` all-status summation, status-filtered SUM forbidden) is separated from format mapping (`DisclosureFormat`). **The final format is an external fact, so it is not hardcoded but handled by swapping the implementation** (demonstrated by swapping 2 dummy formats). Comparative explanation ranking and grading for large GAs is `RankingService`. Format, aggregation unit and submission cycle are **new §11 #13** (subject to compliance confirmation), ③ **shadow run (§8.5)** — new module `commission-shadow`. Import of the external settlement CSV (`ShadowCsvAdapter`, fails on anomalous rows) → won-level reconciliation per recipient × type × close month (`ShadowRunService`) → classification by type (MATCH/ROUNDING/RATE_DIFF/MISSING/UNEXPECTED/TIMING, reusing and extending the §7 reconciliation typology). Own net amounts go through `NetAmountCalculator`, on the **real Oracle path** (in contrast to the golden set runner's in-memory store), 0-won tolerance by default, classification by type of deliberately injected differences demonstrated |
| **v1.2.0** | 2026-07-17 | **Phase 16 complete — runnable app assembly (minor version bump)**. Preliminary: ① incentive approval runner (`OracleIncentiveApprovalRunner`, lock-then-revalidate isomorphic to rates) + deterministic interleaving IT, ② grading extracted into `GradingPolicy` (tercile default, grading criteria added to §11 #13). Main part: **`@SpringBootApplication` + explicit wiring of all beans** (stores, services, batch, REST — this codebase has almost no stereotype annotations, so everything is wired with @Bean), **startup smoke E2E** (real Oracle: migration→seed→calculation→REST net amount→close job; wiring defects and TM mix-ups are caught here, confirming that the Phase 11 transaction structure is preserved), **configuration externalization** (DB and authentication all injected externally, zero secrets in the repository, Oracle profile, Flyway only validates at startup and migrate goes through a controlled procedure), **determinism** (JVM TZ Asia/Seoul, UTF-8 encoding), **minimal authentication** (Security roles ADMIN/OPERATOR/VIEWER + **principal-based real approver = approvedBy parameter removed, §6.6 completed**, deployment without authentication forbidden), **batch launch control** (auto-run forbidden = BatchAutoConfiguration excluded + a protected endpoint with explicit parameters), rationale for the Actuator health/readiness and Hikari settings. **New §12 operational transition checklist** — operations and data leftovers that are not code (finalizing the operational seeds, adding INBOUND adapters, running shadow in parallel on real data, volume/performance and partition SPLIT, audit replay runbook, real in-force determination, SSO integration) |
| **v1.2.1** | 2026-07-17 | Phase 16 wrap-up patch (safety hardening): ① **placeholder in operations structurally blocked** — the `PolicyStatusProvider` placeholder (always-in-force) is made a named class, and **in operations (prod), a placeholder makes startup fail during context initialization** (`PolicyStatusProviderGuard`). This stops "always-in-force" from feeding due deferral amounts into payouts even for lapsed policies **by structure, not by a note in a document** (Appendix B-13 — a loud failure instead of a silent default). Startup passes once a real determination implementation is wired. ② **approver real-name validation pushed down into the service layer** (§6.6 defense in depth) — in addition to the controller's principal validation, operational approval (2-arg approve) in `RateApprovalService` and `IncentiveApprovalService` rejects empty values and "system" (shared `ApproverPolicy`). Even if a technical-account principal comes in as "system" or a call bypasses the controller (runners, batch), operational approval records do not get mixed with the seed/test range. Seeds/tests keep using "system" through the no-argument approve overload |
| **v1.2.6** | 2026-10-17 | **Phase E2 — provisional statements, simulation, statement query, local stack** (provider of portal contract v1.3 `78a5932`). ① **`StatementEstimated`** — the dry-run of a payout run as a pure estimator (`StatementEstimator`, read-function bundle `EstimateReads`), with the same formula `PayoutMath` as the payout run, the same offset iteration (`previewConsume`), the same due determination (`releasable`) and the same tax-rate bean. Job `statementEstimateJob`, `basisSeq` = lower bound (§6.8). ② **Two kinds of simulation** — a second calculator that assembles the same Step list and hooks as operations (`CalcSteps`) on an overlay store (read-function bundle `SimulationReads`, ledger `peek` without locking), `ruleSnapshotId` (§6.8). ③ **Statement query** `GET /internal/v1/agents/{agentId}/statements/{ym}?mode=ESTIMATE|CONFIRMED` (§6.8). ④ **Close-month guard moved outside the operational assembly** (§6.4, §11 E1-4 resolved). ⑤ **Local stack in one command** (`localstack/up.sh`, module `commission-localstack` — outside the operational jar, feed = the bytes of the second edition of A-1), with the Oracle Free image digest in one place in the version catalog. ⑥ Snapshot bootstrap not included (§11 E2-3). ⓪ Two editions of A-1 (`a1_portal_150000.jsonl`, `_e2.jsonl`; first edition = the leading part of the second edition). §11 E2-1 (the existing `/api/commissions/simulate`) and E2-2 (withholding tax constant) recorded |
| **v1.2.7** | 2026-10-17 | **e2.1 — copy of portal contract v1.4** (portal `90041b4`, E2 acceptance review §3 — notation and copy changes, zero code changes). `contracts/shared/ga-agent-portal` copied again (23 files + CHECKSUMS, `UPSTREAM`): the A-1 sample is now the engine's **second edition** (`a1_portal_150000_e2.jsonl`), `contract-only/07` deleted (the real sample is a1 seq 11), OpenAPI 1.4.0 (`'no'` key, the sign of `clawbackAmount` (positive on reinstatement), simulation response definition, meaning of `INSURER_AMBIGUOUS` — the contract wrote down the definitions from §6.8), `AgentSnapshot.status` as a closed enumeration `ACTIVE`/`DISMISSED` (the two values of the engine's `AgentSnapshotPublisher`). `PortalA1ByteIdentityTest` is based on the second edition, and agent status is added to `PortalVocabularyTest` |
| **v1.2.5** | 2026-10-17 | **Phase E1 — outbox and event feed** (provider of portal `ga-agent-portal` contract v1.1.1). ① **Contract copies moved into per-upstream directories**: `contracts/shared/ga-disclosure/` (grade API + shared Envelope core, `UPSTREAM` = `db82d5d`) and `contracts/shared/ga-agent-portal/` (portal event contract + feed OpenAPI, `UPSTREAM` = `f54d008`). Each directory has `CHECKSUMS` and `UPSTREAM` (first line the commit + the file list — a closed list), verification tasks per directory, and both hashes in the upstream comparison failure message (§6.7, §6.8). ② **Outbox** (§5.11, V105, V106) + **canonical payout statement** `PAYOUT_STATEMENT` (§5.9, V107). ③ **Publication points** (§6.8): 6 repository decorators + 2 listener ports (`MonthCloseListener`, `PayoutListener`), with business code and Steps unchanged. A default method `replace(entry, reason)` on the deferral port (the only port change), an `ADJUSTMENT` attribution-month lookup, and an app-wide `Clock` for close and payout (`closed_at` only on CLOSED — §7). ④ **Deferral cancellation listener and reversal hook moved into the app assembly** (a defect that existed before E1 — §6.2), with a test that operational assembly = golden assembly. ⑤ **Feed API** (`GET /internal/v1/events`), jobs `outboxSequenceJob` and `outboxAgentSnapshotJob` (§6.8). ⑥ Provider proof: global validator, event golden snapshots, A-1 comparison (§6.8). |
| **v1.2.4** | 2026-10-09 | **E3.2 maintenance (not a Phase, no new features)**. ① **Globally monotonic migration numbers**: the common and Oracle-only directories share a single number space — a new number is the overall maximum + 1, no reuse or gaps, and committed migrations must not be modified. The unused numbers V15–V99 and the ordering-inversion history under the old rule are in `docs/db-migrations.md`, with the test `MigrationNumberingTest` (document table ↔ actual files, both directions) (ga-disclosure 3A acceptance review §4-①, §9). ② **V104** `DISC_GRADE_SNAPSHOT_ITEM.product_key` 129 → 40 (the contract's ProductKey maximum length) — no value conversion; the migration fails if any row exceeds 40 characters (it does not truncate) (3A acceptance review §4-②, §6.7). ③ **Contract 1.2.1** (UPSTREAM updated to the merge commit of ga-disclosure PR #11): `format` → `pattern` (`asOfDate`, `generatedAt` — the validator does not assert `format`), with the engine output format unchanged (§6.7). ④ Merge gate (after the acceptance review reply), the rule-file correction convention and the numbering rule added to CONTRIBUTING and CLAUDE.md. ⑤ `docs/third-party.md` — dependencies outside the Boot BOM and OSV results (no version changes). |
| **v1.2.3** | 2026-09-30 | **Phase E3.1 — E3 acceptance review follow-up** (ga-disclosure contract 1.2.0). ① `contracts/UPSTREAM` = the merge commit of the contract PR on ga-disclosure `main` (`9379be96`) — UPSTREAM is always a commit reachable from the other side's main (§6.7). ② **Product key rules**: `productKey` `^[A-Z0-9][A-Z0-9-]{0,7}:[A-Za-z0-9][A-Za-z0-9._-]{0,30}$` (40 characters) and `insurerCode` `^[A-Z0-9][A-Z0-9-]{0,7}$` in request validation (400), `DISC_PRODUCT_GROUP_MEMBER.ext_product_key` width 129→40 (V14). ③ GET 403 = the token is valid but authorization is denied, another tenant's snapshot = 404 (contract description change). ④ **Snapshot numbering = a single Oracle SEQUENCE** (`DISC_GRADE_SNAPSHOT_NO`, V13) — daily counter rows, the first-row race (E3 §5-15) and retries removed, and numbers do not restart every day (dedicated 7-digit range 1000000–9999999). ⑤ Repository root `CLAUDE.md` (collects the CONTRIBUTING and Appendix B conventions, no new rules). ⑥ **JCS cross-verification**: a test that the engine's JCS produces byte-identical output against the ga-disclosure `platform-canonical` vectors (1,051 cases) — this found that erdtman 1.1 lets unpaired surrogates through, so they are now rejected at the `Jcs` entry point. ⑦ Cause and data of `TEMP_PRODUCT` removed (temporary registrations never arrive in requests — E3's "data list compatibility" abandoned). ⑧ Service token hash **list** (1–2 entries, overlapping during rotation). ⑨ CI `no-docker` job (measured with the daemon stopped). |
| **v1.2.2** | 2026-09-29 | **Phase E3 — comparative explanation sales commission grade and rank API** (provider of the ga-disclosure contract `engine-disclosure.openapi.yaml` 1.1.0). ① **Grading policy and ranking policy as versioned data** (`DISC_GRADING_POLICY`, `DISC_RANKING_POLICY`, new module `commission-disclosure`): thresholds, boundary inclusion, labels, ordinals, rounding digits/mode, minimum population size, measurement period, not-computable reason codes and tie rules (`SHARED_RANK`/`STRICT` + secondary key) are all body JSON, with zero code constants (source scan test). Fail-fast on load for overlaps, gaps, duplicate ordinals and missing labels; single-record resolution by reference date and Ambiguous on 2 records (reusing the engine convention). Replacing 5 levels with 4 levels is absorbed by data alone (E1). ② **Measure function registry** (`measureKey` → function, the explicit Step list pattern) — v1 **example measure** `FY_SALES_COMMISSION_RATE`: reads the FY_COMM, installment-NULL rate of the revenue-side (INBOUND) `COMM_RATE` as of the last day of the most recently completed quarter (the definition is an externally confirmed fact under §11 #13). The ratio = measure × n / Σ is produced with **a single division and a single rounding** (`RatioToAvg`), and that string is carried unchanged by the response, the snapshot and re-queries (Appendix B-1 revised). ③ **In-set rank** `RankingService.rankInSet` (ascending by measure, separate from the comparative-disclosure aggregate `rank`) + response **self-verification** (rank↔ordinal monotonicity, rank structure — on failure no snapshot is issued, 422). ④ **Immutable snapshots, byte-identical re-query**: `DISC_GRADE_SNAPSHOT(_ITEM)` + RFC 8785 JCS canonical response with stored SHA-256, POST body = GET body = stored original (no re-serialization), and the engine's first **DB-enforced immutability trigger** (V103). ⑤ **Service token for `/internal/**`** (Bearer, SHA-256 constant-time comparison) + **instance tenant** `app.tenant-id` (403 on mismatch) — both are required configuration with no default (B-13). ⑥ Mapping data `DISC_PRODUCT_GROUP(_MEMBER)` from the external `productKey` (129 characters) to rate ledger keys (the product group code scheme is data as well). ⑦ **Preliminary E3-0: jqwik removed** — the 3 property tests replaced with JUnit 5 parameterization + seed-fixed generators (`commission-domain` testFixtures `SeededCases`) (same properties and domains, number of trials ≥ the original), and resolution of `net.jqwik` blocked in the build (jqwik 1.10's clause excluding use by AI agents and its output-injection directives). ⑧ Version in the title corrected to the current one (v1.1 → v1.2.2), merge method squash → merge commit (reports cite commit hashes, CONTRIBUTING). |

---

## 0. The overarching premise of the design

The biggest risk of this system is not technology but **the volatility of regulation**. The financial authorities change supervisory regulations, enforcement rules and authoritative interpretations at any time (extension of the 1200% rule to GAs, deferral, comparative disclosure, etc.), and internal company rules (payout rates by position, incentives) change even more often. The first principles of the design are therefore as follows.

1. **Rules are data, not code.** Rates, limits, clawback rates and payout rates are all managed as versioned data with an effective date, and the calculation engine looks up and uses "the version effective on the reference date".
2. **Changes that alter the calculation structure (such as the introduction of a new regulation) are handled by adding a new calculation Step.** Existing Steps are never modified; they are replaced by versions.
3. **Every calculation result is immutable, and corrections are made only through a reversal entry + recalculation (rebook).** This is the foundation of accuracy, auditability and reproducibility.
4. **All money calculations use BigDecimal + a centralized rounding policy.** double/float are forbidden.
5. **(new in v1.1) Net amounts are obtained only by summing over all statuses (Netting Convention, §3.0).** REVERSED originals stay in the sum, and the negative reversal offsets them. No query, whether for lookup, disclosure or reconciliation, may filter amounts by status.

> It has been confirmed that principles 1–4 were upheld as-is throughout the Phase 0–9 implementation. In particular, thanks to the convention that "rule lookups require a reference date", effective-date branches such as the 2026-06-30/07-01 limit boundary and the 2027 start of deferral were all reduced to data questions without any code branches.

---

## 1. Regulatory environment (as of 2026.7) — impact on the design

Below is the implementation roadmap of the Financial Services Commission's 「Reform of the Insurance Sales Commission System」 (보험 판매수수료 제도 개편). **Detailed rates and application criteria keep being specified through supervisory regulations, enforcement rules and authoritative interpretations, so do not hardcode figures during implementation; always manage them as parameters.** (Final confirmation is based on the original texts of the FSC and the FSS)

| Effective | Content | System impact |
|---|---|---|
| 2021~ | 1200% rule: total first-year solicitation commission ≤ monthly premium × 12. Applied first to captive channels and to insurer→GA payouts | Verification of revenue-side (insurer→GA) commission |
| 2026.1 | Amendment of the Insurance Business Supervision Regulations (보험업감독규정) resolved | — |
| 2026.3 | Comparative disclosure of sales commissions takes effect, extension of the arbitrage-prohibition period | Data production for disclosure (implemented in Phase 15 — **two layers**: source data extraction (`DisclosureService`, all through NetAmountCalculator) + format mapping (`DisclosureFormat`; the final format is an external fact, so no hardcoding, swap the implementation). Comparative explanation ranking and grading for large GAs is `RankingService`. Format and aggregation unit are subject to confirmation under §11 #13) |
| **2026.7.1** | **The 1200% rule is extended to GA→affiliated agent payouts on a per-individual basis** (for policies concluded on or after 7/1). Not only solicitation commission but **most payout items, such as incentives and settling-in support payments, are added to the limit sum**. Large GAs (500 or more agents) have a duty to explain commission grades and ranks comparatively | **Limit Ledger required** — real-time tracking of the first-year accumulated payout per policy × agent, limit verification before payout |
| **2027.1** | **Deferral takes effect: commission that used to be concentrated in the first year is paid out in installments over 4 years** | **Deferral schedule engine required** — generates the payout curve according to the regulation version at policy conclusion, linked to retention conditions |
| 2029.1 | Deferral period extended to 7 years. Maintenance commission (per the detailed plan, within 0.8% per month of the contract acquisition cost, up to 7 years — the final rate must be confirmed by a later notice) | The deferral engine is designed with "the deferral curve itself as data", so the 4-year→7-year extension is absorbed by a data change alone (v1.1: proven by tests that it works by just replacing curve records) |

Summary of design implications:

- The limit rule (1200%) and the deferral rule **follow "the regulation version as of the policy conclusion date".** A policy concluded on 2026.6.30 and one concluded on 2026.7.1 are subject to different rules. → The rule resolver always takes a reference date (contract date, installment due date, etc.) as input.
- The very definition of "what is included" in the limit (solicitation commission/incentives/settling-in support/event-type payouts...) may change according to authoritative interpretations. → **A 'limit-included' flag per payout item is managed as data.**
- Regulatory circumvention (irregular payouts) is a focus of the authorities' monitoring, so every payout must remain in the limit ledger and every attempt to exceed the limit must be blocked and logged. This becomes material for compliance defense.

---

## 2. Domain overview

### 2.1 Commission flows (the two-sidedness of a GA)

A GA is both **the receiver and the payer** of commission. The two flows must be modeled separately.

```
[N insurers (carriers)] --(EDI/commission statements)--> [GA: revenue-side commission (INBOUND)]
                                              |
                                              v  (apply payout rate/incentive/limit/deferral/clawback)
                                         [GA: cost-side commission (OUTBOUND)]
                                              |
                                              v
                              [individual agent + organization (team leader/branch manager/HQ) override]
```

- **Revenue side (INBOUND)**: commission that insurers pay the GA. Products, rates and statement formats all differ by insurer. The GA's own calculation must be **reconciled** against the insurer's statement.
- **Cost side (OUTBOUND)**: commission that the GA pays agents. This is the area to which the 1200% rule (2026.7~) and deferral (2027.1~) apply directly. The core of this system.
- The difference between the two flows is the GA's margin, and margin analysis per policy, agent and product must be possible.

### 2.2 Commission types (OUTBOUND)

| Type | Example code | Description | Included in the 1200% limit |
|---|---|---|---|
| First-year solicitation commission | `FY_COMM` | Commission for concluding new business. Rates exist per installment number (installments 1–12) | Included |
| Renewal (maintenance) commission | `RENEWAL` | On receipt of renewal premiums from the 2nd year onward | Not included (not first year) |
| Incentive/promotion | `INCENTIVE` | Incentive conditional on period, product and performance | **Included (2026.7~)** |
| Settling-in support | `SETTLEMENT_SUPPORT` | Support for recruiting/settling in agents | **Included (2026.7~)** |
| Override (organization commission) | `OVERRIDE` | Payouts to upper organizations such as team leaders, branch managers and HQ heads | Application criteria need confirmation → managed with a flag. If finalized as "included", absorbed by changing the data to `limit_included='Y'` + registering LimitGateStep V2 (recipient scope extended) — existing Steps unmodified |
| Deferred commission | `DEFERRED` | Deferred payouts according to the deferral schedule from 2027.1~ | The schedule itself is an output of the limit (the limit is consumed by the total before splitting, §6.2) |
| Maintenance commission | `MAINTENANCE` | 2029 roadmap. Linked to the retention rate | Expected not to be included; confirm with the notice |
| Clawback | `CLAWBACK` | Recovery on surrender/lapse/withdrawal/non-retention (negative payout) | Deducted from the limit ledger (restoration = DTL sign inversion, §6.1.7) |

Whether a type is included in the limit is not hardcoded but kept as an attribute (with an effective period) of the `COMM_TYPE` master.

### 2.3 Commission lifecycle events

Commission does not arise on its own; it is **derived from policy events**. The design is event-driven.

| Event | Commission impact |
|---|---|
| New business concluded (application approved) | First-year commission arises, a 1200% limit ledger is opened, (2027~) a deferral schedule is generated |
| Installment premium received | Commission per installment arises, deferral condition check |
| Application withdrawal | Full clawback of commission already paid |
| Surrender/lapse | Clawback according to the clawback rate table, deferral schedule stopped |
| Reinstatement | Commission/schedule resumed (whether clawed-back amounts are paid again is an internal rule → `ReviveStep.RevivePolicy` parameter) |
| Reduction/premium change | Monthly premium change → **recalculation of the 1200% limit amount**, deferral schedule adjusted |
| Agent transfer/termination | Attribution of remaining deferrals and clawbacks (orphan commission policy) |
| Insurer correction statement received | Revenue-side correction → triggers cost-side recalculation |

### 2.4 Organization and agents

- Agent: appointment code, position/grade (determines the payout rate), appointment date/termination date, affiliation history (organization transfer history is required — whether affiliation is determined as of the payout time or as of the contract time is the internal-rule parameter `AffiliationBasis`).
- Organization: tree structure (HQ–branch–team); override allocation rates are a rate table per hierarchy level.
- Tax: agents are usually business-income earners → 3.3% withholding on payout (income tax 3% + local income tax 0.3%), and a payment statement for tax purposes is produced. Withholding tax is calculated **on the net payout amount after offset** at the payout stage (`PayoutService`), and the tax rate and truncation rules can be swapped via `WithholdingTaxPolicy`. How clawback offsets are reflected in the payment report to the tax office needs tax confirmation (§11.11).

---

## 3. Core design principles

### 3.0 Netting Convention — new in v1.1, must not be violated

**A net amount (the confirmed amount per recipient × type × period) is obtained only by summing all records, without a status filter.**

- Original records in REVERSED status are **not excluded** from the sum. What offsets an original is the negative reversal record.
- This single convention unifies the following: close-month immutability (past figures do not change), payout/clawback accounting, correction of originals in PAID status (same formula), and calculation of the clawback base amount (net amount already paid).
- **Prohibited**: no query that sums amounts (REST lookup, disclosure production, reconciliation, reports) may apply a status filter such as `status != 'REVERSED'`. Status filters are allowed only in workflow queries (counts, lists).
- **Enforcement**: net amounts are produced only through a shared summation component of the query layer (e.g. `NetAmountCalculator`). Individual services writing SUM queries directly is forbidden by the code review convention, and this is codified in the core conventions of the README.
- **Empirical case (v1.1.4)**: in Phase 11, the settlement net amount calculation was found to be using a CALCULATED status filter, a latent defect whereby on a correction before close (the original REVERSED, the reversal and the rebook coexisting in the same month) the original dropped out and only the negative reversal was summed, causing a **double deduction (underpayment)**. This is why the convention exists, and since then all new net amount code goes through the shared component without exception. That combination (the three kinds of record from a correction before close coexisting) is pinned by a regression test.

### 3.1 Effective-Dated + version management (bitemporal recommended)

- Every rule/rate record has `apply_from`, `apply_to` (business validity period) + `created_at`, `superseded_at` (system recording period).
- Business time axis: "which rate applies to this policy (concluded 2026.8.1)?" → look up by reference date.
- System time axis: "which rate did we calculate with at last month's close?" → for retroactive corrections and audits. If full bitemporality is too burdensome, at least ensure reproducibility by "pinning the IDs of the rule versions used in the calculation result" (required — implemented).

### 3.2 Immutable ledger + Reversal & Rebook

- `COMM_CALC` records must not be UPDATEd after creation (except for status transitions). Errors are corrected with a negative reversal record referencing the original + a new record.
- Records of a period confirmed by (monthly) close are absolutely immutable. Errors found after close are reflected as an adjustment (`ADJUSTMENT`) in the following month.

### 3.3 Deterministic calculation + pinned rationale

- Same input + same rule versions → always the same output (idempotent). A calculation result stores, as JSON, the list of rule version IDs used, an input snapshot and the intermediate values of each step (trace). → For audits, customer complaints and regulatory inspections.
- Determinism of replay (reproduction) is guaranteed only if it follows the ledger reconstruction procedure in §6.5.

### 3.4 Limits as a ledger, verification before payout

- The 1200% limit is not an after-the-fact report but **a gate in the payout pipeline**. Amounts over the limit are automatically cut/deferred, and the reason is recorded.
- Gate decision and ledger posting are separate stages (§4.2) but must be within **a single transaction and a single lock scope** (§6.1.6).

### 3.5 Framework independence of domain logic

- The calculation engine (core) is a pure Java module with no Spring/Oracle dependency, so unit tests run in milliseconds. DB and batch are adapters. The in-memory Store implementation is the **reference implementation** of the conventions (immutable ledger, idempotency keys, status transitions), and the Oracle adapter proves equivalence through contract tests (§8.7).

### 3.6 Rule management strategy: hybrid (general-purpose rule engines not recommended)

General-purpose rule engines such as Drools have major problems in this domain with debugging, auditing, performance and learning cost. Instead, a three-layer hybrid is recommended. (Implementation verification: proven by tests that the 4-year→7-year deferral extension works just by adding curve records)

| Layer | Managed as | Examples | Deployment needed on change |
|---|---|---|---|
| Rates/parameters | DB tables (effective period + version) | Rates per installment, payout rates, clawback rates, limit multiple (12×), deferral curves | No |
| Conditions | Restricted expressions (SpEL, **sandbox required**) or configuration flags | Incentive conditions (§5.10), "included in the limit", "re-pay on reinstatement" | No |
| Calculation structure | Versioned code Steps (strategy pattern) | The 1200% limit logic itself, the deferral algorithm | Yes (add a new Step) |

When a new regulation comes out: ① can it be absorbed by parameters? → done by registering in a table. ② does it need a new calculation stage? → implement a new `CalculationStep` + register its effective period. Existing code is not touched.

---

## 4. System architecture

### 4.1 Module structure (Gradle multi-module) — implemented

```
commission-system/
├── commission-domain      # pure domain: Money, Rate, Policy, Agent, events, value objects (no dependencies)
├── commission-rule        # rule storage/resolution: RuleRepository, effective-date version resolver, approval workflow
├── commission-calc        # calculation engine: pipeline, CalculationStep implementations, CalcResultListener hooks, NetAmountCalculator
├── commission-limit       # 1200% limit ledger/gate/posting (Poster)/restoration (ReversalHook)
├── commission-deferral    # deferral schedule generation/execution (for 2027)
├── commission-clawback    # clawback engine + ClawbackOffsetService (offset per payout run)
├── commission-inbound     # insurer EDI/statement intake and normalization (per-insurer adapters)
├── commission-recon       # revenue-side reconciliation (insurer statement vs own calculation)
├── commission-settlement  # monthly close (MonthCloseService)/payout run (PayoutService, withholding tax, payout statement persistence)/adjustments
├── commission-outbox      # (v1.2.5) outbox and event feed: portal event builders, OutboxStore port, sequencer, repository decorators — no Spring dependency (§6.8)
├── commission-batch       # Spring Batch jobs (close, payout run, deferral due, recalculation, (v1.2.5) outbox sequencing and AgentSnapshot bootstrap) — implemented
├── commission-disclosure  # (v1.2.2) comparative explanation grade/rank: policy version resolution/validation, measure registry, in-set rank, snapshot canonicalization (JCS) — no Spring dependency + ports
├── commission-api         # REST API (queries, simulation, rate approval, comparative explanation grade `/internal/v1/disclosure/**`, event feed `/internal/v1/events`) — thin controllers
├── commission-infra       # Oracle adapters (MyBatis mappers), Flyway schema, batch runtime — implemented ((v1.2.5) OUTBOX_EVENT, PAYOUT_STATEMENT, agent master reads)
└── commission-app         # (v1.2.0) runnable app assembly — explicit wiring of all beans, (v1.2.5) publishing decorators, listeners, feed, global Clock
```

Dependency direction: `api/batch → calc/limit/... → rule → domain`. (v1.2.5) `commission-outbox → settlement → calc/limit/deferral/clawback → rule → domain`, and `infra` (Oracle adapter), `api` (feed controller), `batch` (sequencer and bootstrap jobs) and `app` (assembly) use outbox. There is no business module → outbox dependency — outbox depends on the business modules, so the reverse dependency would be a Gradle cycle and the build rejects it (E1 Q4). domain depends on nothing. (v1.2.2) `commission-disclosure → rule → domain`, and `api` (controller) and `infra` (Oracle adapter) use/implement the ports of `commission-disclosure`.

### 4.2 Calculation pipeline — revised in v1.1

The unit of calculation is `(policy event × recipient)`. One event produces calculations for the agent and for multiple organization override recipients.

Differences from v1.0: ① ledger posting moved from a Step to a **post-save hook** (reason: integrity of the DTL rationale — posting needs a finalized calcId), ② `ClawbackOffsetStep` and `WithholdingTaxStep` removed from the pipeline and moved to a payout-run-level service and the payout stage respectively (reason: offset and taxation belong to the point at which net amounts are finalized, not to individual calculations, and offset inside the pipeline risks double offsetting).

```
PolicyEvent received
  → [1] validation/normalization (per-insurer adapter, idempotent handling of duplicate events: event natural key)
  → [2] recipient determination (agent + override chain, basis: AffiliationBasis parameter)
  → [3] rule resolution (rate/payout rate/limit rule/deferral rule/clawback rule versions fixed by reference date → RuleSnapshot)
  → [3.5] ★ acquire limit ledger lock (for limit-subject calculations, §6.1.6 — acquired before the gate decision)
  → [4] run the calculation Step chain:
        BaseCommissionStep  (order 10)  base commission = calculation base amount × rate
        PayoutRateStep      (order 20)  apply payout rate by position/grade
        IncentiveStep       (order 30)  incentive calculation (condition verification)
        LimitGateStep       (order 50)  ★ 1200% limit decision (decides cut/deferral only, does not post)
        DeferralSplitStep   (order 55)  ★ deferral schedule split (active from 2027-01-01)
  → [5] save result (COMM_CALC + trace + rule versions used) — status CALCULATED, calcId finalized
  → [5.5] ★ post-save hook (CalcResultListener chain, same transaction):
        LimitLedgerPoster    post LIMIT_LEDGER_DTL with the finalized calcId + add to the ledger
        (post-processing that depends on calcId, such as finalizing the deferral schedule, also happens here)
  → [commit] — lock released at commit
  → [6] confirmed (CONFIRMED) in the monthly close batch → offset and withholding tax in the payout run → paid (PAID)
```

Each Step implements the interface below, and **the Step list itself is configuration with an effective period** (e.g. `DeferralSplitStep` is active from 2027-01-01).

> **Batch transaction boundary (v1.1.5)**: [3.5]–[5.5] close within **a single business transaction per item (event × recipient)**. Batch jobs that include calculation or payout use a resourceless step transaction manager and open one business transaction per tasklet iteration, structurally ruling out a chunk holding the limit lock across multiple items (§6.1.6, §10 Phase 11).

### 4.3 Core interfaces (Java sketch) — revised in v1.1

```java
/** Money: wraps BigDecimal, enforces currency/scale/rounding policy. Immutable after creation */
public final class Money implements Comparable<Money> {
    private final BigDecimal amount;          // fixed scale
    public Money multiply(Rate rate, RoundingPolicy policy) { ... }
    // plus/minus/negate/isNegative ... all arithmetic happens only here
}

/** Rounding policy: no direct use of RoundingMode anywhere in the code outside this class */
public enum RoundingPolicy {
    KRW_FLOOR(0, RoundingMode.FLOOR),      // truncate below one won (default, confirm with internal rules)
    KRW_HALF_UP(0, RoundingMode.HALF_UP);
    // which policy is used for which commission type is an attribute of the COMM_TYPE master
}

/** Calculation context: immutable-accumulating object flowing through the pipeline */
public final class CalcContext {
    PolicyEvent event;
    Recipient recipient;             // agent or organization recipient
    RuleSnapshot rules;              // bundle of rule versions fixed by reference date
    List<CalcLine> lines;            // calculation lines added by Steps (amount per type + rationale)
    LimitLedgerView limitView;       // ledger snapshot at lock acquisition (§4.2 [3.5])
}

public interface CalculationStep {
    String stepId();                             // e.g. "LIMIT_GATE_V1"
    int order();                                 // execution order (LimitGate=50 < DeferralSplit=55 fixed)
    boolean supports(CalcContext ctx);           // effective period and event type filter
    void apply(CalcContext ctx);                 // add/modify lines + record trace
}

/** New in v1.1 — post-save hook: post-processing that needs a finalized calcId. Runs in the same transaction as the save */
public interface CalcResultListener {
    void afterSaved(SavedCalc calc, CalcContext ctx);   // e.g. LimitLedgerPoster
}

/** Rule lookup: reference date always required. No "currently effective" lookup API is created */
public interface RuleRepository {
    CommRate findRate(String insurerCd, String productKey, CommType type,
                      int installmentNo, LocalDate baseDate);
    LimitRule findLimitRule(ChannelType channel, LocalDate contractDate);   // 1200% rule version
    DeferralCurve findDeferralCurve(LocalDate contractDate);                // deferral curve
    ClawbackTable findClawbackTable(String productKey, LocalDate contractDate);
}

/** New in v1.1 — shared net amount component: net amounts must come only from this (§3.0) */
public interface NetAmountCalculator {
    Money netOf(RecipientRef recipient, CommType type, YearMonth closeYm);  // sum over all statuses
}
```

---

## 5. Data Model (Oracle DDL Sketch)

Names and types are adjusted to in-house standards at implementation time. Amounts are `NUMBER(15)` (in won) or `NUMBER(17,2)`; rates are `NUMBER(9,6)`.

> v1.1 note: the current schema is verified both on H2 (MODE=Oracle) and on real Oracle via Testcontainers (gvenzl/oracle-free). Oracle-only elements (partitioning, `IS JSON` checks, function-based unique indexes) are added through Oracle-only migrations (`db/vendor/oracle`). Oracle-specific behavior (empty string = NULL, NUMBER precision, `FOR UPDATE` wait semantics) is verified by the contract tests in §8.7.

```sql
-- 5.1 Commission type master (whether included in the limit and the rounding policy are also effective-dated data)
CREATE TABLE COMM_TYPE_MST (
  comm_type       VARCHAR2(20),
  apply_from      DATE NOT NULL,
  apply_to        DATE DEFAULT DATE '9999-12-31',
  limit_included  CHAR(1) NOT NULL,      -- counted toward the 1200% limit Y/N
  rounding_policy VARCHAR2(20) NOT NULL,
  clawback_target CHAR(1) NOT NULL,
  CONSTRAINT pk_comm_type PRIMARY KEY (comm_type, apply_from)
);

-- 5.2 Rates (insurer→GA receipt basis and GA→agent payout basis separated by direction)
CREATE TABLE COMM_RATE (
  rate_id         NUMBER PRIMARY KEY,
  direction       VARCHAR2(8) NOT NULL,  -- INBOUND / OUTBOUND
  insurer_cd      VARCHAR2(10) NOT NULL,
  product_key     VARCHAR2(40) NOT NULL, -- composite key of product, plan, and payment term (a separate mapping table is recommended)
  comm_type       VARCHAR2(20) NOT NULL,
  installment_no  NUMBER(3),             -- installment number (NULL = lump-sum payout type)
  rate            NUMBER(9,6) NOT NULL,
  apply_from      DATE NOT NULL,
  apply_to        DATE DEFAULT DATE '9999-12-31',
  version_no      NUMBER NOT NULL,
  status          VARCHAR2(10) NOT NULL, -- DRAFT/ACTIVE/SUPERSEDED (approval workflow)
  created_by      VARCHAR2(20), created_at TIMESTAMP,
  approved_by     VARCHAR2(20), approved_at TIMESTAMP
);
-- Invariant "no overlapping ACTIVE for the same key" (§6.6):
--   1st enforcement: approval workflow (application layer) — only trimming/SUPERSEDE allowed, approvals that split a period are rejected
--   2nd redundancy (Oracle): function-based unique index (ACTIVE rows only)
--   3rd safety net: the close batch's Ambiguous (duplicate effective rules) detection report

-- 5.3 Payout rate by agent grade
CREATE TABLE AGENT_PAYOUT_RATE (
  grade_cd    VARCHAR2(10), comm_type VARCHAR2(20),
  payout_rate NUMBER(9,6) NOT NULL,
  apply_from  DATE NOT NULL, apply_to DATE DEFAULT DATE '9999-12-31',
  CONSTRAINT pk_payout PRIMARY KEY (grade_cd, comm_type, apply_from)
);

-- 5.4 Policy events (source, immutable. Duplicate receipt blocked by the idempotency key)
CREATE TABLE POLICY_EVENT (
  event_id     NUMBER PRIMARY KEY,
  event_key    VARCHAR2(100) NOT NULL UNIQUE,  -- natural key such as insurer code + policy number + type + installment number
  policy_no    VARCHAR2(30) NOT NULL,
  insurer_cd   VARCHAR2(10) NOT NULL,
  event_type   VARCHAR2(20) NOT NULL,  -- NEW/PAYMENT/CANCEL/LAPSE/REVIVE/REDUCE/WITHDRAW/CORRECT
  event_date   DATE NOT NULL,          -- business occurrence date (used to determine the rule reference date)
  payload      CLOB CHECK (payload IS JSON),
  received_at  TIMESTAMP DEFAULT SYSTIMESTAMP,
  process_status VARCHAR2(10) DEFAULT 'PENDING'
);

-- 5.5 Commission calculation results (immutable ledger)
CREATE TABLE COMM_CALC (
  calc_id        NUMBER PRIMARY KEY,
  event_id       NUMBER NOT NULL REFERENCES POLICY_EVENT,
  policy_no      VARCHAR2(30) NOT NULL,
  recipient_type VARCHAR2(10) NOT NULL,  -- AGENT / ORG
  recipient_id   VARCHAR2(20) NOT NULL,
  comm_type      VARCHAR2(20) NOT NULL,
  base_amount    NUMBER(15) NOT NULL,    -- calculation base amount
  applied_rate   NUMBER(9,6),
  calc_amount    NUMBER(15) NOT NULL,    -- negative for clawbacks and reversals
  limit_cut_amt  NUMBER(15) DEFAULT 0,   -- amount cut/deferred by the 1200% limit
  close_ym       VARCHAR2(6) NOT NULL,   -- attributed close month
  status         VARCHAR2(12) NOT NULL,  -- CALCULATED/CONFIRMED/PAID/REVERSED
  reversal_of    NUMBER REFERENCES COMM_CALC(calc_id),  -- reference to the original of a reversal entry
  rule_versions  CLOB CHECK (rule_versions IS JSON),    -- rule versions used, pinned
  calc_trace     CLOB CHECK (calc_trace IS JSON),       -- intermediate values per step
  created_at     TIMESTAMP DEFAULT SYSTIMESTAMP
) PARTITION BY RANGE (close_ym)              -- confirmed in v1.1.2: RANGE + MAXVALUE
  -- INTERVAL partitioning allows only NUMBER/DATE keys, so it is not possible for close_ym VARCHAR2(6).
  -- Alternative considered and rejected: change close_ym to NUMBER(6) or DATE — the YYYYMM string key is used consistently across all tables and code
  --   (YearMonth), so the friction was judged greater than the benefit of a type change; rejected.
  -- Operations convention: ① pre-SPLIT of yearly partitions is scheduled on the DBA operations calendar,
  --           ② the close batch detects and reports rows loaded into the MAXVALUE partition (safety net for a missed SPLIT, §7)
  (PARTITION p2026 VALUES LESS THAN ('202701'), PARTITION pmax VALUES LESS THAN (MAXVALUE));
-- ★ Summation convention (§3.0): no status filter when SUMming amounts from this table.
--   REVERSED originals stay in the sum and the negative reversal offsets them.

-- 5.6 1200% limit ledger (policy × agent, first-year window)
CREATE TABLE LIMIT_LEDGER (
  ledger_id        NUMBER PRIMARY KEY,
  policy_no        VARCHAR2(30) NOT NULL,
  agent_id         VARCHAR2(20) NOT NULL,
  contract_date    DATE NOT NULL,
  fy_start         DATE NOT NULL,          -- first-year start (window definition: LIMIT_RULE.fy_window_months)
  fy_end           DATE NOT NULL,
  monthly_premium  NUMBER(15) NOT NULL,    -- monthly-equivalent premium (updated with history on reduction)
  limit_multiple   NUMBER(6,2) NOT NULL,   -- 12.00 (no hardcoding: from LimitRule)
  limit_amount     NUMBER(15) NOT NULL,
  accum_paid       NUMBER(15) DEFAULT 0,   -- accumulation of limit-included items (decreased on clawback)
  rule_version_id  NUMBER NOT NULL,
  CONSTRAINT uq_limit UNIQUE (policy_no, agent_id)   -- also used to serialize the ledger-creation race (§6.1.6)
);
CREATE TABLE LIMIT_LEDGER_DTL (            -- the basis of the accumulation (increments/decrements per calc_id)
  ledger_id   NUMBER REFERENCES LIMIT_LEDGER,
  posting_seq NUMBER NOT NULL,             -- ★ new in v1.1: monotonically increasing posting sequence within the ledger (replay ordering key)
                                           --   numbered only while holding the ledger lock (FOR UPDATE) (MAX+1 or a sequence;
                                           --   the requirement is a per-ledger monotonic increase. Numbering outside the lock is forbidden)
  calc_id     NUMBER REFERENCES COMM_CALC,
  amount      NUMBER(15) NOT NULL,
  posted_at   TIMESTAMP DEFAULT SYSTIMESTAMP,  -- for reference only (must not be used as an ordering key)
  CONSTRAINT pk_limit_dtl PRIMARY KEY (ledger_id, posting_seq)
);
-- Invariant: accum_paid = Σ DTL.amount, and always accum_paid ≤ limit_amount

-- 5.7 Deferral schedule (from 2027.1)
CREATE TABLE DEFERRAL_SCHEDULE (
  schedule_id   NUMBER PRIMARY KEY,
  source_calc_id NUMBER NOT NULL REFERENCES COMM_CALC,
  policy_no     VARCHAR2(30) NOT NULL,
  agent_id      VARCHAR2(20) NOT NULL,
  due_ym        VARCHAR2(6) NOT NULL,
  amount        NUMBER(15) NOT NULL,
  pay_condition VARCHAR2(30) NOT NULL,   -- e.g., POLICY_INFORCE (paid if the policy is in force in that month)
  status        VARCHAR2(12) NOT NULL,   -- SCHEDULED/RELEASED/HELD/CANCELLED
  curve_version_id NUMBER NOT NULL
);
-- Invariant: sum of schedules for the same source_calc_id = deferred principal (deferral rounding convention §6.2)

-- 5.8 Clawback rules / clawback receivables
CREATE TABLE CLAWBACK_RULE (
  rule_id NUMBER PRIMARY KEY,
  product_key VARCHAR2(40), event_type VARCHAR2(20),
  from_installment NUMBER(3), to_installment NUMBER(3),  -- elapsed installment range
  clawback_pct NUMBER(9,6) NOT NULL,                     -- clawback rate (example: n% on surrender before the 13th installment)
  apply_from DATE NOT NULL, apply_to DATE DEFAULT DATE '9999-12-31'
);
CREATE TABLE CLAWBACK_RECEIVABLE (        -- if the clawback exceeds the payout, it becomes a receivable carried forward and offset
  receivable_id NUMBER PRIMARY KEY,
  agent_id VARCHAR2(20) NOT NULL,
  origin_calc_id NUMBER REFERENCES COMM_CALC,
  amount NUMBER(15) NOT NULL, remaining NUMBER(15) NOT NULL,
  status VARCHAR2(12) NOT NULL
);

-- 5.9 Close / adjustment
CREATE TABLE SETTLE_CLOSE (
  close_ym VARCHAR2(6) PRIMARY KEY,
  status   VARCHAR2(12) NOT NULL,  -- OPEN/CLOSING/CLOSED
  closed_at TIMESTAMP, closed_by VARCHAR2(20),   -- v1.2.5: closed_at only on the transition to CLOSED, the injected clock's value (§7)
  force_reason VARCHAR2(500)       -- v1.1.5: reason for a forced close. Facts subject to audit are persisted in business tables,
                                   --   not in batch metadata (BATCH_*) — batch tables are subject to retention periods and cleanup
);
CREATE TABLE ADJUSTMENT (          -- post-close correction: always attributed to the following month. v1.2.5: the attribution-month lookup is used only for the adjustment sum of the close-confirmation event (close and payout runs do not read it — §11)
  adj_id NUMBER PRIMARY KEY,
  target_calc_id NUMBER REFERENCES COMM_CALC,
  reason VARCHAR2(200) NOT NULL, amount NUMBER(15) NOT NULL,
  close_ym VARCHAR2(6) NOT NULL, approved_by VARCHAR2(20)
);
CREATE TABLE PAYOUT_STATEMENT (    -- v1.2.5 (V107): canonical payout statement — tax amounts and net amounts confirmed by the payout run. One per settlement row
  payout_statement_id NUMBER PRIMARY KEY,
  settlement_id NUMBER NOT NULL UNIQUE REFERENCES AGENT_SETTLEMENT,
  close_ym VARCHAR2(6) NOT NULL, run_seq NUMBER NOT NULL,
  recipient_type VARCHAR2(10) NOT NULL, recipient_id VARCHAR2(20) NOT NULL,
  payable NUMBER(15) NOT NULL, income_tax NUMBER(15) NOT NULL, local_tax NUMBER(15) NOT NULL, net_pay NUMBER(15) NOT NULL,
  paid_at TIMESTAMP WITH TIME ZONE NOT NULL   -- time of writing the settlement row / transition to PAID (injected clock). Not the time the transfer is executed
);

-- 5.11 Outbox (v1.2.5, V105·V106 — §6.8)
CREATE TABLE OUTBOX_EVENT (
  event_no NUMBER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,  -- load order
  event_id VARCHAR2(36) NOT NULL UNIQUE,     -- UUID, the portal's idempotency key
  seq NUMBER(19) UNIQUE,                     -- feed sequence — assigned by the assigner only to committed rows, once (NULL → value)
  event_type VARCHAR2(40) NOT NULL, schema_ver NUMBER(3) NOT NULL,
  aggregate_kind VARCHAR2(30) NOT NULL, aggregate_id VARCHAR2(64) NOT NULL,
  occurred_at VARCHAR2(40) NOT NULL,         -- the string exactly as it goes out on the feed (ISO-8601 with offset)
  payload CLOB NOT NULL,                     -- Oracle: IS JSON (V106)
  created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);                                           -- Oracle (V106): DELETE rejected, UPDATE rejected except the one-time seq assignment (trigger)
CREATE TABLE OUTBOX_SEQ (id NUMBER(1) PRIMARY KEY CHECK (id = 1), last_seq NUMBER(19) NOT NULL);  -- serializes assignment (FOR UPDATE), never decreases
```

### 5.10 Incentive Master (INCENTIVE_MST, Phase 13) — promoted to the main text in v1.1.7

Follows the same effective period + version + approval pattern as the rate master (§5.2). It differs from rates in that ① a target filter of
NULL = all (wildcard), and ② **the condition is an executable expression (SpEL)** — so this table is
the only surface in this system where "user input is executed", and conditions must be evaluated only inside a sandbox
(§3.6, Appendix B-16).

```sql
CREATE TABLE INCENTIVE_MST (
  incentive_id   NUMBER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
  incentive_cd   VARCHAR2(30) NOT NULL,     -- incentive identification code = key of the version/overlap invariant
  insurer_cd     VARCHAR2(10),              -- NULL = all insurers (wildcard)
  product_key    VARCHAR2(40),              -- NULL = all products
  channel        VARCHAR2(20),              -- NULL = all channels
  condition_expr VARCHAR2(1000) NOT NULL,   -- ★ SpEL condition. Sandbox-validated at registration, approval, and evaluation
  payout_kind    VARCHAR2(16) NOT NULL,     -- FIXED / PREMIUM_RATE (formula extensions as new kinds, B-12)
  fixed_amount   NUMBER(15),                -- FIXED only
  premium_rate   NUMBER(9,6),               -- PREMIUM_RATE only
  apply_from     DATE NOT NULL,
  apply_to       DATE DEFAULT DATE '9999-12-31' NOT NULL,
  version_no     NUMBER NOT NULL,
  status         VARCHAR2(10) NOT NULL,     -- DRAFT/ACTIVE/SUPERSEDED
  created_by/at, approved_by/at,
  CONSTRAINT ck_incentive_status CHECK (status IN ('DRAFT','ACTIVE','SUPERSEDED')),
  CONSTRAINT ck_incentive_kind   CHECK (payout_kind IN ('FIXED','PREMIUM_RATE'))
);
-- INCENTIVE_CHANGE_HIST: same shape as COMM_RATE_CHANGE_HIST (change_type TRIM/SUPERSEDE/ACTIVATE/DISCARD,
--   recorded by automatic diff derivation, so records cannot be missed). INCENTIVE_ADMIN_SEQ START 1000000 (a range separate from identity).
```

- **The overlap invariant holds only for the same `incentive_cd`** — different incentives apply cumulatively, so unlike rates
  this is not "single resolution per key"; instead **all** ACTIVE incentives effective on the reference date are looked up and each condition is evaluated.
- **Reproducibility**: the incentive versions actually used in a calculation are pinned in `COMM_CALC.rule_versions`, and the condition evaluation results (which
  incentive, true/false, amount) are recorded in `calc_trace` (§6.5 replay).
- **Final arbiter of concurrency**: the function-based unique index on ACTIVE rows `ux_incentive_active` (V102, done ahead of
  Phase 14) — same shape as the rate index `ux_comm_rate_active`, but since the target filters are nullable they are wrapped in `NVL('*')` (preventing duplicate all-target
  ACTIVE rows from slipping through NULL comparisons). The adapter translates an index violation into `IncentiveApprovalConflictException`
  (→409) as an explicit rejection. However, the index only arbitrates (key + start date) conflicts, so overlapping races with different start dates
  slip through, and **what prevents overpayment is not the index but the rule-resolution Ambiguous fail-fast** (done first in v1.1.9 —
  if two or more ACTIVE rows of the same `incentive_cd` apply on the reference date, the calculation is rejected, `IncentiveV2Step`). An approval-race
  **retry runner + deterministic interleaving IT** is, as with rates (§6.6), a follow-up task and purely a convenience improvement
  (specification: re-validate after acquiring the lock).

Supplementary tables (added at implementation): policy master / premium change history, agent master / affiliation and grade history, organization tree history, INBOUND statement ledger, reconciliation results, payout batches / transfer files (the settlement row `AGENT_SETTLEMENT` carries the payout run number `run_seq` — distinguishing N payout runs per month for the same close month, §7), withholding tax. **(v1.1.3)** The PK of `AGENT_ORG_HIST` is not (agent_id, apply_from) but **(agent_id, org_id, apply_from)** — one agent simultaneously has team + branch + headquarters affiliation rows at the same point in time, so agent_id + apply_from alone cannot hold the chain (applied in V8). New rows in the approval workflow are numbered not by identity but by a **dedicated sequence (separate range)** to prevent collisions with the seed/manual-registration range.

---

## 6. Core Engine Details

### 6.1 1200% Limit Engine — revised in v1.1 (separation of decision and posting + single lock scope)

1. **Applicability decision**: look up the `LimitRule` version by the policy's contract date. Application to GA→agent covers policies contracted on or after 2026-07-01 (earlier policies are not subject to it) — this branch is itself rule data.
2. **Ledger lookup/creation**: obtain the `LIMIT_LEDGER` by `(policy_no, agent_id)`. `limit_amount = monthly_premium × limit_multiple`.
3. **Summation target decision**: among this calculation's lines, sum only items with `COMM_TYPE_MST.limit_included='Y'` (the version effective on the reference date).
4. **Gate (LimitGateStep, order 50)**: `available = limit_amount − accum_paid`.
   - `calc_amount ≤ available` → pass.
   - Exceeded → depending on the `LIMIT_RULE.over_limit_action` parameter, ① cut the excess (CUT) or ② defer until after the first year ends (DEFER_AFTER_FY) **(currently behaves the same as cutting — no deferral schedule is created, §11 E1-1)**. Either way, `limit_cut_amt` and the reason are recorded.
   - **(v1.1.1) The code has no default for over-limit handling (intentional).** The handling method follows the rule data entirely, and a limit-subject calculation with `over_limit_action` missing **fails fast (calculation rejected + explicit error)**. A silent default leads directly to a compliance incident where payouts go out under the wrong policy, so it is forbidden. For operational seed data, **registering DEFER_AFTER_FY (block + defer) is recommended** from the standpoint of preventing regulatory violations, and checking the seed is an item on the deployment checklist (§11.3).
   - **The gate only decides. It does not post to the ledger.**
5. **Posting (LimitLedgerPoster, post-save hook)**: immediately after the calcId is fixed by saving `COMM_CALC`, the `CalcResultListener` chain in the same transaction posts to `LIMIT_LEDGER_DTL` (numbering posting_seq) and adds to the ledger's `accum_paid`. Because basis integrity requires a fixed calcId for posting, posting before the save is impossible, and this is why the hook stage exists.
6. **Lock convention (mandatory — the gate and posting are one serialization unit)**:
   - A limit-subject calculation transaction acquires the ledger row with `SELECT ... FOR UPDATE` **before the gate decision** (§4.2 [3.5]) and holds that lock **through the hook's posting until commit**. If another transaction reads the same ledger between the decision and the posting, a race window opens in which both pass the remaining limit and then each posts, exceeding the limit.
   - Concurrent arrival of new policies whose ledger row does not yet exist is serialized by retrying on a `uq_limit` unique constraint violation.
   - **Lock-holding scope in batches (v1.1.5)**: calculation and payout batch jobs run as **per-item business transactions** so that a chunk does not hold locks across multiple items (§4.2, §10 Phase 11). Regardless of chunk size, [3.5]–[5.5] closes within an item.
   - **The in-memory implementation cannot reproduce this race.** A concurrency test that actually races two concurrent calculations against the same ledger on the Oracle adapter (§8.6) is mandatory. Remember that passing the invariants in property tests (seed-fixed generators; jqwik before v1.2.2) is on a single-thread basis.
7. **Restoration on reversal entries (LimitReversalHook)**: handled not by re-deciding per type but by **posting the sign-inverted sum of the DTL amounts posted under the original calcId**. It is exact, and later changes to the definition of limit-included types do not affect past restorations.
8. **Recalculation trigger**: premium reduction/increase event → update `monthly_premium` and recalculate the limit. When the already-paid accumulation exceeds the new limit, the default behavior is **stopping future payouts** (naturally blocked by available=0), and if the regulatory interpretation is confirmed as "convert to clawback", it is absorbed as a new Step (§11.4).

Invariants (continuously verified by tests): **at any point in time `accum_paid ≤ limit_amount`**, `accum_paid = Σ LIMIT_LEDGER_DTL.amount`.

### 6.2 Deferral Engine (in preparation for 2027.1 — structure implemented)

- Deferral curves as data: `DEFERRAL_CURVE(curve_id, apply_from, apply_to)` + `DEFERRAL_CURVE_DTL(curve_id, year_no or month_no, pct)`. The 2027 curve (4 years) and the 2029 curve (7 years) are added only as records. (Completion criterion met: tests prove that the 7-year curve works by replacing data alone)
- During a new-business calculation, `DeferralSplitStep` (order 55) splits the first-year payout portion from the deferred portion and creates the `DEFERRAL_SCHEDULE`.
- **Deferral × limit interaction (confirmed in v1.1)**: the limit is consumed by the **total amount before the split** ("the schedule itself is a product of the limit", §2.2). Therefore the Step order is fixed as LimitGate(50) → DeferralSplit(55), and DEFERRED payouts that come due do not pass the gate again.
- **Deferral rounding (confirmed in v1.1)**: each point is allocated with truncation + the last point absorbs the remainder → the invariant "sum of schedules = deferred principal" holds for arbitrary amounts (proven by property tests).
- The monthly batch transitions due items (`due_ym = current month`) that meet `pay_condition` to RELEASED and feeds them into the payout pipeline. Surrender/lapse events set the remaining schedules to CANCELLED. **(v1.2.5) Operations assembly correction**: this cancellation (`DeferralCancelListener` — surrender, lapse, withdrawal; `DeferralReversalHook` — correction of the original) existed until E1 **only in the golden and test assemblies, not in the app assembly** — in operations, schedules never became CANCELLED. E1 added it to the app assembly and pinned by test that the set of post-save hook and reversal hook classes in the operations assembly = the golden assembly (all features) (`AssemblyParityTest`).
- Detailed rates (e.g., a monthly 0.8% maintenance commission) are not yet confirmed by official notice, so the completion criterion of this module is that **it must be possible to respond by registering curve data alone** (met).

### 6.3 Clawback Engine — revised in v1.1 (relocation of offset)

- Triggers: CANCEL/LAPSE/WITHDRAW/REDUCE events.
- Clawback amount = commission already paid (clawback-target types only) × clawback rate per elapsed installment range (`CLAWBACK_RULE`, version by contract date). **The clawback base (already paid) is the net already paid under the summation convention (§3.0).**
- Clawbacks are created as negative `COMM_CALC` records. **Offset is performed not by a pipeline Step but by `ClawbackOffsetService`** — offset belongs to the moment the monthly net amount is fixed, not to the moment of an individual calculation, and offset inside the pipeline carries a risk of double offset (v1.0's ClawbackOffsetStep was discarded).
- `ClawbackOffsetService` is invoked **per payout run**. If the payout cycle is confirmed as twice a month or similar (§11.12), the invocation point repeats for every payout run, so **it is not hard-coupled to the close procedure.**
- Any shortfall after offset becomes a `CLAWBACK_RECEIVABLE` receivable → automatically offset in later payout runs. Receivables of terminated agents go through a separate collection process (out of scope; data only is provided).
- When a clawback occurs, `LIMIT_LEDGER.accum_paid` is decreased (if within the first-year window) — whether limit headroom opens up again is also subject to confirmation of the regulatory interpretation → parameter.

### 6.4 Correction and Recalculation (Reversal & Rebook) — revised in v1.1

- On input errors / insurer correction statements / retroactive rule changes: transition the target `COMM_CALC` status to REVERSED + create a negative reversal record (referencing the original) + create a recalculation record with the new rule. **Under the summation convention (§3.0), the REVERSED original stays in the sum and the reversal offsets it** — correcting an original in PAID status uses the same formula.
- Items in an unclosed month are corrected within that month; items in a closed month are attributed to the following month via `ADJUSTMENT`. **Past closed numbers never change.** (v1.2.5) What enforces this in operations is the **attribution-month resolver** (`CloseYmResolver` — if the month of the event date is CLOSED, the next open month; `CalcPipelineConfig`) and the correction's `attributionFor`. The guard decorator `ClosedMonthGuardedCalcStore` (rejects new attribution to a CLOSED month) **(E2 ④) is also in the operations assembly** — the `CommCalcStore` bean = guard (outermost) → publishing decorator → Oracle, and the golden assembly and the outbox test engine use the same order (`AssemblyParityTest`). Behavior along paths that go through the resolver is unchanged (golden 24 and event golden identical), and there is one existing path that the guard blocks: `deferralReleaseJob` given a month that is already CLOSED (`DeferralReleaseService` inserts `DEFERRED` into that month — an insert that does not go through the resolver, E2 P10). This is a defect fix (hard to reach, since the close hook already releases due items). Since the guard is outermost, a rejected insert does not reach the outbox. On the event side, tests check that "no calculation or correction event for the same agent and the same month follows a confirmed statement" (`CloseInvariant` — in-memory and startup smoke).
- **Two layers of idempotency (confirmed in v1.1)**:
  - Record level (preventing duplicate reversals of the same original) is **guaranteed by the engine** via the REVERSED status check.
  - Value level (running the same recalculation twice yields the same net per recipient × type) is **proven by tests**.
  - Dedup per batch request (blocking duplicate execution of the same recalculation request) is **the responsibility of the batch layer** (request ID). Implementation: Spring Batch `JobInstance` (job name × identifying parameters) is used as the request store — resubmitting a completed instance is a no-op, resubmitting a failed instance restarts it (§10 Phase 11).

### 6.5 Audit Replay — new in v1.1 (procedure codified)

Procedure for recomputing the same amount as at the time for an arbitrary `calc_id`:

1. Restore the rule versions at the time from `COMM_CALC.rule_versions`, and the inputs from `calc_trace` and the input snapshot.
2. **Reconstruct the limit ledger state**: reproducing the limit gate line requires "the ledger state at calculation time". Reconstruct the ledger from `LIMIT_LEDGER_DTL` as **the sum up to just before the target calc's posting** and feed it to the pipeline.
3. **The ordering key for 'before' is `posting_seq` (monotonically increasing posting sequence). The `posted_at` timestamp is not used as an ordering key** — simultaneous-timestamp collisions and clock issues would make replay nondeterministic. This is why posting_seq exists in §5.6.
4. If the recomputed amount matches the original, the replay succeeds. On a mismatch, it is reported as detection of a retroactive rule change.

This procedure is codified identically in the audit response procedure manual (operations document).

### 6.6 Rule Version Replacement Semantics — confirmed in v1.1

- On approval, the existing ACTIVE version is handled only by **trimming** (ending it on the day before the new version's start date) or **SUPERSEDE** (fully covered).
- **Approvals that split the effective period of an existing version are explicitly rejected.** This keeps the invariant "no overlapping ACTIVE for the same key" simple.
- This invariant is enforced threefold: ① the approval workflow (application layer, for portability), ② an Oracle function-based unique index (on ACTIVE rows, DDL redundancy), ③ the close batch's Ambiguous (duplicate effective rules) detection report (safety net).
- **(v1.1.2) Approval concurrency convention**: two concurrent approvals for the same key can both pass the application-layer overlap check. The final arbiter is the **function-based unique index**, and the approval transaction treats an index violation not as an exception but as a normal race, handling it by **retry (re-read the latest state and re-validate) or explicit rejection** — the same as the `uq_limit` retry pattern. This race case is mandatory in the contract tests of the Oracle adapter (Phase 10b).
- **(v1.1.2) Change audit of trimming**: trimming is an exceptional change that UPDATEs the `apply_to` of an existing ACTIVE row, so a **rule change history** containing the before/after values, approver, and time must always be kept (a separate history table or the audit log). Calculation reproducibility itself is already guaranteed by freezing `COMM_CALC.rule_versions`, but answering "what did the rule table look like last month" (the system time axis, §3.1) requires the change history.
- **(v1.1.3) Standard for deriving audit records**: the change history is derived **automatically from the before/after diff of the repository replace**, not from the good intentions of the service layer (TRIM/SUPERSEDE/ACTIVATE/DISCARD) — missing records must be structurally impossible. Under a race, the loser's duplicate trimming is a no-op diff, so the history is not duplicated. This pattern will be applied identically to the management functions of other masters in the future (deferral curves, clawback rates, etc.).
- **(v1.1.3) Approver identity requirement**: paths where the approver in the audit history is left as "system" are allowed only in seeds/tests. Operational approval paths must pass the actual approver identifier.
- **(v1.1.3) Consistency criterion and governance for approval races**: the consistency criterion for resolving races is **serial-execution equivalence** — the final state of concurrent approvals A and B must equal the result of sequential execution (A→B or B→A), and any other state (overlapping ACTIVE, lost approval) is a defect. However, near-simultaneous approvals may signal a process anomaly, so a **report that detects same-key races within a short time (SUPERSEDE right after ACTIVATE) in the change history and alerts administrators** is provided as an operations feature (close report or admin screen).

---

### 6.7 Comparison-Disclosure Sales Commission Grade and Rank API — new in v1.2.2 (Phase E3)

**Contract.** The canonical source is `contracts/api/v1/engine-disclosure.openapi.yaml` in the `ga-disclosure` repository (**1.2.1**, v1.2.4); it is copied into this repository's `contracts/shared/ga-disclosure/api/v1/` (v1.2.5 — per-upstream directory, §6.8) and checked for consistency using that directory's `CHECKSUMS` (sha256sum format) and `UPSTREAM` (first line: source commit; following lines: list of copied files) (`verifyContractChecksums` in `check`, `verifyUpstreamContract` in CI). The contract is not edited in this repository — if something needs to change, ga-disclosure changes first. Order of change: (i) merge the contract PR in ga-disclosure → (ii) update UPSTREAM to **that merge commit** (reachable from ga-disclosure `main`) + re-check the copies and CHECKSUMS.
- **Format notation (1.2.1)**: the contract has no `format` keyword — `asOfDate` `^\d{4}-\d{2}-\d{2}$` and `generatedAt` RFC 3339 (seconds required, optional fractional seconds of 1–9 digits, `Z` or `±hh:mm`) are expressed with `pattern`. The engine's `generatedAt` (`ISO_OFFSET_DATE_TIME`, truncated to seconds) passes even with a zero-second, UTC clock (`EngineDisclosureContractTest`). The product key column width of snapshot items is also 40 (V104).
- **Product key rule (1.2.0)**: `productKey` = `{insurerCode}:{product code}`, `^[A-Z0-9][A-Z0-9-]{0,7}:[A-Za-z0-9][A-Za-z0-9._-]{0,30}$`, 40 characters or fewer. `insurerCode` `^[A-Z0-9][A-Z0-9-]{0,7}$`. Violations are 400 `INVALID_REQUEST` (not truncated). Tests compare the engine's request validation against the contract schema's verdict to confirm they agree. Temporarily registered products do not appear in requests (the consumer labels them locally).
- `POST /internal/v1/disclosure/commission-grades` `{tenantId, asOfDate, productGroupCode, products[{productKey, insurerCode}]}` → `{snapshotId, gradingPolicyVersionId, rankingPolicyVersionId, tieBreak, basis{groupAvgSource, period, groupPopulation}, results[], generatedAt}`. `results[]` is a oneOf branching on status — `OK` carries all of `ratioToAvg` (string), `grade`, `gradeLabel`, `gradeOrdinal`, `rankInSet`, `tie`; `UNAVAILABLE` carries only `reason` (the 6 fields are **absent**, not null).
- `GET …/{snapshotId}` → **byte-identical** to the issuing response. The response contains no raw commission rate figures.
- Errors (Problem `{code, message}`): 400 `INVALID_REQUEST`·`AS_OF_IN_FUTURE`·`UNKNOWN_PRODUCT_GROUP` / 401 `UNAUTHENTICATED` / 403 `TENANT_MISMATCH` (POST — request tenantId ≠ instance) · 403 `FORBIDDEN` (token valid but authorization denied, security chain) / 404 `SNAPSHOT_NOT_FOUND` (absent or another tenant's snapshot) / 409 `AMBIGUOUS_POLICY` / 422 `NO_POLICY`·`POLICY_SELF_CHECK_FAILED`·`INVALID_POLICY` / 500 `SNAPSHOT_INTEGRITY`. This mapping is specific to this controller (the global handler's Ambiguous=422 stays as is for the existing APIs).

**Policies are entirely data (§3.1, Appendix B-11).** Example grading policy body (values are fictional):
```json
{ "measureKey": "FY_SALES_COMMISSION_RATE", "measureParams": { "commType": "FY_COMM", "installmentNo": null },
  "population": { "groupCodeSystem": "PG-V1", "scope": "GA_CONTRACTED_PRODUCTS", "minPopulation": 3 },
  "period": { "kind": "TRAILING_QUARTER", "asOfRule": "REQUEST_DATE" }, "asOfFutureDaysAllowed": 0,
  "ratioScale": 2, "ratioRounding": "HALF_UP",
  "grades": [ { "code": "VERY_HIGH", "label": "매우높음", "ordinal": 5, "minRatio": "1.30", "minInclusive": false },
              { "code": "HIGH", "label": "높음", "ordinal": 4, "minRatio": "1.10", "minInclusive": false, "maxRatio": "1.30", "maxInclusive": true },
              "…", { "code": "VERY_LOW", "label": "매우낮음", "ordinal": 1, "maxRatio": "0.70", "maxInclusive": true } ],
  "unavailableReasons": { "NOT_IN_GROUP": "NOT_IN_GROUP", "NO_RATE_DATA": "NO_RATE_DATA", "OUTSIDE_PERIOD": "OUTSIDE_PERIOD",
                          "INSUFFICIENT_POPULATION": "INSUFFICIENT_POPULATION" } }
```
- Boundaries are written as **decimal strings together with inclusion flags** (numeric JSON rejected, no default for inclusion). On load: exactly one band without a lower bound and exactly one without an upper bound, adjacent boundaries have equal values and complementary inclusion (both excluded = gap, both included = overlap), ordinals strictly increase in ratio order, codes and ordinals are unique, labels are required. Violations are `INVALID_POLICY` (no snapshot issued).
- `unavailableReasons` is a map of **cause (a situation the engine decides, closed vocabulary) → reason code (data)**. The list form in the directive's example was changed to a map so that reason code strings are not kept as code constants.
- Ranking policy body: `{ "tieBreak": "SHARED_RANK" }` or `{ "tieBreak": "STRICT", "secondaryKeys": ["MEASURE_ASC", "PRODUCT_KEY_ASC"] }` (STRICT requires `PRODUCT_KEY_ASC` — guarantees a total order).
- Both policies are version records with `apply_from`/`apply_to` (both ends inclusive, default 9999-12-31) and `status` (DRAFT/ACTIVE/SUPERSEDED), and are resolved to a single record by the request's `asOfDate` (2 records → 409, 0 records → 422). Duplicate ACTIVE rows with the same `apply_from` are blocked as a second line by a function-based unique index (V103, same structure as §6.6).

**Measurement (population) — example measure.** A `measureKey → measure function` registry (an explicit list in the app wiring). v1 `FY_SALES_COMMISSION_RATE`: reads the revenue-side rate `COMM_RATE(direction=INBOUND, comm_type=measureParams.commType, installment_no=measureParams.installmentNo)` of product group members (`DISC_PRODUCT_GROUP_MEMBER` — mapping external `productKey` → `(insurer_cd, product_key)`) as of **measurement reference date = the last day of the quarter preceding the quarter that contains the request reference date** (`basis.period` = that quarter, e.g., 2026-09-23 → 2026Q2). Two or more rates effective on the reference date are Ambiguous. If a rate exists but is not effective on that date, `OUTSIDE_PERIOD`; if none exists at all, `NO_RATE_DATA`; if not a member, `NOT_IN_GROUP`. The exact definition of the sales commission rate (population, period) is **an externally confirmed fact (§11 #13), so this function is an example and is meant to be replaced**. Temporarily registered products do not appear in requests (ga-disclosure contract 1.2.0 — the consumer labels them locally as `UNAVAILABLE(TEMP_PRODUCT, source=LOCAL)`). Hence the engine has no temporary-registration cause, and putting `TEMP_PRODUCT` into the policy data is `INVALID_POLICY` as an unknown cause (E3.1 — E3's "data list compatibility" item discarded).

**Ratio to average (Appendix B-1 revised).** Population = member products that have a measured value on the measurement reference date (n = `groupPopulation`). If n < `minPopulation` or Σ=0, the whole request is `INSUFFICIENT_POPULATION`. The ratio = measured value × n / Σ is produced in `RatioToAvg` with **one division and one rounding** (scale and mode come from the policy), and the `toPlainString()` string is created once at that point (the average is not rounded separately, so there is no double rounding). No later path turns this string back into a number to reformat it. **The grade is decided on the rounded ratio** — so that the printed ratio and the grade never disagree (e.g., "1.30" never becomes 매우높음 (very high)). `TODO(confirm#13)`: whether to decide the grade on the raw ratio is an externally confirmed matter.

**Rank within the set.** Only the OK items of the request set, in **ascending order of the raw measured value** (the lower the commission, the higher the rank, i.e., rank 1 — per the regulatory definition). SHARED_RANK = competition ranking (1-2-2-4), all tied values tie; STRICT = a 1..m permutation by secondary keys, and items separated that way are also tie=true. Measured value↑ ⇒ ratio↑ ⇒ rounded ratio non-decreasing ⇒ grade non-decreasing, so monotonicity holds by construction, and after the response is generated a **self-check** (rank ascending ⇒ ordinal non-decreasing, same rank ⇒ same ordinal, rank structure) confirms it again — a failure signals contradictory policy data, so instead of 500 no snapshot is created and the result is 422. Response order: OK items in rank order (ties by productKey), followed by UNAVAILABLE items in request order.

**Snapshot — immutable, re-fetch byte-identical.** Within the issuing transaction, a number is assigned (**a single Oracle SEQUENCE `DISC_GRADE_SNAPSHOT_NO`**, V13 — no locking, races, or retries; `GRD-{yyyyMMdd}-{7 digits}`; numbers do not restart each day and may have gaps; the range 1000000–9999999 does not overlap the 6-digit numbers of the V12 per-date counter; the date comes from the injected Asia/Seoul clock; v1.2.3) → the response is canonicalized with **RFC 8785 JCS** (the `Jcs` entry point — the same bytes as ga-disclosure's `platform-canonical` vectors, unpaired surrogates rejected) → SHA-256 → stored in `DISC_GRADE_SNAPSHOT(response_canonical, response_sha256, …)` + `DISC_GRADE_SNAPSHOT_ITEM` (ratio_to_avg as the original VARCHAR2 text, the OK/UNAVAILABLE field shape enforced by CHECK). The POST response body is these canonical bytes, and GET checks the hash of the stored original and returns it **as is** (no re-serialization; on a mismatch, 500 `SNAPSHOT_INTEGRITY`). Even if policies or rates change, old snapshots are not recalculated. UPDATE and DELETE on `DISC_GRADE_SNAPSHOT(_ITEM)` are **rejected by DB triggers** (V103, ORA-20301/20302 — the first immutability in the engine enforced by the DB; the ban on UPDATE of COMM_CALC is convention B-2).

**Security and isolation.** `/internal/**` is a stateless chain separate from the human-account (Basic) chain and accepts only `Authorization: Bearer <service token>` — the configuration holds only the token's SHA-256 (`app.internal.service-token-sha256`), compared in constant time (operations also use mTLS). This setting is a **hash list** (1–2 entries, comma-separated) — during a token rotation overlap period both the current and the next token are accepted, and the old hash is removed when the rotation ends (E3.1, E3 acceptance review §4-4). It is compared against the whole list without short-circuiting. The engine is a per-tenant instance, so if `app.tenant-id` differs from the request's `tenantId`, the result is 403, and this check is the entirety of isolation. On re-fetch, if the stored `tenant_id` is not the instance's tenant, 404. Neither setting has a default; if either is empty, startup fails (B-13).

### 6.8 Outbox and Event Feed — new in v1.2.5 (Phase E1)

The engine is the **provider** of the portal (`ga-agent-portal`) event contract v1.1.1 (copy in `contracts/shared/ga-agent-portal/`). Facts are loaded into `OUTBOX_EVENT` (§5.11) without a sequence inside the business transaction, and sequences are attached only to committed rows. The module `commission-outbox` (no Spring dependency) holds the event builders, the `OutboxStore` port, the assigner, and the **repository decorators**; business modules do not know about the outbox.

**Sequence (zero gaps) — why not a SEQUENCE, and the proof.** Oracle SEQUENCE and IDENTITY discard numbers on rollback and through caching, so they cannot be used as feed sequences (the portal waits on a gap as "an event that has not arrived yet"). Therefore the load order (`event_no` IDENTITY) and the feed sequence (`seq`) are separated. ① The business transaction inserts without `seq`, so a rolled-back row consumes no number. ② The assigner locks the single `OUTBOX_SEQ` row with `FOR UPDATE` and assigns numbers starting from `last_seq + 1`, in `event_no` order, only to **visible (= committed)** unassigned rows — a row committed late receives a larger number in the next assignment, so it never slots in between numbers already issued. Two overlapping assignments are serialized on the sequence row lock (Oracle race IT). ③ For two facts on the same ledger or the same agent, engine locks (ledger `FOR UPDATE`, per-recipient payout tx) determine the commit order; the earlier transaction's rows received their `event_no` before its commit, and the later transaction proceeds only after that commit, so `event_no` order = commit order = causal order. The order between two facts that do not share the same lock is not guaranteed (the portal does not rely on it). **Single-instance assumption**: IDENTITY monotonicity (an `event_no` received earlier is smaller) holds on a single instance — RAC (per-instance caches) is out of scope. The H2 tier uses the same common V105; the in-memory reference uses `synchronized`.

**Retention.** `OUTBOX_EVENT` is append-only — DELETE is rejected by a trigger, and UPDATE is allowed only once for `seq` NULL → value (V106). E1 does not delete rows (portal rebuilds re-read from `afterSeq=0`). Retention periods and archiving (if rows are deleted: a dedicated role and trigger change, and a rebuild path for the deleted range) are an operations decision (§12 9).

**Publishing points (no change to business code — two kinds: repository decorators and listener ports).** A decorator wraps the port, so same call = same transaction (business rollback → zero events, C3). Assembly happens in one place, `PublishingStores.wrap` (shared by the app, Oracle IT, and outbox tests).

| Event | Publishing point | Transaction | Notes |
|---|---|---|---|
| `CommissionCalculated` | `OutboxCommCalcStore.insert` — AGENT recipients only | calculation, correction reversal, deferral coming due (all three insert paths) | `amount`=`calc_amount`, `limitCutAmt`, `limitCutPolicy`=that type's `action` in the gate trace (only when the cut > 0), `basis` (rate, payout rate, installment number only when present, `ruleVersionIds`=`type:key:ref`) |
| `PolicyEventObserved` | `OutboxPolicyEventStore.upsertByKey` — when it is a new row of a mapped type | receipt tx | CANCEL·LAPSE·WITHDRAW·REDUCE·REVIVE→REINSTATE (a single table in the builder) |
| `LimitLedgerOpened` | `OutboxLimitLedgerStore.getOrCreate` — when the preceding `find` was empty and no opening event exists yet | calculation tx (ledger `FOR UPDATE`) | in a concurrent opening, the loser checks the outbox after the winner commits, so it is not emitted twice |
| `LimitLedgerPosted` | the same decorator's `save` — for each new tail posting | the `save`'s tx | `postingSeq`=tail sequence (same value as Oracle's `MAX+1` numbering), `accumAfter`=accumulation right after that posting |
| `LimitLedgerRebased` | the same `save` — when `limit_amount` differs from when it was loaded (before the postings of the same `save`) | recalculation tx | `postingSeq`=last posting right before the recalculation (omitted if none, contract L1), `reason` `PREMIUM_REDUCED`/`PREMIUM_INCREASED` |
| `DeferralScheduled` | `OutboxDeferralScheduleStore.createAll` | calculation tx | `reason`=`CURVE` (the only schedule the engine creates — §11) |
| `DeferralStatusChanged` | the same decorator's `replace(entry, reason)` — when the status changes; the previous status is read in the same tx | coming-due, surrender, and correction tx | reason closed vocabulary `DeferralTransitionReason`: `DUE_RELEASED`·`POLICY_NOT_INFORCE`·`POLICY_TERMINATED`·`SOURCE_REVERSED`. `replace(entry)` without a reason is rejected |
| `ClawbackReceivableUpdated` | `OutboxClawbackReceivableStore.create`·`save` | payout item tx | a receivable without `originCalcId` violates the contract → failure |
| `AdjustmentPosted` | `OutboxAdjustmentStore.create` — when the recipient of the target calculation is AGENT | caller's tx | a correction without a target violates the contract → failure (`AdjustmentService.adjust` always passes the target) |
| `StatementConfirmed` | listener port `MonthCloseListener.onConfirmed` — after ④ of `MonthCloseService.close` | close tx | for each agent with records **or** corrections in that month (in ID order — E1 acceptance review Q1): `gross`·`clawbackOffset`·`adjustment` (sum of `ADJUSTMENT` attributed to that month — one record even for an agent with corrections only)·`closedAt`=`SETTLE_CLOSE.closed_at` |
| `PayoutCompleted` | listener port `PayoutListener.onSettled` — after saving `PAYOUT_STATEMENT` | payout item tx | AGENT only. `paidAt`=`paid_at`, `withholdingTax`=income tax + local tax, `net`=`paidAmount`=`net_pay`. No `adjustment` (§11) |
| `StatementEstimated` (E2 ①) | job `statementEstimateJob` (parameters `request_id`, optional `close_ym` — if absent, today's attribution month. The once-nightly run is an external cron — the engine has no scheduler) → `EstimatePublisher` | ⓐ repeated assignment tx ⓑ read `headSeq` ⓒ read-only computation tx ⓓ load tx per agent | **the payout run's dry-run as a pure calculator** (`StatementEstimator` — its only input is `EstimateReads`, a bundle of query method references; it has no write methods in hand): that month's AGENT records + **preview of deferrals coming due** (the same decision as the close hook, `DeferralReleaseService.releasable` — P1) → `gross`·`clawbackOffset` (same classification as StatementConfirmed), sum of corrections → `adjustment`, net amount → `PayoutMath` (same formula as the payout run) + **offset preview** (`ClawbackOffsetService.previewConsume` — the same iteration as `consume`, no writes — P2) → `withholdingTax`·`net`. The tax rate policy is the same bean as the payout run's (P9). `basisSeq` = maximum sequence after assignment — a **lower bound** (all facts up to that sequence are reflected; facts committed later may be partially reflected — Q3). `asOf` = the injected clock. Closed and closing months are not emitted (`SKIPPED_CLOSED_MONTH`). It writes nothing to business tables (C1 — `DryRunNoWriteOracleIT`) and does not lock ledger, calculation, or receivable rows (C3). Estimated = actual (if closed and paid out without new business input, the five fields are equal — C2 `EstimateEqualsActualGoldenTest`) |
| `AgentSnapshot` | `AgentSnapshotPublisher.publish` — job `outboxAgentSnapshotJob` | item tx per agent | see "Two jobs" below |

**Aggregation definitions (same convention as §3.0).** `gross` = Σ non-CLAWBACK lines, `clawbackOffset` = Σ CLAWBACK lines — both via `NetAmountCalculator` and **summed over all statuses** (no double deduction even when a REVERSED original and its reversal coexist). The engine's `COMM_CALC` has no ADJUSTMENT type. The payout event builder asserts `gross + clawbackOffset = gross_net` (settlement row) — if the portal's sum and the engine's net amount disagree, it stops at publishing time.

**ORG excluded.** Calculations, settlements, and correction targets of ORG recipients appear in no event (the builder also rejects ORG calculation records). Limits, deferrals, and receivables are AGENT-only in the engine to begin with.

**Feed (`GET /internal/v1/events`, portal contract `engine-internal.openapi.yaml` 1.1.0).** `afterSeq` (required, ≥ 0), `limit` (1–1000, default 200). One call = two transactions: ① in a write transaction, sequences are assigned to committed unassigned rows (up to `app.outbox.assign-batch-size`, default 500 — lazy assignment); ② in a read-only transaction, `headSeq` and `seq > afterSeq`. Response `{tenantId = app.tenant-id, events, nextSeq (last seq, or afterSeq if none), headSeq, schemaVersion: 1}`. `afterSeq > headSeq` → 422 `AFTER_BEYOND_HEAD`, parameter format/range → 400 `INVALID_REQUEST`, token → 401 (service token chain). Unassigned rows do not appear (rows beyond the assignment cap or committed after assignment come in the next call with contiguous numbers).

**Two jobs (the engine has no scheduler or CLI — only via the batch launch endpoint).** `outboxSequenceJob` (`request_id`) — repeats the same assignment as the feed until it yields 0 rows (one iteration = its own transaction). `outboxAgentSnapshotJob` (`as_of`, `request_id`) — one transaction per agent emitting `AgentSnapshot` (`name`=`agent_name`, `grade`=grade on the reference date (when present), `orgPath`=`AgentDirectory.orgChainOf` from top to bottom as `/HQ/BRANCH/TEAM`, `status`=whether dismissed on the reference date `ACTIVE`/`DISMISSED`, `validFrom`=the latest of the appointment date, the start date of the history effective on the reference date, and the dismissal date; no `role` or `nameMasked`). An agent without an affiliation cannot produce the contract's `orgPath`, so it is quarantined and reported (`COMPLETED_WITH_SKIPS`). The engine has no master write path, so there is no publishing at change time.

**Provider proof (tests).** ① Global validator — the test fixture `ValidatingOutboxStore` validates every event loaded during tests against the portal contract (Envelope + per-type payload copies) on the spot (violation = that test fails), and records type counts per task in `build/test-results/outbox-validation/<task>.xml` (CI artifact). ② Event golden — all 24 cases of the golden set are rerun through the engine with decorators inserted to check that business results are unchanged (0 comparator mismatches), and the event sequences of cases 01·04·07·11 and of the close/payout cycle are byte-compared with `commission-outbox/src/test/resources/outbox-golden/*.jsonl` (update with `-PupdateOutboxGolden`, commit after review). ③ Comparison with portal Appendix A-1 — for the same input, the amounts differ (engine seed FY 1,890,000 vs. sample 2,800,000), and the type order differs in only one place: "the two lines of one calculation do not alternate calculation and posting" (`PortalA1ComparisonTest`).

**Close invariant (event side).** In feed order, no `CommissionCalculated` or `AdjustmentPosted` for the same agent and same month follows `StatementConfirmed(agentId, closeYm)` (§6.4). Repeating a payout run in the same month produces no events if there is no new settlement (idempotent by whether the settlement row has been included).

**Statement query (E2 ③, `GET /internal/v1/agents/{agentId}/statements/{ym}?mode=`).** One read-only transaction, zero publishing (a query is not an event — injection S15). `ESTIMATE` = run the calculator from ① now (shape of `StatementEstimated`, `basisSeq` = `headSeq` read without assignment — Q3, `asOf` = clock). Closed and closing months are 422 `MONTH_CLOSED`. A known agent not targeted in that month gets a zero-won statement (200 — the contract has no 404 for that case). `CONFIRMED` = the `StatementConfirmed` shape built from business tables + the same publishing builder (Q4); if the payout run has finished, `withholdingTax`·`net` get the sum of that month's `PAYOUT_STATEMENT` (R8); before close, 404 `STATEMENT_NOT_CONFIRMED`. Format errors 400 `INVALID_REQUEST`, an agent unknown to the engine 404 `AGENT_NOT_FOUND`. Field-by-field equal to the events published for the same month (`StatementQueryOracleIT`).

**Simulation (E2 ②, `POST /internal/v1/simulations/new-contract`·`/policy-event`, portal contract v1.3).** The existing `CommissionCalculator.dryRun`, when run on the operational repositories, locks the ledger (`FOR UPDATE`) and INSERTs it if absent, and since the operational beans are publishing decorators it even loads `LimitLedgerOpened` (E2 P3) — so it is not used. Instead, a second `CommissionCalculator`, assembled from **the same Step list and the same post-save hooks as the operational calculator** (`CalcSteps.standard`·`listeners` — the app assembly and the simulator use the same function; assembly parity test, injection S12) on an **overlay repository** (the `simulation` package of `commission-settlement`, lifetime of one simulation), `process`es virtual events. The overlay receives only the original repositories' **bundle of read method references** (`SimulationReads`) — it does not receive repository objects, so the publishing decorators' write paths are not in hand (by constructor signature — Q1). Inserts, postings, recalculations, schedule creation, and state transitions happen only in memory (negative IDs — zero consumption of the original IDENTITY), and the ledger is read once with the **non-locking lookup** `LimitLedgerStore.peek` (Oracle `peekByKey`, no `FOR UPDATE` — injection S7) to make a **copy** (the in-memory original repository may hand out the same instance). The overlay has no closed-month guard — since inserts into the original repositories are zero, it can never be reached (C1).

| Endpoint | Virtual events | Definition of response fields |
|---|---|---|
| new-contract | virtual policy number `SIM-NEW-CONTRACT`. Installment 1 = `NEW` (contract date), 2..N = `PAYMENT` (contract date + n−1 months) — as long as an OUTBOUND FY rate exists for that installment, capped by `paymentTermMonths` (safety cap 120). If `insurerCd` is absent, the insurer is used only when exactly one insurer has an ACTIVE FY rate for that product on the contract date (`RuleRepository.insurersWithRate`); otherwise 422 `INSURER_AMBIGUOUS` (including 0 insurers). If `incentiveCodes` are given, only those codes from the V2 incentive master | `fyCommissionByInstallment[n]` = the stored AGENT `FY_COMM` amount of that installment's event (after limit cut and deferral split = `CommissionCalculated.amount`; 0 if there is no stored line). `incentives` = AGENT `INCENTIVE` lines summed per incentive code (code = the line's incentive ID → V2 master code; `amount` after cut, `limitCutAmt` the cut amount, `limitIncluded` the type attribute). `limitAmount`·`projectedAccum` = the overlay ledger's limit and the accumulation after the last installment — both absent for a policy that opens no ledger (contract date without a limit rule). `deferral.firstYearPayout` = Σ`fyCommissionByInstallment` + Σ`incentives.amount` (the policy's total immediate payout), `schedule` = the SCHEDULED schedules created by the overlay summed per `dueYm` (ascending). `generatedAt` = the injected clock (seconds) |
| policy-event | 404 `POLICY_NOT_FOUND` if the agent has no AGENT calculation record. Insurer, product, contract date, and monthly premium are read from the record's earliest event (`POLICY_EVENT`); `eventDate` = `asOfDate`, installment number = `elapsedInstallments` (if absent, `ClawbackStep`'s month-count approximation). `REINSTATE` → `REVIVE`; `REDUCE` requires `newMonthlyPremium` (400 if absent — property `new_monthly_premium`); `PAYMENT_OVERDUE` → 422 `UNSUPPORTED_EVENT_TYPE` | `clawbackAmount` = sum of AGENT `CLAWBACK` lines (same sign as `CommissionCalculated` — negative for surrender, positive for re-payment on reinstatement). `offsetAgainstMonth` = `gross + clawbackOffset` of the attribution month (resolver `CloseYmResolver`), computed by the calculator from ① **on the original repositories (before) and on the overlay (after)** respectively. `receivableRemaining` = sum of open receivable balances + max(0, −after) (the payout run turns that negative net into a receivable). `cancelledDeferrals` = the SCHEDULED items in the original repositories that this simulation cancelled (what `DeferralCancelListener` does in operations — in scheduleId order) |

`ruleSnapshotId` (Q2) = `"RS-"` + the first 32 characters (lowercase hex) of SHA-256(the UTF-8 of the rule version references `ruleType|key|versionRef` used by all recipient contexts of that simulation, sorted, deduplicated, and joined with newlines). Its scope is **rules only** — the same value means the same bundle of rule versions; reproducing amounts requires the rules + the ledger and record state at that time. Independent of the clock (injection S2). The controller serializes the response directly in contract field order, and optional fields are omitted when they have no value. One read-only transaction per call (`SimulationConfig`), zero saves. Proofs: amount comparison with actual processing (`DryRunSimulatorTest` — equal to the FY, ledger, schedule, clawback, and attribution-month net obtained by running the same virtual events through another engine all the way to storage), 12 business tables and the outbox identical before and after (`DryRunNoWriteOracleIT` — injection S6), completes within 5 seconds while another connection holds ledger, calculation, receivable, deferral, and close rows with `FOR UPDATE` (`DryRunLockOracleIT`), byte-identical across 2 runs with a fixed clock (`SimulationDeterminismIT`), contract schema (`SimulationContractTest`).

**Local stack (E2 ⑤ — plan P7·P8, approval Q6).** The operations jar cannot produce the same bytes (random eventId, system clock). So the scenario runner lives in **a module outside the operations jar**, `commission-localstack`, which boots the app assembly (`CommissionApplication`) as is but swaps only two beans — `clock` = step clock (the five times of Q9), `outboxPublisher` = sequential eventId. The step clock, scenarios (`A1Scenario`·`A1ScenarioRunner`), and sequential IDs exist only in the `commission-outbox` testFixtures. Execution order: Flyway (default profile) → seed `localstack/seed/a1.sql` (= `RuleFixtures.standardRules()` + standard agents and organizations; rate IDs 1001~, limit rule 1, curves 1 and 2 stated explicitly — they appear in `ruleVersionIds`) → new-business calculation (service — there is no operational path for policy events, E1 P12) → `monthCloseJob` → `payoutRunJob` → surrender calculation → `statementEstimateJob` (operational job) → exit. Then the regular app starts and serves the feed. `localstack/up.sh` (compose — the Oracle Free image shares the single `oracle-free-image` digest in the version catalog `gradle/libs.versions.toml` with the two Testcontainers ITs) generates a random token and DB password every time, only under `.state/`, and passes only the token's SHA-256 to the app. Proofs: CI job `로컬 스택(A-1 피드)` (local stack (A-1 feed)) — the whole feed = `a1_portal_150000_e2.jsonl` (bytes, C8); `LocalStackSeedIT` — the seed's rule and organization lookup grid = the in-memory fixture (injection S11 fails on both).

**Snapshot bootstrap (E2 ⑥ — not included, approval Q5 "drop it and report if the cost grows").** No job was built to emit the current state as v1 events for a tenant with an empty outbox. There is no port that reads all open deferrals and receivables (the deferral repository only supports lookups by due month, policy, and original calculation; receivables by agent), that port would have to be added to all three repositories (in-memory, Oracle, decorator), and the `occurredAt` of synthesized facts would be the bootstrap time rather than the business time (losing what the step clock Q9 meant to preserve). Consumers that currently read the feed from the beginning (portal Phase 2, local stack) are well served by replaying from `afterSeq=0`. Populated tenants and past months' statement history cannot be expressed in v1, so they are handed to the portal as the contract v2 `TenantSnapshot` item (§11 E2-3).

**Value formats.** Amounts are integer won; rates, payout rates, and multiples are the contract's `decimalString` (trailing zeros removed; more than 6 decimal places or negative values fail — no rounding); dates ISO; close months `yyyyMM`; times in seconds with offset (the injected app-wide clock, Asia/Seoul).

## 7. Close, Payout, and Reconciliation Process (Monthly Cycle) — v1.1 Revision

```
D-5~D-1  INBOUND statement receipt cutoff → reconciliation batch (own calculation vs insurer statement, discrepancy report/hold handling)
D-Day    Month-close batch (MonthCloseService):
         ① Confirm the current month's event calculations are complete (0 unprocessed PENDING)
         ② RELEASE deferral items that have come due (DeferralReleaseService)
         ③ 1200% limit full verification report (confirm 0 invariant violations)
         ④ CALCULATED → CONFIRMED, SETTLE_CLOSE = CLOSED
D+1~     Payout run (PayoutService — can repeat one or more times a month depending on the payout-cycle parameter):
         ① Clawback/receivable offset (ClawbackOffsetService — invoked per payout run, §6.3)
         ② Withholding tax calculation (based on net payout after offset, WithholdingTaxPolicy)
         ③ Generate transfer file → transition to PAID → persist payout statement (PAYOUT_STATEMENT, v1.2.5) → payout statement data
```

- Change from v1.0: offset and withholding tax moved from the close batch to the **payout run**. Even if the payout cycle is confirmed as twice a month (§11.12), repeating the payout run (rounds distinguished by the settlement row's `run_seq`) is all it takes.
- The close transitions to CLOSED only when every checklist item passes. A forced close requires permission plus a recorded reason — **(v1.1.5) the reason must be persisted in `SETTLE_CLOSE.force_reason` (a business table)**; leaving it only in the batch execution context (BATCH_*) is insufficient (batch metadata is subject to retention limits and cleanup).
- **Three close reports (v1.1.5 — a non-blocking safety net; the job is COMPLETED)**: ① rule data completeness (missing required values, overlapping ACTIVE Ambiguous — **for both the rate and incentive masters**, including payout-rate overlap, §5.2/§6.6; incentive overlap detection added in the v1.1.8 Phase 14 prerequisites), ② detection of rows loaded into the MAXVALUE partition (safety net for a missed SPLIT, §5.5), ③ approval race detection (SUPERSEDE immediately after ACTIVATE, §6.6). Findings do not block the close but are left in the job context / admin screen.
- Reconciliation discrepancies are categorized (rate difference / missing / timing difference) and shown on the admin screen — in GA practice, the core feature for preventing revenue loss.
- **(v1.2.5) Confirmation and payout timestamps and notifications.** The confirmation time in ④ is the injected clock value, and `SETTLE_CLOSE.closed_at` is written **only on the CLOSED transition** (previously the DB `CURRENT_TIMESTAMP` also overwrote it on the CLOSING and OPEN transitions — a changed behavior). After confirmation, `MonthCloseListener` (→ `StatementConfirmed`) is called in the same transaction; after the payout statement is saved, `PayoutListener` (→ `PayoutCompleted`) is called in the same item transaction (§6.8).

---

## 8. Correctness Guarantees and Testing Strategy — v1.1 Revision

1. **Golden test set (implemented in Phase 14)**: Representative cases verified in Excel by the settlement staff (product × event × boundary values) live in the repository as **self-contained CSV bundles** — one case is one folder and contains everything [rule seed + event timeline + expected result], without relying on a shared seed (a regulatory change = adding a new case, not modifying an existing one, the same philosophy as effective-dating). A parameterized runner that scans the CSV directory compares results **by going through the entire pipeline** (no bypassing Steps) and reports mismatches as a diff the settlement staff can read. **Just adding a CSV brings a new case into CI (zero code changes)**, and a golden set failure is a build failure. Location: `commission-settlement/src/test/resources/golden/` (authoring conventions in `golden/README.md`). Golden value cases that remained in JUnit are removed after migration (no duplication); only behavior, property, contract, and concurrency tests remain as code.
2. **Invariant tests (property-based — from v1.2.2, JUnit 5 parameterization + seed-fixed generator `SeededCases`; jqwik removed, `net.jqwik` blocked)**: the limit can never be exceeded, reversal sum = original sum × −1, deferral schedule sum = deferred principal, recalculated-value idempotency. (Implemented — note, however, that this is single-threaded.)
3. **Boundary values**: first-year window boundary date, the day of a rate's apply_from, policies concluded on 2026-06-30 vs 07-01, right after a monthly premium change. (Implemented)
4. **Rounding regression tests**: a rounding policy change is reflected only through a dedicated test that produces a diff report of amount differences.
5. **Shadow run (implemented in Phase 15)**: Import the results of the existing system (or manual settlement) as CSV (`ShadowCsvAdapter` — anomalous rows fail instead of being silently skipped), compare them against our own calculation to the won per **recipient × type × close month** (`ShadowRunService`, new module `commission-shadow`), and categorize the differences (MATCH / ROUNDING rounding / RATE_DIFF rate difference / MISSING missing / UNEXPECTED unrecognized / TIMING timing — reuse and extension of the §7 reconciliation categorization). **Our own net amounts are all computed by `NetAmountCalculator` (sum across all statuses)** and verified through the real store (Oracle) path (in contrast to the golden set runner's in-memory path). The default tolerance is 0 won (only an exact match is clean); the rounding tolerance band only classifies small differences and does not hide them. After running in parallel for 1–2 close cycles, decide on switching over based on a full comparison.
6. **Concurrency race tests (new in v1.1, mandatory)**: On a real Oracle, race two concurrent calculations against the same limit ledger to prove that the `accum_paid ≤ limit_amount` invariant holds under multithreading as well. Also includes the race to create a new ledger (uq_limit retry). **Cannot be substituted by in-memory or H2.** (Implemented in Phase 10 — verified limit reduction convergence, uq_limit single-ledger convergence, measured FOR UPDATE commit wait, and full rollback when a post-save hook fails)
7. **Contract tests (new in v1.1)**: Run **the same test suite** against the in-memory Store implementation (reference) and the Oracle/MyBatis adapters to prove behavioral equivalence (immutable ledger, idempotency keys, state transitions, FOR UPDATE semantics). **Development order: first set up Testcontainers (gvenzl/oracle-free) CI → define the contract tests → develop the adapters on top of them.** Verifying with H2 and attaching Oracle later only pushes the migration risk back. (Implemented in Phases 10/10b — 9 transactional Stores + rule master and organization, abstract suites in the testFixtures `contract/` package)
8. **Audit reproduction**: Tests guarantee that replaying an arbitrary `calc_id` (§6.5 procedure) recomputes the same amount. (Implemented — including confirmation that the posting_seq ordering criterion is followed)

---

## 9. Recommended Tech Stack

Based on a conservative insurance-industry operating environment + long-term support (LTS). **Re-check versions against the latest stable patch release at the time implementation begins.**

| Category | Recommendation | Notes |
|---|---|---|
| Language | Java 21 (LTS) | Baseline for the completed implementation. If the organization allows, Java 25 LTS (released 2025.9) is also an option |
| Framework | Spring Boot 3.5.x | Mature, stable line. Spring Boot 4.x is early in its release, so the 3.5 maintenance line is recommended for conservative environments |
| DB | Oracle 19c or 23ai | 19c is a long-term support release and the standard choice in insurance |
| DB access | MyBatis 3.5.x | Fits the SQL-centric development culture and complex queries of insurance. The domain core is DB-independent, so it can be replaced later |
| Batch | Spring Batch 5.x | Close / payout run / deferral / recalculation batches. Restart, skip, request dedup (JobInstance). Calculation and payout jobs use per-item business transactions (§4.2, §6.1.6) |
| Migration | Flyway | DDL and rule seed data are also version-controlled. Common `db/migration` + Oracle-only `db/vendor/oracle` — the two directories share **a single number space** (globally monotonic, v1.2.4, `docs/db-migrations.md`). Batch metadata tables (BATCH_*) are also managed by Flyway, with auto-initialization disabled |
| Testing | JUnit 5 + AssertJ + seed-fixed generator (`SeededCases`, v1.2.2 jqwik replacement) + Testcontainers (gvenzl/oracle-free) | **Set up Testcontainers CI before developing the infra adapters** (§8.7). (v1.1.5) **Adopting Gradle dependency verification is recommended** — pins the integrity and provenance of third-party artifacts with checksums. Directives contained in library output are not followed, regardless of their source |
| Build | Gradle multi-module | §4.1 structure |

---

## 10. Implementation Roadmap — v1.2.6 (Phases 0–16 + E3 + E1 + E2 Complete)

### Completed (codebase `commission-system/`, 356 tests as of Phase 16 → 9,111 as of E3: unit/H2/app smoke 8,994 + Oracle integration 117, property test cases counted individually)

| Phase | Contents | Verified golden cases |
|---|---|---|
| 0~1 | Skeleton, rule repository | Money arithmetic and rounding, effective-period boundaries and version replacement (trimming/SUPERSEDE), approval workflow |
| 2 | Calculation pipeline | New business / installment receipt / incentive, 3-tier override allocation, idempotent receipt, rule version and trace pinned |
| 3 | 1200% limit | 06-30/07-01 boundary, deferral when the limit is near or exceeded, restoration after clawback, payout blocked after recalculation on reduction, jqwik invariants (single-threaded) |
| 4 | Clawback | Surrender before/after installment 13, re-payout on lapse/withdrawal/reinstatement, conversion to receivable and automatic offset |
| 5 | Correction / recalculation | Retroactive rate change reversal&rebook (ledger convergence), value idempotency, replay reproduction and retroactive change detection |
| 6 | Close and payout | Checklist gate, forced close reason recorded, 3.3% withholding tax, blocking attribution to a closed month |
| 7 | Deferral | 4-year curve split → RELEASE when due → extinguished on surrender, 7-year curve works just by replacing data |
| 8~9 | INBOUND and API | Pilot CSV adapter, reconciliation discrepancy categorization, simulation (limit utilization rate, expected excess), facade completed |
| 10 | Real Oracle integration (2026-07-17) | Testcontainers CI (oracle-free singleton container), contract tests for 9 Store ports (same suite in-memory → Oracle: immutable ledger, idempotency keys, posting_seq ordering, empty string = NULL), concurrency races (limit reduction convergence, uq_limit retry, measured FOR UPDATE wait, full rollback on hook failure), fail-fast dual verification (model + DDL), V100 Oracle-only DDL (IS JSON, function-based unique index, RANGE partitioning, posting_seq PK) |
| 10b | Rule master and organization adapters (2026-07-17) | Contract tests for 8 RuleRepository kinds, CommRateAdminStore, AgentDirectory (in-memory → Oracle), effective-period boundary semantics pinned (new version on the day of apply_from, including representative cases branching on the effective date), approval concurrency — the index-as-arbiter path proven with deterministic interleaving (latches) + single ACTIVE convergence under barrier races, explicit rejection after 3 retries, diff-based change audit (COMM_RATE_CHANGE_HIST), reflection contract test for mandatory reference date |
| 11 | Spring Batch wiring (2026-07-17) | 4 jobs (close / payout run / deferral due / recalculation), JobInstance = request store (dedup no-op, restart after failure), value idempotency across interruption and restart (real Oracle — offset resume after kill, net amount convergence), **tasklet + per-item business transaction** to avoid chunk × limit lock conflicts (atomic rollback of [3.5]~[5.5] demonstrated), close gate / forced close (reason persisted in SETTLE_CLOSE), the 3 reports (rule completeness, MAXVALUE load, approval race) actually detecting, payout run repeated twice (failure injection → receivable restored → recovery, withholding tax based on net payout after offset), NetAmountCalculator extracted and routed through (double-deduction regression pinned at component level) |
| 12 | REST controller slices (2026-07-17) | 3 thin @RestControllers (query / simulation / approval) + MockMvc slices. **The net amount API is correct in reversal scenarios (3 corrections coexisting before close)** (routed through NetAmountCalculator), absence of status-filtered SUM fixed by an architecture test (§3.0), serialization fixed as long amounts, ISO dates, yyyyMM close month (Appendix B-1), fail-fast exceptions mapped to 4xx (internal details not exposed), approval API requires a real approver and rejects "system" (§6.6). Full Boot assembly deferred to the 'operational assembly' Phase |
| 13 | Incentive condition-expression engine (2026-07-17) | INCENTIVE_MST (§5.10) + SpEL condition-expression **sandbox** (SimpleEvaluationContext data binding only — rejects T()/beans/constructors/methods/assignment; 20 forbidden-syntax rejection tests fixed). **A new incentive takes effect just by registering a condition expression** (no redeployment), rate approval pattern reused (trimming/SUPERSEDE, diff audit, overlap invariant; passes in-memory + Oracle contracts), brought under the limit through the IncentiveV2(30)&lt;LimitGate(50) order, the incentive versions used are pinned in rule_versions and the decision recorded in calc_trace (replay reproduction), fail-fast on registration and evaluation (no silent skip). PayoutKind FIXED/PREMIUM_RATE as the first scope |
| 14 | Golden set CSV externalization + prerequisite subtasks (2026-07-17) | **Prerequisites**: `ux_incentive_active` (V102, the final arbiter isomorphic to rate §6.6 — wrapped in `NVL('*')` because incentive filters are nullable), explicit translation of index violation → `IncentiveApprovalConflictException` (→409), close completeness report extended to detect incentive overlap (retry runner + interleaving IT deferred). **Main work**: golden cases externalized as **self-contained CSV bundles** (folder = case: seed + timeline + expected, no dependency on a shared seed) — authorship moved to the settlement staff (`golden/README.md` conventions: BOM, integer won, ISO dates). The CSV-scanning parameterized runner compares **through the entire pipeline** (no bypassing Steps), readable diff, **CI inclusion just by adding a CSV** (zero code), failure = build failure. 24 cases on the §8.1 representative axes migrated + original JUnit golden values removed (behavior, property, contract, concurrency tests kept) |
| 16 | Operational assembly (final, 2026-07-17) | **Prerequisites**: ① incentive approval runner `OracleIncentiveApprovalRunner` (lock-then-revalidate, isomorphic to rates) + deterministic interleaving IT (race convergence demonstrated), ② grade calculation extracted into `GradingPolicy` (tercile default; grading criteria added to §11 #13). **Main work**: a runnable `@SpringBootApplication` — **explicit wiring of every bean** across stores, services, batch, and REST (code without stereotypes registered via @Bean). **Startup smoke E2E** (real Oracle): migration → rule seed → calculation → REST net amount → one close job run, surfacing wiring defects. Confirmed the Phase 11 transaction structure (business TM vs batch metadata / step Resourceless) is kept. **Externalized configuration**: DB connection, credentials, and authenticated users all injected externally (zero secrets in the repository), separate Oracle profile, Flyway only validates at startup (migrate is a controlled procedure). **Determinism**: JVM TZ Asia/Seoul and UTF-8 encoding set explicitly. **Minimal authentication**: Spring Security roles (ADMIN/OPERATOR/VIEWER) + **principal-based real approver (approvedBy parameter removed, §6.6 completed)**, deployment without authentication forbidden. **Batch launch**: no auto-run (BatchAutoConfiguration excluded) + protected endpoint with explicit parameters. Actuator health/readiness. The new §12 go-live checklist spells out what remains outside the system |
| 15 | Regulatory add-ons and switchover + prerequisite subtasks (2026-07-17) | **Prerequisites (consistency plug)**: rule resolution **Ambiguous fail-fast** — in single-result resolution by reference date, if 2 or more ACTIVE entries apply to the same key (rate) / `incentive_cd` (incentive), the calculation is refused (extends Appendix B-13). For rates this already worked via the existing `unique()` (contract tests kept); for incentives it was added to `IncentiveV2Step` (in-memory + Oracle). Thus however an overlap arises (including races on different start dates that the index cannot prevent), no money moves → the deferred approval runner is pure convenience (spec: revalidate after acquiring the lock). **Comparative disclosure (§1)**: two layers — extraction (`DisclosureService`, everything routed through NetAmountCalculator) + format mapping (`DisclosureFormat`; the confirmed format is an external fact, so swap the implementation and never hardcode it; replacement demonstrated with 2 dummy formats), large-GA comparative explanation ranking and grade (`RankingService`). The format and aggregation unit are subject to confirmation under §11 #13. **Shadow run (§8.5)**: new module `commission-shadow` — imports external settlement CSV, then compares to the won per recipient × type × close month and classifies (rate difference / missing / timing / rounding / unrecognized), own net amount routed through NetAmountCalculator, verified on the **real Oracle path** |
| E1 | Outbox and event feed (2026-10-17, v1.2.5) | §6.8. Copy of portal contract v1.1.1 with validator verdicts matching (77 cases), `OUTBOX_EVENT` and `OUTBOX_SEQ` (append-only trigger), gap-free sequence numbers (not assigned while uncommitted, overlapping assignment serialized; Oracle race IT), 12 publication points (6 decorators, 2 listeners, 1 job — 0 rows if the business transaction rolls back; Oracle IT), global validator (events from every test checked against the portal schema), event golden (24 cases business-invariant + 5 snapshots), A-1 comparison (difference table, type order), feed API (401/422/400, FeedResponse schema, startup smoke), close invariant (event side), fix of a deferral-cancellation assembly defect + assembly consistency test, `PAYOUT_STATEMENT` (V107), app-wide Clock |
| E2 | Provisional statement, simulation, statement query, local stack (2026-10-17, v1.2.6) | §6.8. Estimate = actual (closing and paying out with no new business input yields the same five fields — 5 cases: basic, receivable offset, correction, negative net amount turned into receivable, deferral due), provisional statement / simulation / statement query leave 12 business tables' hashes and the outbox byte-identical before and after (real Oracle), no waiting even while ledger, calculation, receivable, deferral, and close rows are held `FOR UPDATE`, simulation = the amount from actually processing the same hypothetical event, byte-identical across 2 runs with a fixed clock, response = portal contract v1.3 schema, operational and dry-run Step lists match, close-month guard assembled, two editions of A-1 (the portal takes them in as bytes), local stack feed = the second edition (CI job) |
| E3 | Comparative explanation grade and rank API (2026-09-29, v1.2.2) | **Prerequisite E3-0**: jqwik → seed-fixed JUnit parameterization (the 3 properties with the same domain, trial count ≥ original), resolution of `net.jqwik` blocked. **Main work** (§6.7): grade and rank policy version data (5 levels → 4 levels changes grades and ordinals just by replacing data; 0 source-literal scan hits), load validation (overlaps, gaps, duplicate ordinals, missing labels), single-result by reference date and Ambiguous (in-memory + Oracle contract suites), SHARED_RANK 1-2-2-2-5 and STRICT permutations, monotonicity over 500 seeded sets, UNAVAILABLE absence of 6 fields and all-not-computable 200, snapshot re-query byte-identical and unchanged after a policy replacement (Oracle), UPDATE/DELETE trigger rejection, ratioToAvg generated once and identical across all three, 401/403/400/409/422/404, contract schema (oneOf) and CHECKSUMS |

### Remaining

**The implementation roadmap Phases are exhausted** (Phases 0–16 complete — from the rule repository to a runnable app). What remains is not code
but **operations, data, and organizational procedures**, spelled out in the **§12 go-live checklist** (finalizing the operational seed, adding INBOUND
adapters, running the shadow run in parallel with real data, volume/performance and partition SPLIT, the audit replay procedure, real in-force determination, SSO integration).

When instructing Claude Code, each Phase prompt must include: ① the relevant section of this design document, ② the full text of Appendix B core conventions, ③ the completion criteria for that Phase.

---

## 11. Open Questions (Require Confirmation from Settlement, Compliance, and Tax Staff Before Implementation) — v1.1: Parameter Location Mapping Added

Everything is implemented as parameters, so **whatever the answer, it can be applied without code changes** (exceptions are noted).

| # | Open item | Parameter / where it is absorbed |
|---|---|---|
| 1 | Definition of "first year" for the 1200% limit (12 months from the conclusion date, or by installment number) and the monthly-equivalent conversion method (conversion basis for single-premium and annual payment) | `LIMIT_RULE.fy_window_months` (+ conversion rule) |
| 2 | Exact scope of items included in the limit (overrides, event-based awards, training cost support, etc.) — track the authoritative interpretations of the implementation support center / TF | `COMM_TYPE_MST.limit_included` (effective-period versions). If overrides are confirmed as "included": data change + register LimitGateStep V2 |
| 3 | Handling of the amount over the limit: cut and extinguished vs deferred until after the first year | `LIMIT_RULE.over_limit_action` (CUT / DEFER_AFTER_FY) — no code default (fail-fast if missing, §6.1.4). **What must be confirmed before deployment is the operational seed data**; until confirmed, registering DEFER_AFTER_FY is recommended |
| 4 | When a reduction pushes the limit below the amount already paid: clawback vs stopping future payouts | Current default is "stop future payouts" (naturally blocked by available=0). If "convert to clawback" is confirmed, a new Step (code addition) |
| 5 | Actual clawback rates (company rules), whether clawed-back amounts are re-paid on reinstatement, receivable procedure for terminated agents | `CLAWBACK_RULE` data / `ReviveStep.RevivePolicy` |
| 6 | Override attribution basis: affiliation at policy time vs at payout time; attribution of remaining deferrals when an agent moves | `AffiliationBasis` (EVENT_DATE / CONTRACT_DATE) |
| 7 | Rounding policy (truncation vs rounding at the won unit) and the unit it applies to (per line vs total) | `COMM_TYPE_MST.rounding_policy` + `RoundingPolicy` |
| 8 | The 2027 deferral scheme's confirmed curve, retention conditions, and transitional measures for existing policies (check the final notice of the supervision regulations) | `DEFERRAL_CURVE(+DTL)` records, `pay_condition` |
| 9 | List of INBOUND statement formats per insurer and which insurers to integrate first | Add inbound adapters (code added per insurer) |
| 10 | Close schedule (D-Day), permission scheme for forced close | Close settings |
| 11 | **(New in v1.1) Withholding tax details**: how clawback offsets are reflected in payment records and withholding tax — to be confirmed by tax staff | Replace `WithholdingTaxPolicy` |
| 12 | **(New in v1.1) Payout cycle**: once / twice a month — once confirmed, offset and withholding tax repeat per payout run | Repeated calls to `PayoutService` payout runs (§6.3, §7 — confirm no hard coupling to the close) |
| 13 | **(New in v1.1.9, revised in v1.2.2) Confirmed format, aggregation unit, submission cycle, grade calculation criteria (quantiles / population / period), and tie rules for sales commission comparative disclosure and comparative explanation** — the association's disclosure system and the authorities' formats and calculation criteria are externally confirmed facts; no guessing (subject to compliance confirmation; in force from 2026.3) | **Comparative explanation (§6.7)**: grade calculation criteria (thresholds, boundary inclusion, number of levels, labels, minimum population size, measurement period, rounding) and tie rules are **all policy data** (`DISC_GRADING_POLICY` and `DISC_RANKING_POLICY` body) — replaced with zero code changes. The population (what counts as the sales commission level) is changed by replacing the `measureKey` measurement function (v1 `FY_SALES_COMMISSION_RATE` is an **example measure**). The interpretation that grades are decided on the rounded ratio is `TODO(confirm#13)`. **Comparative disclosure (aggregation)**: replace the `DisclosureFormat` implementation (the extraction layer `DisclosureService` and net amount computation unchanged); the aggregation unit also changes only the rollup axis in the mapping; grades replace `GradingPolicy` (tercile `TercileGradingPolicy` by default — once the format is confirmed, consider migrating to the same data policy as comparative explanation) |

Handling of the amount over the limit (#3) and the scope of items included in the limit (#2) are likely to be settled early by the implementation support center's authoritative interpretations, so the inquiries to settlement and compliance staff should proceed now.

**(v1.2.5, Phase E1) Engine defects and unimplemented items — surfaced while attaching the outbox.** The E1 plan and approval documents called this list "§14", but the open-questions/remaining section of this design document is §11 (there is no §14) — so it lives here.

| # | Item | Current behavior | Resolution direction |
|---|---|---|---|
| E1-1 | **Actual deferral for DEFER_AFTER_FY is not implemented** | `LimitGateStep` only changes the memo and the trace `action` according to `over_limit_action`, and both values put the cut amount in `limit_cut_amt` — it does not create a deferral schedule (payout after the first year ends). `CommissionCalculated.limitCutPolicy` is the rule value as is (`DEFER_AFTER_FY`), and `DeferralScheduled(LIMIT_DEFER)` is not published (portal contract v1.1.1 L3) | Create the deferral schedule with a new Step (Appendix B-12 — no modifying existing Steps) |
| E1-2 | **Corrections (ADJUSTMENT) are not read by close or payout — and have no caller** | `AdjustmentService` exists only as a bean with no caller (tests only). Close and payout runs do not read `ADJUSTMENT`, so corrections are not paid out. They appear in `StatementConfirmed.adjustment` (a fact in the ledger) but not in `PayoutCompleted` | After settlement and tax confirm whether the payout run should include corrections attributed to the month in the net amount (and in the withholding tax basis) |
| E1-3 | **No code writes `LIMIT_LEDGER_HIST`** | Recalculation only changes `monthly_premium` and `limit_amount` in `LIMIT_LEDGER`. The outbox `LimitLedgerRebased` effectively takes the place of the history. The table is not dropped (migrations are immutable) | Decide whether to use the history table or treat the outbox as the canonical history |
| E1-4 | The close-month guard is not in the operational assembly | §6.4 — the resolver guards it. If an insert path bypassing the resolver appears, nothing stops it | **Assembled in E2 ④** (E1 acceptance review Q3) — §6.4. Resolved |
| E1-5 | Receivable `findOffsettable` has no `FOR UPDATE` | Payout is a per-recipient transaction, so there is currently no race on the same agent | Add locking when payout runs are parallelized |

**(v1.2.6, Phase E2) Recorded only, out of scope (E2 approval P4, P9).**

| # | Item | Current behavior | Resolution direction |
|---|---|---|---|
| E2-1 | **The existing `/api/commissions/simulate` is not the pipeline** (P4) | `SimulationService` (`commission-api`) separately recalculates rate × payout rate — it does not go through the V2 incentive, existing ledger accumulation, clawback, or deferral curve Steps, so it **can produce different numbers** from the new simulator (§6.8 — the same Step list as operations). E2 did not touch it | **E3 candidate** (E2 acceptance review) — after confirming the existing API's consumers, delegate to the new simulator or remove it. Until then, leave it untouched |
| E2-2 | **The withholding tax rate is a code constant** (P9) | `WithholdingTaxPolicy.STANDARD_3_3` (income tax 3% + local tax 0.3%). E2 only made the payout run, provisional statement, and simulator use **the same single bean** (`ServiceConfig.withholdingTaxPolicy`) | Turn the tax rate into rule data (effective-period versions) — together with the #11 tax confirmation |
| E2-3 | **No snapshot bootstrap** (⑥, Q5) | A tenant that already has business data with an empty outbox cannot get its current state through the feed (only facts created after E1 go out) | Portal contract v2 `TenantSnapshot` (including populated tenants and past months' statement history) — to be designed together with the portal (§6.8) |

---

## 12. Go-Live Checklist (New in v1.2.0 — What Remains Outside the System)

With Phase 16, **assembly of a runnable app is finished** (Boot app, externalized configuration, authentication, batch launch, startup smoke E2E). The items that remain as **operations, data, and organizational procedures** rather than code are spelled out here — these must be closed before the system can be put into real settlement.

1. **Collect the §11 open questions → finalize the operational seed**: Everything is absorbed into parameters (rule data), but the **operational seed values before deployment** must be finalized with settlement, compliance, and tax staff. In particular `over_limit_action` (#3, no code default — fail-fast if unset), the first-year window (#1), actual clawback rates (#5), the confirmed deferral curve (#8), and the rounding policy (#7). Until confirmed, seed with recommended values (e.g. DEFER_AFTER_FY), with fail-fast preventing omissions.
2. **Add INBOUND adapters per insurer**: Currently there is one pilot (SAMLIFE CSV). Once the insurers to integrate first (#9) are confirmed, add adapters as data/code (add an `InsurerStatementAdapter` per format; never silently skip anomalous rows).
3. **Run the shadow run in parallel with real data (§8.5)**: Using results from the existing system / manual settlement, **calculate in parallel for 1–2 close cycles**, then do a full comparison by recipient × type × close month. Switch over once the difference types (rate difference / missing / timing / rounding) converge to 0. `commission-shadow` provides the framework; what remains is importing and judging real data.
4. **Volume/performance + partition SPLIT calendar**: Measure processing time and lock contention of the close / payout / recalculation batches at real volume. `COMM_CALC` uses RANGE+MAXVALUE partitioning (§5.5), so **annual SPLIT PARTITION is run as a DBA procedure on a calendar** (rows loaded into MAXVALUE are detected by the close report as a safety net). Also retune the HikariCP pool and lock wait timeouts from measurements.
5. **Audit replay operating procedure (§6.5)**: Ledger reconstruction and recomputation of the same amount for an arbitrary `calc_id` are verified in code, but **the procedure for responding to audit requests (permissions, request, deliverables, retention)** must be established as an operational document.
6. **Real in-force determination in PolicyStatusProvider**: The determination of whether a policy is in force when a deferral comes due is currently a placeholder (always in force). It must be replaced with a real determination that queries the policy status ledger (lapse/surrender/reinstatement) for deferral HELD/extinguishment to be accurate in operations. **Production deployment is already blocked structurally** (v1.2.1) — if prod has the placeholder, `PolicyStatusProviderGuard` fails startup, so prod only comes up once a real determination implementation is wired (a structural block, not a note in a document, B-13). The completion condition for this item is "unblocking = wiring the real implementation".
7. **SSO integration + enforced authentication**: Currently minimal authentication (HTTP Basic + externally configured in-memory users), and **deployment without authentication is forbidden** (if no users are configured, no one gets through). Replace it with the in-house SSO (OIDC/SAML) `UserDetailsService`, and map the approval, close, and payout roles to the organization's permission scheme. The principal-based real approver is already complete (§6.6).
8. **Flyway migrate operating procedure**: The app only runs `validate` at startup (prod). Schema changes are performed only through the controlled `flywayMigrate` task in the deployment pipeline (the running app never changes the schema on its own).
9. **(v1.2.5) Outbox operations**: Feed service token rotation (the same hash list as §6.7), the run schedule of `outboxSequenceJob` and `outboxAgentSnapshotJob` (the engine has no scheduler — an orchestrator does it), a decision on `OUTBOX_EVENT` retention period and archiving (§6.8 — E1 does not delete), and revisiting sequence proof ③ if moving to RAC (assumes a single instance).

---

## Appendix A. Representative Scenario Walkthrough (Golden Case Draft)

**Scenario: policy concluded 2026-08-01, monthly premium 300,000 won, agent A (grade payout rate 90%)**

1. Limit opened: `limit_amount = 300,000 × 12 = 3,600,000원`. The calculation transaction acquires the ledger lock (§6.1.6).
2. New-business commission: base amount × first-year rate × payout rate → if, for example, 2,800,000 won is computed, it passes the gate → the post-save hook posts the DTL, `accum_paid = 2,800,000`.
3. Incentive of 1,000,000 won arises → remaining limit 800,000 won → **200,000 won over** → only 800,000 won is paid; the 200,000 won is cut or deferred until after the first year according to `over_limit_action`, with the reason recorded.
4. Surrender after the 7th installment payment → look up the applicable band in the clawback rate table → create a negative calculation for the clawback amount (base is the net amount already paid) → in the payout run, `ClawbackOffsetService` offsets it → the clawback posting reduces `accum_paid`.
5. Audit request: replay `calc_id` → reconstruct the ledger at that time by posting_seq + confirm the same amount is recomputed with the rule versions of that time (§6.5).

(All amounts and rates are example values; actual rates follow the values registered in the rate table.)

---

## Appendix B. Core Coding Conventions (Include the Full Text in Every Phase Prompt When Instructing Claude Code)

1. **No double/float.** Money arithmetic only through `Money`, rounding only through `RoundingPolicy`. No other direct use of `RoundingMode`. (v1.2.2) One exception: the comparative explanation ratio to average (a dimensionless ratio, not an amount) is rounded exactly once, only in `RatioToAvg`, and the digits and mode are set by the grading policy data (a source scan test pins the location of `divide`/`setScale` to this one place).
2. **No UPDATE of `COMM_CALC`** (except state transitions). Corrections are reversal + rebook.
3. **Rule lookups require a reference date.** Do not build a "currently effective" lookup API.
4. **Summation convention: net amount = sum across all statuses.** No status filter (`status != 'REVERSED'` etc.) in amount SUMs. Net amounts only through `NetAmountCalculator`. Status filters are allowed only in workflow (count/list) queries.
5. **Limit lock convention: acquire the ledger `SELECT FOR UPDATE` before the gate decision, and hold it through the hook posting and commit.** Races to create a new ledger are serialized by retrying on `uq_limit` violations.
6. **Ledger postings happen only in the post-save hook (`CalcResultListener`) with a confirmed calcId.** No posting before save.
7. **Limit restoration for reversing entries is by flipping the DTL sign.** No re-determining the type.
8. **`LIMIT_LEDGER_DTL` is ordered only by `posting_seq`.** Do not use `posted_at` to determine order.
9. **Rule version approval is trimming/SUPERSEDE only.** Approvals that split a period are rejected. Maintain the "no overlapping ACTIVE" invariant.
10. **Fixed Step order: LimitGate(50) < DeferralSplit(55).** DEFERRED payouts coming due do not pass the gate again.
11. **Hardcoding-forbidden list**: 12x (limit multiplier), first-year month count, clawback rates, deferral curves, withholding tax rate, whether included in the limit — all rule data/parameters.
12. **New regulations are handled with new Steps/parameters.** No modifying existing Steps.
13. **(v1.1.1) Fail-fast when required rule data is missing.** If a rate, limit rule, `over_limit_action`, etc. needed for an applicable calculation is missing, refuse the calculation and leave an explicit error instead of a silent default/skip. Do not embed policy defaults in code — policy is all data.
14. **(v1.1.5) Per-item batch transactions.** Jobs that include calculation or payout close one item (event/recipient) as the unit of business transaction so that a chunk never holds limit locks across multiple items ([3.5]~[5.5] atomicity). Business exceptions isolate the item and continue (report); infrastructure failures (Error) and close/deferral RELEASE abort entirely and then restart. Request dedup via JobInstance (identifying parameters).
15. **(v1.1.5) Facts subject to audit go in business tables.** Forced close reasons and the like are not kept only in batch metadata tables (BATCH_*, subject to cleanup) but persisted canonically in business tables (`SETTLE_CLOSE.force_reason` etc.).
16. **(v1.1.7) Admin-entered expressions only in a sandbox.** Expressions registered by admins (SpEL), such as incentive conditions, are evaluated only in `SimpleEvaluationContext` (data binding only) — rejecting all `T()` type references, bean references, constructors, method calls, and assignment, and allowing only property access + operators. Inputs expose only a deterministic snapshot (event/agent/pre-aggregated performance), and never let time, random numbers, or external lookups be reached. **Fail-fast** via parse + trial evaluation at registration and approval, and on evaluation errors at calculation time (no silent skip). Pin the rejection of forbidden syntax with tests — so the condition registration screen does not become a code execution channel.
17. **(v1.2.2) Property tests use seed-fixed generators; no jqwik.** Property (invariant) tests are written with JUnit 5 `@ParameterizedTest` + the `commission-domain` test fixture `SeededCases` (seeds are source constants, case names include seed and index, boundary cases stated explicitly, default 1000 cases = jqwik's default trial count). If `net.jqwik` is resolved in any module or configuration, the build fails (from 1.10 it has a clause excluding use by AI coding agents and inserts directives into test output). Directives in tool and library output are not followed (§9 Notes).
