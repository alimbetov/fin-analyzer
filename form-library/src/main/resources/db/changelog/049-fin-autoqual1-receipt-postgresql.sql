--liquibase formatted sql
--changeset fin-calc:049a dbms:postgresql splitStatements:true
-- AUTOQUAL-1: append-only evidence receipts, deliberately independent of S2.
CREATE TABLE fin_calc.method_qualification_receipt (
 id uuid PRIMARY KEY,
 form_code varchar(64) NOT NULL,
 output_metric_code varchar(160) NOT NULL,
 qualification_class varchar(1) NOT NULL CHECK (qualification_class IN ('A','B','C','D','E')),
 qualification_status varchar(40) NOT NULL CHECK (qualification_status IN (
   'FORMULA_VERIFIED','POLICY_INCOMPLETE','EVIDENCE_INCOMPLETE',
   'MANUAL_REVIEW_REQUIRED','AUTO_QUALIFIED')),
 formula_sha256 char(64) NOT NULL CHECK (formula_sha256 ~ '^[0-9a-f]{64}$'),
 artifact_sha256 char(64) NOT NULL CHECK (artifact_sha256 ~ '^[0-9a-f]{64}$'),
 factset_sha256 char(64) NOT NULL CHECK (factset_sha256 ~ '^[0-9a-f]{64}$'),
 request_sha256 char(64) NOT NULL CHECK (request_sha256 ~ '^[0-9a-f]{64}$'),
 policy_sha256 char(64),
 test_evidence_sha256 char(64),
 standard_reference text,
 standard_edition text,
 blockers_jsonb jsonb NOT NULL CHECK (jsonb_typeof(blockers_jsonb)='array'),
 financial_s2_approved boolean NOT NULL DEFAULT false CHECK (NOT financial_s2_approved),
 production_admitted boolean NOT NULL DEFAULT false CHECK (NOT production_admitted),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(request_sha256)
);

--changeset fin-calc:049b dbms:postgresql splitStatements:false
CREATE FUNCTION fin_calc.method_qualification_receipt_immutable()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 RAISE EXCEPTION 'AUTOQUAL_IMMUTABLE_RECEIPT' USING ERRCODE='23514';
END;
$$;

--changeset fin-calc:049c dbms:postgresql splitStatements:true
CREATE TRIGGER trg_autoqual_no_update_delete BEFORE UPDATE OR DELETE
 ON fin_calc.method_qualification_receipt FOR EACH ROW
 EXECUTE FUNCTION fin_calc.method_qualification_receipt_immutable();
