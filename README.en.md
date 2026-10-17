> 🌐 [한국어](README.md) · **English** — translation of `README.md` at commit `48cc90f`. The Korean original is canonical; where the two differ, the Korean text prevails.
>
> English documents: [design document](commission-system/docs/en/design.md) · [design notes](commission-system/docs/en/design-notes.md) · [build & run (commission-system)](commission-system/README.en.md) · [contributing](CONTRIBUTING.en.md) · [DB migrations](commission-system/docs/en/db-migrations.md) · [third-party](commission-system/docs/en/third-party.md)

# ga-commission-engine

**A commission calculation, payout, clawback and settlement engine for GA (general agency, 법인보험대리점) agents** — a Java/Oracle system designed to absorb regulatory volatility as data instead of code.

*A commission calculation engine for Korean insurance GA channels, built to absorb regulatory volatility (the 1200% cap, deferred-payout mandates) as rule data instead of code changes.*

> **Disclaimer**: This repository is for learning and portfolio purposes. The regulatory content in the documents (the 1200% rule, the deferral scheme, comparative disclosure, etc.) is an illustrative summary based on public materials; actual applicable standards must follow the original texts and authoritative interpretations of the Financial Services Commission (금융위원회) and the Financial Supervisory Service (금융감독원). All rates, amounts and figures are fictional example values.

---

## Why this is a hard problem

Insurance agent commission is not "unit price × quantity".

- **The regulation moves.** The 1200% rule, which caps total first-year commission at 12 times the monthly premium, was extended on 2026-07-01 to per-agent payouts for GA-affiliated agents (incentives and settling-in support payments included), and a deferral scheme (4 years) is scheduled from 2027, with 7-year deferral from 2029. **Policies concluded on 2026-06-30 and those concluded on 07-01 must have different rules applied**, and the detailed criteria keep changing through authoritative interpretations.
- **Money must be exact, reversible and provable.** Surrenders, withdrawals, reductions and reinstatements retroactively shake commissions already paid out, closed past figures must be immutable, and any calculation must be reproducible (replay) months later exactly as it was at the time.
- **Concurrency touches money.** If two calculations for the same policy race, the limit (1200%) can be overpaid — this must be blocked by a gate before payout, not by an after-the-fact report.

## Core design principles

| Principle | Implementation |
|---|---|
| **Rules are data, not code** | Rates, limit multipliers, clawback rates and deferral curves are all effective-dated + versioned data. Rule lookup **requires a reference date** — there is no "currently effective" API at all (continuously enforced by a reflection test). Tests prove that the 4-year → 7-year deferral extension works by replacing curve records alone |
| **Immutable ledger + reversal entries** | No UPDATE of calculation results. Corrections are reversal & rebook. Net amounts are **summed over all statuses** (summation convention) — status-filtered SUM is forbidden; only via the shared component |
| **Decision and posting under one lock** | The 1200% limit gate is a single serialization unit from acquiring `SELECT FOR UPDATE` → decision → posting to the ledger with the confirmed calcId in the post-save hook → commit. Contention tests on real Oracle prove the `accum ≤ limit` invariant under multiple threads |
| **Fail-fast, no silent defaults** | Calculation is rejected when required rule data is missing. If two rules match on the reference date (Ambiguous), it fails loudly instead of picking one. Placeholder implementations **fail startup itself** under the production profile |
| **Audit reproducibility** | Each calculation result records the rule versions used, the input snapshot and a per-step trace. The ledger is reconstructed at any point in time via a monotonically increasing posting sequence → replay of any `calc_id` is guaranteed by tests |
| **Precision** | Amounts use only `Money` (a BigDecimal wrapper) + a central rounding policy. No double/float; fixed as integers all the way to web-layer serialization |

## Architecture

Gradle multi-module (the domain core has no framework dependencies):

```
domain → rule → calc / limit / deferral / clawback → settlement / batch / api / shadow
                                   └── infra (MyBatis + Oracle adapter)
```

