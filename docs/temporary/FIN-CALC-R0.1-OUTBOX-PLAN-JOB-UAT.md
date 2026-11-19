# FIN CALC-R0.1 — Qualified Outbox → Reproducible Plan → PostgreSQL READY Job

**Delivery:** code-level UAT scope, 2026-11-19. **Financial S2: HOLD.** Must not be mistaken for financial approval, an attested release build, or CALC-R0.2 execution.

## 1. Actual persistence and boundaries

Reuse **existing** Liquibase 019 `fin_calc.calc_outbox_event`, `calc_plan`, `calc_job` (no new migration). FACT-1.B 022 is the trusted input source for this **synthetic TEST_ONLY pipeline**. No HTTP qualification controller, no draft event → qualified job, no result/current publication in R0.1.

`FACT-1.B TX-Q` commits persisted FactSet, three typed observations, receipt, epoch/control, and FACTSET_QUALIFIED in one DB transaction. `CALC-R0.1 TX-B` separately takes one `FACT1B_F02_UAT` / `FACTSET_QUALIFIED` unprocessed event by `FOR UPDATE SKIP LOCKED`, **reconstructs and re-hashes the exact F02 source and all three persisted observation identities inside the same PostgreSQL transaction**, validates receipt/project/user/scope/epoch and active not-revoked control, appends an immutable plan and one READY job, and ACKs the event in that same TX. Any exception rolls back plan/job/ACK together.

`DRAFT_REVISION_SAVED`, other producers or unsigned client payloads are not admission. A revoked or superseded valid event is ACKed with `CALC_R01_STALE_OR_REVOKED` but never planned; the old immutable FactSet remains for history. A malformed or hash-mismatched event rolls back, stays pending, and requires operator investigation; **there is no permissive fallback or automatic poison ACK**. This is intentionally fail-closed, so a poison event can block a UAT poller until the operator quarantines it.

## 2. Exact deterministic plan identity

`F02CalcPlanDescriptor` version `FIN-CALC-R01-F02-V1` and `F02_TEST_ONLY_V1`:

- input metrics: `F02.netRevenue`, `F02.cogs`, `F02.operatingExpenses` (KZT, MONTH, numeric PRESENT only)
- DAG: `grossProfit=netRevenue-cogs`; `grossMargin=grossProfit/netRevenue`; `operatingResult=grossProfit-operatingExpenses`
- signed cost convention in this **test-only** method: expense inputs nonnegative; division by zero becomes INVALID in **future R0.2**, not silent zero
- pinned **hypothetical** rounding policy: money scale 2; margin scale 8; HALF_EVEN
- `algorithm_artifact_sha256` is a SHA of a *synthetic versioned algorithm descriptor*, **not an independently attested build JAR digest**. Production release must replace it with SHA of a reproducible built artifact + manifest and independent reviewer decision.
- `execution_fingerprint` is length-delimited/versioned SHA of full tenant/scope, factSet UUID and factset SHA, receipt binding and epoch, pinned source revision/version/hash/row order/schema/mapping, versioned algorithm/config/params and DAG SHA. No mutable `latest`, raw JSON string or `Map.toString()` in identity.

Plan storage: existing `calc_plan` (qualification_mode=TEST_QUALIFIED). Job storage: existing `calc_job` (state=READY, attempts=0, fencing_token=0). Same exact fingerprint is idempotent: existing plan and job are validated, never reset or duplicated on replay.

## 3. UAT deployment safety

The Spring bean is present but defaults to **OFF**. To run the disposable PG16 UAT dispatcher explicitly:

```properties
fin.fact.uat-committer.enabled=true
fin.calc.uat.dispatch.enabled=true
fin.calc.uat.scheduler.enabled=true
fin.calc.uat.scheduler.delay-ms=5000
```

No public REST dispatcher endpoint, no externally trusted S2 key, no production IAM adapter. The scheduler is registered **only when both dispatcher and scheduler flags are true**. For isolated deterministic tests, enable the first two but leave `scheduler.enabled=false`, then invoke `dispatchBatch(1..32)` internally. Run **only on isolated synthetic UAT databases**; no exposure of port 8086.

## 4. PostgreSQL 16 acceptance

`CalcR01PostgresqlTest` defines 11 independently reportable cases:

- R01-01 initial F02 plan + one job + SHA/fingerprint + event processed.
- R01-02 duplicate delivery reuses plan/job and increments outbox attempt only.
- R01-03 eight independent consumers -> one processed event and job.
- R01-04 draft event cannot qualify.
- R01-05 local revoked FactSet suppresses planning.
- R01-06 tampered event SHA rejected without plan/job/ACK.
- R01-07 three failpoints: AFTER_PLAN, AFTER_JOB, BEFORE_ACK -> complete rollback.
- R01-08 older revision events cannot plan after newer active revision.
- R01-09 normal dispatcher flag disabled.
  
`scripts/check-fin-calc-r01-junit.py` checks expected 11, zero skipped/failed and required cases; mandatory CI PostgreSQL Testcontainers flow checks the same commit. `scripts/smoke-fin-fact.sh --quick` selects one essential R0.1 case (8 total); `--extended` includes all 11 (205 total smoke cases, subject to CI verification).

## 5. R0.2 next, **not part of R0.1**

1. Claim READY jobs in bounded pools with PostgreSQL lease owner/fencing token, and reclaim expired leases.
2. Read **pinned `fin_fact.fact_observation`**, not free-form `fin_intake` JSON; verify plan SHA, FactSet/control and F02 descriptor.
3. Compute typed BigDecimal `grossProfit`, `grossMargin`, `operatingResult`. Zero revenue -> INVALID ratio state, not 0 or NaN. No floating-point math.
4. TX-D: fenced immutable `calc_result` insert + job success atomically, with full lineage and replay digest check.
5. TX-E separately validates authority epoch and revocation before CAS `calc_current_result`. A revoked plan can remain historical but never be CURRENT.
6. Negative PG16 tests: wrong lease, expired lease, cross-tenant, epoch rewind, duplicate execution with divergent result, crash between TX-D/TX-E, immutable UPDATE/DELETE.

**Definition of done R0.1:** PostgreSQL 11/11 positive/negative including concurrent consumers, legacy FACT-1.B/INGEST regression, and CI release gate all GREEN on the *same* HEAD SHA. This is UAT qualification only. R0.2 is not to be claimed complete until real immutable result is computed and persisted.
