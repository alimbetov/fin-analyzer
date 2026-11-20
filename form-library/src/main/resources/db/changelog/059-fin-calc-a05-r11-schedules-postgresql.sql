--liquibase formatted sql
--changeset fin-a05:059a dbms:postgresql splitStatements:true
-- CALC-A05 R1.1: immutable, UAT-only calculated contractual cash schedule.
-- Source remains the exact C05 fin_intake + typed A05-R1.0 contract.
-- No EIR, GESV, booked F03 cash, 167th form, financial qualification or S2.
CREATE TABLE fin_calc.a05_schedule_revision (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  project_id uuid NOT NULL,
  source_revision_id uuid NOT NULL
    REFERENCES fin_intake.lib_form_revision(id),
  contract_revision_id uuid NOT NULL
    REFERENCES fin_intake.a05_loan_contract_revision(id),
  app_contract_revision_id uuid NOT NULL
    REFERENCES fin_calc.calc_application_contract_revision(id),
  app_contract_sha256 char(64) NOT NULL
    CHECK(app_contract_sha256 ~ '^[0-9a-f]{64}$'),
  source_content_sha256 char(64) NOT NULL
    CHECK(source_content_sha256 ~ '^[0-9a-f]{64}$'),
  terms_sha256 char(64) NOT NULL
    CHECK(terms_sha256 ~ '^[0-9a-f]{64}$'),
  engine_descriptor_sha256 char(64) NOT NULL
    CHECK(engine_descriptor_sha256 ~ '^[0-9a-f]{64}$'),
  custom_input_sha256 char(64) NOT NULL
    CHECK(custom_input_sha256 ~ '^[0-9a-f]{64}$'),
  variant_sha256 char(64) NOT NULL
    CHECK(variant_sha256 ~ '^[0-9a-f]{64}$'),
  result_sha256 char(64) NOT NULL
    CHECK(result_sha256 ~ '^[0-9a-f]{64}$'),
  repayment_method varchar(24) NOT NULL
    CHECK(repayment_method IN('ANNUITY','EQUAL_PRINCIPAL','CUSTOM')),
  currency_code char(3) NOT NULL CHECK(currency_code='KZT'),
  day_count_convention varchar(32) NOT NULL
    CHECK(day_count_convention IN('ACT_365_FIXED','ACT_360','THIRTY_E_360')),
  rounding_scale integer NOT NULL CHECK(rounding_scale BETWEEN 0 AND 2),
  rounding_mode varchar(20) NOT NULL
    CHECK(rounding_mode IN('HALF_EVEN','HALF_UP')),
  drawdown numeric(38,2) NOT NULL CHECK(drawdown>0),
  financed_fees numeric(38,2) NOT NULL CHECK(financed_fees>=0),
  withheld_fees numeric(38,2) NOT NULL CHECK(withheld_fees>=0),
  upfront_paid_fees numeric(38,2) NOT NULL CHECK(upfront_paid_fees>=0),
  net_cash_proceeds numeric(38,2) NOT NULL CHECK(net_cash_proceeds>=0),
  opening_liability numeric(38,2) NOT NULL CHECK(opening_liability>0),
  total_principal numeric(38,2) NOT NULL CHECK(total_principal>0),
  total_contractual_interest numeric(38,2) NOT NULL
    CHECK(total_contractual_interest>=0),
  total_scheduled_fees numeric(38,2) NOT NULL CHECK(total_scheduled_fees>=0),
  total_scheduled_cash numeric(38,2) NOT NULL CHECK(total_scheduled_cash>=0),
  final_balance numeric(38,2) NOT NULL CHECK(final_balance=0),
  row_count integer NOT NULL CHECK(row_count BETWEEN 1 AND 600),
  state varchar(32) NOT NULL DEFAULT 'UAT_UNQUALIFIED'
    CHECK(state='UAT_UNQUALIFIED'),
  financially_approved boolean NOT NULL DEFAULT false
    CHECK(NOT financially_approved),
  production_admitted boolean NOT NULL DEFAULT false
    CHECK(NOT production_admitted),
  created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
  CONSTRAINT uq_a05_schedule_variant UNIQUE(source_revision_id,variant_sha256),
  CHECK(opening_liability=drawdown+financed_fees),
  CHECK(net_cash_proceeds=drawdown-withheld_fees-upfront_paid_fees),
  CHECK(total_principal=opening_liability),
  CHECK(total_scheduled_cash=
        total_principal+total_contractual_interest+total_scheduled_fees)
);
CREATE INDEX idx_a05_schedule_exact_source
  ON fin_calc.a05_schedule_revision(organization_id,project_id,source_revision_id);

CREATE TABLE fin_calc.a05_schedule_custom_installment (
  schedule_revision_id uuid NOT NULL
    REFERENCES fin_calc.a05_schedule_revision(id),
  installment_no integer NOT NULL CHECK(installment_no>0),
  due_at date NOT NULL,
  requested_principal numeric(38,2) NOT NULL CHECK(requested_principal>=0),
  PRIMARY KEY(schedule_revision_id,installment_no),
  UNIQUE(schedule_revision_id,due_at)
);

