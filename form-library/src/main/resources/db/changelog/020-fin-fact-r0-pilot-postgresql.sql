--liquibase formatted sql
--changeset fin-fact:020a dbms:postgresql splitStatements:true
-- FACT-0: schema only. TEST_QUALIFIED is a synthetic local admission state,
-- not financial S2. No application code may infer approval from this DDL.
CREATE SCHEMA IF NOT EXISTS fin_fact;

CREATE TABLE fin_fact.fact_set (
 id UUID PRIMARY KEY,
 organization_id UUID NOT NULL,
 economic_entity_id UUID NOT NULL,
 business_scope_id UUID NOT NULL,
 project_id UUID NOT NULL,
 dataset_code VARCHAR(128) NOT NULL,
 scope_key CHAR(64) NOT NULL,
 data_space VARCHAR(32) NOT NULL,
 coverage VARCHAR(32) NOT NULL,
 reporting_basis VARCHAR(64) NOT NULL,
 currency_code CHAR(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
 target_period_type VARCHAR(32) NOT NULL,
 target_period_code VARCHAR(64) NOT NULL,
 target_period_start DATE NOT NULL,
 target_period_end_exclusive DATE NOT NULL,
 source_revisions_jsonb JSONB NOT NULL CHECK(jsonb_typeof(source_revisions_jsonb)='array'),
 source_set_sha256 CHAR(64) NOT NULL,
 mapping_bundle_sha256 CHAR(64) NOT NULL,
 canonicalization_version INT NOT NULL CHECK(canonicalization_version>0),
 observation_count INT NOT NULL CHECK(observation_count>=0),
 factset_sha256 CHAR(64) NOT NULL,
 qualification_binding_sha256 CHAR(64) NOT NULL,
 qualification_epoch BIGINT NOT NULL CHECK(qualification_epoch>=0),
 qualification_mode VARCHAR(32) NOT NULL CHECK(qualification_mode='TEST_QUALIFIED'),
 issuer VARCHAR(128) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 CONSTRAINT ck_factset_period CHECK(target_period_start<target_period_end_exclusive),
 CONSTRAINT uq_factset_scope_identity UNIQUE(id,organization_id,scope_key),
 CONSTRAINT uq_factset_exact_binding UNIQUE(organization_id,factset_sha256,qualification_binding_sha256)
);
CREATE INDEX idx_factset_period_scope
 ON fin_fact.fact_set(organization_id,scope_key,target_period_start,created_at DESC);

-- A UUID-only PK is not legal on PostgreSQL partitioned RANGE(period_start).
-- Do NOT add DEFAULT PARTITION: missing historical coverage must fail closed.
CREATE TABLE fin_fact.fact_observation (
 period_start DATE NOT NULL,
 observation_id UUID NOT NULL,
 factset_id UUID NOT NULL,
 source_revision_id UUID NOT NULL,
 source_form_code VARCHAR(128) NOT NULL,
 source_form_version_id UUID NOT NULL,
 source_row_key VARCHAR(128) NOT NULL,
 source_field_code VARCHAR(128) NOT NULL,
 source_content_sha256 CHAR(64) NOT NULL,
 mapping_sha256 CHAR(64) NOT NULL,
 metric_code VARCHAR(160) NOT NULL,
 metric_contract_version VARCHAR(64) NOT NULL,
 period_type VARCHAR(32) NOT NULL,
 period_end_exclusive DATE NOT NULL,
 period_semantics VARCHAR(32) NOT NULL CHECK(period_semantics IN
  ('INTERVAL','AS_OF','EVENT','YEAR_TO_DATE','LIFE_TO_DATE')),
 dimension_digest CHAR(64) NOT NULL,
 dimensions_jsonb JSONB NOT NULL DEFAULT '{}'::jsonb
  CHECK(jsonb_typeof(dimensions_jsonb)='object'),
 observation_key_sha256 CHAR(64) NOT NULL,
 value_kind VARCHAR(24) NOT NULL CHECK(value_kind='NUMERIC'),
 value_state VARCHAR(24) NOT NULL CHECK(value_state='PRESENT'),
 numeric_value NUMERIC(38,12) NOT NULL,
 unit_code VARCHAR(64) NOT NULL,
 currency_code CHAR(3) CHECK(currency_code IS NULL OR currency_code ~ '^[A-Z]{3}$'),
 canonical_value_sha256 CHAR(64) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 CONSTRAINT pk_fact_observation PRIMARY KEY(period_start,observation_id),
 CONSTRAINT uq_fact_observation_business_key UNIQUE(period_start,factset_id,observation_key_sha256),
 CONSTRAINT fk_fact_observation_factset FOREIGN KEY(factset_id)
  REFERENCES fin_fact.fact_set(id),
 CONSTRAINT fk_fact_observation_revision FOREIGN KEY(source_revision_id)
  REFERENCES fin_intake.lib_form_revision(id),
 CONSTRAINT fk_fact_observation_form_version FOREIGN KEY(source_form_version_id)
  REFERENCES fin_lib.lib_form_version(id),
 CONSTRAINT ck_fact_observation_period CHECK(period_start<period_end_exclusive)
) PARTITION BY RANGE(period_start);
CREATE INDEX idx_fact_observation_factset_period
 ON fin_fact.fact_observation(factset_id,period_start);
CREATE INDEX idx_fact_observation_period_metric
 ON fin_fact.fact_observation(period_start,metric_code,factset_id);

CREATE TABLE fin_fact.factset_control (
 id UUID PRIMARY KEY,
 stable_scope_key CHAR(64) NOT NULL UNIQUE,
 organization_id UUID NOT NULL,
 active_factset_id UUID,
 qualification_epoch BIGINT NOT NULL CHECK(qualification_epoch>=0),
 revoked BOOLEAN NOT NULL DEFAULT false,
 row_version BIGINT NOT NULL DEFAULT 0 CHECK(row_version>=0),
 updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 CONSTRAINT fk_factset_control_active_scope FOREIGN KEY
  (active_factset_id,organization_id,stable_scope_key)
  REFERENCES fin_fact.fact_set(id,organization_id,scope_key)
);

-- Both backends exist now. A plan may ONLY reference a persisted fact set.
ALTER TABLE fin_calc.calc_plan ADD CONSTRAINT fk_calc_plan_factset
 FOREIGN KEY(factset_id) REFERENCES fin_fact.fact_set(id);

--changeset fin-fact:020b dbms:postgresql splitStatements:false
CREATE FUNCTION fin_fact.reject_immutable_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'FIN_FACT_APPEND_ONLY' USING ERRCODE='23514';
END;
$$;

--changeset fin-fact:020c dbms:postgresql splitStatements:true
-- Fact headers, numeric observations, plans and accepted results are append-only.
-- Outbox, job lease and CURRENT pointer remain mutable by controlled services.
CREATE TRIGGER trg_factset_append_only
 BEFORE UPDATE OR DELETE ON fin_fact.fact_set
 FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_observation_append_only
 BEFORE UPDATE OR DELETE ON fin_fact.fact_observation
 FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_calc_plan_append_only
 BEFORE UPDATE OR DELETE ON fin_calc.calc_plan
 FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_calc_result_append_only
 BEFORE UPDATE OR DELETE ON fin_calc.calc_result
 FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
