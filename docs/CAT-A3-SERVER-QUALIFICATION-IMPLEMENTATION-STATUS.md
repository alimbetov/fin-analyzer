# FIN CAT-A3 — server MethodGate / FactGate integration audit

Status: **PARTIAL IMPLEMENTATION / HOLD**. Working branch `feat/fin-r0-r1a`, PR #2 Draft. Do not merge to main or claim production financial methodology approval.

## Delivered

- `backend/src/main/java/kz/fin/backend/execution/CatA3MethodFactAdmission.java`: read-only server-owned first-stage admission; checks methodology catalog release HOLD, 60 unqualified families, pilot F01–F03 and DF-CSH-01…06 whitelist, published fact scope (organization/entity/business scope/revision), matching snapshot hash, data space and reporting basis.
- `backend/src/test/java/kz/fin/backend/execution/CatA3MethodFactAdmissionTest.java`: JUnit/Mockito cases for unknown method, unsupported form, malformed revision, missing publication, mismatched hash, data-space mismatch and explicit S2 HOLD.
- All decisions return `allowsActualExecution=false`. This gate **cannot approve** any method. It does not accept a request-body approval flag.
- Legacy `SingleExecutorScheduler` and existing financial adapters were intentionally not changed; they have separate regression and qualification contracts.

## Open gates; what exactly must be implemented next

1. **Independent S2**: a named financial reviewer must sign *per variant*, not 60 method families globally: formula, reporting basis, units, sign/reversal, calendar, rounding tolerance, applicable form field revision and economic limitations. A code commit or synthetic golden test cannot substitute for independent approval.
2. **Persistent immutable approval decision**: design a server-only approval record with methodCode+version+form schema version, reviewer identity, immutable review evidence SHA, active/effective dates, revocation, and explicit authorization. Approval must not be supplied by React/Excel or inferred from `metric_contract.qualification` alone.
3. **Full FactGate**: extend the first-stage published snapshot inspection by `TrustedFinancialPopulationService.requireReviewedActual` using **server-derived** required groups from the signed methodology, including exact source rows, reviewed intake hashes, nonduplicated population, unit/currency/period consistency. Partial group coverage must not authorize computation.
4. **Adapter wiring**: only when gates 1–3 are accepted, add a narrowly scoped CAT-A3 method variant through `MethodTaskAdapter` and `SingleExecutor`. Gate at both `applicable` and `execute` to prevent stale approval/revocation races. Preserve default-disabled automatic execution until acceptance.
5. **Postgres integration**: test foreign tenant/revision, changed hash, absent approved source, duplicate source row, extra/unmapped facts, revoked or expired S2, concurrent publication, stale snapshot, missing fields, incompatible period/currency, exact same-SHA re-run. Use Testcontainers PostgreSQL, not H2 as a substitute.
6. **React authenticated E2E**: Excel V2 → server preview → human review → publish `FactSetRevision` → `SingleExecutor` → diagnostic report → reload, with two users, authorization/ABAC and version pinning. For missing methodology approval, show `METHOD_S2_HOLD`, not a computed financial recommendation.

## Acceptance: stages and evidence

- **CAT-A3.0**: method catalogue and publication inspection rejects foreign/mutated facts; JUnit green.
- **CAT-A3.1**: signed server-only S2 qualification record persisted; source proof + approval invalidation tested.
- **CAT-A3.2**: server-side data-population verifier and method verifier compose into one permit; no client switch.
- **CAT-A3.3**: one selected F01/F02/F03 approved variant wired to executor; complete integration test green.
- **CAT-A3.4**: browser two-user E2E with versioned Excel roundtrip; one SHA Node + Maven/Testcontainers + Playwright/macOS acceptance gate.

**Not done:** independent financial S2, persisted qualification store, method/fact gate composition, auto-execution integration, real Postgres/browser E2E and green CI evidence on the current commit. No claims of completed production readiness.
