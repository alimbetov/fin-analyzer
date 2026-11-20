--liquibase formatted sql
--changeset fin-a05:061a dbms:postgresql splitStatements:true
-- A05-R1.2 amendment is an immutable SCENARIO projection over EXACT R1.1
-- schedule. It is not an actual payment event, F03 posting, or modified C05.
CREATE TABLE fin_calc.a05_amendment_revision (
 id uuid PRIMARY KEY,
 organization_id uuid NOT NULL,
 project_id uuid NOT NULL,
 parent_schedule_revision_id uuid NOT NULL
   REFERENCES fin_calc.a05_schedule_revision(id),
 source_revision_id uuid NOT NULL REFERENCES fin_intake.lib_form_revision(id),
 contract_revision_id uuid NOT NULL REFERENCES fin_intake.a05_loan_contract_revision(id),
 app_contract_revision_id uuid NOT NULL REFERENCES fin_calc.calc_application_contract_revision(id),
 source_content_sha256 char(64) NOT NULL CHECK(source_content_sha256 ~ '^[0-9a-f]{64}$'),
 terms_sha256 char(64) NOT NULL CHECK(terms_sha256 ~ '^[0-9a-f]{64}$'),
 parent_result_sha256 char(64) NOT NULL CHECK(parent_result_sha256 ~ '^[0-9a-f]{64}$'),
 engine_descriptor_sha256 char(64) NOT NULL CHECK(engine_descriptor_sha256 ~ '^[0-9a-f]{64}$'),
 event_sha256 char(64) NOT NULL CHECK(event_sha256 ~ '^[0-9a-f]{64}$'),
 scenario_sha256 char(64) NOT NULL CHECK(scenario_sha256 ~ '^[0-9a-f]{64}$'),
 result_sha256 char(64) NOT NULL CHECK(result_sha256 ~ '^[0-9a-f]{64}$'),
 event_kind varchar(24) NOT NULL CHECK(event_kind IN('PREPAYMENT','GRACE','RATE_STEP')),
 effective_date date NOT NULL,
 prepayment_amount numeric(38,2),
 prepayment_policy varchar(32),
 grace_through_date date,
 grace_mode varchar(32),
 revised_annual_rate_pct numeric(20,10),
 repayment_method varchar(24) NOT NULL CHECK(repayment_method IN('ANNUITY','EQUAL_PRINCIPAL')),
 actual_maturity_date date NOT NULL,
 original_opening_principal numeric(38,2) NOT NULL CHECK(original_opening_principal>0),
 total_scheduled_principal numeric(38,2) NOT NULL CHECK(total_scheduled_principal>=0),
 total_extra_principal numeric(38,2) NOT NULL CHECK(total_extra_principal>=0),
 total_capitalized_interest numeric(38,2) NOT NULL CHECK(total_capitalized_interest>=0),
 total_cash_interest numeric(38,2) NOT NULL CHECK(total_cash_interest>=0),
 total_scheduled_fees numeric(38,2) NOT NULL CHECK(total_scheduled_fees>=0),
 total_cash_due numeric(38,2) NOT NULL CHECK(total_cash_due>=0),
 final_principal numeric(38,2) NOT NULL CHECK(final_principal=0),
 row_count integer NOT NULL CHECK(row_count BETWEEN 1 AND 600),
 state varchar(40) NOT NULL DEFAULT 'UAT_SCENARIO_NOT_PAYMENT_FACT'
   CHECK(state='UAT_SCENARIO_NOT_PAYMENT_FACT'),
 financially_approved boolean NOT NULL DEFAULT false CHECK(NOT financially_approved),
 production_admitted boolean NOT NULL DEFAULT false CHECK(NOT production_admitted),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(parent_schedule_revision_id,scenario_sha256),
 CHECK(total_scheduled_principal+total_extra_principal=
   original_opening_principal+total_capitalized_interest),
 CHECK(total_cash_due=total_scheduled_principal+total_extra_principal+
  total_cash_interest+total_scheduled_fees),
 CHECK(
  (event_kind='PREPAYMENT' AND prepayment_amount>0
    AND prepayment_policy IN('REDUCE_TERM','REDUCE_INSTALLMENT')
    AND grace_through_date IS NULL AND grace_mode IS NULL
    AND revised_annual_rate_pct IS NULL)
  OR
  (event_kind='GRACE' AND prepayment_amount IS NULL
    AND prepayment_policy IS NULL AND grace_through_date IS NOT NULL
    AND grace_mode IN('PRINCIPAL_ONLY','FULL_CAPITALIZE')
    AND revised_annual_rate_pct IS NULL)
  OR
  (event_kind='RATE_STEP' AND prepayment_amount IS NULL
    AND prepayment_policy IS NULL AND grace_through_date IS NULL
    AND grace_mode IS NULL
    AND revised_annual_rate_pct BETWEEN 0 AND 500)
 )
);
CREATE INDEX idx_a05_amendment_source
 ON fin_calc.a05_amendment_revision(organization_id,project_id,source_revision_id);

