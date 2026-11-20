--liquibase formatted sql
--changeset fin-calc:050a dbms:postgresql splitStatements:true
-- CALC-C01/C02: SYNTHETIC UAT, not financial S2 or production calc_result.
CREATE TABLE fin_calc.calc154_asof_uat_result (
 id uuid PRIMARY KEY,
 organization_id uuid NOT NULL,
 factset_id uuid NOT NULL REFERENCES fin_fact.fact_set(id),
 form_code varchar(3) NOT NULL CHECK(form_code IN ('C01','C02')),
 form_version_id uuid NOT NULL REFERENCES fin_lib.lib_form_version(id),
 policy_version varchar(64) NOT NULL CHECK(policy_version='ASOF_DEBT_POSITIVE_STOCK_UAT_V1'),
 as_of_date date NOT NULL,
 currency_code char(3) NOT NULL CHECK(currency_code ~ '^[A-Z]{3}$'),
 factset_sha256 char(64) NOT NULL CHECK(factset_sha256 ~ '^[0-9a-f]{64}$'),
 observation_sha256 char(64) NOT NULL CHECK(observation_sha256 ~ '^[0-9a-f]{64}$'),
 result_sha256 char(64) NOT NULL CHECK(result_sha256 ~ '^[0-9a-f]{64}$'),
 primary_metric_code varchar(160) NOT NULL,
 primary_value numeric(38,12) NOT NULL CHECK(primary_value>=0),
 overdue_value numeric(38,12),
 overdue_share numeric(38,12),
 overdue_share_state varchar(48) NOT NULL CHECK(overdue_share_state IN
    ('PRESENT','MISSING_OPTIONAL_INPUT','UNDEFINED_ZERO_DENOMINATOR')),
 source_row_count integer NOT NULL CHECK(source_row_count>0),
 financially_approved boolean NOT NULL DEFAULT false CHECK(financially_approved=false),
 production_admitted boolean NOT NULL DEFAULT false CHECK(production_admitted=false),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 CONSTRAINT uq_calc154_asof_uat_exact_result UNIQUE(organization_id,factset_id,form_code,policy_version),
 CONSTRAINT ck_calc154_asof_uat_ratio_status CHECK(
  (overdue_share_state='PRESENT' AND overdue_share IS NOT NULL
     AND overdue_share BETWEEN 0 AND 1 AND overdue_value IS NOT NULL)
  OR (overdue_share_state='UNDEFINED_ZERO_DENOMINATOR' AND overdue_share IS NULL
     AND overdue_value IS NOT NULL)
  OR (overdue_share_state='MISSING_OPTIONAL_INPUT' AND overdue_share IS NULL
     AND overdue_value IS NULL))
);
CREATE INDEX idx_calc154_asof_uat_org_date
 ON fin_calc.calc154_asof_uat_result(organization_id,as_of_date,form_code);

--changeset fin-calc:050b dbms:postgresql splitStatements:true
CREATE TRIGGER trg_calc154_asof_uat_result_immutable
 BEFORE UPDATE OR DELETE ON fin_calc.calc154_asof_uat_result
 FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_calc154_asof_uat_result_no_truncate
 BEFORE TRUNCATE ON fin_calc.calc154_asof_uat_result
 FOR EACH STATEMENT EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
