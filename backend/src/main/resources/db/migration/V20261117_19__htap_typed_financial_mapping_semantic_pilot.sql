-- FIN HTAP W1.2.A: evidence-backed, typed semantic mapping FOR EXISTING
-- F01/F02/F03/pilot source fields only. This is not IFRS certification.
-- Corresponds to ISO 8601 ISO-calendar period pilot; currency stays on facts.
-- It never promotes any mapping to executable or ACTUAL.
CREATE TABLE fin_calc.htap_period_policy_version (
  policy_code VARCHAR(64) PRIMARY KEY,
  calendar_id VARCHAR(64) NOT NULL,
  calendar_version VARCHAR(64) NOT NULL,
  period_type VARCHAR(16) NOT NULL,
  coverage VARCHAR(24) NOT NULL,
  time_kind VARCHAR(16) NOT NULL,
  measure_kind VARCHAR(16) NOT NULL,
  policy_state VARCHAR(32) NOT NULL
    CHECK(policy_state='TECHNICAL_PILOT_NOT_FINANCIAL_S2'),
  UNIQUE (calendar_id,calendar_version,period_type,coverage,time_kind,measure_kind),
  CHECK (calendar_id='ISO_CALENDAR' AND calendar_version='1'
     AND period_type='MONTH' AND coverage='PERIOD_ONLY'
     AND time_kind='INTERVAL' AND measure_kind='FLOW')
);
INSERT INTO fin_calc.htap_period_policy_version VALUES
  ('ISO_MONTH_FLOW_PERIOD_ONLY_V1','ISO_CALENDAR','1','MONTH',
   'PERIOD_ONLY','INTERVAL','FLOW','TECHNICAL_PILOT_NOT_FINANCIAL_S2');
CREATE TRIGGER trg_htap_period_policy_immutable
  BEFORE UPDATE OR DELETE ON fin_calc.htap_period_policy_version
  FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

CREATE TABLE fin_calc.htap_mapping_semantic_evidence (
  organization_id UUID NOT NULL,
  mapping_id UUID NOT NULL,
  source_field VARCHAR(128) NOT NULL,
  metric_code VARCHAR(128) NOT NULL,
  metric_version VARCHAR(64) NOT NULL,
  measure_kind VARCHAR(16) NOT NULL CHECK(measure_kind='FLOW'),
  time_kind VARCHAR(16) NOT NULL CHECK(time_kind='INTERVAL'),
  unit_code VARCHAR(32) NOT NULL,
  period_policy_code VARCHAR(64) NOT NULL
    REFERENCES fin_calc.htap_period_policy_version(policy_code),
  semantic_standard VARCHAR(32) NOT NULL
    CHECK(semantic_standard='INTERNAL_FIN_PILOT'),
  semantic_version VARCHAR(64) NOT NULL
    CHECK(semantic_version='FIN_PILOT_V1'),
  reviewer_subject VARCHAR(200) NOT NULL CHECK(length(btrim(reviewer_subject))>0),
  evidence_sha256 CHAR(64) NOT NULL CHECK(evidence_sha256 ~ '^[0-9a-f]{64}$'),
  semantic_state VARCHAR(48) NOT NULL DEFAULT 'VALIDATED_PILOT_NOT_S2'
    CHECK(semantic_state='VALIDATED_PILOT_NOT_S2'),
  execution_permitted BOOLEAN NOT NULL DEFAULT FALSE
    CHECK(NOT execution_permitted),
  recorded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY(organization_id,mapping_id),
  FOREIGN KEY(organization_id,mapping_id)
    REFERENCES fin_calc.htap_mapping_candidate_version(organization_id,mapping_id),
  FOREIGN KEY(metric_code,metric_version)
    REFERENCES fin_calc.metric_contract(metric_code,metric_version)
);
CREATE FUNCTION fin_calc.htap_mapping_semantic_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE c fin_calc.htap_mapping_candidate_version%ROWTYPE;
DECLARE m fin_calc.metric_contract%ROWTYPE;
DECLARE v_review fin_calc.htap_mapping_review%ROWTYPE;
BEGIN
  SELECT * INTO c FROM fin_calc.htap_mapping_candidate_version
   WHERE organization_id=NEW.organization_id AND mapping_id=NEW.mapping_id;
  IF NOT FOUND
    OR c.mapping_payload->>'sourceField' IS DISTINCT FROM NEW.source_field
    OR c.mapping_payload->>'metricCode' IS DISTINCT FROM NEW.metric_code
    OR c.mapping_payload->>'metricVersion' IS DISTINCT FROM NEW.metric_version
    OR NOT EXISTS (
      SELECT 1 FROM fin_fact.pilot_field_mapping f
       WHERE f.form_code=c.form_code AND f.form_version=c.form_version
         AND f.source_field=NEW.source_field
         AND f.metric_code=NEW.metric_code
         AND f.metric_version=NEW.metric_version
         AND f.required_time_kind=NEW.time_kind
    ) THEN
    RAISE EXCEPTION 'FIN_HTAP_UNVERIFIED_PILOT_SOURCE_FIELD';
  END IF;
  SELECT * INTO m FROM fin_calc.metric_contract
   WHERE metric_code=NEW.metric_code AND metric_version=NEW.metric_version;
  IF NOT FOUND OR m.qualification<>'QUALIFIED'
     OR m.unit_code<>NEW.unit_code
     OR m.measure_kind<>NEW.measure_kind THEN
    RAISE EXCEPTION 'FIN_HTAP_METRIC_SEMANTIC_CONFLICT';
  END IF;
  SELECT * INTO v_review FROM fin_calc.htap_mapping_review
    WHERE organization_id=NEW.organization_id AND mapping_id=NEW.mapping_id;
  IF NOT FOUND OR v_review.decision<>'APPROVED'
     OR v_review.reviewer_subject<>NEW.reviewer_subject
     OR v_review.evidence_sha256<>NEW.evidence_sha256 THEN
    RAISE EXCEPTION 'FIN_HTAP_MAPPING_INDEPENDENT_REVIEW_REQUIRED';
  END IF;
  RETURN NEW;
