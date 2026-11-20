--liquibase formatted sql
--changeset fin-mg:057a dbms:postgresql splitStatements:true
-- MG-R0.2. Binding an editorial business indicator to a physical field or
-- an EXACT existing formula revision is an explicit candidate contract.
-- No text-name inference, no additional metric registry and no execution.
CREATE TABLE fin_calc.metric_indicator_binding_revision (
  id uuid PRIMARY KEY,
  goal_code varchar(64) NOT NULL CHECK(goal_code ~ '^[A-Z][A-Z0-9_]{2,63}$'),
  indicator_code varchar(160) NOT NULL
    CHECK(indicator_code ~ '^[A-Z][A-Z0-9_]{2,159}$'),
  revision_number integer NOT NULL CHECK(revision_number>0),
  target_metric_code varchar(160) NOT NULL CHECK(length(trim(target_metric_code))>0),
  binding_kind varchar(32) NOT NULL
    CHECK(binding_kind IN ('SOURCE_FIELD','FORMULA_REVISION')),
  formula_revision_id uuid,
  evidence_ref varchar(160) NOT NULL CHECK(length(trim(evidence_ref))>0),
  binding_sha256 char(64) NOT NULL CHECK(binding_sha256 ~ '^[0-9a-f]{64}$'),
  review_state varchar(40) NOT NULL DEFAULT 'EDITORIAL_CANDIDATE_NOT_S2'
    CHECK(review_state='EDITORIAL_CANDIDATE_NOT_S2'),
  financially_approved boolean NOT NULL DEFAULT false CHECK(NOT financially_approved),
  production_admitted boolean NOT NULL DEFAULT false CHECK(NOT production_admitted),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(goal_code,indicator_code,revision_number),
  CHECK((binding_kind='SOURCE_FIELD' AND formula_revision_id IS NULL)
    OR (binding_kind='FORMULA_REVISION' AND formula_revision_id IS NOT NULL))
);
CREATE INDEX idx_mg_indicator_candidates
 ON fin_calc.metric_indicator_binding_revision(goal_code,indicator_code,revision_number);

--changeset fin-mg:057b dbms:postgresql splitStatements:false
CREATE FUNCTION fin_calc.verify_metric_indicator_binding()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE expected text;
BEGIN
  expected:=encode(sha256(convert_to(
    'FIN-MG-R02-INDICATOR-LINK-V1|'||NEW.goal_code||'|'||
    NEW.indicator_code||'|'||NEW.revision_number::text||'|'||
    NEW.target_metric_code||'|'||NEW.binding_kind||'|'||
    coalesce(NEW.formula_revision_id::text,'NONE')||'|'||NEW.evidence_ref,
    'UTF8')),'hex');
  IF NEW.binding_sha256 IS DISTINCT FROM expected THEN
    RAISE EXCEPTION 'MG_INDICATOR_BINDING_SHA_DRIFT' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;

--changeset fin-mg:057c dbms:postgresql splitStatements:true
CREATE TRIGGER trg_mg_binding_verify
 BEFORE INSERT ON fin_calc.metric_indicator_binding_revision
 FOR EACH ROW EXECUTE FUNCTION fin_calc.verify_metric_indicator_binding();
CREATE TRIGGER trg_mg_binding_immutable
 BEFORE UPDATE OR DELETE ON fin_calc.metric_indicator_binding_revision
 FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_mg_binding_no_truncate
 BEFORE TRUNCATE ON fin_calc.metric_indicator_binding_revision
 FOR EACH STATEMENT EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

--changeset fin-mg:057d dbms:postgresql splitStatements:true
-- The fin_lib form catalog is bootstrapped by Java AFTER Liquibase;
-- these explicit symbolic links are validated on read against live catalog.
-- NET_PROFIT is sourced from F02.netProfit, NOT gross/operating profit.
-- CASH_END is sourced from F03.closingCash, NOT an assumed calculation.
WITH seed(id,goal_code,indicator_code,revision_number,target_metric_code,
          binding_kind,evidence_ref) AS (
 VALUES
 ('7e5480ab-9d2d-467d-a1b7-d4f388661001'::uuid,
  'BANK_FINANCING','NET_PROFIT',1,'F02.netProfit',
  'SOURCE_FIELD','CFO-01_F02_SOURCE_FIELD_CANDIDATE'),
 ('7e5480ab-9d2d-467d-a1b7-d4f388661002'::uuid,
  'BANK_FINANCING','CASH_END',1,'F03.closingCash',
  'SOURCE_FIELD','CFO-01_F03_SOURCE_FIELD_CANDIDATE')
)
INSERT INTO fin_calc.metric_indicator_binding_revision
 (id,goal_code,indicator_code,revision_number,target_metric_code,
  binding_kind,evidence_ref,binding_sha256)
SELECT id,goal_code,indicator_code,revision_number,target_metric_code,
       binding_kind,evidence_ref,
       encode(sha256(convert_to(
        'FIN-MG-R02-INDICATOR-LINK-V1|'||goal_code||'|'||
        indicator_code||'|'||revision_number::text||'|'||
        target_metric_code||'|'||binding_kind||'|NONE|'||evidence_ref,
        'UTF8')),'hex')
FROM seed;
