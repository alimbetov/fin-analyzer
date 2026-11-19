--liquibase formatted sql
--changeset fin-calc:038a dbms:postgresql splitStatements:true
-- CALC-3C.1: append-only reviewer PROPOSALS for required outputs.
-- A draft is NOT financial approval; original 154 REVIEW_REQUIRED facts
-- and immutable historical formula contracts are never overwritten.
CREATE TABLE fin_calc.required_output_review_draft_revision (
  id UUID PRIMARY KEY,
  form_code VARCHAR(128) NOT NULL,
  form_version_id UUID NOT NULL REFERENCES fin_lib.lib_form_version(id),
  source_schema_sha256 CHAR(64) NOT NULL
    CHECK(source_schema_sha256 ~ '^[0-9a-f]{64}$'),
  revision_number INTEGER NOT NULL CHECK(revision_number>=1),
  predecessor_id UUID REFERENCES fin_calc.required_output_review_draft_revision(id),
  output_decisions_jsonb JSONB NOT NULL CHECK(
    jsonb_typeof(output_decisions_jsonb)='array'
    AND jsonb_array_length(output_decisions_jsonb)<=128),
  review_state VARCHAR(32) NOT NULL DEFAULT 'DRAFT_UNQUALIFIED'
    CHECK(review_state='DRAFT_UNQUALIFIED'),
  finance_approval VARCHAR(32) NOT NULL DEFAULT 'NOT_S2_APPROVED'
    CHECK(finance_approval='NOT_S2_APPROVED'),
  reviewer_ref VARCHAR(128) NOT NULL CHECK(length(btrim(reviewer_ref))>0),
  rationale TEXT NOT NULL CHECK(length(btrim(rationale))>0),
  definition_sha256 CHAR(64) NOT NULL CHECK(definition_sha256 ~ '^[0-9a-f]{64}$'),
  binding_sha256 CHAR(64) NOT NULL CHECK(binding_sha256 ~ '^[0-9a-f]{64}$'),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CONSTRAINT uq_calc3c1_review_draft_revision
    UNIQUE(form_version_id,revision_number),
  CONSTRAINT uq_calc3c1_review_draft_definition
    UNIQUE(form_version_id,definition_sha256)
);
CREATE INDEX idx_calc3c1_review_draft_form_version
  ON fin_calc.required_output_review_draft_revision(form_code,form_version_id,revision_number DESC);

--changeset fin-calc:038b dbms:postgresql splitStatements:false
-- Database-level validation applies also to direct SQL inserts (not just Java).
CREATE FUNCTION fin_calc.validate_required_output_review_draft()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
  prior RECORD;
  item jsonb;
  metric text;
  kind text;
  mode text;
  formula_uuid uuid;
  found integer;
  seen text[] := ARRAY[]::text[];
BEGIN
  SELECT v.content_mode INTO mode
  FROM fin_lib.lib_form_definition f
  JOIN fin_lib.lib_form_version v ON v.id=f.current_version_id
  WHERE f.form_code=NEW.form_code
    AND v.id=NEW.form_version_id
    AND v.schema_sha256=NEW.source_schema_sha256
    AND v.content_mode IN ('SINGLE_RECORD','ROWSET');
  IF NOT FOUND THEN
    RAISE EXCEPTION 'CALC3C1_REVIEW_FORM_VERSION_STALE' USING ERRCODE='23514';
  END IF;

  IF NEW.revision_number=1 THEN
    IF NEW.predecessor_id IS NOT NULL THEN
      RAISE EXCEPTION 'CALC3C1_REVIEW_UNEXPECTED_PREDECESSOR' USING ERRCODE='23514';
    END IF;
  ELSE
    SELECT revision_number,form_version_id INTO prior
    FROM fin_calc.required_output_review_draft_revision
    WHERE id=NEW.predecessor_id;
    IF NOT FOUND OR prior.revision_number<>NEW.revision_number-1
       OR prior.form_version_id<>NEW.form_version_id THEN
      RAISE EXCEPTION 'CALC3C1_REVIEW_INVALID_PREDECESSOR' USING ERRCODE='23514';
    END IF;
  END IF;

  FOR item IN SELECT value FROM jsonb_array_elements(NEW.output_decisions_jsonb)
  LOOP
    IF jsonb_typeof(item)<>'object'
       OR jsonb_typeof(item->'metricCode')<>'string'
       OR jsonb_typeof(item->'requiredness')<>'string'
       OR jsonb_typeof(item->'sourceKind')<>'string'
    THEN RAISE EXCEPTION 'CALC3C1_REVIEW_ITEM_UNTYPED' USING ERRCODE='23514'; END IF;
    metric:=item->>'metricCode';
    kind:=item->>'sourceKind';
    IF metric NOT LIKE NEW.form_code||'.%'
       OR NULLIF(btrim(metric),'') IS NULL
       OR (item->>'requiredness') NOT IN ('REQUIRED','OPTIONAL')
       OR kind NOT IN ('SOURCE','REFERENCE','DERIVED')
       OR metric=ANY(seen)
    THEN RAISE EXCEPTION 'CALC3C1_REVIEW_ITEM_INVALID' USING ERRCODE='23514'; END IF;
    seen:=array_append(seen,metric);

    IF kind='DERIVED' THEN
      IF (item->>'formulaRevisionId') IS NULL
         OR (item->>'formulaRevisionId') !~
            '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
      THEN RAISE EXCEPTION 'CALC3C1_DERIVED_REVISION_REQUIRED' USING ERRCODE='23514'; END IF;
      formula_uuid:=(item->>'formulaRevisionId')::uuid;
      SELECT count(*) INTO found FROM fin_calc.formula_contract_revision c
      WHERE c.id=formula_uuid AND c.form_code=NEW.form_code
        AND c.form_version_id=NEW.form_version_id
        AND c.source_schema_sha256=NEW.source_schema_sha256
        AND c.output_metric_code=metric
        AND c.financial_approval_state='NOT_S2_APPROVED';
    ELSE
      IF item->>'formulaRevisionId' IS NOT NULL THEN
        RAISE EXCEPTION 'CALC3C1_UNEXPECTED_FORMULA_REVISION' USING ERRCODE='23514'; END IF;
      SELECT count(*) INTO found FROM fin_lib.lib_metric_definition m
      JOIN fin_lib.lib_form_field ff ON ff.form_version_id=NEW.form_version_id
        AND ff.field_code=m.source_field_code
      WHERE m.form_code=NEW.form_code AND m.metric_code=metric
        AND m.metric_kind='SOURCE_FIELD'
        AND (kind='REFERENCE' OR ff.field_type IN ('MONEY','DECIMAL','INTEGER'));
    END IF;
    IF found<>1 THEN
      RAISE EXCEPTION 'CALC3C1_REVIEW_SOURCE_NOT_PINNED' USING ERRCODE='23514'; END IF;
  END LOOP;
  RETURN NEW;
END;
$$;

--changeset fin-calc:038c dbms:postgresql splitStatements:true
CREATE TRIGGER trg_calc3c1_review_draft_verify
  BEFORE INSERT ON fin_calc.required_output_review_draft_revision
  FOR EACH ROW EXECUTE FUNCTION fin_calc.validate_required_output_review_draft();
CREATE TRIGGER trg_calc3c1_review_draft_immutable
  BEFORE UPDATE OR DELETE ON fin_calc.required_output_review_draft_revision
  FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_calc3c1_review_draft_no_truncate
  BEFORE TRUNCATE ON fin_calc.required_output_review_draft_revision
  FOR EACH STATEMENT EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
