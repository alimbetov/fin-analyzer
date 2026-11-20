--liquibase formatted sql
--changeset fin-calc:055a dbms:postgresql splitStatements:true
-- CALC-POLICY-R0: 10 user-defined accounting policy decisions for planning.
-- Values are editorial, not IFRS clause qualification or production admission.
CREATE TABLE fin_calc.methodology_policy_decision_revision (
 id uuid PRIMARY KEY,
 decision_code varchar(8) NOT NULL CHECK(decision_code ~ '^(P0|P1)_[1-7]$'),
 revision_number integer NOT NULL CHECK(revision_number>0),
 scope_level varchar(48) NOT NULL CHECK(scope_level IN (
  'SYSTEM','OBLIGATION','PORTFOLIO_CLASS','INVENTORY_CLASS',
  'LOAN_CONTRACT','PROJECT_SCENARIO','FIXED_ASSET',
  'REPORTING_SCOPE','SYSTEM_AND_FORM')),
 selected_rule_code varchar(96) NOT NULL
   CHECK(selected_rule_code ~ '^[A-Z][A-Z0-9_]{2,95}$'),
 decision_state varchar(48) NOT NULL CHECK(decision_state IN (
  'EDITORIAL_ACCEPTED_WITH_GATES','NUMERIC_PARAMS_NOT_APPROVED')),
 affected_form_codes_jsonb jsonb NOT NULL CHECK(
  jsonb_typeof(affected_form_codes_jsonb)='array'
    AND jsonb_array_length(affected_form_codes_jsonb)>0),
 decision_text text NOT NULL CHECK(length(trim(decision_text))>0),
 guardrail_text text NOT NULL CHECK(length(trim(guardrail_text))>0),
 required_evidence_jsonb jsonb NOT NULL CHECK(
  jsonb_typeof(required_evidence_jsonb)='array'
    AND jsonb_array_length(required_evidence_jsonb)>0),
 source_repository_path text NOT NULL,
 source_raw_sha256 char(64) NOT NULL CHECK(source_raw_sha256 ~ '^[0-9a-f]{64}$'),
 decision_sha256 char(64) NOT NULL CHECK(decision_sha256 ~ '^[0-9a-f]{64}$'),
 financial_s2_approved boolean NOT NULL DEFAULT false CHECK(NOT financial_s2_approved),
 production_admitted boolean NOT NULL DEFAULT false CHECK(NOT production_admitted),
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 UNIQUE(decision_code,revision_number)
);
CREATE INDEX idx_method_policy_revision_code
 ON fin_calc.methodology_policy_decision_revision(decision_code,revision_number DESC);

--changeset fin-calc:055b dbms:postgresql splitStatements:false
CREATE FUNCTION fin_calc.verify_methodology_policy_decision_revision()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE expected text;
BEGIN
 IF NEW.decision_sha256 IS DISTINCT FROM
    encode(sha256(convert_to(
     'FIN-METHOD-POLICY-DECISION-V1|'||
     NEW.decision_code||'|'||NEW.revision_number::text||'|'||
     NEW.scope_level||'|'||NEW.selected_rule_code||'|'||
     NEW.decision_state||'|'||NEW.affected_form_codes_jsonb::text||'|'||
     NEW.decision_text||'|'||NEW.guardrail_text||'|'||
     NEW.required_evidence_jsonb::text||'|'||
     NEW.source_raw_sha256,'UTF8')),'hex') THEN
   RAISE EXCEPTION 'METHOD_POLICY_DECISION_SHA_MISMATCH' USING ERRCODE='23514';
 END IF;
 IF (SELECT count(*) FROM jsonb_array_elements_text(NEW.affected_form_codes_jsonb)) <>
    (SELECT count(DISTINCT f.code)
     FROM jsonb_array_elements_text(NEW.affected_form_codes_jsonb) f(code))
 OR EXISTS (
    SELECT 1 FROM jsonb_array_elements_text(NEW.affected_form_codes_jsonb) f(code)
    WHERE f.code !~ '^[A-Z0-9-]{2,40}$'
 )
 THEN
   RAISE EXCEPTION 'METHOD_POLICY_DUPLICATE_OR_INVALID_FORM' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$$;

--changeset fin-calc:055c dbms:postgresql splitStatements:true
CREATE TRIGGER trg_method_policy_sha BEFORE INSERT
 ON fin_calc.methodology_policy_decision_revision FOR EACH ROW
 EXECUTE FUNCTION fin_calc.verify_methodology_policy_decision_revision();