END
$$;
CREATE TRIGGER trg_htap_mapping_semantic_guard
BEFORE INSERT ON fin_calc.htap_mapping_semantic_evidence
FOR EACH ROW EXECUTE FUNCTION fin_calc.htap_mapping_semantic_guard();
CREATE TRIGGER trg_htap_mapping_semantic_immutable
BEFORE UPDATE OR DELETE ON fin_calc.htap_mapping_semantic_evidence
FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
REVOKE ALL ON fin_calc.htap_mapping_semantic_evidence FROM PUBLIC;

COMMENT ON TABLE fin_calc.htap_mapping_semantic_evidence IS
 'Technical semantic agreement with actual immutable pilot_field_mapping and QUALIFIED metric_contract only. No IFRS tag, financial S2, ACTUAL or runtime permit.';

CREATE VIEW fin_calc.htap_mapping_semantic_coverage_v1
WITH (security_invoker=true) AS
SELECT c.organization_id,c.mapping_id,c.form_code,c.form_version,
 c.mapping_version,c.mapping_sha256,
 s.source_field,s.metric_code,s.metric_version,s.measure_kind,
 s.time_kind,s.unit_code,s.period_policy_code,s.semantic_standard,
 s.semantic_state,
 (a.mapping_id=c.mapping_id AND a.mapping_sha256=c.mapping_sha256)
   AS editorially_active,
 false AS finance_s2_qualified, false AS execution_permitted
FROM fin_calc.htap_mapping_candidate_version c
LEFT JOIN fin_calc.htap_mapping_semantic_evidence s
  ON s.organization_id=c.organization_id AND s.mapping_id=c.mapping_id
LEFT JOIN fin_calc.htap_mapping_active a
  ON a.organization_id=c.organization_id AND a.mapping_id=c.mapping_id;
REVOKE ALL ON fin_calc.htap_mapping_semantic_coverage_v1 FROM PUBLIC;
