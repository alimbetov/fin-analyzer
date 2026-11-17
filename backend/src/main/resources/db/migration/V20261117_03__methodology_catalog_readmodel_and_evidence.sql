-- MR-R0.1: durable evidence of methodology progress without rewriting the
-- original 60-method JSON snapshot or faking method S2 qualification.
CREATE TABLE fin_calc.method_implementation_evidence (
  sequence_no BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  method_code VARCHAR(32) NOT NULL REFERENCES fin_calc.method_family(method_code),
  implementation_status VARCHAR(80) NOT NULL CHECK (implementation_status IN (
    'NOT_YET_AUDITED','CODE_CANDIDATE_NOT_QUALIFIED',
    'PURE_ENGINE_GOLDENS_AUTHORED_NO_TRUSTED_ADAPTER',
    'ONE_VARIANT_WIRED_UNQUALIFIED',
    'INTEGRATION_TESTED_UNQUALIFIED','READY_FOR_INDEPENDENT_S2')),
  evidence_sha256 CHAR(64) NOT NULL CHECK(evidence_sha256 ~ '^[0-9a-f]{64}$'),
  recorded_by VARCHAR(200) NOT NULL CHECK(length(btrim(recorded_by))>0),
  reason VARCHAR(2000) NOT NULL CHECK(length(btrim(reason))>0),
  recorded_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_fin_method_evidence_lookup
  ON fin_calc.method_implementation_evidence(method_code,sequence_no DESC);
CREATE TRIGGER trg_fin_method_evidence_immutable BEFORE UPDATE OR DELETE
ON fin_calc.method_implementation_evidence FOR EACH ROW
EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

-- ALTER/CREATE OR REPLACE VIEW may only append new columns and preserve the
-- previous column names/order/types used by existing read consumers.
CREATE OR REPLACE VIEW fin_calc.methodology_catalog_view AS
SELECT f.method_code, f.method_group, f.title,
       COALESCE(e.implementation_status,f.implementation_status)::VARCHAR(80)
         AS implementation_status,
       f.catalog_version, f.release_qualified, f.auto_run_allowed,
       COALESCE(cfg.total_config_versions,0) AS total_config_versions,
       COALESCE(a.active_variant_count,0) AS active_variant_count,
       COALESCE(a.active_variants,'[]'::jsonb) AS active_variants,
       FALSE AS method_execution_permitted,
       COALESCE(last_config.latest_variants,'[]'::jsonb) AS latest_variant_configs,
       e.sequence_no AS implementation_evidence_sequence
FROM fin_calc.method_family f
LEFT JOIN LATERAL (
  SELECT ev.implementation_status,ev.sequence_no
  FROM fin_calc.method_implementation_evidence ev
  WHERE ev.method_code=f.method_code
  ORDER BY ev.sequence_no DESC LIMIT 1
) e ON TRUE
LEFT JOIN LATERAL (
  SELECT count(*) AS total_config_versions
  FROM fin_calc.method_config_version v WHERE v.method_code=f.method_code
) cfg ON TRUE
LEFT JOIN LATERAL (
  SELECT count(*) AS active_variant_count,
         jsonb_agg(jsonb_build_object(
           'variantCode',ac.variant_code,'configId',v.config_id,
           'versionNo',v.version_no,'algorithmVersion',v.algorithm_version,
           'parameters',v.parameters,'parametersSha256',v.params_sha256,
           'effectiveSha256',v.effective_sha256,
           'pointerVersion',ac.pointer_version)
           ORDER BY ac.variant_code) AS active_variants
  FROM fin_calc.method_active_config ac
  JOIN fin_calc.method_config_version v ON v.config_id=ac.config_id
  WHERE ac.method_code=f.method_code
) a ON TRUE
LEFT JOIN LATERAL (
  SELECT jsonb_agg(jsonb_build_object(
     'variantCode',v.variant_code,'configId',v.config_id,
     'versionNo',v.version_no,'effectiveSha256',v.effective_sha256,
     'parameters',v.parameters,
     'moderation',COALESCE(r.decision,'PENDING'))
     ORDER BY v.variant_code) AS latest_variants
  FROM (
    SELECT DISTINCT ON (q.variant_code)
      q.variant_code,q.config_id,q.version_no,q.effective_sha256,q.parameters
    FROM fin_calc.method_config_version q
    WHERE q.method_code=f.method_code
    ORDER BY q.variant_code,q.version_no DESC
  ) v
  LEFT JOIN fin_calc.method_config_review r ON r.config_id=v.config_id
) last_config ON TRUE;

-- NOTE: evidence rows describe engineering status only; no such row may
-- enable ACTUAL/S2/autoRun, all of which are separate existing controls.