CREATE TRIGGER trg_method_policy_immutable BEFORE UPDATE OR DELETE
 ON fin_calc.methodology_policy_decision_revision FOR EACH ROW
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_method_policy_no_truncate BEFORE TRUNCATE
 ON fin_calc.methodology_policy_decision_revision FOR EACH STATEMENT
 EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

--changeset fin-calc:055d dbms:postgresql splitStatements:true
-- Source and source-form catalog are loaded by different lifecycle phases:
-- do not use a premature FK into Java-seeded fin_lib definitions at Liquibase time.
WITH seed(
 decision_code,revision_number,scope_level,selected_rule_code,decision_state,
 affected_form_codes_jsonb,decision_text,guardrail_text,required_evidence_jsonb) AS (
 VALUES
 ('P0_1',1,'SYSTEM','DUAL_REPORTING_BASES_SEPARATE','EDITORIAL_ACCEPTED_WITH_GATES','["C01","C02","F01","F02","F03","F07","P01","P02","P07"]'::jsonb,'Сохранить исходные факты, строить отдельные МСФО и управленческие результаты; не объединять в одном CalcPlan.','Зафиксировать reportingBasis и источник корректировок; IFRS корректировки не записываются поверх fin_intake.','["ReportingBasis dictionary","Versioned adjustments by basis","Scope and FactSet revisions"]'::jsonb),
 ('P0_2',1,'OBLIGATION','REPORTED_ASOF_BALANCE_WITH_OPTIONAL_LEDGER_VERIFICATION','EDITORIAL_ACCEPTED_WITH_GATES','["C01","C02","DF-CSH-02","DF-CSH-04","DF-CSH-05"]'::jsonb,'Готовый остаток FactSet — исходное значение; при наличии полного журнала операций выполнить независимую сверку.','Не вычислять выдуманные платежи и корректировки; при недостатке движений указывать SOURCE_ONLY; аномалии REVIEW_REQUIRED.','["Immutable as-of stock snapshot","Business obligation key","Settlement and adjustment events","Difference tolerance policy"]'::jsonb),
 ('P0_3',1,'PORTFOLIO_CLASS','IFRS9_SIMPLIFIED_MATRIX_FIRST_FOR_ELIGIBLE_TRADE_RECEIVABLES','EDITORIAL_ACCEPTED_WITH_GATES','["C01","C05","SF-K01"]'::jsonb,'Для допустимой торговой дебиторской задолженности матрица ECL — первый UAT-профиль; общая модель и PD/LGD/EAD — раздельный будущий профиль.','Соответствие области IFRS9 проверять на уровне типа инструмента/портфеля, не запрещать разные модели в отдельных сегментах.','["Instrument classification","Bucket exposures","Historical loss calibration","Forward-looking evidence"]'::jsonb),
 ('P0_4',1,'INVENTORY_CLASS','SAME_NATURE_AND_USE_COST_FORMULA','EDITORIAL_ACCEPTED_WITH_GATES','["C03","DF-STK-01","DF-STK-02","DF-STK-03"]'::jsonb,'Последовательно применять формулу себестоимости для класса запасов одинакового характера и использования.','Для не взаимозаменяемых объектов предусмотреть specific identification; смена политики требует новой версии/обоснования.','["Inventory nature/use class","Lot ledger and valuations","Policy validity dates","Policy change explanation"]'::jsonb),
 ('P0_5',1,'LOAN_CONTRACT','CONTRACTUAL_CASH_AND_EIR_ACCOUNTING_SCHEDULES','EDITORIAL_ACCEPTED_WITH_GATES','["C05","P04","P09","F01","F02","F03","DF-CSH-06"]'::jsonb,'Сохранять договорный денежный график и отдельную бухгалтерскую амортизированную стоимость с EIR.','Договорные суммы cash должны сверяться с фактическими событиями; бухгалтерские EIR начисления не тождественны cash процентам/платежам.','["Contract revision and dated cashflows","Fees and EIR assumptions","Day count and rate version","Payment allocation"]'::jsonb),
 ('P0_6',1,'PROJECT_SCENARIO','DCF_VARIANT_PER_SCENARIO_WITH_RATE_PAIRING','EDITORIAL_ACCEPTED_WITH_GATES','["P01","P02","P03","P04","P05","P06","P07","P08","P09"]'::jsonb,'Выбор FCFF/WACC или FCFE/Ke делается на уровне сценария и хранится как неизменяемая revision.','Не сравнивать Enterprise Value с Equity Value напрямую без моста стоимости; все связанные P-расчёты используют одну scenario revision.','["Dated cashflow revision","Matching rate source revision","Scenario and valuation date","Terminal method and equity bridge"]'::jsonb),
 ('P0_7',1,'LOAN_CONTRACT','DSCR_COVENANT_FIRST_ANALYTICAL_SEPARATE','EDITORIAL_ACCEPTED_WITH_GATES','["P04","P09","C05","DF-CSH-06"]'::jsonb,'Ковенантный DSCR вычислять по подписанному определению договора; аналитический DSCR — отдельный показатель.','Если договорное определение отсутствует, аналитический показатель НЕ подтверждает выполнение кредитного ковенанта; EIR interest не является cash debt service.','["Loan covenant text and effective version","CFADS component definitions","Debt-service components","Grace periods and exclusions"]'::jsonb),
 ('P1_1',1,'FIXED_ASSET','COMPONENT_DEPRECIATION_WHEN_MATERIAL','EDITORIAL_ACCEPTED_WITH_GATES','["C04","F01","DF-AST-01"]'::jsonb,'Поддерживать значимые компоненты с отдельными сроками/методами; неделимые объекты могут учитыватьcя целиком.','Порог существенности не утверждён; метод, срок, остаточная стоимость и дата ввода требуют версии.','["Component keys and values","Estimated useful lives","Residual values","Depreciation method"]'::jsonb),
 ('P1_2',1,'REPORTING_SCOPE','ONE_CASH_SOURCE_DUAL_CFO_REPRESENTATION','EDITORIAL_ACCEPTED_WITH_GATES','["F02","F03","P03","C01","C02","C03","C06"]'::jsonb,'Прямой/косвенный методы должны рассчитываться из согласованных первичных потоков и корректировок.','Равенство CFO проверять только на одной отчётной базе, валюте, периоде и периметре; неденежные и FX мосты обязательны.','["Canonical cash events","Indirect bridge noncash adjustments","Currency and reporting scope","Opening and closing cash"]'::jsonb),
 ('P1_3',1,'SYSTEM_AND_FORM','VERSIONED_THRESHOLDS_WITH_TIGHTEN_ONLY_OVERRIDES','NUMERIC_PARAMS_NOT_APPROVED','["C01","C02","C03","C04","C05","F01","F02","F03","P01","P07","P09"]'::jsonb,'Системные параметры существенности/округления/курса/допусков версионировать, локальные переопределения ужесточать.','Предложенные 0.5 percent / 0.1 percent / 1000 currency units не являются стандартами и НЕ должны автоматически стать значениями по умолчанию.','["Materiality base selection","Currency and quote source version","Rounding scale and mode","Absolute and relative tolerance approval"]'::jsonb)
)
INSERT INTO fin_calc.methodology_policy_decision_revision(
 id,decision_code,revision_number,scope_level,selected_rule_code,decision_state,
 affected_form_codes_jsonb,decision_text,guardrail_text,required_evidence_jsonb,
 source_repository_path,source_raw_sha256,decision_sha256)
SELECT
 md5('FIN-POLICY-V1:'||s.decision_code)::uuid,
 s.decision_code,s.revision_number,s.scope_level,s.selected_rule_code,
 s.decision_state,s.affected_form_codes_jsonb,s.decision_text,
 s.guardrail_text,s.required_evidence_jsonb,
 'docs/sources/FIN-CALC154-USER-P0-P1-FINANCIAL-POLICY-ANSWERS-2026-11-20.md',
 'b285e5c69490e93c44f10b058359b4c319e0b275d72aeec6c6d0550aedcd4309',
 encode(sha256(convert_to(
  'FIN-METHOD-POLICY-DECISION-V1|'||
  s.decision_code||'|'||s.revision_number::text||'|'||
  s.scope_level||'|'||s.selected_rule_code||'|'||
  s.decision_state||'|'||s.affected_form_codes_jsonb::text||'|'||
  s.decision_text||'|'||s.guardrail_text||'|'||
  s.required_evidence_jsonb::text||'|'||
  'b285e5c69490e93c44f10b058359b4c319e0b275d72aeec6c6d0550aedcd4309','UTF8')),'hex')
FROM seed s;
