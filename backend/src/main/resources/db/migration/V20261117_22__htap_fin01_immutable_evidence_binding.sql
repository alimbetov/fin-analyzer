-- HTAP-V1.3B.2 immutable evidence snapshot. This is not a permission to execute.
-- No automatic S2 approvals, artifact trust anchors, keys or ExecutionPermits.
ALTER TABLE fin_exec.htap_fin01_two_month_blocked_plan
  ADD CONSTRAINT uq_fin01_pair_organization_id UNIQUE(organization_id,id);

CREATE TABLE fin_exec.htap_fin01_evidence_binding (
  id UUID PRIMARY KEY,
  organization_id UUID NOT NULL,
  blocked_pair_id UUID NOT NULL,
  plan_sha256 CHAR(64) NOT NULL CHECK(plan_sha256 ~ '^[0-9a-f]{64}$'),
  config_id UUID NOT NULL REFERENCES fin_calc.method_config_version(config_id),
  contract_id UUID NOT NULL REFERENCES fin_calc.method_parameter_contract(contract_id),
  contract_version VARCHAR(64) NOT NULL,
  config_version INTEGER NOT NULL CHECK(config_version>0),
  pointer_version BIGINT NOT NULL CHECK(pointer_version>0),
  effective_sha256 CHAR(64) NOT NULL CHECK(effective_sha256 ~ '^[0-9a-f]{64}$'),
  params_sha256 CHAR(64) NOT NULL CHECK(params_sha256 ~ '^[0-9a-f]{64}$'),
  schema_sha256 CHAR(64) NOT NULL CHECK(schema_sha256 ~ '^[0-9a-f]{64}$'),
  algorithm_id VARCHAR(128) NOT NULL CHECK(algorithm_id='FIN-01/REV_NET_MOM'),
  algorithm_version VARCHAR(64) NOT NULL CHECK(algorithm_version='R2_SQL_PILOT_1'),
  algorithm_class_sha256 CHAR(64) NOT NULL CHECK(algorithm_class_sha256 ~ '^[0-9a-f]{64}$'),
  method_s2_review_id UUID NOT NULL REFERENCES fin_calc.method_s2_review(id),
  s2_evidence_sha256 CHAR(64) NOT NULL CHECK(s2_evidence_sha256 ~ '^[0-9a-f]{64}$'),
  artifact_sha256 CHAR(64) NOT NULL CHECK(artifact_sha256 ~ '^[0-9a-f]{64}$'),
  dependency_lock_sha256 CHAR(64) NOT NULL CHECK(dependency_lock_sha256 ~ '^[0-9a-f]{64}$'),
  build_manifest JSONB NOT NULL CHECK(jsonb_typeof(build_manifest)='object'),
  manifest_sha256 CHAR(64) NOT NULL CHECK(manifest_sha256 ~ '^[0-9a-f]{64}$'),
  signer_key_id VARCHAR(128) NOT NULL CHECK(signer_key_id ~ '^[A-Za-z0-9._-]{3,128}$'),
  signature_base64 TEXT NOT NULL CHECK(length(signature_base64) BETWEEN 80 AND 256),
  artifact_verification VARCHAR(32) NOT NULL DEFAULT 'UNVERIFIED'
    CHECK(artifact_verification='UNVERIFIED'),
  binding_state VARCHAR(32) NOT NULL DEFAULT 'EVIDENCE_BOUND_HOLD'
    CHECK(binding_state='EVIDENCE_BOUND_HOLD'),
  execution_permitted BOOLEAN NOT NULL DEFAULT FALSE CHECK(NOT execution_permitted),
  binding_sha256 CHAR(64) NOT NULL CHECK(binding_sha256 ~ '^[0-9a-f]{64}$'),
  created_by VARCHAR(200) NOT NULL CHECK(length(btrim(created_by))>0),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  FOREIGN KEY(organization_id,blocked_pair_id)
    REFERENCES fin_exec.htap_fin01_two_month_blocked_plan(organization_id,id),
  UNIQUE(organization_id,blocked_pair_id),
  UNIQUE(organization_id,binding_sha256)
);
CREATE FUNCTION fin_exec.guard_fin01_evidence_binding()
RETURNS TRIGGER LANGUAGE plpgsql AS $body$
DECLARE p RECORD;
DECLARE c RECORD;
DECLARE a RECORD;
DECLARE s RECORD;
BEGIN
  SELECT b.*,parent.data_space INTO p
  FROM fin_exec.htap_fin01_two_month_blocked_plan b
  JOIN fin_exec.htap_blocked_method_plan parent
    ON parent.organization_id=b.organization_id AND parent.plan_id=b.blocked_plan_id
  WHERE b.organization_id=NEW.organization_id AND b.id=NEW.blocked_pair_id;
  IF p.id IS NULL OR p.plan_sha256<>NEW.plan_sha256
    OR p.plan_state<>'BLOCKED_PLAN_ONLY' OR p.execution_permitted
    OR p.data_space<>'SYNTHETIC'
    OR p.algorithm_class_sha256<>NEW.algorithm_class_sha256
  THEN RAISE EXCEPTION 'FIN01_BINDING_PLAN_MISMATCH'; END IF;
  SELECT * INTO a FROM fin_calc.method_active_config ac
  WHERE ac.method_code='FIN-01' AND ac.variant_code='REV_NET_MOM' FOR SHARE;
  SELECT cfg.* INTO c FROM fin_calc.method_config_version cfg
  WHERE cfg.config_id=NEW.config_id;
  IF c.config_id IS NULL OR a.config_id IS DISTINCT FROM NEW.config_id
    OR a.pointer_version IS DISTINCT FROM NEW.pointer_version
    OR c.method_code<>'FIN-01' OR c.variant_code<>'REV_NET_MOM'
    OR c.engine<>'SQL' OR c.contract_id<>NEW.contract_id
    OR c.version_no<>NEW.config_version
    OR c.effective_sha256<>NEW.effective_sha256
    OR c.params_sha256<>NEW.params_sha256
    OR c.algorithm_id<>NEW.algorithm_id
    OR c.algorithm_version<>NEW.algorithm_version
    OR c.algorithm_sha256<>NEW.algorithm_class_sha256
    OR NOT EXISTS(
      SELECT 1 FROM fin_calc.method_parameter_contract co
      WHERE co.contract_id=NEW.contract_id
        AND co.method_code='FIN-01' AND co.variant_code='REV_NET_MOM'
        AND co.validation_profile='FIN01_MONTHLY_MOM_TYPED_V1'
        AND co.contract_version=NEW.contract_version
        AND co.schema_sha256=NEW.schema_sha256)
    OR NOT EXISTS(
      SELECT 1 FROM fin_calc.method_config_review r
      WHERE r.config_id=NEW.config_id AND r.decision='APPROVED')
    OR NOT EXISTS(
      SELECT 1 FROM fin_calc.htap_catalog_epoch e
      WHERE e.organization_id=NEW.organization_id AND e.catalog_epoch=p.catalog_epoch)
    OR NOT EXISTS(
      SELECT 1 FROM fin_calc.htap_mapping_active m
      WHERE m.organization_id=NEW.organization_id AND m.mapping_id=p.mapping_id
        AND m.mapping_sha256=p.mapping_sha256 AND m.catalog_epoch=p.catalog_epoch)
  THEN RAISE EXCEPTION 'FIN01_BINDING_CONFIG_NOT_CURRENT_OR_MODERATED'; END IF;
  SELECT * INTO s FROM fin_calc.method_s2_review rev
  WHERE rev.id=NEW.method_s2_review_id;
  IF s.id IS NULL OR s.review_decision<>'APPROVED'
    OR s.method_code<>NEW.algorithm_id OR s.method_version<>NEW.algorithm_version
    OR s.form_code<>'F02' OR s.form_version<>'FIN-PILOT-V1'
    OR s.reporting_basis<>'MANAGEMENT'
    OR s.review_evidence_sha<>NEW.s2_evidence_sha256
    OR CURRENT_TIMESTAMP<s.effective_from OR CURRENT_TIMESTAMP>=s.effective_until
    OR EXISTS(SELECT 1 FROM fin_calc.method_s2_revocation r WHERE r.review_id=s.id)
  THEN RAISE EXCEPTION 'FIN01_BINDING_S2_NOT_EFFECTIVE'; END IF;
  IF NEW.build_manifest->>'methodCode' IS DISTINCT FROM NEW.algorithm_id
    OR NEW.build_manifest->>'algorithmVersion' IS DISTINCT FROM NEW.algorithm_version
    OR NEW.build_manifest->>'artifactSha256' IS DISTINCT FROM NEW.artifact_sha256
    OR NEW.build_manifest->>'dependencyLockSha256' IS DISTINCT FROM NEW.dependency_lock_sha256
    OR NEW.build_manifest->>'implementationClassSha256' IS DISTINCT FROM NEW.algorithm_class_sha256
    OR jsonb_typeof(NEW.build_manifest->'dependencies') IS DISTINCT FROM 'array'
    OR jsonb_array_length(NEW.build_manifest->'dependencies')=0
  THEN RAISE EXCEPTION 'FIN01_BINDING_MANIFEST_INCONSISTENT'; END IF;
  -- Hash domain is explicitly PostgreSQL 16 JSONB canonical text.
  NEW.manifest_sha256:=encode(sha256(convert_to(
    'FIN:HTAP:BUILD-MANIFEST:PG16-V1|'||NEW.build_manifest::text,'UTF8')),'hex');
  NEW.binding_sha256:=encode(sha256(convert_to(
    'FIN:HTAP:FIN01:BINDING:PG16-V1|'||
     jsonb_build_array(NEW.organization_id,NEW.blocked_pair_id,NEW.plan_sha256,
       NEW.config_id,NEW.contract_id,NEW.contract_version,NEW.config_version,
       NEW.pointer_version,NEW.effective_sha256,NEW.params_sha256,
       NEW.schema_sha256,NEW.algorithm_id,NEW.algorithm_version,
       NEW.algorithm_class_sha256,NEW.method_s2_review_id,NEW.s2_evidence_sha256,
       NEW.artifact_sha256,NEW.dependency_lock_sha256,NEW.manifest_sha256,
       NEW.signer_key_id,NEW.signature_base64,
       NEW.artifact_verification,NEW.binding_state,NEW.execution_permitted)::text,
     'UTF8')),'hex');
  RETURN NEW;
END;
$body$;
CREATE TRIGGER trg_fin01_evidence_binding_insert
BEFORE INSERT ON fin_exec.htap_fin01_evidence_binding
FOR EACH ROW EXECUTE FUNCTION fin_exec.guard_fin01_evidence_binding();
CREATE TRIGGER trg_fin01_evidence_binding_immutable
BEFORE UPDATE OR DELETE ON fin_exec.htap_fin01_evidence_binding
FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
REVOKE ALL ON fin_exec.htap_fin01_evidence_binding FROM PUBLIC;
COMMENT ON TABLE fin_exec.htap_fin01_evidence_binding IS
  'Immutable FIN-01 evidence snapshot. Signature NOT verified here; no execution permit.';
