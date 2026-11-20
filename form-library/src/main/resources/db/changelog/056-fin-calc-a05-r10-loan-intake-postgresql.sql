--liquibase formatted sql
--changeset fin-a05:056a dbms:postgresql splitStatements:true
-- A05-R1.0: typed loan terms are an immutable companion of EXACT C05
-- fin_intake.lib_form_revision. No independent loan master/financial writer.
CREATE TABLE fin_intake.a05_loan_identity (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  economic_entity_id uuid NOT NULL,
  business_scope_id uuid NOT NULL,
  project_id uuid NOT NULL,
  instance_id uuid NOT NULL UNIQUE
    REFERENCES fin_intake.lib_form_instance(id),
  source_row_id uuid NOT NULL,
  loan_business_key varchar(120) NOT NULL
    CHECK (loan_business_key ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{2,119}$'),
  agreement_ref varchar(255) NOT NULL CHECK (length(trim(agreement_ref))>0),
  creditor_ref varchar(255) NOT NULL CHECK (length(trim(creditor_ref))>0),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_a05_loan_business_identity UNIQUE
    (organization_id,project_id,loan_business_key),
  CONSTRAINT uq_a05_loan_identity_scope UNIQUE
    (id,instance_id,organization_id,project_id)
);

CREATE TABLE fin_intake.a05_loan_contract_revision (
  id uuid PRIMARY KEY,
  identity_id uuid NOT NULL REFERENCES fin_intake.a05_loan_identity(id),
  source_revision_id uuid NOT NULL UNIQUE REFERENCES fin_intake.lib_form_revision(id),
  source_content_sha256 char(64) NOT NULL CHECK(source_content_sha256 ~ '^[0-9a-f]{64}$'),
  terms_sha256 char(64) NOT NULL CHECK(terms_sha256 ~ '^[0-9a-f]{64}$'),
  revision_number integer NOT NULL CHECK(revision_number>0),
  contract_date date NOT NULL,
  disbursement_date date NOT NULL,
  accrual_start_date date NOT NULL,
  maturity_date date NOT NULL,
  principal numeric(38,2) NOT NULL CHECK(principal>0),
  currency_code char(3) NOT NULL CHECK(currency_code='KZT'),
  jurisdiction char(2) NOT NULL CHECK(jurisdiction='KZ'),
  perspective varchar(16) NOT NULL CHECK(perspective='BORROWER'),
  repayment_method varchar(24) NOT NULL
    CHECK(repayment_method IN ('ANNUITY','EQUAL_PRINCIPAL','CUSTOM')),
  payment_frequency varchar(16) NOT NULL CHECK(payment_frequency='MONTHLY'),
  due_day integer NOT NULL CHECK(due_day BETWEEN 1 AND 28),
  interest_compounding varchar(16) NOT NULL CHECK(interest_compounding='SIMPLE'),
  day_count_convention varchar(32) NOT NULL
    CHECK(day_count_convention IN ('ACT_365_FIXED','ACT_360','THIRTY_E_360')),
  business_day_adjustment varchar(24) NOT NULL CHECK(business_day_adjustment='NONE'),
  rounding_scale integer NOT NULL CHECK(rounding_scale BETWEEN 0 AND 2),
  rounding_mode varchar(20) NOT NULL CHECK(rounding_mode IN ('HALF_EVEN','HALF_UP')),
  final_residual_rule varchar(32) NOT NULL CHECK(final_residual_rule='ADJUST_LAST_PAYMENT'),
  rate_rule_revision_id uuid NOT NULL UNIQUE,
  calendar_rule_revision_id uuid NOT NULL UNIQUE,
  change_reason varchar(255) NOT NULL CHECK(length(trim(change_reason))>0),
  financially_approved boolean NOT NULL DEFAULT false CHECK(NOT financially_approved),
  production_admitted boolean NOT NULL DEFAULT false CHECK(NOT production_admitted),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(identity_id,revision_number),
  CHECK(contract_date<=disbursement_date),
  CHECK(disbursement_date<=accrual_start_date AND accrual_start_date<maturity_date)
);
CREATE INDEX idx_a05_loan_contract_identity
 ON fin_intake.a05_loan_contract_revision(identity_id,revision_number);

CREATE TABLE fin_intake.a05_loan_rate_rule_revision (
  id uuid PRIMARY KEY,
  contract_revision_id uuid NOT NULL UNIQUE
    REFERENCES fin_intake.a05_loan_contract_revision(id),
  rate_kind varchar(24) NOT NULL CHECK(rate_kind='FIXED'),
  nominal_annual_rate_pct numeric(20,10) NOT NULL
    CHECK(nominal_annual_rate_pct>=0 AND nominal_annual_rate_pct<=500),
  effective_from date NOT NULL,
  financial_review_state varchar(32) NOT NULL DEFAULT 'UAT_NOT_REVIEWED'
    CHECK(financial_review_state='UAT_NOT_REVIEWED'),
  financially_approved boolean NOT NULL DEFAULT false CHECK(NOT financially_approved)
);

CREATE TABLE fin_intake.a05_loan_calendar_rule_revision (
  id uuid PRIMARY KEY,
  contract_revision_id uuid NOT NULL UNIQUE
    REFERENCES fin_intake.a05_loan_contract_revision(id),
  payment_frequency varchar(16) NOT NULL CHECK(payment_frequency='MONTHLY'),
  due_day integer NOT NULL CHECK(due_day BETWEEN 1 AND 28),
  day_count_convention varchar(32) NOT NULL
    CHECK(day_count_convention IN ('ACT_365_FIXED','ACT_360','THIRTY_E_360')),
  business_day_adjustment varchar(24) NOT NULL CHECK(business_day_adjustment='NONE'),
  rounding_scale integer NOT NULL CHECK(rounding_scale BETWEEN 0 AND 2),
  rounding_mode varchar(20) NOT NULL CHECK(rounding_mode IN ('HALF_EVEN','HALF_UP')),
  final_residual_rule varchar(32) NOT NULL CHECK(final_residual_rule='ADJUST_LAST_PAYMENT')
);

CREATE TABLE fin_intake.a05_loan_drawdown_revision (
  id uuid PRIMARY KEY,
  contract_revision_id uuid NOT NULL UNIQUE
    REFERENCES fin_intake.a05_loan_contract_revision(id),
  disbursement_date date NOT NULL,
  amount numeric(38,2) NOT NULL CHECK(amount>0),
  event_state varchar(20) NOT NULL CHECK(event_state='PLANNED'),
  cash_direction varchar(20) NOT NULL CHECK(cash_direction='INFLOW_BORROWER')
);

CREATE TABLE fin_intake.a05_loan_fee_rule_revision (
  id uuid PRIMARY KEY,
  contract_revision_id uuid NOT NULL
    REFERENCES fin_intake.a05_loan_contract_revision(id),
  fee_code varchar(80) NOT NULL CHECK(fee_code ~ '^[A-Z][A-Z0-9_]{1,79}$'),
  fee_rule_type varchar(40) NOT NULL CHECK(fee_rule_type IN
    ('FIXED','RECURRING_FIXED','PERCENT_OF_DISBURSEMENT',
     'PERCENT_OF_ORIGINAL','PERCENT_OF_OUTSTANDING','PERCENT_OF_LIMIT')),
  fee_settlement varchar(32) NOT NULL CHECK(fee_settlement IN
    ('UPFRONT_PAID','WITHHELD','FINANCED','RECURRING','AT_MATURITY')),
  calculation_basis varchar(32) NOT NULL CHECK(calculation_basis IN
    ('FIXED_AMOUNT','DISBURSEMENT','ORIGINAL_PRINCIPAL','OUTSTANDING','LIMIT')),
  amount_or_rate numeric(38,10) NOT NULL CHECK(amount_or_rate>0),
  frequency varchar(16) NOT NULL CHECK(frequency IN ('ONCE','MONTHLY')),
  event_date_rule varchar(32) NOT NULL CHECK(event_date_rule IN
    ('DISBURSEMENT_DATE','SCHEDULE_DUE_DATE','MATURITY_DATE')),
  ifrs_eir_classification varchar(32) NOT NULL CHECK(ifrs_eir_classification='UNREVIEWED'),
  kz_gesv_classification varchar(32) NOT NULL CHECK(kz_gesv_classification='UNREVIEWED'),
  financially_approved boolean NOT NULL DEFAULT false CHECK(NOT financially_approved),
  UNIQUE(contract_revision_id,fee_code),
  CHECK((fee_rule_type IN ('FIXED','RECURRING_FIXED') AND calculation_basis='FIXED_AMOUNT')
    OR (fee_rule_type='PERCENT_OF_DISBURSEMENT' AND calculation_basis='DISBURSEMENT')
    OR (fee_rule_type='PERCENT_OF_ORIGINAL' AND calculation_basis='ORIGINAL_PRINCIPAL')
    OR (fee_rule_type='PERCENT_OF_OUTSTANDING' AND calculation_basis='OUTSTANDING')
    OR (fee_rule_type='PERCENT_OF_LIMIT' AND calculation_basis='LIMIT')),
  CHECK((fee_rule_type IN ('FIXED','RECURRING_FIXED'))
    OR (amount_or_rate<=100)),
  CHECK((fee_settlement='RECURRING' AND frequency='MONTHLY'
       AND event_date_rule='SCHEDULE_DUE_DATE')
    OR (fee_settlement<>'RECURRING' AND frequency='ONCE')),
  CHECK((fee_rule_type='RECURRING_FIXED' AND fee_settlement='RECURRING')
    OR fee_rule_type<>'RECURRING_FIXED')
);

CREATE TABLE fin_intake.a05_loan_command_receipt (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  idempotency_key varchar(200) NOT NULL,
  request_sha256 char(64) NOT NULL CHECK(request_sha256 ~ '^[0-9a-f]{64}$'),
  contract_revision_id uuid NOT NULL UNIQUE
    REFERENCES fin_intake.a05_loan_contract_revision(id),
  source_revision_id uuid NOT NULL UNIQUE
    REFERENCES fin_intake.lib_form_revision(id),
  UNIQUE(organization_id,idempotency_key)
);

--changeset fin-a05:056b dbms:postgresql splitStatements:false
-- Verify exact C05 ROWSET, organization/project identity, and canonical SHA.
CREATE FUNCTION fin_intake.a05_verify_contract_source()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
  identity_row fin_intake.a05_loan_identity%ROWTYPE;
  source_row record;
BEGIN
  SELECT * INTO identity_row FROM fin_intake.a05_loan_identity
  WHERE id=NEW.identity_id;
  SELECT r.id,r.instance_id,r.revision_no,r.content_mode,
         r.canonical_content_sha256,i.currency,i.organization_id,i.project_id,
         i.economic_entity_id,i.business_scope_id,f.form_code
  INTO source_row
  FROM fin_intake.lib_form_revision r
  JOIN fin_intake.lib_form_instance i ON i.id=r.instance_id
  JOIN fin_lib.lib_form_definition f ON f.id=i.form_id
  WHERE r.id=NEW.source_revision_id;
  IF NOT FOUND OR source_row.form_code<>'C05'
     OR source_row.content_mode<>'ROWSET'
     OR source_row.instance_id IS DISTINCT FROM identity_row.instance_id
     OR source_row.organization_id IS DISTINCT FROM identity_row.organization_id
     OR source_row.project_id IS DISTINCT FROM identity_row.project_id
     OR source_row.economic_entity_id IS DISTINCT FROM identity_row.economic_entity_id
     OR source_row.business_scope_id IS DISTINCT FROM identity_row.business_scope_id
     OR source_row.currency IS DISTINCT FROM NEW.currency_code
     OR source_row.revision_no IS DISTINCT FROM NEW.revision_number
     OR source_row.canonical_content_sha256 IS DISTINCT FROM NEW.source_content_sha256
  THEN
    RAISE EXCEPTION 'A05_C05_REVISION_SCOPE_OR_SHA_MISMATCH' USING ERRCODE='23514';
  END IF;
  IF (SELECT count(*) FROM fin_intake.lib_form_revision_row rr
      WHERE rr.revision_id=NEW.source_revision_id)<>1 OR
     NOT EXISTS (
       SELECT 1 FROM fin_intake.lib_form_revision_row rr
       WHERE rr.revision_id=NEW.source_revision_id
         AND rr.row_id=identity_row.source_row_id
         AND rr.values_json::jsonb->>'agreementRef'=identity_row.agreement_ref
         AND rr.values_json::jsonb->>'creditor'=identity_row.creditor_ref
         AND rr.values_json::jsonb->>'currency'=NEW.currency_code
     )
  THEN
    RAISE EXCEPTION 'A05_C05_ROW_CONTRACT_MISMATCH' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;

--changeset fin-a05:056c dbms:postgresql splitStatements:false
-- Deferred so C05 FormDraftService inserts a revision FIRST, and the typed
-- companion is written LATER IN THE SAME TRANSACTION before commit.
-- Existing C05 instances without a typed A05 identity stay untouched.
CREATE FUNCTION fin_intake.a05_require_atomic_sidecar()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF EXISTS(SELECT 1 FROM fin_intake.a05_loan_identity
     WHERE instance_id=NEW.instance_id)
     AND NOT EXISTS(SELECT 1 FROM fin_intake.a05_loan_contract_revision
     WHERE source_revision_id=NEW.id)
  THEN
    RAISE EXCEPTION 'A05_TYPED_C05_COMPANION_REQUIRED' USING ERRCODE='23514';
  END IF;
  RETURN NULL;
END;
$$;

--changeset fin-a05:056d dbms:postgresql splitStatements:true
CREATE TRIGGER trg_a05_verify_contract_source
 BEFORE INSERT ON fin_intake.a05_loan_contract_revision FOR EACH ROW
 EXECUTE FUNCTION fin_intake.a05_verify_contract_source();

CREATE CONSTRAINT TRIGGER trg_a05_c05_atomic_companion
 AFTER INSERT ON fin_intake.lib_form_revision
 DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
 EXECUTE FUNCTION fin_intake.a05_require_atomic_sidecar();

CREATE TRIGGER trg_a05_loan_identity_immutable
 BEFORE UPDATE OR DELETE ON fin_intake.a05_loan_identity FOR EACH ROW
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_a05_loan_revision_immutable
 BEFORE UPDATE OR DELETE ON fin_intake.a05_loan_contract_revision FOR EACH ROW
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_a05_rate_immutable
 BEFORE UPDATE OR DELETE ON fin_intake.a05_loan_rate_rule_revision FOR EACH ROW
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_a05_calendar_immutable
 BEFORE UPDATE OR DELETE ON fin_intake.a05_loan_calendar_rule_revision FOR EACH ROW
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_a05_drawdown_immutable
 BEFORE UPDATE OR DELETE ON fin_intake.a05_loan_drawdown_revision FOR EACH ROW
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_a05_fee_immutable
 BEFORE UPDATE OR DELETE ON fin_intake.a05_loan_fee_rule_revision FOR EACH ROW
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_a05_receipt_immutable
 BEFORE UPDATE OR DELETE ON fin_intake.a05_loan_command_receipt FOR EACH ROW
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
