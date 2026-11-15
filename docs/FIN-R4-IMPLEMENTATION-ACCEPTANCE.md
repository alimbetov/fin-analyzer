# FIN-R4 implementation and acceptance evidence

**Development branch:** `feat/fin-r0-r1a` / Draft PR #2.  
**Merge:** HOLD. **Production ACTUAL:** HOLD.  
**Methodological qualification:** NOT APPROVED. **React/Keycloak end-to-end:** NOT IMPLEMENTED.

## Implemented backend slices

### R4.1: source population completeness

- `V7__financial_population_and_origin.sql`: immutable `fact_origin`, `source_population_control`; database verifies registered count and control total and serializes row registration vs sealing.
- `TrustedFinancialPopulationService`: server-side method requires a published SYNTHETIC FactSet and explicit server-owned required groups. It checks exact source row IDs, source field mapping, approved values, selected row count, control total, money units and consistent currency. Unregistered/omitted rows fail closed.
- Integration tests: `TrustedFinancialPopulationIntegrationTest` verifies partial approved selections, distinct row identities, cross-tenant denial, unpublished input, absent requirements, invalid control values, unknown form mappings, and cross-currency failure.

**Limit:** This proves population coverage relative to the persisted and independently reviewed source manifest; it does not prove that the uploaded source document itself contained every transaction. A complete input-file extraction and reviewer approval service are still mandatory.

### R4.2: versioned pilot source-to-canonical mapping

- `V7` defines exact form/field/metric/version/time-kind mappings for `F01`, `F02`, `F03`, `DF-CSH-01`, `DF-CSH-02`. The only supported version is `FIN-PILOT-V1` (not a statutory codebook).
- `V8__approved_source_intake.sql` stores immutable normalized input fingerprint, reviewed source document digest, currency, explicit amount scale (1 or 1000), approved cell count, reviewer reference and review time.
- `TrustedFinancialFormMapper` verifies source revision and the independent stored intake contract; maps fiscal month to AS_OF or INTERVAL dates, transforms thousands of KZT with exact BigDecimal, writes immutable `fact_assertion` plus exact `fact_origin`, then seals population controls in one transaction.
- `TrustedFinancialFormMapperIntegrationTest` tests all five form families, F01 month-end stock, F02/F03 and cash flows, KZT-thousands scaling, unknown field rejection, altered/missing cells, publication and subsequent population validation.

**Limit:** There is no production XLSX parser/issuer generating the approved source-intake record, nor end-to-end source-approval segregation-of-duties, Keycloak/OIDC principal derivation, or ABAC permission service. Calling these service methods with user-supplied `authorizedOrg` or `reviewedBy` would be unsafe. No public controller is introduced; ACTUAL remains blocked in the population guard.

### R4.3: factor decomposition and materiality

- `DiagnosticProofEngine` implements reproducible SKU-aligned **arithmetic** `volume + mix + price + cost = change in gross profit` with an exact reconciled residual.
- SKU mismatch, duplicates, missing evidence revision, zero baseline units and invalid money/units reject rather than inventing an attribution.
- `MaterialityPolicy` is explicitly reviewed and versioned; without a supplied reviewed threshold, severity is `NOT_ASSESSED`. Evidence consistency is NOT causal proof.
- `R4ProofAndDecisionTest` contains independent fixed values, loss threshold tests, negative cases and hypothesis-states tests.

**Limit:** The in-memory materiality policy is not yet fetched from a signed, tenant-approved PostgreSQL policy registry. No financial or causal S2 approval exists.

### R4.4: scenario comparisons and post-action monitoring

- `DecisionScenarioEngine` computes monthly do-nothing/action cash rollforwards, explicit implementation and recurring cost, restricted vs unrestricted cash, minimum liquidity and discounted incremental cash flows.
- No horizon gaps, mismatched action/baseline months, negative costs, illegal discount rate, cross-scope or implicit ACTUAL paths are permitted.
- `DecisionOutcomeMonitor` compares source-labelled closing-cash observations against a frozen scenario, rejects missing or duplicate months, and explicitly marks the result `SIMULATED_ONLY`, never confirmed causal improvement.
- `R4ProofAndDecisionTest` and `DecisionOutcomeMonitorTest` anchor independent golden/negative results.

**Limit:** No trusted actual outcome attestation, human approval workflow, durable action-execution log, reminder scheduling or outcome feedback loop exists yet. No autonomous execution.

## CI qualification gate

`.github/workflows/ci.yml` makes the following additive classes mandatory on every source SHA:
- `TrustedFinancialPopulationIntegrationTest`
- `TrustedFinancialFormMapperIntegrationTest`
- `R4ProofAndDecisionTest`
- `DecisionOutcomeMonitorTest`

CI must require Node/React/Playwright, JUnit and real PostgreSQL Testcontainers, macOS browsers and a single combined `acceptance-gate`. A green test suite is NOT a production or economic-method approval. Record the exact successful SHA and run URL.

## Next blocking tasks

1. **P0 source authenticity:** versioned XLSX issuance and bounded parser; genuine immutable raw-document digest and independently reviewed row/value manifest; preview, conflict resolution, approval provenance and integrity linkage to canonical facts.
2. **P0 security:** OIDC Keycloak subject and tenant from validated claims, ABAC by zones, periods, versions, documents and fields, plus audit and (if needed) PostgreSQL RLS. Never accept organization or approved states from the browser.
3. **P0 financial source math:** qualified F01/F02/F03 and cash bridges, 3-statement reconciliation, historical comparability, unit/currency/VAT treatment and independent finance S2.
4. **P1 policy governance:** approved methodological threshold registry, validated business-model applicability, immutable approved rule and finding snapshots.
5. **P1 decision lifecycle:** durable human-reviewed action approvals, options with true opportunity costs, approved scenario assumptions and post-action measurement from a separately authorized ACTUAL factset.
6. **P1 UI:** React proof drill-down, next-best evidence, XLSX/React authoring, verified publication, what-if and save/reopen; Playwright human and browser UAT.

No main merge or production claims until those gates are closed.
