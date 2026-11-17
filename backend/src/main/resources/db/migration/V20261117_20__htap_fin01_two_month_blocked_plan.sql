-- HTAP-V1.2: narrow immutable, non-executable two-period FIN-01 input manifest.
-- Reuses R2.B blocked plan. Neither this table nor its trigger can issue
-- ExecutionPermit, analysis tasks, method results, or reports.
ALTER TABLE fin_exec.htap_blocked_method_plan
    ADD CONSTRAINT uq_htap_blocked_plan_scoped_id UNIQUE (organization_id, plan_id);

CREATE TABLE fin_exec.htap_fin01_two_month_blocked_plan (
  id UUID PRIMARY KEY,
  organization_id UUID NOT NULL,
  economic_entity_id UUID NOT NULL,
  business_scope_id UUID NOT NULL,
  fact_set_revision_id UUID NOT NULL,
  snapshot_hash CHAR(64) NOT NULL CHECK(snapshot_hash ~ '^[0-9a-f]{64}$'),
  blocked_plan_id UUID NOT NULL,
  source_revision_id UUID NOT NULL,
  mapping_id UUID NOT NULL,
  mapping_sha256 CHAR(64) NOT NULL CHECK(mapping_sha256 ~ '^[0-9a-f]{64}$'),
  catalog_epoch BIGINT NOT NULL CHECK(catalog_epoch>0),
  registry_fingerprint CHAR(64) NOT NULL CHECK(registry_fingerprint ~ '^[0-9a-f]{64}$'),
  previous_fact_id UUID NOT NULL,
  current_fact_id UUID NOT NULL,
  previous_month DATE NOT NULL,
  current_month DATE NOT NULL,
  currency_code CHAR(3) NOT NULL CHECK(currency_code='KZT'),
  period_policy_code VARCHAR(64) NOT NULL
    CHECK(period_policy_code='ISO_MONTH_FLOW_PERIOD_ONLY_V1'),
  algorithm_class_sha256 CHAR(64) NOT NULL
    CHECK(algorithm_class_sha256 ~ '^[0-9a-f]{64}$'),
  artifact_trust VARCHAR(40) NOT NULL DEFAULT 'CLASS_BYTES_ONLY_UNATTESTED'
    CHECK(artifact_trust='CLASS_BYTES_ONLY_UNATTESTED'),
  plan_state VARCHAR(32) NOT NULL DEFAULT 'BLOCKED_PLAN_ONLY'
    CHECK(plan_state='BLOCKED_PLAN_ONLY'),
  execution_permitted BOOLEAN NOT NULL DEFAULT FALSE
    CHECK(NOT execution_permitted),
  plan_sha256 CHAR(64) NOT NULL CHECK(plan_sha256 ~ '^[0-9a-f]{64}$'),
  created_by VARCHAR(200) NOT NULL CHECK(length(btrim(created_by))>0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  CHECK(previous_fact_id<>current_fact_id),
  CHECK(date_trunc('month', previous_month)::date=previous_month),
  CHECK(date_trunc('month', current_month)::date=current_month),
  CHECK(previous_month + INTERVAL '1 month'=current_month),
  FOREIGN KEY(organization_id,blocked_plan_id)
    REFERENCES fin_exec.htap_blocked_method_plan(organization_id,plan_id),
  FOREIGN KEY(previous_fact_id,organization_id)
    REFERENCES fin_fact.fact_assertion(id,organization_id),
  FOREIGN KEY(current_fact_id,organization_id)
    REFERENCES fin_fact.fact_assertion(id,organization_id),
  UNIQUE(organization_id,plan_sha256)
);

CREATE FUNCTION fin_exec.htap_fin01_pair_plan_insert_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_previous RECORD;
DECLARE v_current RECORD;
DECLARE v_blocked RECORD;
DECLARE v_epoch BIGINT;
BEGIN
  SELECT * INTO v_blocked FROM fin_exec.htap_blocked_method_plan
    WHERE organization_id=NEW.organization_id AND plan_id=NEW.blocked_plan_id;
  IF NOT FOUND OR v_blocked.plan_state<>'BLOCKED_PLAN_ONLY'
     OR v_blocked.fact_set_revision_id<>NEW.fact_set_revision_id
     OR v_blocked.economic_entity_id<>NEW.economic_entity_id
     OR v_blocked.business_scope_id<>NEW.business_scope_id
     OR v_blocked.snapshot_hash<>NEW.snapshot_hash
     OR v_blocked.registry_fingerprint<>NEW.registry_fingerprint
     OR v_blocked.catalog_epoch<>NEW.catalog_epoch
     OR v_blocked.data_space<>'SYNTHETIC'
     OR NOT EXISTS (
        SELECT 1 FROM jsonb_array_elements(v_blocked.plan_payload) node
        WHERE node->>'methodCode'='FIN-01/REV_NET_MOM'
          AND node->>'status'='BLOCKED'
          AND node->'executionPermitted'='false'::jsonb)
  THEN
    RAISE EXCEPTION 'FIN_HTAP_FIN01_BLOCKED_PARENT_MISMATCH';
  END IF;
  SELECT catalog_epoch INTO v_epoch FROM fin_calc.htap_catalog_epoch
    WHERE organization_id=NEW.organization_id;
  IF v_epoch IS DISTINCT FROM NEW.catalog_epoch
     OR NOT EXISTS (
       SELECT 1 FROM fin_calc.htap_mapping_active a
       JOIN fin_calc.htap_mapping_semantic_evidence e
         ON e.organization_id=a.organization_id AND e.mapping_id=a.mapping_id
       WHERE a.organization_id=NEW.organization_id
         AND a.mapping_id=NEW.mapping_id
         AND a.mapping_sha256=NEW.mapping_sha256
         AND a.catalog_epoch=NEW.catalog_epoch
         AND a.form_code='F02' AND a.form_version='FIN-PILOT-V1'
         AND e.metric_code='REV_NET' AND e.metric_version='1.0.0'
         AND e.source_field='netRevenue'
         AND e.period_policy_code=NEW.period_policy_code
         AND e.semantic_state='VALIDATED_PILOT_NOT_S2'
         AND NOT e.execution_permitted)
  THEN RAISE EXCEPTION 'FIN_HTAP_FIN01_MAPPING_EPOCH_STALE'; END IF;
  SELECT a.* INTO v_previous FROM fin_fact.fact_assertion a
     JOIN fin_fact.fact_selection s ON s.organization_id=a.organization_id
       AND s.fact_assertion_id=a.id AND s.fact_set_revision_id=NEW.fact_set_revision_id
     JOIN fin_fact.fact_origin o ON o.organization_id=a.organization_id
       AND o.fact_assertion_id=a.id AND o.source_revision_id=NEW.source_revision_id
       AND o.source_field='netRevenue'
    WHERE a.id=NEW.previous_fact_id AND a.organization_id=NEW.organization_id;
  SELECT a.* INTO v_current FROM fin_fact.fact_assertion a
     JOIN fin_fact.fact_selection s ON s.organization_id=a.organization_id
       AND s.fact_assertion_id=a.id AND s.fact_set_revision_id=NEW.fact_set_revision_id
     JOIN fin_fact.fact_origin o ON o.organization_id=a.organization_id
       AND o.fact_assertion_id=a.id AND o.source_revision_id=NEW.source_revision_id
       AND o.source_field='netRevenue'
    WHERE a.id=NEW.current_fact_id AND a.organization_id=NEW.organization_id;
  IF v_previous.id IS NULL OR v_current.id IS NULL
     OR v_previous.economic_entity_id<>NEW.economic_entity_id
     OR v_current.economic_entity_id<>NEW.economic_entity_id
     OR v_previous.business_scope_id<>NEW.business_scope_id
     OR v_current.business_scope_id<>NEW.business_scope_id
     OR v_previous.source_revision_id<>NEW.source_revision_id
     OR v_current.source_revision_id<>NEW.source_revision_id
     OR v_previous.metric_code<>'REV_NET' OR v_current.metric_code<>'REV_NET'
     OR v_previous.metric_version<>'1.0.0' OR v_current.metric_version<>'1.0.0'
     OR v_previous.time_kind<>'INTERVAL' OR v_current.time_kind<>'INTERVAL'
     OR v_previous.reporting_basis<>'MANAGEMENT'
     OR v_current.reporting_basis<>'MANAGEMENT'
     OR v_previous.currency_code<>NEW.currency_code
     OR v_current.currency_code<>NEW.currency_code
     OR v_previous.unit_code<>'MONEY' OR v_current.unit_code<>'MONEY'
     OR v_previous.dimensions<>'{}'::jsonb OR v_current.dimensions<>'{}'::jsonb
     OR v_previous.value_state NOT IN ('PRESENT','EXPLICIT_ZERO')
     OR v_current.value_state NOT IN ('PRESENT','EXPLICIT_ZERO')
     OR v_previous.start_inclusive<>NEW.previous_month
     OR v_previous.end_exclusive<>NEW.current_month
     OR v_current.start_inclusive<>NEW.current_month
     OR v_current.end_exclusive<>(NEW.current_month + INTERVAL '1 month')::date
  THEN RAISE EXCEPTION 'FIN_HTAP_FIN01_SOURCE_PAIR_UNQUALIFIED'; END IF;

  NEW.plan_sha256:=encode(sha256(convert_to(
      'FIN:HTAP:FIN01:PAIR:PG16-V1|' ||
      jsonb_build_array(NEW.organization_id,NEW.economic_entity_id,
        NEW.business_scope_id,NEW.fact_set_revision_id,NEW.snapshot_hash,
        NEW.blocked_plan_id,NEW.source_revision_id,NEW.mapping_id,
        NEW.mapping_sha256,NEW.catalog_epoch,NEW.registry_fingerprint,
        NEW.previous_fact_id,NEW.current_fact_id,NEW.previous_month,
        NEW.current_month,NEW.currency_code,NEW.period_policy_code,
        v_previous.numeric_value,v_current.numeric_value,
        NEW.algorithm_class_sha256,NEW.artifact_trust,
        NEW.plan_state,NEW.execution_permitted)::text,'UTF8')),'hex');
  RETURN NEW;
END $$;

CREATE TRIGGER trg_htap_fin01_pair_guard
BEFORE INSERT ON fin_exec.htap_fin01_two_month_blocked_plan
FOR EACH ROW EXECUTE FUNCTION fin_exec.htap_fin01_pair_plan_insert_guard();
CREATE TRIGGER trg_htap_fin01_pair_immutable
BEFORE UPDATE OR DELETE ON fin_exec.htap_fin01_two_month_blocked_plan
FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
REVOKE ALL ON fin_exec.htap_fin01_two_month_blocked_plan FROM PUBLIC;
COMMENT ON TABLE fin_exec.htap_fin01_two_month_blocked_plan IS
  'HTAP-V1.2: immutable FIN-01 two-month source pin, blocked-only, never executable';
