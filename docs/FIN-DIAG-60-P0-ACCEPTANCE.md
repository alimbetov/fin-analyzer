# FIN-DIAG-60 — P0 Acceptance and Traceability Matrix

**Дата:** 2026-11-16 · **Версия:** 1.1.0 · **Статус:** `SPECIFICATION_ACCEPTANCE_DEFINED / NO TESTS EXECUTED`  
Нормативный контракт: [FIN-EXECUTION-CONTRACT-V1.md](FIN-EXECUTION-CONTRACT-V1.md).  
Независимый набор синтетических входов и результатов: [p0-golden-cases-v1.json](../backend/src/test/resources/fin/method60/p0-golden-cases-v1.json).  
Источники: [60-method main TZ](FIN-DIAG-60-IMPLEMENTATION-TZ.md), [Auto-Orchestration TZ](FIN-DIAG-60-AUTO-ORCHESTRATION-TZ.md).

## 1. Состояние

Требования P0-01…P0-07 **документально уточнены**, но до реализации Java/PostgreSQL/React и выполнения приёмки **не закрыты технически**. Следующий исполнитель должен получить `GREEN` на одном SHA, приложить независимые финансовые golden результаты и подписи reviewers. В таблице статус `SPECIFIED` не означает `IMPLEMENTED`.

## 2. Матрица P0 → исправление → доказательство

| P0 | Риск исходного ТЗ | Нормативное решение | ID fixtures | Обязательный тест | Gate |
|---|---|---|---|---|---|
| P0-01 | Нет единого источника контрактов | `FIN-EXECUTION-CONTRACT-V1`, semantic version, enum compatibility, schema migrations | P0-01 | Contract/JSON schema registry test, unknown code rejection | No schema drift |
| P0-02 | Дубли, lost event, race, stale worker | approval+outbox TX, dedup, idempotency conflict, lease fencing, bounded retry | P0-02A/B/C | PostgreSQL Testcontainers crash/retry/concurrency, unique constraints | Exactly one logical publication |
| P0-03 | ML и CORE на разных revisions; отчет изменяемый | `AnalysisManifest`, immutable CORE, late ML creates child report revision | P0-03A/B, P0-RPT-01 | JUnit report snapshot + API E2E | Historical CORE unchanged |
| P0-04 | COMPLETE без анализа | versioned `AnalysisProfile`, 3 independent readiness axes | P0-04A/B/C | Profile completeness, ML failure isolation | Correct conditional completeness |
| P0-05 | Invalid cache reuse, broken dependency graph | typed REQUIRED/OPTIONAL/EVIDENCE_ONLY DAG + fingerprints | P0-05A/B/C | DAG/property tests, restatement and reconciliation | Only true dependents recomputed |
| P0-06 | 30d purge destroys published evidence | data-class retention, legal hold, separate object/metadata deletion | P0-06A/B/C, P0-DATA-01 | cleanup worker PostgreSQL/RustFS fault injection | No approved data loss |
| P0-07 | Family falsely qualified by one variant | family/variant hierarchy + independent method passport/test | P0-07A/B/C | Separate golden + negative per variant | All required variants qualified |

Cross-cutting: `P0-SEC-01` verifies cross-tenant 403 and no cache leakage. `P0-RPT-01` verifies CORE unchanged between report modes. All mock inputs are `SYNTHETIC`.

## 3. Independent synthetic fixture policy

File `p0-golden-cases-v1.json` contains **21** synthetic test-case definitions with inputs and independently specified expected contracts. It is a requirement asset, **not executable JUnit code**. No factory should call production services to generate expected results. For monetary/ratio values, compare canonical BigDecimal strings; for orchestration, compare observable state transitions/results rather than private implementation details.

Concrete reference calculations:
- `400/200=2.0000` for Current Ratio;
- Pareto values `500+300+150+50=1000`, shares `0.5/0.3/0.15/0.05`, cumulative `0.5/0.8/0.95/1`;
- `400/0` returns `NOT_COMPUTABLE`, not NaN/Infinity;
- three duplicate approved revenues `F02=1000`, detail `600+400`, sector `1000` represent **one** canonical revenue total = `1000`;
- legal hold blocks an otherwise eligible 30-day delete;
- report `r1` is immutable when compatible late-ML completes.

**Fixture gaps:** test data proves only contract-level examples, not all 60 methodology variants, PDFs, localizations, full cross-sector fixtures or statistically valid ML quality. Separate `FIN-METHOD-QUALIFICATION-MATRIX` is required for 60/60.

## 4. Required implementation order

**P0-ENG-01 — Contract adapter.** Inventory existing DTO/enums/jobs; introduce normalized DTO mappings to normative state axes without changing public API silently. Include backward compatibility tests.

**P0-ENG-02 — Approval outbox / execution.** Add existing-schema-aware Flyway migrations, immutable fact snapshots, event idempotency, lease fencing, retry, report publication safety. Real PostgreSQL Testcontainers required (H2 is insufficient for SKIP LOCKED and lock behavior).

**P0-ENG-03 — DAG + cache.** Implement semantic dependencies, source policy versions, selective recalculation, conflict-only branch blocking; test tenant isolation in cache and report.

**P0-ENG-04 — CORE/report profile.** Versioned analysis profiles, coreReadiness and optional ML lifecycle, immutable report revisions, report manifest, no external publication by background worker.

**P0-ENG-05 — Retention.** Data-class TTL, legal hold, cleanup ledger, RustFS/S3 fault testing, backup retention policy; synthetic clock injection to avoid waiting 30 days.

**P0-ENG-06 — Qualification.** Hierarchical 60 method families and child variants, golden tests plus reviewed exceptions for qualitative workflows.

## 5. Mandatory test methods and evidence

- Java 21 / JUnit5 unit + property-based checks (DAG, ratios, idempotency and immutability).
- PostgreSQL Testcontainers with Flyway for outbox, tenant, SKIP LOCKED, crash before/after commit, stale fence, concurrent update.
- RustFS/S3 interface mocked for partial failure plus a real compatible object-storage integration when available.
- Vitest API contract and Playwright React browser E2E: preview→approval→CORE; ML disabled/unavailable; late ML report revision; report export and trace.
- Java/React/XLSX parity for shared approved facts. All 4 locales have matching numeric report fingerprint.
- Injection/authorization tests: forged APPROVED, forged ADVISORY, cross tenant, unauthorized scoped field/period, malicious XLSX.
- Performance smoke: bounded concurrency, quota errors, queue backlog, cancellation and retry; publish observed p95/p99/RSS/CPU, no invented SLO success.
- Deterministic clock for TTL and seeded simulations; no date-relative sleep or flaky CI.

Evidence report per acceptance item: testClass/testMethod, fixtureId, actual expected, commit SHA, CI run URL, environment, status PASS/FAIL/BLOCKED, reviewer and follow-up issue. **Do not label PASS without actual test logs.**

## 6. Required blockers before release

`SPEC_HARDENED` only after normative docs, fixture files, cross-links and completeness checks.  
`IMPLEMENTED` only after code executed on source branch.  
`TESTED` only after green JUnit/Vitest/Playwright on same SHA.  
`METHOD_APPROVED` requires independent economic S2 method review.  
`RELEASE_QUALIFIED` requires auth/tenancy, real report/export UAT, stability/cleanup evidence and no unresolved P0.

**Current determination:** documentation P0 baseline clarified; **production and method qualification HOLD**. `main` unchanged.
