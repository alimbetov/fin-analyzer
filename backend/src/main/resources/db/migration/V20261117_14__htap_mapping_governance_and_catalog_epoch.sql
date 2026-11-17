-- FIN HTAP W1.2.A governance: append-only review decisions and monotonic
-- tenant/form-scoped catalog mapping epoch. No execution/ACTUAL promotion.
-- Requires V20261117_13; does not change or rehash historic candidate rows.
ALTER TABLE fin_calc.htap_mapping_candidate_version
    ADD CONSTRAINT uq_htap_mapping_org_id UNIQUE (organization_id,mapping_id);
ALTER TABLE fin_calc.htap_mapping_candidate_version
    ADD CONSTRAINT uq_htap_mapping_identity_sha
    UNIQUE (organization_id,form_code,form_version,mapping_id,mapping_sha256);

CREATE TABLE fin_calc.htap_mapping_review (
    review_id UUID PRIMARY KEY,
    mapping_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    decision VARCHAR(16) NOT NULL CHECK (decision IN ('APPROVED','REJECTED')),
    reviewer_subject VARCHAR(200) NOT NULL CHECK (length(btrim(reviewer_subject))>0),
    evidence_sha256 CHAR(64) NOT NULL CHECK (evidence_sha256 ~ '^[0-9a-f]{64}$'),
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (organization_id,mapping_id),
    CONSTRAINT fk_htap_mapping_review_org FOREIGN KEY(organization_id,mapping_id)
        REFERENCES fin_calc.htap_mapping_candidate_version(organization_id,mapping_id)
);
CREATE FUNCTION fin_calc.htap_mapping_review_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE author TEXT;
BEGIN
    SELECT authored_by INTO author
      FROM fin_calc.htap_mapping_candidate_version
     WHERE mapping_id=NEW.mapping_id AND organization_id=NEW.organization_id;
    IF author IS NULL OR author=NEW.reviewer_subject THEN
       RAISE EXCEPTION 'FIN_HTAP_MAPPING_REVIEW_SOD_FAILED';
    END IF;
    RETURN NEW;
END
$$;
CREATE TRIGGER trg_htap_mapping_review_guard
BEFORE INSERT ON fin_calc.htap_mapping_review FOR EACH ROW
EXECUTE FUNCTION fin_calc.htap_mapping_review_guard();
CREATE TRIGGER trg_htap_mapping_review_immutable
BEFORE UPDATE OR DELETE ON fin_calc.htap_mapping_review FOR EACH ROW
EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

-- Global tenant/catalog epoch monotonically increases on each published mapping
-- pointer change. The active pointer is NOT immutable history; journal is.
CREATE TABLE fin_calc.htap_catalog_epoch (
    organization_id UUID PRIMARY KEY,
    catalog_epoch BIGINT NOT NULL CHECK (catalog_epoch>0)
);
CREATE TABLE fin_calc.htap_mapping_active (
    organization_id UUID NOT NULL,
    form_code VARCHAR(128) NOT NULL,
    form_version VARCHAR(64) NOT NULL,
    mapping_id UUID NOT NULL,
    mapping_sha256 CHAR(64) NOT NULL,
    catalog_epoch BIGINT NOT NULL CHECK (catalog_epoch>0),
    PRIMARY KEY (organization_id,form_code,form_version),
    CONSTRAINT fk_htap_mapping_active_exact FOREIGN KEY
      (organization_id,form_code,form_version,mapping_id,mapping_sha256)
      REFERENCES fin_calc.htap_mapping_candidate_version
      (organization_id,form_code,form_version,mapping_id,mapping_sha256)
);
CREATE TABLE fin_calc.htap_mapping_activation_journal (
    activation_id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    form_code VARCHAR(128) NOT NULL,
    form_version VARCHAR(64) NOT NULL,
    mapping_id UUID NOT NULL,
    mapping_sha256 CHAR(64) NOT NULL,
    catalog_epoch BIGINT NOT NULL CHECK (catalog_epoch>0),
    activated_by VARCHAR(200) NOT NULL CHECK (length(btrim(activated_by))>0),
    activated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(organization_id,catalog_epoch),
    FOREIGN KEY(organization_id,form_code,form_version,mapping_id,mapping_sha256)
       REFERENCES fin_calc.htap_mapping_candidate_version
       (organization_id,form_code,form_version,mapping_id,mapping_sha256)
);
CREATE TRIGGER trg_htap_mapping_activation_immutable
BEFORE UPDATE OR DELETE ON fin_calc.htap_mapping_activation_journal FOR EACH ROW
EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

