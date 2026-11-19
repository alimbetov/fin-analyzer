-- FIN HTAP v2 / fin_fact DDL PROPOSAL — PostgreSQL 16
-- HISTORICAL DESIGN DRAFT ONLY, NOT executed as a Liquibase changeset.
-- Source of truth is now the ACTUAL runtime migrations in form-library:
--   019-fin-calc-r0-postgresql.sql
--   020-fin-fact-r0-pilot-postgresql.sql
--   021-fin-fact-scope-source-immutability-postgresql.sql
-- This draft still contains hard-coded example partitions; actual runtime
-- partitions are created by FactMonthlyPartitionService with bound verification.
-- DDL is deliberately non-destructive and does not auto-create a DEFAULT partition.
-- Genuine S2 approval, IAM and tenant isolation are NOT supplied by this SQL.

CREATE SCHEMA IF NOT EXISTS fin_fact;

CREATE TABLE fin_fact.fact_set (
    id                          UUID PRIMARY KEY,
    organization_id             UUID NOT NULL,
    economic_entity_id          UUID NOT NULL,
    business_scope_id           UUID NOT NULL,
    project_id                  UUID NOT NULL,
    dataset_code                VARCHAR(128) NOT NULL,
    scope_key                   CHAR(64) NOT NULL,
    data_space                  VARCHAR(32) NOT NULL,
    coverage                    VARCHAR(32) NOT NULL,
    reporting_basis             VARCHAR(64) NOT NULL,
    currency_code               CHAR(3) NOT NULL,
    target_period_type          VARCHAR(32) NOT NULL,
    target_period_code          VARCHAR(64) NOT NULL,
    target_period_start         DATE NOT NULL,
    target_period_end_exclusive DATE NOT NULL,
    source_revisions_jsonb      JSONB NOT NULL,
    source_set_sha256           CHAR(64) NOT NULL,
    mapping_bundle_sha256       CHAR(64) NOT NULL,
    canonicalization_version    INTEGER NOT NULL CHECK (canonicalization_version > 0),
    observation_count           INTEGER NOT NULL CHECK (observation_count >= 0),
    factset_sha256              CHAR(64) NOT NULL,
    qualification_binding_sha256 CHAR(64) NOT NULL,
    qualification_epoch         BIGINT NOT NULL CHECK (qualification_epoch >= 0),
    qualification_mode          VARCHAR(32) NOT NULL CHECK (qualification_mode IN ('TEST_QUALIFIED')),
    issuer                      VARCHAR(128) NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_factset_interval CHECK (target_period_start < target_period_end_exclusive),
    CONSTRAINT ck_factset_source_refs CHECK (jsonb_typeof(source_revisions_jsonb) = 'array'),
    CONSTRAINT ck_factset_currency CHECK (currency_code ~ '^[A-Z]{3}$'),
    -- Redundant composite key provides DB-level tenant/scope safety for active pointer.
    CONSTRAINT uq_factset_scope_identity UNIQUE (id, organization_id, scope_key),
    CONSTRAINT uq_factset_qualified_binding UNIQUE
         (organization_id, factset_sha256, qualification_binding_sha256)
);
CREATE INDEX idx_factset_scope_period
    ON fin_fact.fact_set (organization_id, scope_key, target_period_start, created_at DESC);

