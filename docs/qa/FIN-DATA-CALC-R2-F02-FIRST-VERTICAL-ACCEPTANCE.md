# FIN DATA-CALC-R2.0–R2.3 — First Vertical Implementation & UAT Evidence

**Дата:** 2026-11-20  
**Ветка:** `feat/fin-form-library-standalone`  
**ТЗ:** [FIN DATA-CALC-R2 Cross-form Roadmap](../FIN-DATA-CALC-R2-CROSS-FORM-CALCULATION-ROADMAP.md)  
**Scope:** local UAT / SYNTHETIC / MANAGEMENT / monthly / KZT / **F02 only**.  
**Financial S2:** CLOSED; **production admission:** CLOSED.  
**Important:** evidence is pinned to GitHub Actions head SHAs, not silently transferred to subsequent commits.

## Implemented

| Roadmap gate | Code | Proven result |
|---|---|---|
| DC-R2.0 | Compiler fixes; CGLIB `final` proxy fixes; Liquibase 051 dollar quote and 052 duplicate changesets repaired | Java21/H2, FACT PG16 smoke and CALC-154/166 reached GREEN on independent intermediary commits |
| DC-R2.1 | `FinIntakeUatSourceAdapter.readExact(org, project, revision, form)` | Uses existing `FormFactCandidateService.extractExact` (INGEST-2 verified content/row-order SHA in REPEATABLE_READ); refuses latest fallback, wrong scope/form and EVIDENCE content |
| DC-R2.1 DTO | `FormDraftService.DraftView.revisionId` | Additive exact immutable revision UUID in read/reopen API; other fields unchanged |
| DC-R2.2 | `F02IntakeUatPreviewService` | Verified exact user-saved revision → existing test-only F02 mapping descriptor/assembler → existing BigDecimal kernel. No commit to `fin_fact`, `calc_plan` or production |
| DC-R2.3 API | `POST /api/v1/uat/calculations/f02/preview` | Off by default. Requires explicit server feature flag `fin.calc.intake-uat.enabled=true`; request needs organizationId/projectId/exactRevisionId |
| DC-R2.3 UI | `frontend/src/App.tsx` + `frontend/src/api.ts` | Calculation/Explain panel on F02 only, opt-in `VITE_FIN_INTAKE_UAT=true`, disabled with unsaved/uncertain source changes |
| DC-R2.3 Browser | `frontend/e2e/f02-calculation-uat.spec.ts` | Real React save → rehashed preview → edit → second revision → historical replay, no mocked financial responses |

**Mathematical pilot inputs**: `netRevenue, cogs, operatingExpenses`.  
**Outputs**: `grossProfit`, `grossMargin`, `operatingResult`.  
**Key restrictions**: positive test-only amounts, KZT, monthly, `MANAGEMENT`, `SYNTHETIC`; zero denominator gives `INVALID / DIVISION_BY_ZERO` for grossMargin while other results remain calculable.

## Evidence recorded from real GitHub Actions logs

| Job | Commit SHA of executed code | Evidence | Outcome |
|---|---|---|---|
| FIN DATA-CALC-R2 Pinned Intake F02 PG16 | `137b52b921baa1d5e0747fe99c3ebe2b3f8e1460` | https://github.com/alimbetov/fin-analyzer/actions/runs/44676111884 | **PASS 5 tests / 0 failures / 0 errors / 0 skipped** |
| FIN DATA-CALC-R2 F02 Browser Intake E2E | `c404b1715eae96dccfaecac55a5c5cd5a292b808` | https://github.com/alimbetov/fin-analyzer/actions/runs/44676486083 | **PASS 1 Chromium / 0 failures / 0 skipped** |
| Java21 / H2 regression | `62e0090ec4740edffb562cdd93e70593db3932e9` | https://github.com/alimbetov/fin-analyzer/actions/runs/44676228282 | **PASS** |
| FACT PG16 Quick Smoke | `62e0090ec4740edffb562cdd93e70593db3932e9` | https://github.com/alimbetov/fin-analyzer/actions/runs/44676228281 | **PASS** |
| Historical SQL CALC-154 candidate checks | `de4e4d332c2b7354e1a3eba4081f8b28ecff256a` | https://github.com/alimbetov/fin-analyzer/actions/runs/44676083190 | **PASS**, not independent accounting qualification |

A subsequent frontend TypeScript regression was found: `ImportMeta.env` type absent. Added `frontend/src/vite-env.d.ts` (`754316cdb7`). Re-qualification on this and later SHAs requires actual CI results; **do not infer GREEN for latest HEAD**.

## How to run in isolated UAT

The FIN editor is deliberately **unauthenticated local UAT** in this slice. Use dummy financial data and keep Vite/Backend ports bound to localhost. **Never enable in a public/production deployment.**

The project already documents its PostgreSQL connection `FIN_FORM_DB_URL`, `FIN_FORM_DB_DRIVER`, `FIN_FORM_DB_USER`, `FIN_FORM_DB_PASSWORD` and backend port 8086. Configure those before running:

```bash
# Isolated PostgreSQL 16 available; Java 21 / Maven ready
mvn -B -ntp -f form-library/pom.xml -DskipTests package

FIN_CALC_INTAKE_UAT_ENABLED=true \
java -jar form-library/target/fin-form-library-0.1.0-SNAPSHOT.jar

# In another terminal (frontend npm dependencies installed):
cd frontend
VITE_FIN_INTAKE_UAT=true npm run dev
```

The local UI must show the F02 calculation panel only when frontend opt-in is set. All other existing 166 forms keep the normal editing UI.

## Golden manual smoke in browser

1. Open F02, create a monthly `MANAGEMENT`/`SYNTHETIC` KZT report.
2. Enter revenue `200`, COGS `100`, operating expenses `50`; save, observe `revisionId` and SHA in backend read DTO.
3. Click **Рассчитать F02**. The real preview reports grossProfit `100.00`, grossMargin `0.50000000`, operatingResult `50.00`; check provenance and exact SHA.
4. Update revenue to `300`. The calculate control must become disabled while the editor is dirty.
5. Save revision 2, calculate again: grossProfit `200.00`, grossMargin `0.66666667`, operatingResult `150.00`. Recalculate revision 1 via API; result remains original.
6. Confirm no `fin_fact.fact_set` row was written by the preview (PG16 integration test), and source SHA mismatch/wrong project access is rejected.

## Acceptance boundary

**Implemented and tested:** F02 saved-revision preview, source integrity, math, browser path and replay. This is a read-only preview, not a persistent immutable `UatCalcResult` receipt, not SQL/Java parity for exact user-entered snapshot, and not a qualified financial plan.

**Still required to close entire DC-R2.0–R2.5 / R1 first release:**
- Full current HEAD qualification: Java/H2, PostgreSQL 16, React/Chromium and Liquibase.
- Persisted immutable UAT result/receipt with deterministic idempotent key; SQL/Java parity from same source revision; audit/plan pins.
- C01/C02 actual user-entered ROWSET AS_OF preview and no-double-settlement controls, followed by generic Calculate/Explain/History UI.
- Financial policy resolution, variant pinning, FX/period compatibility and cross-form checks from DC-R2.6.

**Next recommended implementation task:** DC-R2.4 using the same exact-source adapter and `CalcAsOfDebtKernel`; retain current synthetic FactSet executor as a separate qualified test lane. No production `fin_fact` writes from unqualified intake.
