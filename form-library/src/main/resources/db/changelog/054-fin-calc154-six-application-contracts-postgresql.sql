--liquibase formatted sql
--changeset fin-calc:054a dbms:postgresql splitStatements:true
-- CALC-APPS-R0: six append-only, version-pinned INPUT CONTRACTS.
-- These are NOT additional forms, executable methods, or production permissions.
CREATE TABLE fin_calc.calc_application_definition (
 app_code varchar(16) PRIMARY KEY CHECK(app_code ~ '^CALC-A0[1-6]$'),
 title_ru text NOT NULL CHECK(length(trim(title_ru))>0),
 financial_domain varchar(64) NOT NULL CHECK(length(trim(financial_domain))>0),
 lifecycle_state varchar(32) NOT NULL DEFAULT 'DESIGN_ONLY'
   CHECK(lifecycle_state='DESIGN_ONLY'),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE fin_calc.calc_application_contract_revision (
 id uuid PRIMARY KEY,
 app_code varchar(16) NOT NULL REFERENCES fin_calc.calc_application_definition(app_code),
 revision_number integer NOT NULL CHECK(revision_number>0),
 source_form_codes_jsonb jsonb NOT NULL CHECK(
   jsonb_typeof(source_form_codes_jsonb)='array'
   AND jsonb_array_length(source_form_codes_jsonb)>0),
 candidate_input_fields_jsonb jsonb NOT NULL CHECK(
   jsonb_typeof(candidate_input_fields_jsonb)='array'
   AND jsonb_array_length(candidate_input_fields_jsonb)>0),
 proposed_outputs_jsonb jsonb NOT NULL CHECK(
   jsonb_typeof(proposed_outputs_jsonb)='array'
   AND jsonb_array_length(proposed_outputs_jsonb)>0),
 proposed_variants_jsonb jsonb NOT NULL CHECK(
   jsonb_typeof(proposed_variants_jsonb)='array'
   AND jsonb_array_length(proposed_variants_jsonb)>0),
 blocker_description text NOT NULL CHECK(length(trim(blocker_description))>0),
 contract_sha256 char(64) NOT NULL CHECK(contract_sha256 ~ '^[0-9a-f]{64}$'),
 contract_state varchar(32) NOT NULL DEFAULT 'SCHEMA_CANDIDATE'
   CHECK(contract_state='SCHEMA_CANDIDATE'),
 financial_s2_approved boolean NOT NULL DEFAULT false CHECK(NOT financial_s2_approved),
 production_admitted boolean NOT NULL DEFAULT false CHECK(NOT production_admitted),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(app_code,revision_number),
 UNIQUE(app_code,contract_sha256),
 CONSTRAINT ck_app_inputs_array_strings CHECK(
    NOT jsonb_path_exists(candidate_input_fields_jsonb,'$[*] ? (@.type() != "string")')),
 CONSTRAINT ck_app_sources_array_strings CHECK(
    NOT jsonb_path_exists(source_form_codes_jsonb,'$[*] ? (@.type() != "string")')),
 CONSTRAINT ck_app_outputs_array_strings CHECK(
    NOT jsonb_path_exists(proposed_outputs_jsonb,'$[*] ? (@.type() != "string")')),
 CONSTRAINT ck_app_variants_array_strings CHECK(
    NOT jsonb_path_exists(proposed_variants_jsonb,'$[*] ? (@.type() != "string")'))
);

--changeset fin-calc:054b dbms:postgresql splitStatements:false
-- Canonical SHA computed from PostgreSQL jsonb text, not caller evidence.
CREATE FUNCTION fin_calc.validate_calc_application_contract()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
 expected text;
BEGIN
 IF EXISTS (
  SELECT 1 FROM jsonb_array_elements_text(NEW.candidate_input_fields_jsonb) AS x(value)
  WHERE x.value !~ '^[A-Za-z][A-Za-z0-9_]*:(TEXT|UUID|DATE|MONEY|DECIMAL|INTEGER|ENUM|CURRENCY)$'
 ) THEN
  RAISE EXCEPTION 'CALC_APPLICATION_FIELD_TYPE_INVALID' USING ERRCODE='23514';
 END IF;
 IF (SELECT count(*) FROM jsonb_array_elements_text(NEW.source_form_codes_jsonb)) <>
    (SELECT count(DISTINCT x.value)
     FROM jsonb_array_elements_text(NEW.source_form_codes_jsonb) x(value))
 OR (SELECT count(*) FROM jsonb_array_elements_text(NEW.candidate_input_fields_jsonb)) <>
    (SELECT count(DISTINCT split_part(x.value,':',1))
     FROM jsonb_array_elements_text(NEW.candidate_input_fields_jsonb) x(value))
 THEN
  RAISE EXCEPTION 'CALC_APPLICATION_DUPLICATE_SOURCE_OR_INPUT' USING ERRCODE='23514';
 END IF;
 expected:=encode(sha256(convert_to(
    'FIN-CALC-APPLICATION-CONTRACT-V1|'||NEW.app_code||'|'||
    NEW.revision_number::text||'|'||
    NEW.source_form_codes_jsonb::text||'|'||
    NEW.candidate_input_fields_jsonb::text||'|'||
    NEW.proposed_outputs_jsonb::text||'|'||
    NEW.proposed_variants_jsonb::text||'|'||
    NEW.blocker_description,'UTF8')),'hex');
 IF NEW.contract_sha256 IS DISTINCT FROM expected THEN
  RAISE EXCEPTION 'CALC_APPLICATION_CONTRACT_SHA_MISMATCH' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$$;

--changeset fin-calc:054c dbms:postgresql splitStatements:true
CREATE TRIGGER trg_calc_app_contract_validate
 BEFORE INSERT ON fin_calc.calc_application_contract_revision
 FOR EACH ROW EXECUTE FUNCTION fin_calc.validate_calc_application_contract();

CREATE TRIGGER trg_calc_app_definition_immutable
 BEFORE UPDATE OR DELETE ON fin_calc.calc_application_definition
 FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_calc_app_definition_no_truncate
 BEFORE TRUNCATE ON fin_calc.calc_application_definition
 FOR EACH STATEMENT EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_calc_app_contract_immutable
 BEFORE UPDATE OR DELETE ON fin_calc.calc_application_contract_revision
 FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_calc_app_contract_no_truncate
 BEFORE TRUNCATE ON fin_calc.calc_application_contract_revision
 FOR EACH STATEMENT EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

--changeset fin-calc:054d dbms:postgresql splitStatements:true
INSERT INTO fin_calc.calc_application_definition(app_code,title_ru,financial_domain)
VALUES
 ('CALC-A01','График обязательств и распределения платежей','DEBT_SETTLEMENT'),
 ('CALC-A02','Матрица ECL и кредитных рисков','CREDIT_RISK'),
 ('CALC-A03','Учёт партий запасов и NRV','INVENTORY_VALUATION'),
 ('CALC-A04','Карточка амортизации компонентов','PPE_DEPRECIATION'),
 ('CALC-A05','Условия кредита и версионированный график','LOAN_SCHEDULE'),
 ('CALC-A06','Денежные потоки инвестиционного сценария','INVESTMENT_DCF');

--changeset fin-calc:054e dbms:postgresql splitStatements:true
-- Field inputs form a variant-dependent CANDIDATE UNION, not globally mandatory.\n-- Requiredness is resolved after choosing a variant and financial policy.\n-- Schema templates will be reviewed with finance. Existing fin_lib forms are
-- created by Java bootstrap *after* Liquibase, hence source references are
-- resolved and tested on read, never prematurely FK-pinned during migration.
WITH seed(app_code,revision_number,source_form_codes_jsonb,candidate_input_fields_jsonb,
          proposed_outputs_jsonb,proposed_variants_jsonb,blocker_description) AS (
 VALUES
 ('CALC-A01',1,'["C01","C02","C11","DF-CSH-02","DF-CSH-04","DF-CSH-05","DF-CSH-06"]'::jsonb,'["obligationBusinessKey:TEXT","installmentKey:TEXT","contractVersionId:UUID","effectiveDueDate:DATE","recognizedAmount:MONEY","allocatedPaymentAmount:MONEY","paymentEffectiveAt:DATE","allocationPolicyRevisionId:UUID"]'::jsonb,'["openBalance","overdueAmount","daysPastDue","agingBucket"]'::jsonb,'["CONTRACTUAL_ORDER","FIFO_DUE_DATE","EXPLICIT_LEDGER_ALLOCATION"]'::jsonb,'Event-level settlements, allocation precedence, amendments and duplicate identities are not proven by current forms'),
 ('CALC-A02',1,'["C01","C05","DF-CSH-04","SF-K01"]'::jsonb,'["exposureBusinessKey:TEXT","grossExposure:MONEY","bucketCode:ENUM","historicalLossRate:DECIMAL","forwardLookingAdjustmentRevisionId:UUID","modelVersionId:UUID","recoveryAssumptionsRevisionId:UUID"]'::jsonb,'["expectedCreditLoss","allowanceCoverage","grossRiskExposure","stageOrBucket"]'::jsonb,'["IFRS9_SIMPLIFIED_MATRIX","IFRS9_GENERAL_ECL","RISK_PD_LGD_EAD"]'::jsonb,'Historical losses, forward-looking model, staging, effective dates and independent expected controls not evidenced'),
 ('CALC-A03',1,'["C03","DF-STK-01","DF-STK-02","DF-STK-03"]'::jsonb,'["sku:TEXT","warehouse:TEXT","inventoryLotBusinessKey:TEXT","movementDate:DATE","movementType:ENUM","quantity:DECIMAL","unitCost:MONEY","estimatedSellingPrice:MONEY","costToComplete:MONEY","costToSell:MONEY","valuationPolicyRevisionId:UUID"]'::jsonb,'["costOfSales","closingQuantity","closingCost","netRealizableValue","writeDown"]'::jsonb,'["FIFO","WEIGHTED_AVERAGE","SPECIFIC_IDENTIFICATION"]'::jsonb,'Inventory lots with corrections, unit conversion, expected selling price and selling costs not guaranteed by source'),
 ('CALC-A04',1,'["C04","F01","DF-AST-01"]'::jsonb,'["assetBusinessKey:TEXT","componentBusinessKey:TEXT","inServiceDate:DATE","originalCost:MONEY","residualValue:MONEY","usefulLifePeriods:INTEGER","depreciationMethod:ENUM","revaluationRevisionId:UUID","impairmentEvidenceRevisionId:UUID"]'::jsonb,'["periodDepreciation","accumulatedDepreciation","carryingAmount","impairmentLoss"]'::jsonb,'["STRAIGHT_LINE","DECLINING_BALANCE","UNITS_OF_PRODUCTION"]'::jsonb,'Component-level cost, useful life, residuals, revaluation and impairment sources absent'),
 ('CALC-A05',1,'["C05","P04","P09","DF-CSH-06","DF-CSH-02"]'::jsonb,'["loanBusinessKey:TEXT","contractRevisionId:UUID","rateRevisionId:UUID","eventEffectiveAt:DATE","cashFlowAmount:MONEY","principalComponent:MONEY","contractualInterest:MONEY","commission:MONEY","dayCountConvention:ENUM","modificationEventRevisionId:UUID"]'::jsonb,'["contractualDebtService","carryingAmount","effectiveInterestRate","scheduleBalance","cashInterestPaid"]'::jsonb,'["NOMINAL_CONTRACT_RATE","EIR_AMORTIZED_COST","ANNUITY","EQUAL_PRINCIPAL"]'::jsonb,'Exact dated repayments, fees, day count, contract amendments and cash-vs-accrual classification absent'),
 ('CALC-A06',1,'["P01","P02","P03","P04","P05","P06","P07","P08","P09","DF-PRJ-03","DF-PRJ-05"]'::jsonb,'["scenarioRevisionId:UUID","cashflowRevisionId:UUID","cashflowDate:DATE","flowValue:MONEY","flowBasis:ENUM","discountRateRevisionId:UUID","discountRate:DECIMAL","terminalMethod:ENUM","terminalAssumptionsRevisionId:UUID","currencyCode:CURRENCY"]'::jsonb,'["npv","irrCandidates","enterpriseValue","equityValue","scenarioDelta"]'::jsonb,'["FCFF_WACC","FCFE_KE"]'::jsonb,'Dated original cashflow stream, WACC/Ke source, terminal assumptions and matched P-forms not qualified')
)
INSERT INTO fin_calc.calc_application_contract_revision(
 id,app_code,revision_number,source_form_codes_jsonb,candidate_input_fields_jsonb,
 proposed_outputs_jsonb,proposed_variants_jsonb,blocker_description,contract_sha256)
SELECT
 md5(random()::text||clock_timestamp()::text)::uuid,
 s.app_code,s.revision_number,s.source_form_codes_jsonb,s.candidate_input_fields_jsonb,
 s.proposed_outputs_jsonb,s.proposed_variants_jsonb,s.blocker_description,
 encode(sha256(convert_to(
  'FIN-CALC-APPLICATION-CONTRACT-V1|'||s.app_code||'|'||
  s.revision_number::text||'|'||
  s.source_form_codes_jsonb::text||'|'||
  s.candidate_input_fields_jsonb::text||'|'||
  s.proposed_outputs_jsonb::text||'|'||
  s.proposed_variants_jsonb::text||'|'||
  s.blocker_description,'UTF8')),'hex')
FROM seed s;