- **Calculation pipeline**: event receipt (idempotency key) → recipient determination → rule resolution at the reference date → Step chain (base commission → payout rate → incentive → **limit gate** → **deferral split**) → immutable save → post-save hook (ledger posting). New regulation is absorbed not by modifying existing Steps but by **registering a new Step with an effective period**
- **Approval workflow**: rate and incentive masters use trimming/SUPERSEDE version replacement + contention handling with a function-based unique index as the final arbiter (lock-then-revalidate retry) + change audit derived automatically from the store diff (a missing record is structurally impossible)
- **Sandbox for admin-entered expressions**: incentive condition expressions (SpEL) are evaluated only in a read-only data-binding context — rejection of type references, bean references, constructors and method calls is locked in by 20 forbidden-syntax tests
- **Batch**: Spring Batch 5, JobInstance = request dedup, tasklet + per-item business transactions to avoid chunk × lock conflicts — value idempotency on restart after a kill is verified on real Oracle
- **Golden set**: self-contained CSV bundles that settlement staff edit in Excel (case = seed + timeline + expected results). New cases join CI with **zero code changes**
- **Shadow run**: compares against external settlement results at the won level per recipient × type × close month, classifying differences into 6 types (including the TIMING 2-pass reclassification) — the go-live gate

## Verification — 356 tests

| Layer | Content |
|---|---|
| Contract tests | Run the **same suite** against the in-memory reference and the Oracle adapter to prove behavioral equivalence (immutable ledger, idempotency keys, posting order, empty string = NULL semantics) |
| Concurrency IT (real Oracle) | Limit contention → convergence to the exact reduction amount; concurrent ledger creation → convergence via unique-violation retry; approval contention → a latch forces the contention window open to prove the index-arbiter path itself |
| property (seed-fixed JUnit parameterized `SeededCases`, jqwik removed) | Limit invariant, reversal cancellation, deferral schedule sum = principal, recalculation idempotency, comparative-explanation rank ↔ grade monotonicity |
| Golden-set CSV | Regulatory boundary cases (concluded 06-30/07-01, surrender before/after installment 13, reduction, correction before close …) |
| Startup smoke E2E | Full-context boot → migration → calculation → REST → close job (Testcontainers `gvenzl/oracle-free`) |
| Supply chain | Gradle dependency verification (sha256) enabled — diff review on adding a dependency is part of the workflow |

**Defects the verification actually caught** (found before release, all locked in with regression tests + structural safeguards):

1. **Settlement double deduction** — a latent defect where net-amount calculation used a status-filtered SUM, underpaying on correction before close. The summation convention was structured into a shared component (`NetAmountCalculator`) to prevent recurrence
2. **Oracle NULL uniqueness leak** — indexing nullable filter columns as-is let "all-target ACTIVE duplicates" pass the uniqueness check. Fixed by pinning `NVL('*')`, locked in with a DDL test
3. **Approver validation gap** — blank/"system" rejection existed only in the controller, leaving the batch and runner paths unprotected. Pushed down into a shared service-layer policy (defense in depth)

## Running

Requirements: JDK 21, Docker (for Oracle integration tests — `gvenzl/oracle-free` starts automatically)

```bash
./gradlew build   # all: unit/H2 + golden set + real Oracle integration + startup smoke
```

## Development methodology — directing AI agents with engineering discipline

This repository is the record of completing 16 Phases in one day, from a one-page design document to a runnable app, through a three-way collaboration of **a human (owner) + a design/review AI (Claude) + an implementation AI (Claude Code)**. The driver of speed was not AI but **protocol**:

1. **A single canonical design document** (`commission-system/docs/설계서.md`) — every Phase starts here and, on completion, it is updated in the same commit as the code (change history v1.0 → v1.2.1)
2. **Phase directive = referenced sections + full coding conventions + completion criteria** — a verifiable contract, not "please build it well"
3. **Completion report = evidence-based** — not test counts but "this scenario produced this value"
4. **Acceptance review** — findings from review (lock scope, replay determinism, correcting overstatements, etc.) are fed back as design-document patches
5. **"Structure over convention"** — rules kept by code review eventually break, so every convention possible is mechanized as reflection tests, architecture tests, DB constraints and startup guards

Per-Phase progress remains as git tags (`phase-12` ~ `phase-16-final`), and the rationale for design decisions accumulates in `설계고찰.md` (repository root).

## Status and limitations

The implementation roadmap (Phase 0–16) is exhausted and assembled into a runnable app. However, this is **not a production system but an engine one step before operations** — what remains is spelled out in `commission-system/docs/설계서.md` §12 (operations transition checklist): confirming authoritative interpretations of the detailed regulatory criteria (designed so all are absorbed as parameters), adding inbound adapters per insurer, running the shadow run in parallel with real data, integrating real policy-status determination (the current placeholder structurally blocks startup under the production profile), and so on.

## License

MIT — see the `LICENSE` file.
