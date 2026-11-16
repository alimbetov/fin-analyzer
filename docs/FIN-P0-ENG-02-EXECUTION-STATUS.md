# FIN P0-ENG-02 — Outbox Consumer + SingleExecutor Task Scheduling

**Date:** 2026-11-16 · **Branch:** `feat/fin-r0-r1a`  
**Status:** `CODED / LOCAL AND CI EXECUTION NOT YET VERIFIED / RELEASE HOLD`

## Implemented in this slice

1. `V20261116_01__fact_publication_outbox.sql` and `FactSetPublicationService`: append immutable outbox event in the *same* transaction as verified fact publication and current pointer update.
2. `V20261116_02__single_executor_queue.sql`: durable runs/tasks/dependency edges/event delivery ledger. Unique `event_id` ensures one logical run for each approved publication event.
3. `MethodTaskAdapter`: server-side execution SPI. `autoEligible` is **false by default**; new financial/ML/optimization methods do not silently join automatic processing.
4. `PublishedFactIntegrityAdapter`: one real, **internal non-financial** `SYS/VERIFY_PUBLISHED_FACT_SET` task, validating trusted publication hash and approved facts. It is not one of the 60 economic methodologies.
5. `SingleExecutorScheduler`: atomic event-to-DAG plan, unknown dependency/cycle rejection at startup, task leasing `FOR UPDATE SKIP LOCKED`, retry/backoff, fencing token guard, TTL recovery of expired worker leases, dependent task blocking on failed prerequisites, run completion tracking.
6. `ExecutionSchedulingConfiguration` and `application.yml`: opt-in `FIN_EXECUTION_ENABLED=false` by default; scheduled tick every 10 seconds when enabled.
7. `V20261116_03__outbox_plan_retry.sql`: durable planning retry / dead-letter ledger so a failing event cannot permanently block the next event.
8. JUnit tests: integration scenarios in `FactPublicationIntegrationTest` for outbox transaction rollback, immutable event, unique logical task execution, expired lease reclaim and poison-event isolation; `SingleExecutorGraphTest` for unknown/cyclic dependency and duplicate adapters; `ExecutionContractsTest` for independent CORE/ML readiness.

### Runtime flow

`Trusted publication → immutable outbox → transactional plan (run/tasks/delivery) → ready DAG tasks → worker claim with lease+fence → specialized MethodTaskAdapter → JSONB result → run completion`.

Worker adapter runs **outside the database transaction**, and its result is published only if the fencing token still matches. Physical computation may repeat after crash; logical published result remains unique. Do not claim exactly-once physical execution.

### Not delivered / release blockers

- **No verified CI GREEN on current SHA**; tests have been authored but not executed/confirmed.
- No automatic execution of 60 actual financial methodologies. Only the internal fact-integrity adapter is auto-enabled.
- Full `AnalysisProfile` selection, versioned methodology qualification, signed report snapshot and `CORE/CORE_WITH_ML` UI are not integrated into this scheduler.
- No public API for start/preview/status/cancel, ABAC on report reads, scheduler metrics, or exact resource/cost quotas.
- Persisted planning retry ledger and dead-letter after five attempts are implemented via `V20261116_03__outbox_plan_retry.sql` and `SingleExecutorScheduler`. Still require executed concurrency tests, monitoring/alerting and retry operations before production.
- Adapter executions longer than the 60-second lease can be re-executed. Fencing prevents stale publication but **heartbeat, timeouts and cancellation** remain mandatory before adding CPU-heavy methods or ML.
- Idempotency across manual user-initiated report requests is not implemented; current dedup is by immutable event ID.
- There is not yet a tenant-facing ML flow. All ML/Monte Carlo execution remains opt-in and blocked.

## Verification commands

```bash
mvn -B -ntp -f backend/pom.xml verify
npm ci
npm --prefix packages/sector-pilot test
npm run typecheck
npm test
npm run build
npm run test:e2e -- --project=chromium
```

Require Java 21, running Docker for PostgreSQL Testcontainers, one commit SHA for all jobs, actual CI URLs and logs. Tests must be executed and reviewed; do not infer PASS from file presence.

## Follow-up acceptance criteria

**EXEC-R2A:** PostgreSQL Testcontainers GREEN for event processing, concurrent pollers, crash-before/after-commit, lease expiry, stale worker, dependency graph and tenant isolation.  
**EXEC-R2B:** persisted poisoned-event retry/backoff/dead letter, metrics and heartbeat for long-running workers.  
**EXEC-R2C:** approved methodology registry + `AnalysisProfile` and versioned calculation manifest, selective execution with real financial executor.  
**EXEC-R3:** immutable CORE report and on-demand optional ML report enrichment on identical approved facts.

**Operations:** `FIN_EXECUTION_ENABLED=true` only in an isolated environment after migrations and verification. `main` untouched.

## EXEC-R2 Qualification hardening update — 2026-11-16

This update amends the earlier implementation status; **all tests below remain authored, not witnessed PASS**.

- **R2-Q01 Expired-lease fencing fix.** `SingleExecutorScheduler.complete()` and `retryOrFail()` now require both the current fencing token and `lease_until > now()`. A worker whose lease elapsed cannot publish stale output even before a replacement worker claims the task.
- **R2-Q02 Two-poller concurrency proof.** Added a PostgreSQL Testcontainers integration scenario: two independent scheduler instances consume one published event concurrently, then assert one run and one computed task.
- **R2-Q03 Lease expiry without waiting.** A synthetic adapter expires its own lease, returns a result, and verifies the first output was rejected. The reclaimed attempt gets a new fencing token and publishes the only durable result.
- **R2-Q04 Poison planning exhaustion.** A synthetic always-failing applicability adapter exhausts five persisted planner attempts; the event is marked `dead_letter` and not repeatedly processed.
- **R2-Q05 Failure isolation.** Earlier integration scenario checks a poisoned event cannot permanently block a subsequently published healthy event.

### Qualification matrix

| Evidence | Expected | Verified in this update |
|---|---|---|
| Outbox migration / publisher atomicity | Commit-or-rollback | Code and tests inspected only |
| 2 workers / 1 event | 1 logical run, 1 published task | Test added, execution pending |
| Expired lease | Stale output rejected, token increments | Fix/test added, execution pending |
| Poison event | 5 attempts → dead-letter, next healthy event proceeds | Test added, execution pending |
| JUnit 5 / Maven / PostgreSQL Testcontainers | GREEN on same SHA | **NOT CONFIRMED** |
| React CORE report E2E | Immutable qualified report | **NOT IMPLEMENTED** |
| Financial method adapter | Qualified real calculation | **NOT IMPLEMENTED** |

### Next gated actions

1. Run `mvn -B -ntp -f backend/pom.xml verify` with a real PostgreSQL Testcontainers Docker daemon; capture Surefire reports and failing logs.
2. Confirm `FIN CI / backend-verification`, `verify`, `desktop-macos`, `acceptance-gate` all `success` on the same SHA; record actual URLs.
3. Fix every failing test on that SHA (new SHA requires new complete green).
4. Perform independent S2 review of concurrency/DB isolation and source trust; consider worker heartbeats/timeouts for any adapter running near the fixed 60-second lease.
5. **Only then** connect FIN-01/FIN-04 first qualified economic variants using the existing `AutoIndicatorEngine` / `TrustedAutoReportService` without duplicating formulas, and build a CORE report snapshot.

Until those gates, `FIN_EXECUTION_ENABLED=false` outside isolated local qualification and **release HOLD**.