CREATE TABLE fin_calc.a05_amendment_line (
 amendment_revision_id uuid NOT NULL REFERENCES fin_calc.a05_amendment_revision(id),
 installment_no integer NOT NULL CHECK(installment_no>0),
 accrual_from date NOT NULL,
 due_at date NOT NULL CHECK(due_at>accrual_from),
 opening_principal numeric(38,2) NOT NULL CHECK(opening_principal>=0),
 annual_rate_pct numeric(20,10) NOT NULL CHECK(annual_rate_pct BETWEEN 0 AND 500),
 contractual_interest_raw numeric(38,12) NOT NULL CHECK(contractual_interest_raw>=0),
 cash_interest_due numeric(38,2) NOT NULL CHECK(cash_interest_due>=0),
 capitalized_interest numeric(38,2) NOT NULL CHECK(capitalized_interest>=0),
 scheduled_principal numeric(38,2) NOT NULL CHECK(scheduled_principal>=0),
 extra_principal numeric(38,2) NOT NULL CHECK(extra_principal>=0),
 fees_due numeric(38,2) NOT NULL CHECK(fees_due>=0),
 closing_principal numeric(38,2) NOT NULL CHECK(closing_principal>=0),
 total_cash_due numeric(38,2) NOT NULL CHECK(total_cash_due>=0),
 PRIMARY KEY(amendment_revision_id,installment_no),
 UNIQUE(amendment_revision_id,due_at),
 CHECK(closing_principal=opening_principal+capitalized_interest-
   scheduled_principal-extra_principal),
 CHECK(total_cash_due=scheduled_principal+extra_principal+
   cash_interest_due+fees_due)
);
CREATE TABLE fin_calc.a05_amendment_line_fee (
 amendment_revision_id uuid NOT NULL,
 installment_no integer NOT NULL,
 fee_code varchar(80) NOT NULL,
 fee_amount numeric(38,2) NOT NULL CHECK(fee_amount>0),
 PRIMARY KEY(amendment_revision_id,installment_no,fee_code),
 FOREIGN KEY(amendment_revision_id,installment_no)
   REFERENCES fin_calc.a05_amendment_line(amendment_revision_id,installment_no)
);
CREATE TABLE fin_calc.a05_amendment_command_receipt (
 id uuid PRIMARY KEY,
 organization_id uuid NOT NULL,
 idempotency_key varchar(200) NOT NULL,
 request_sha256 char(64) NOT NULL CHECK(request_sha256 ~ '^[0-9a-f]{64}$'),
 amendment_revision_id uuid NOT NULL REFERENCES fin_calc.a05_amendment_revision(id),
 UNIQUE(organization_id,idempotency_key)
);

--changeset fin-a05:061b dbms:postgresql splitStatements:false
CREATE FUNCTION fin_calc.a05_verify_amendment_parent()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE parent_row record;
BEGIN
 SELECT organization_id,project_id,source_revision_id,contract_revision_id,
  app_contract_revision_id,source_content_sha256,terms_sha256,result_sha256
 INTO parent_row FROM fin_calc.a05_schedule_revision
 WHERE id=NEW.parent_schedule_revision_id;
 IF NOT FOUND
 OR parent_row.organization_id IS DISTINCT FROM NEW.organization_id
 OR parent_row.project_id IS DISTINCT FROM NEW.project_id
 OR parent_row.source_revision_id IS DISTINCT FROM NEW.source_revision_id
 OR parent_row.contract_revision_id IS DISTINCT FROM NEW.contract_revision_id
 OR parent_row.app_contract_revision_id IS DISTINCT FROM NEW.app_contract_revision_id
 OR parent_row.source_content_sha256 IS DISTINCT FROM NEW.source_content_sha256
 OR parent_row.terms_sha256 IS DISTINCT FROM NEW.terms_sha256
 OR parent_row.result_sha256 IS DISTINCT FROM NEW.parent_result_sha256
 THEN RAISE EXCEPTION 'A05_R12_EXACT_PARENT_PIN_DRIFT' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$$;

--changeset fin-a05:061c dbms:postgresql splitStatements:true
CREATE TRIGGER trg_a05_amendment_parent
 BEFORE INSERT ON fin_calc.a05_amendment_revision
 FOR EACH ROW EXECUTE FUNCTION fin_calc.a05_verify_amendment_parent();
CREATE TRIGGER trg_a05_amendment_immutable
 BEFORE UPDATE OR DELETE ON fin_calc.a05_amendment_revision
 FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_a05_amendment_line_immutable
 BEFORE UPDATE OR DELETE ON fin_calc.a05_amendment_line
 FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_a05_amendment_fee_immutable
 BEFORE UPDATE OR DELETE ON fin_calc.a05_amendment_line_fee
 FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_a05_amendment_receipt_immutable
 BEFORE UPDATE OR DELETE ON fin_calc.a05_amendment_command_receipt
 FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_a05_amendment_no_truncate
 BEFORE TRUNCATE ON fin_calc.a05_amendment_revision
 FOR EACH STATEMENT EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_a05_amendment_line_no_truncate
 BEFORE TRUNCATE ON fin_calc.a05_amendment_line
 FOR EACH STATEMENT EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_a05_amendment_fee_no_truncate
 BEFORE TRUNCATE ON fin_calc.a05_amendment_line_fee
 FOR EACH STATEMENT EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_a05_amendment_receipt_no_truncate
 BEFORE TRUNCATE ON fin_calc.a05_amendment_command_receipt
 FOR EACH STATEMENT EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
