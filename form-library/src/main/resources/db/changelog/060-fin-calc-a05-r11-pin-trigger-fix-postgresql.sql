--liquibase formatted sql
--changeset fin-a05:060a dbms:postgresql splitStatements:false
-- Fix 059 PL/pgSQL local record "found" shadowing boolean special FOUND.
-- The original financial tables/contracts are unchanged.
CREATE OR REPLACE FUNCTION fin_calc.a05_verify_schedule_pins()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE pinned record;
BEGIN
 SELECT l.organization_id,l.project_id,
  c.source_revision_id,c.source_content_sha256,c.terms_sha256,
  c.currency_code,c.repayment_method,c.day_count_convention,
  c.rounding_scale,c.rounding_mode,
  a.app_code,a.contract_sha256
 INTO pinned
 FROM fin_intake.a05_loan_contract_revision c
 JOIN fin_intake.a05_loan_identity l ON l.id=c.identity_id
 JOIN fin_calc.calc_application_contract_revision a
   ON a.id=NEW.app_contract_revision_id
 WHERE c.id=NEW.contract_revision_id;
 IF NOT FOUND
   OR pinned.organization_id IS DISTINCT FROM NEW.organization_id
   OR pinned.project_id IS DISTINCT FROM NEW.project_id
   OR pinned.source_revision_id IS DISTINCT FROM NEW.source_revision_id
   OR pinned.source_content_sha256 IS DISTINCT FROM NEW.source_content_sha256
   OR pinned.terms_sha256 IS DISTINCT FROM NEW.terms_sha256
   OR pinned.currency_code IS DISTINCT FROM NEW.currency_code
   OR pinned.repayment_method IS DISTINCT FROM NEW.repayment_method
   OR pinned.day_count_convention IS DISTINCT FROM NEW.day_count_convention
   OR pinned.rounding_scale IS DISTINCT FROM NEW.rounding_scale
   OR pinned.rounding_mode IS DISTINCT FROM NEW.rounding_mode
   OR pinned.app_code<>'CALC-A05'
   OR pinned.contract_sha256 IS DISTINCT FROM NEW.app_contract_sha256
 THEN
   RAISE EXCEPTION 'A05_R11_EXACT_C05_OR_APPLICATION_PIN_MISMATCH' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$$;