CREATE TABLE fin_fact.fact_observation (
    period_start                DATE NOT NULL,
    observation_id              UUID NOT NULL,
    factset_id                  UUID NOT NULL
        REFERENCES fin_fact.fact_set(id),
    source_revision_id          UUID NOT NULL
        REFERENCES fin_intake.lib_form_revision(id),
    source_form_code            VARCHAR(128) NOT NULL,
    source_form_version_id      UUID NOT NULL
        REFERENCES fin_lib.lib_form_version(id),
    source_row_key              VARCHAR(128) NOT NULL,
    source_field_code           VARCHAR(128) NOT NULL,
    source_content_sha256       CHAR(64) NOT NULL,
    mapping_sha256              CHAR(64) NOT NULL,
    metric_code                 VARCHAR(160) NOT NULL,
    metric_contract_version     VARCHAR(64) NOT NULL,
    period_type                 VARCHAR(32) NOT NULL,
    period_end_exclusive        DATE NOT NULL,
    period_semantics            VARCHAR(32) NOT NULL
        CHECK (period_semantics IN ('INTERVAL','AS_OF','EVENT','YEAR_TO_DATE','LIFE_TO_DATE')),
    dimension_digest            CHAR(64) NOT NULL,
    dimensions_jsonb            JSONB NOT NULL DEFAULT '{}'::jsonb,
    observation_key_sha256      CHAR(64) NOT NULL,
    value_kind                  VARCHAR(24) NOT NULL CHECK (value_kind = 'NUMERIC'),
    value_state                 VARCHAR(24) NOT NULL CHECK (value_state = 'PRESENT'),
    numeric_value               NUMERIC(38,12) NOT NULL,
    unit_code                   VARCHAR(64) NOT NULL,
    currency_code               CHAR(3),
    canonical_value_sha256      CHAR(64) NOT NULL,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- PostgreSQL 16: PK and all UNIQUE constraints include partition key.
    CONSTRAINT pk_fact_observation PRIMARY KEY (period_start, observation_id),
    CONSTRAINT uq_fact_observation_business_key
        UNIQUE (period_start, factset_id, observation_key_sha256),
    CONSTRAINT ck_fact_observation_interval CHECK (period_start < period_end_exclusive),
    CONSTRAINT ck_fact_observation_dimensions CHECK (jsonb_typeof(dimensions_jsonb) = 'object'),
    CONSTRAINT ck_fact_observation_currency CHECK
        (currency_code IS NULL OR currency_code ~ '^[A-Z]{3}$')
) PARTITION BY RANGE (period_start);

CREATE INDEX idx_fact_observation_scope_period_metric
    ON fin_fact.fact_observation (period_start, factset_id, metric_code);
CREATE INDEX idx_fact_observation_factset_period
    ON fin_fact.fact_observation (factset_id, period_start);

CREATE TABLE fin_fact.factset_control (
    id                   UUID PRIMARY KEY,
    stable_scope_key     CHAR(64) NOT NULL UNIQUE,
    organization_id      UUID NOT NULL,
    active_factset_id    UUID,
    qualification_epoch  BIGINT NOT NULL CHECK (qualification_epoch >= 0),
    revoked              BOOLEAN NOT NULL DEFAULT false,
    row_version          BIGINT NOT NULL DEFAULT 0 CHECK (row_version >= 0),
    updated_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- No cross-tenant or wrong-scope pointer allowed at DB layer.
    CONSTRAINT fk_factset_active_same_scope
        FOREIGN KEY (active_factset_id, organization_id, stable_scope_key)
        REFERENCES fin_fact.fact_set (id, organization_id, scope_key)
);

-- Immutable rows can never be updated; revoke by factset_control epoch instead.
-- If creating this as Liquibase formatted SQL use splitStatements:false for the function.
CREATE FUNCTION fin_fact.reject_immutable_mutation() RETURNS TRIGGER
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'FIN_FACT_APPEND_ONLY'
        USING ERRCODE = '23514';
END;
$$;
CREATE TRIGGER trg_fact_set_append_only
    BEFORE UPDATE OR DELETE ON fin_fact.fact_set
    FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_fact_observation_append_only
    BEFORE UPDATE OR DELETE ON fin_fact.fact_observation
    FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

-- PRE-CREATE monthly range partitions in a short independent maintenance TX.
-- This example is not a production roll-forward; actual tool checks bounds and indexes.
CREATE TABLE fin_fact.fact_observation_2026_10
    PARTITION OF fin_fact.fact_observation
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE fin_fact.fact_observation_2026_11
    PARTITION OF fin_fact.fact_observation
    FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');

-- Future final Liquibase 020, AFTER both schemas exist:
-- ALTER TABLE fin_calc.calc_plan
--   ADD CONSTRAINT fk_calc_plan_factset
--     FOREIGN KEY(factset_id) REFERENCES fin_fact.fact_set(id);
--
-- IMPORTANT:
-- * No MERGE UPDATE of immutable fact rows. Append new rows for new qualified FactSet.
-- * Ingestion performs exact source/tenant/mapping SHA and count verification in TX-Q.
-- * Writers must check required monthly partition coverage BEFORE TX-Q; no DDL in save TX.
-- * Never DROP a financial partition automatically.
-- * Test-only qualifier is not financial S2.
