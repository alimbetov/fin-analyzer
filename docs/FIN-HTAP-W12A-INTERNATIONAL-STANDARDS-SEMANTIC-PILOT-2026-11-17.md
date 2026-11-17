# FIN HTAP — International-standards alignment and W1.2.A typed mapping pilot

**Date:** 2026-11-17 · **Branch:** `feat/fin-r0-r1a` · **Status:** code delivered, same-HEAD CI/independent S2 still required. **No IFRS compliance claim; ACTUAL and runnable plans remain HOLD.**

## 1. Adopted rules (pragmatic, normative)

1. **IFRS Accounting Taxonomy / XBRL:** Use concept+taxonomy version, reporting entity, exact period, unit, dimension/context and provenance as design inspiration for financial semantic identities. A FIN code such as `REV_NET` is an **internal** concept and does **not** inherit any IFRS tag automatically. IFRS Accounting Taxonomy **2025 remains current for 2026** (IFRS Foundation announcement 2026-02), while local filing authorities can impose particular versions. The official IFRS/XBRL taxonomy contents have licensing restrictions for commercial software integration; do not bundle, reproduce or claim licensed compliance without separate legal/product verification.
2. **ISO 8601:** use normalized exact calendar boundaries and half-open intervals; `AS_OF` points, `YEAR_TO_DATE` and `LIFE_TO_DATE` are not interchangeable monthly FLOW. `ISO_CALENDAR/1 MONTH/PERIOD_ONLY/INTERVAL/FLOW` is the **only implemented W1.2.A technical pilot**.
3. **ISO 4217:** Java `Currency.getInstance` validates recognized currency codes in addition to format; preserve code and unit in FinancialSliceKey. Any currency conversion requires separately trusted FX rate/source/date and explicit method; never infer FX or aggregate currencies.
4. **Data lineage + four-eyes principle:** published FactSet snapshot SHA, source revision/form version/source field, qualified metric contract, period policy, mapping hash and reviewer decision must agree. Authenticated writer != reviewer. Independent **financial methodology S2** and role-based execution permission are additional gates.
5. **PostgreSQL least privilege:** SQL constraints and append-only triggers detect accidental mutation and simple bypass but **do not protect against an owner/superuser**, direct DB write roles or untrusted service-level actor forwarding. Production must separate migration owner/read/app-write/operator roles, revoke arbitrary review writes, validate RLS/tenant pool behavior and authenticated actor evidence before ACTUAL.

Official references:
- https://www.ifrs.org/issued-standards/ifrs-taxonomy/
- https://www.ifrs.org/news-and-events/news/2026/02/ifrs-accounting-taxonomy-2025-to-remain-current-for-2026/
- https://www.ifrs.org/digital-financial-reporting/
- https://www.xbrl.org/taxonomy-architecture/
- https://www.postgresql.org/docs/16/ddl-rowsecurity.html
- https://www.postgresql.org/docs/16/perm-functions.html

## 2. Code delivered, no fictitious family coverage

| Component | Exact implementation | Contract/limit |
|---|---|---|
| Currency semantics | `HtapFinancialSliceKey` | unknown ISO 4217 code rejected; same name different currency distinct |
| Typed calendar policy | `V20261117_19__htap_typed_financial_mapping_semantic_pilot.sql` | immutable single seed policy `ISO_MONTH_FLOW_PERIOD_ONLY_V1`; explicitly `TECHNICAL_PILOT_NOT_FINANCIAL_S2` |
| Exact field→metric | same migration, `fin_calc.htap_mapping_semantic_evidence` | check candidate `sourceField/metricCode/metricVersion` against real immutable `fin_fact.pilot_field_mapping` for form/version/source field; existing `fin_calc.metric_contract` must be `QUALIFIED`, measure `FLOW`, unit compatible, time `INTERVAL`, independent APPROVED mapping review |
| Standard assignment | same migration | only `INTERNAL_FIN_PILOT / FIN_PILOT_V1`; **zero IFRS auto-tags**, only one source field per candidate in pilot |
| Authoritative metadata | `HtapMappingGovernanceService.validatePilotSemantics` | FIN_REVIEW scoped signed JWT; read authoritative mapping/review/field/metric SQL, **not** user-supplied metric fields. Writes immutable semantic evidence, still `execution_permitted=false` |
| Read model | `fin_calc.htap_mapping_semantic_coverage_v1` | shows editor active + candidate semantic status separately from `finance_s2_qualified=false` and `execution_permitted=false` |
| Code binary fingerprint | `HtapRuntimeClassDigest` | computes actual class byte SHA-256; **CLASS_BYTES_ONLY_UNATTESTED**, not CI-attested JAR/dependency/config/release image SHA or method permit |
| Tests | `HtapMappingGovernancePostgresTest`, `HtapFinancialSliceKeyCalendarTest`, `HtapRuntimeClassDigestTest` | correct F02 `FIN-PILOT-V1/netRevenue → REV_NET/1.0.0` with QUALIFIED FLOW/MONEY contract; rejects mismatched COGS/field, untrusted actor, overwrite; invalid ZZZ currency; stable class SHA and no permit |

**Do not rewrite or requalify existing 259 proposal keys, 60 method families or 166 form catalogs.** Data field reconciliation proceeds on selected real forms and qualified contracts. The pilot cannot certify all sources merely because a matching name exists.

## 3. Why R2.B remains BLOCKED PLAN_ONLY

The existing `HtapBlockedPlanOnlyService` creates a dependency-ordered read-only preview and immutable blocked evidence for exactly one published FactSet. Its blocked receipt hashes all selected financial slice identity and registry/catalog epoch, not a calculation or sealed report. The new typed mapping evidence is a **predecessor**, not an automatic R2.B runnable flag.

Open P0:
- Authenticate and authorize review/activation end-to-end including revocation and production PostgreSQL role grants, not only signed `Jwt` provided by internal caller.
- Canonical financial standard-specific semantic binding for more source fields; prove source-document lineage, per-value accounting context/currency, reported periodType+Coverage and usable fiscal versions in source assertions. Existing `fact_assertion` alone does not store explicit YTD/LTD calendar version; do not backfill by guessing.
- Finance owner and independent S2 approve a specific source/metric/method configuration. `htap_mapping_semantic_evidence` is still a technical candidate, not this sign-off.
- Build reproducible **artifact attestation** for packaged JAR/module and dependencies, source and released image digest, effective config SHA, signed review/actor and revoked-state check at planning time. A `.class` digest does not meet this gate.
- Create a **separate** qualified, immutable runnable PLAN_ONLY contract only after these gates; never upgrade or reinterpret `BLOCKED_PLAN_ONLY` rows or issue execution permit from them.
- PostgreSQL 16 Testcontainers fresh and populated migrations, same SHA CI; benchmark index/pruning using representative volume before adding index on populated `fin_fact`.

## 4. Acceptance

For this increment, accept only: exact F02 pilot source mapping and matching metric/period with real immutable evidence; one correct schema/epoch; negative field, metric, unit, timeKind, actor, currency, duplicate attempt, and zero output writes; no automatic IFRS/S2/ACTUAL promotion. Commit success is not CI success, and CI success is not financial S2.

**Next vertical:** require one fully reviewed F02 month input set, approved tenant-scoped semantic mapping and published immutable snapshot; independently reviewed source values and metric semantics; then bind the actual FIN-01 implementation build manifest/effective config SHA and define a future runnable plan with no execution. No W2/W3 production or W6 speed claim.
