-- R2.B M1 foundation: immutable PLAN_ONLY snapshot of blocked decisions.
-- No execution tasks, worker leases, output receipts or CORE reports.
-- This snapshot is not a runnable plan and does not imply Method S2.
CREATE TABLE fin_exec.htap_blocked_method_plan (
  plan_id UUID PRIMARY KEY,
  organization_id UUID NOT NULL,
  economic_entity_id UUID NOT NULL,
  business_scope_id UUID NOT NULL,
  fact_set_revision_id UUID NOT NULL,
  snapshot_hash CHAR(64) NOT NULL CHECK (snapshot_hash ~ '^[0-9a-f]{64}$'),
  data_space VARCHAR(16) NOT NULL CHECK (data_space IN ('ACTUAL','SYNTHETIC')),
  catalog_epoch BIGINT NOT NULL CHECK (catalog_epoch>=0),
  registry_fingerprint CHAR(64) NOT NULL
    CHECK (registry_fingerprint ~ '^[0-9a-f]{64}$'),
  plan_payload JSONB NOT NULL CHECK (
    jsonb_typeof(plan_payload)='array'
    AND jsonb_array_length(plan_payload)>0),
  plan_sha256 CHAR(64) NOT NULL CHECK (plan_sha256 ~ '^[0-9a-f]{64}$'),
  created_by VARCHAR(200) NOT NULL CHECK (length(btrim(created_by))>0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  plan_state VARCHAR(32) NOT NULL DEFAULT 'BLOCKED_PLAN_ONLY'
    CHECK(plan_state='BLOCKED_PLAN_ONLY'),
  CONSTRAINT fk_htap_plan_published_tenant FOREIGN KEY
    (fact_set_revision_id,organization_id)
    REFERENCES fin_fact.fact_set_publication(fact_set_revision_id,organization_id),
  CONSTRAINT uq_htap_blocked_plan_by_sha UNIQUE(organization_id,plan_sha256)
);
CREATE INDEX idx_htap_blocked_plan_source ON fin_exec.htap_blocked_method_plan
  (organization_id,fact_set_revision_id,created_at DESC);

CREATE FUNCTION fin_exec.htap_blocked_plan_insert_guard()
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
    IF jsonb_typeof(elem)<>'object'
       OR elem->>'status' IS DISTINCT FROM 'BLOCKED'
       OR elem->'executionPermitted' IS DISTINCT FROM 'false'::jsonb
       OR elem->>'methodCode' IS NULL
       OR jsonb_typeof(elem->'reasonCodes')<>'array'
       OR jsonb_array_length(elem->'reasonCodes')=0 THEN
      RAISE EXCEPTION 'FIN_HTAP_PLAN_EXECUTABLE_FORBIDDEN';
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
CREATE TRIGGER trg_htap_blocked_plan_guard
BEFORE INSERT ON fin_exec.htap_blocked_method_plan
FOR EACH ROW EXECUTE FUNCTION fin_exec.htap_blocked_plan_insert_guard();
CREATE TRIGGER trg_htap_blocked_plan_immutable
BEFORE UPDATE OR DELETE ON fin_exec.htap_blocked_method_plan
FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

-- Internal function is an authorization-sensitive surface. Do not grant its
-- EXECUTE privilege to PUBLIC; trusted service role grants are deployment-owned.
REVOKE ALL ON FUNCTION
fin_calc.htap_mapping_activate_v1(UUID,TEXT,TEXT,UUID,BIGINT,TEXT)
FROM PUBLIC;

COMMENT ON TABLE fin_exec.htap_blocked_method_plan IS
 'R2.B blocked decision evidence only; never runnable or a method execution authority';
