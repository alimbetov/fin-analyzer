--liquibase formatted sql
--changeset fin-calc:039a dbms:postgresql splitStatements:false
-- CALC-3C.1: PostgreSQL FOUND is an implicit BOOLEAN. A local INTEGER named
-- found in the initial 038 implementation shadowed it and broke draft writes.
-- Fix with a new immutable migration: never rewrite 038's Liquibase checksum.
CREATE OR REPLACE FUNCTION fin_calc.validate_required_output_review_draft()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
  prior RECORD;
  item jsonb;
  metric text;
  kind text;
  mode text;
  formula_uuid uuid;
  matched_count integer;
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
      SELECT count(*) INTO matched_count FROM fin_calc.formula_contract_revision c
      WHERE c.id=formula_uuid AND c.form_code=NEW.form_code
        AND c.form_version_id=NEW.form_version_id
        AND c.source_schema_sha256=NEW.source_schema_sha256
        AND c.output_metric_code=metric
        AND c.financial_approval_state='NOT_S2_APPROVED';
    ELSE
      IF item->>'formulaRevisionId' IS NOT NULL THEN
        RAISE EXCEPTION 'CALC3C1_UNEXPECTED_FORMULA_REVISION' USING ERRCODE='23514'; END IF;
      SELECT count(*) INTO matched_count FROM fin_lib.lib_metric_definition m
      JOIN fin_lib.lib_form_field ff ON ff.form_version_id=NEW.form_version_id
        AND ff.field_code=m.source_field_code
      WHERE m.form_code=NEW.form_code AND m.metric_code=metric
        AND m.metric_kind='SOURCE_FIELD'
        AND (kind='REFERENCE' OR ff.field_type IN ('MONEY','DECIMAL','INTEGER'));
    END IF;
    IF matched_count<>1 THEN
      RAISE EXCEPTION 'CALC3C1_REVIEW_SOURCE_NOT_PINNED' USING ERRCODE='23514'; END IF;
  END LOOP;
  RETURN NEW;
END;
$$;