CREATE FUNCTION fin_calc.htap_mapping_activate_v1(
    p_org UUID, p_form TEXT, p_version TEXT, p_mapping UUID,
    p_expected_epoch BIGINT, p_actor TEXT)
RETURNS BIGINT LANGUAGE plpgsql AS $$
DECLARE v_epoch BIGINT;
DECLARE v_sha CHAR(64);
BEGIN
   IF p_org IS NULL OR p_mapping IS NULL OR p_actor IS NULL OR btrim(p_actor)=''
      OR p_expected_epoch IS NULL OR p_expected_epoch < 0 THEN
      RAISE EXCEPTION 'FIN_HTAP_MAPPING_ACTIVATION_INPUT_INVALID';
   END IF;
   -- Caller is a trusted service with FIN_PUBLISH; DB function is NOT an
   -- authorization boundary, hence deployment privileges must be restricted.
   INSERT INTO fin_calc.htap_catalog_epoch(organization_id,catalog_epoch)
   VALUES(p_org, 1) ON CONFLICT DO NOTHING;
   SELECT catalog_epoch INTO v_epoch
     FROM fin_calc.htap_catalog_epoch
    WHERE organization_id=p_org FOR UPDATE;
   -- 0 means initial revision before any activation; row is initialized to 1
   -- only when the *first* successful activation is committed.
   IF v_epoch <> GREATEST(p_expected_epoch,1) THEN
      RAISE EXCEPTION 'FIN_HTAP_CATALOG_EPOCH_STALE';
   END IF;
   IF p_expected_epoch=0 AND EXISTS
       (SELECT 1 FROM fin_calc.htap_mapping_activation_journal
         WHERE organization_id=p_org) THEN
      RAISE EXCEPTION 'FIN_HTAP_CATALOG_EPOCH_STALE';
   END IF;
   IF p_expected_epoch>0 AND v_epoch<>p_expected_epoch THEN
      RAISE EXCEPTION 'FIN_HTAP_CATALOG_EPOCH_STALE';
   END IF;
   SELECT mapping_sha256 INTO v_sha
     FROM fin_calc.htap_mapping_candidate_version
    WHERE organization_id=p_org AND form_code=p_form
      AND form_version=p_version AND mapping_id=p_mapping;
   IF v_sha IS NULL OR NOT EXISTS (
        SELECT 1 FROM fin_calc.htap_mapping_review
        WHERE organization_id=p_org AND mapping_id=p_mapping
          AND decision='APPROVED') THEN
      RAISE EXCEPTION 'FIN_HTAP_MAPPING_NOT_REVIEWED';
   END IF;
   IF EXISTS (SELECT 1 FROM fin_calc.htap_mapping_active
               WHERE organization_id=p_org AND form_code=p_form
                 AND form_version=p_version AND mapping_id=p_mapping) THEN
      RAISE EXCEPTION 'FIN_HTAP_MAPPING_ALREADY_ACTIVE';
   END IF;
   v_epoch := CASE WHEN p_expected_epoch=0 THEN 1 ELSE p_expected_epoch+1 END;
   UPDATE fin_calc.htap_catalog_epoch SET catalog_epoch=v_epoch WHERE organization_id=p_org;
   INSERT INTO fin_calc.htap_mapping_active
      (organization_id,form_code,form_version,mapping_id,mapping_sha256,catalog_epoch)
   VALUES(p_org,p_form,p_version,p_mapping,v_sha,v_epoch)
   ON CONFLICT(organization_id,form_code,form_version) DO UPDATE
       SET mapping_id=EXCLUDED.mapping_id,
           mapping_sha256=EXCLUDED.mapping_sha256,
           catalog_epoch=EXCLUDED.catalog_epoch;
   INSERT INTO fin_calc.htap_mapping_activation_journal
      (activation_id,organization_id,form_code,form_version,mapping_id,
       mapping_sha256,catalog_epoch,activated_by)
   VALUES(gen_random_uuid(),p_org,p_form,p_version,p_mapping,v_sha,v_epoch,p_actor);
   RETURN v_epoch;
END;
$$;

COMMENT ON FUNCTION fin_calc.htap_mapping_activate_v1(UUID,TEXT,TEXT,UUID,BIGINT,TEXT)
IS 'Internal transactional CAS; requires trusted service/FIN_PUBLISH and least-privilege DB grants. Does not grant financial execution.';
