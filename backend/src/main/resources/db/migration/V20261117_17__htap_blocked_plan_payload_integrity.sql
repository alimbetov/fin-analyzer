-- R2.B: harden plan payload guard against missing/NULL arrays and forged
-- source/registry/slice fingerprints. Forward-only migration on V16.
CREATE OR REPLACE FUNCTION fin_exec.htap_blocked_plan_insert_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE pub RECORD;
DECLARE elem JSONB;
DECLARE v_current BIGINT;
BEGIN
  SELECT organization_id,economic_entity_id,business_scope_id,snapshot_hash,data_space
    INTO pub FROM fin_fact.fact_set_publication
   WHERE fact_set_revision_id=NEW.fact_set_revision_id
     AND organization_id=NEW.organization_id;
  IF NOT FOUND OR pub.economic_entity_id<>NEW.economic_entity_id
     OR pub.business_scope_id<>NEW.business_scope_id
     OR pub.snapshot_hash<>NEW.snapshot_hash
     OR pub.data_space<>NEW.data_space
     OR NOT EXISTS(
       SELECT 1 FROM fin_exec.fact_publication_outbox o
       WHERE o.organization_id=NEW.organization_id
         AND o.fact_set_revision_id=NEW.fact_set_revision_id
         AND o.snapshot_hash=NEW.snapshot_hash
         AND o.event_type='FACT_SET_PUBLISHED')
  THEN
    RAISE EXCEPTION 'FIN_HTAP_BLOCKED_PLAN_SOURCE_UNTRUSTED';
  END IF;
  SELECT catalog_epoch INTO v_current FROM fin_calc.htap_catalog_epoch
    WHERE organization_id=NEW.organization_id;
  IF NEW.catalog_epoch<>COALESCE(v_current,0) THEN
    RAISE EXCEPTION 'FIN_HTAP_PLAN_CATALOG_EPOCH_CONFLICT';
  END IF;

  FOR elem IN SELECT value FROM jsonb_array_elements(NEW.plan_payload)
  LOOP
    IF jsonb_typeof(elem) IS DISTINCT FROM 'object'
       OR elem->>'status' IS DISTINCT FROM 'BLOCKED'
       OR elem->'executionPermitted' IS DISTINCT FROM 'false'::jsonb
       OR elem->>'methodCode' IS NULL
       OR jsonb_typeof(elem->'reasonCodes') IS DISTINCT FROM 'array'
       OR COALESCE(jsonb_array_length(elem->'reasonCodes'),0)=0
       OR jsonb_typeof(elem->'dependencyCodes') IS DISTINCT FROM 'array'
       OR COALESCE(elem->>'sourceFingerprint','')<>NEW.snapshot_hash
       OR COALESCE(elem->>'registryFingerprint','')<>NEW.registry_fingerprint
       OR COALESCE(elem->>'exactSliceFingerprint','') !~ '^[0-9a-f]{64}$'
    THEN
      RAISE EXCEPTION 'FIN_HTAP_PLAN_EXECUTABLE_OR_INCOMPLETE_FORBIDDEN';
    END IF;
  END LOOP;
  NEW.plan_sha256:=encode(sha256(convert_to(
    'FIN:HTAP:BLOCKED_PLAN:PG16-V1|' ||
    jsonb_build_array(NEW.organization_id,NEW.economic_entity_id,
      NEW.business_scope_id,NEW.fact_set_revision_id,NEW.snapshot_hash,
      NEW.data_space,NEW.catalog_epoch,NEW.registry_fingerprint,
      NEW.plan_payload)::text,'UTF8')),'hex');
  RETURN NEW;
END
$$;

REVOKE ALL ON fin_exec.htap_blocked_method_plan FROM PUBLIC;
REVOKE ALL ON fin_calc.htap_mapping_authorship FROM PUBLIC;
REVOKE ALL ON fin_calc.htap_mapping_review FROM PUBLIC;
REVOKE ALL ON fin_calc.htap_mapping_active FROM PUBLIC;
REVOKE ALL ON fin_calc.htap_mapping_activation_journal FROM PUBLIC;
COMMENT ON FUNCTION fin_exec.htap_blocked_plan_insert_guard()
IS 'Never permits executable methods, missing lineage, or incomplete plan payload. Requires trusted service role for plan insert.';