CREATE TABLE fin_calc.a05_schedule_line (
  schedule_revision_id uuid NOT NULL
    REFERENCES fin_calc.a05_schedule_revision(id),
  installment_no integer NOT NULL CHECK(installment_no>0),
  accrual_from date NOT NULL,
  due_at date NOT NULL CHECK(due_at>accrual_from),
  year_fraction_raw numeric(38,24) NOT NULL CHECK(year_fraction_raw>0),
  opening_principal numeric(38,2) NOT NULL CHECK(opening_principal>=0),
  contractual_interest_raw numeric(38,12) NOT NULL CHECK(contractual_interest_raw>=0),
  contractual_interest_due numeric(38,2) NOT NULL
    CHECK(contractual_interest_due>=0),
  principal_due numeric(38,2) NOT NULL CHECK(principal_due>=0),
  fees_due numeric(38,2) NOT NULL CHECK(fees_due>=0),
  closing_principal numeric(38,2) NOT NULL CHECK(closing_principal>=0),
  total_cash_due numeric(38,2) NOT NULL CHECK(total_cash_due>=0),
  PRIMARY KEY(schedule_revision_id,installment_no),
  UNIQUE(schedule_revision_id,due_at),
  CHECK(closing_principal=opening_principal-principal_due),
  CHECK(total_cash_due=principal_due+contractual_interest_due+fees_due)
);

CREATE TABLE fin_calc.a05_schedule_line_fee (
  schedule_revision_id uuid NOT NULL,
  installment_no integer NOT NULL,
  fee_code varchar(80) NOT NULL,
  fee_amount numeric(38,2) NOT NULL CHECK(fee_amount>0),
  PRIMARY KEY(schedule_revision_id,installment_no,fee_code),
  FOREIGN KEY(schedule_revision_id,installment_no)
    REFERENCES fin_calc.a05_schedule_line(schedule_revision_id,installment_no)
);

CREATE TABLE fin_calc.a05_schedule_command_receipt (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL,
  idempotency_key varchar(200) NOT NULL,
  request_sha256 char(64) NOT NULL CHECK(request_sha256 ~ '^[0-9a-f]{64}$'),
  schedule_revision_id uuid NOT NULL
    REFERENCES fin_calc.a05_schedule_revision(id),
  UNIQUE(organization_id,idempotency_key)
);

--changeset fin-a05:059b dbms:postgresql splitStatements:false
-- Fail closed if a schedule is inserted for a wrong org/project, C05
-- revision, source SHA, typed terms or candidate CALC-A05 contract.
CREATE FUNCTION fin_calc.a05_verify_schedule_pins()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE found record;
BEGIN
 SELECT l.organization_id,l.project_id,
  c.source_revision_id,c.source_content_sha256,c.terms_sha256,
  c.currency_code,c.repayment_method,c.day_count_convention,
  c.rounding_scale,c.rounding_mode,
  a.app_code,a.contract_sha256
 INTO found
 FROM fin_intake.a05_loan_contract_revision c
 JOIN fin_intake.a05_loan_identity l ON l.id=c.identity_id
 JOIN fin_calc.calc_application_contract_revision a
   ON a.id=NEW.app_contract_revision_id
 WHERE c.id=NEW.contract_revision_id;
 IF NOT FOUND
   OR found.organization_id IS DISTINCT FROM NEW.organization_id
   OR found.project_id IS DISTINCT FROM NEW.project_id
   OR found.source_revision_id IS DISTINCT FROM NEW.source_revision_id
   OR found.source_content_sha256 IS DISTINCT FROM NEW.source_content_sha256
   OR found.terms_sha256 IS DISTINCT FROM NEW.terms_sha256
   OR found.currency_code IS DISTINCT FROM NEW.currency_code
   OR found.repayment_method IS DISTINCT FROM NEW.repayment_method
   OR found.day_count_convention IS DISTINCT FROM NEW.day_count_convention
   OR found.rounding_scale IS DISTINCT FROM NEW.rounding_scale
   OR found.rounding_mode IS DISTINCT FROM NEW.rounding_mode
   OR found.app_code<>'CALC-A05'
   OR found.contract_sha256 IS DISTINCT FROM NEW.app_contract_sha256
 THEN
   RAISE EXCEPTION 'A05_R11_EXACT_C05_OR_APPLICATION_PIN_MISMATCH' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$$;

--changeset fin-a05:059c dbms:postgresql splitStatements:true
CREATE TRIGGER trg_a05_schedule_pins
 BEFORE INSERT ON fin_calc.a05_schedule_revision
 FOR EACH ROW EXECUTE FUNCTION fin_calc.a05_verify_schedule_pins();

CREATE TRIGGER trg_a05_schedule_immutable
 BEFORE UPDATE OR DELETE ON fin_calc.a05_schedule_revision
 FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_a05_custom_immutable
 BEFORE UPDATE OR DELETE ON fin_calc.a05_schedule_custom_installment
 FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_a05_line_immutable
 BEFORE UPDATE OR DELETE ON fin_calc.a05_schedule_line
 FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_a05_line_fee_immutable
 BEFORE UPDATE OR DELETE ON fin_calc.a05_schedule_line_fee
 FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_a05_schedule_receipt_immutable
 BEFORE UPDATE OR DELETE ON fin_calc.a05_schedule_command_receipt
 FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_a05_schedule_no_truncate
 BEFORE TRUNCATE ON fin_calc.a05_schedule_revision
 FOR EACH STATEMENT EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_a05_custom_no_truncate
 BEFORE TRUNCATE ON fin_calc.a05_schedule_custom_installment
 FOR EACH STATEMENT EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_a05_line_no_truncate
 BEFORE TRUNCATE ON fin_calc.a05_schedule_line
 FOR EACH STATEMENT EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_a05_line_fee_no_truncate
 BEFORE TRUNCATE ON fin_calc.a05_schedule_line_fee
 FOR EACH STATEMENT EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_a05_receipt_no_truncate
 BEFORE TRUNCATE ON fin_calc.a05_schedule_command_receipt
 FOR EACH STATEMENT EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
