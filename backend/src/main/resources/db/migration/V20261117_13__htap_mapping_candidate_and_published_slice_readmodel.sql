-- FIN HTAP W1.2.A: mapping versions are evidence CANDIDATES, never approvals.
-- Published source selection remains grounded in immutable fin_fact and outbox.
-- No changes to prior Flyway files, existing source identities, execution flags,
-- method approval, aggregate cache or physical workspace DDL.
CREATE TABLE fin_calc.htap_mapping_candidate_version (
    mapping_id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    organization_id UUID NOT NULL,
    form_code VARCHAR(128) NOT NULL
      CHECK (form_code ~ '^[A-Z][A-Z0-9-]{1,127}$'),
    form_version VARCHAR(64) NOT NULL
      CHECK (length(btrim(form_version)) > 0),
    mapping_version VARCHAR(64) NOT NULL
      CHECK (mapping_version ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'),
    mapping_payload JSONB NOT NULL
      CHECK (jsonb_typeof(mapping_payload)='object'
        AND mapping_payload<>'{}'::jsonb),
    mapping_sha256 CHAR(64) NOT NULL
      CHECK (mapping_sha256 ~ '^[0-9a-f]{64}$'),
    hash_codec VARCHAR(32) NOT NULL DEFAULT 'PG16_HTAP_MAP_V1'
      CHECK (hash_codec='PG16_HTAP_MAP_V1'),
    review_state VARCHAR(32) NOT NULL DEFAULT 'PROPOSED_UNREVIEWED'
      CHECK (review_state='PROPOSED_UNREVIEWED'),
    authored_by VARCHAR(200) NOT NULL
      CHECK (length(btrim(authored_by))>0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_htap_mapping_candidate_version
      UNIQUE (organization_id,form_code,form_version,mapping_version)
);

COMMENT ON TABLE fin_calc.htap_mapping_candidate_version IS
 'W1.2.A versioned proposal only: no trusted mapping activation/epoch or execution permission';

CREATE FUNCTION fin_calc.htap_mapping_candidate_hash_on_insert()
RETURNS TRIGGER LANGUAGE plpgsql AS $body$
BEGIN
    NEW.mapping_sha256 := encode(sha256(convert_to(
        'FIN:HTAP:MAPPING:PG16-V1|' ||
        jsonb_build_array(NEW.organization_id, NEW.form_code, NEW.form_version,
                          NEW.mapping_version, NEW.mapping_payload)::text,
        'UTF8')), 'hex');
    RETURN NEW;
END;
$body$;
CREATE TRIGGER trg_htap_mapping_candidate_hash
BEFORE INSERT ON fin_calc.htap_mapping_candidate_version
FOR EACH ROW EXECUTE FUNCTION fin_calc.htap_mapping_candidate_hash_on_insert();
CREATE TRIGGER trg_htap_mapping_candidate_immutable
BEFORE UPDATE OR DELETE ON fin_calc.htap_mapping_candidate_version
FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

CREATE INDEX idx_htap_mapping_candidate_form
    ON fin_calc.htap_mapping_candidate_version
    (organization_id,form_code,form_version,mapping_version);

-- Existing fact_selection primary key (fact_set_revision_id,fact_key_hash)
-- and assertion PK remain available for the exact published-source lookup.
-- Do NOT build an unmeasured, write-blocking ordinary index on a populated
-- source table in an ordinary transactional Flyway migration.
-- Any new large-table lookup index requires EXPLAIN evidence plus separate
-- zero/low-downtime rollout (CONCURRENTLY outside a transaction), W6 gate.

-- SECURITY INVOKER avoids privilege escalation through a view owner.
-- A trusted internal resolver must additionally enforce an authorized
-- tenant; this view grants neither authorization nor a calculation permit.
CREATE VIEW fin_calc.htap_published_slice_candidate_v1
WITH (security_invoker=true) AS
SELECT p.organization_id, p.economic_entity_id, p.business_scope_id,
       p.fact_set_revision_id, p.snapshot_hash AS published_snapshot_sha256,
       p.reporting_basis, p.data_space, p.published_at,
       s.fact_key_hash, a.id AS assertion_id, a.source_revision_id,
       sr.form_code, sr.form_version, a.metric_code, a.metric_version,
       a.time_kind, a.start_inclusive, a.end_exclusive,
       a.currency_code, a.unit_code, a.dimension_hash, a.value_state,
       a.numeric_value,
       'MAPPING_VERSION_NOT_QUALIFIED'::text AS mapping_status,
       false AS method_execution_permitted
FROM fin_fact.fact_set_publication p
JOIN fin_exec.fact_publication_outbox o
  ON o.organization_id=p.organization_id
 AND o.economic_entity_id=p.economic_entity_id
 AND o.business_scope_id=p.business_scope_id
 AND o.fact_set_revision_id=p.fact_set_revision_id
 AND o.snapshot_hash=p.snapshot_hash
 AND o.reporting_basis=p.reporting_basis
 AND o.data_space=p.data_space
 AND o.event_type='FACT_SET_PUBLISHED'
JOIN fin_fact.fact_selection s
  ON s.organization_id=p.organization_id
 AND s.fact_set_revision_id=p.fact_set_revision_id
JOIN fin_fact.fact_assertion a
  ON a.id=s.fact_assertion_id
 AND a.organization_id=s.organization_id
 AND a.economic_entity_id=p.economic_entity_id
 AND a.business_scope_id=p.business_scope_id
 AND a.reporting_basis=p.reporting_basis
JOIN fin_fact.approved_source_revision sr
  ON sr.organization_id=a.organization_id
 AND sr.source_revision_id=a.source_revision_id;

COMMENT ON VIEW fin_calc.htap_published_slice_candidate_v1 IS
 'W1.2.A read-only published source candidates, not mapped/qualified slices and never execution authority';
REVOKE ALL ON fin_calc.htap_published_slice_candidate_v1 FROM PUBLIC;
