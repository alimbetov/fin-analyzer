-- FIN-SIM-R1: synthetic-only quality benchmark, separate from operational facts.
-- No seed rows are installed by Flyway; loading is an explicit test/local action.
CREATE SCHEMA IF NOT EXISTS fin_sim;

CREATE TABLE fin_sim.dataset (
    scenario_code VARCHAR(64) PRIMARY KEY,
    sector_code CHAR(1) NOT NULL CHECK (sector_code ~ '^[A-U]$'),
    profile_code VARCHAR(64) NOT NULL,
    generated_version VARCHAR(32) NOT NULL,
    first_month DATE NOT NULL,
    last_month DATE NOT NULL,
    currency_code CHAR(3) NOT NULL CHECK (currency_code ~ '^[A-Z]{3}$'),
    expected_outcome VARCHAR(32) NOT NULL CHECK (
        expected_outcome IN ('DIAGNOSE','REJECT_CURRENCY')),
    note TEXT NOT NULL,
    CONSTRAINT ck_dataset_months CHECK (
        first_month <= last_month
        AND EXTRACT(DAY FROM first_month)=1
        AND EXTRACT(DAY FROM last_month)=1)
);

CREATE TABLE fin_sim.observation (
    scenario_code VARCHAR(64) NOT NULL REFERENCES fin_sim.dataset(scenario_code),
    source_revision SMALLINT NOT NULL DEFAULT 1 CHECK (source_revision > 0),
    metric_code VARCHAR(128) NOT NULL CHECK (metric_code ~ '^[A-Z][A-Z0-9_]{1,127}$'),
    metric_version VARCHAR(64) NOT NULL,
    period_month DATE NOT NULL CHECK (EXTRACT(DAY FROM period_month)=1),
    time_kind VARCHAR(16) NOT NULL CHECK (time_kind IN ('INTERVAL','AS_OF')),
    measure_kind VARCHAR(16) NOT NULL CHECK (measure_kind IN ('FLOW','STOCK','LEVEL')),
    unit_code VARCHAR(32) NOT NULL,
    currency_code CHAR(3),
    source_form_code VARCHAR(128) NOT NULL,
    source_field_code VARCHAR(128) NOT NULL,
    value_state VARCHAR(24) NOT NULL CHECK (
        value_state IN ('PRESENT','EXPLICIT_ZERO','MISSING','NOT_APPLICABLE','ESTIMATED')),
    numeric_value NUMERIC(38,12),
    estimate_method_ref VARCHAR(200),
    PRIMARY KEY (scenario_code,source_revision,metric_code,period_month),
    CONSTRAINT ck_sim_value CHECK (
       (value_state IN ('PRESENT','EXPLICIT_ZERO','ESTIMATED') AND numeric_value IS NOT NULL)
       OR (value_state IN ('MISSING','NOT_APPLICABLE') AND numeric_value IS NULL)),
    CONSTRAINT ck_sim_zero CHECK (value_state<>'EXPLICIT_ZERO' OR numeric_value=0),
    CONSTRAINT ck_sim_estimate CHECK (
       (value_state='ESTIMATED' AND estimate_method_ref IS NOT NULL)
       OR (value_state<>'ESTIMATED' AND estimate_method_ref IS NULL)),
    CONSTRAINT ck_sim_currency CHECK (
       (unit_code='MONEY' AND currency_code ~ '^[A-Z]{3}$')
       OR (unit_code<>'MONEY' AND currency_code IS NULL)),
    CONSTRAINT ck_sim_finite CHECK (
       numeric_value IS NULL OR numeric_value::text NOT IN ('NaN','Infinity','-Infinity'))
);

CREATE TABLE fin_sim.expected_finding (
    scenario_code VARCHAR(64) NOT NULL REFERENCES fin_sim.dataset(scenario_code),
    source_revision SMALLINT NOT NULL DEFAULT 1,
    rule_code VARCHAR(128) NOT NULL,
    finding_kind VARCHAR(32) NOT NULL CHECK (
        finding_kind IN ('OBSERVATION','HYPOTHESIS','MISSING_EVIDENCE')),
    expected_count SMALLINT NOT NULL CHECK (expected_count BETWEEN 0 AND 10),
    PRIMARY KEY (scenario_code,source_revision,rule_code,finding_kind)
);

CREATE TABLE fin_sim.evaluation_run (
    id UUID PRIMARY KEY,
    scenario_code VARCHAR(64) NOT NULL REFERENCES fin_sim.dataset(scenario_code),
    source_revision SMALLINT NOT NULL,
    organization_id UUID NOT NULL,
    fact_set_revision_id UUID NOT NULL,
    assessment_id UUID,
    engine_version VARCHAR(64) NOT NULL,
    passed BOOLEAN NOT NULL,
    expected_finding_total INTEGER NOT NULL,
    actual_finding_total INTEGER NOT NULL,
    executed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_sim_fact_set_tenant FOREIGN KEY (fact_set_revision_id,organization_id)
        REFERENCES fin_fact.fact_set_publication(fact_set_revision_id,organization_id),
    CONSTRAINT fk_sim_assessment_tenant FOREIGN KEY (assessment_id,organization_id)
        REFERENCES fin_diag.assessment_run(id,organization_id)
);
CREATE INDEX idx_sim_month_metric ON fin_sim.observation(
    scenario_code,period_month,metric_code);
CREATE INDEX idx_sim_runs_by_scenario ON fin_sim.evaluation_run(
    scenario_code,executed_at DESC);

-- This is diagnostic evidence, not a real FormRegistry.
-- Production code must never copy these rows into ACTUAL or PLAN.
