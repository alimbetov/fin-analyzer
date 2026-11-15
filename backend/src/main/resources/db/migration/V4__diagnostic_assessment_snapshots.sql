-- FIN-DIAG-R1: immutable experimental diagnostic assessment snapshots.
-- No existing migrations are modified. V1 rules are not financially qualified.
CREATE SCHEMA IF NOT EXISTS fin_diag;

CREATE TABLE fin_diag.assessment_run (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    fact_set_revision_id UUID NOT NULL,
    evaluation_month DATE NOT NULL,
    rule_set_code VARCHAR(128) NOT NULL,
    rule_set_version VARCHAR(64) NOT NULL,
    engine_version VARCHAR(64) NOT NULL,
    definition_hash CHAR(64) NOT NULL,
    input_fingerprint CHAR(64) NOT NULL,
    snapshot JSONB NOT NULL,
    snapshot_hash CHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_diag_tenant UNIQUE (id,organization_id),
    CONSTRAINT uq_diag_logical UNIQUE
        (organization_id,fact_set_revision_id,evaluation_month,
         rule_set_code,rule_set_version),
    CONSTRAINT fk_diag_published_fact_set FOREIGN KEY
        (fact_set_revision_id,organization_id)
        REFERENCES fin_fact.fact_set_publication(fact_set_revision_id,organization_id),
    CONSTRAINT ck_diag_month_start CHECK (EXTRACT(DAY FROM evaluation_month)=1),
    CONSTRAINT ck_diag_rule_code CHECK
        (rule_set_code ~ '^[A-Z][A-Z0-9_]{1,127}$'),
    CONSTRAINT ck_diag_rule_version CHECK (length(btrim(rule_set_version))>0),
    CONSTRAINT ck_diag_engine_version CHECK (length(btrim(engine_version))>0),
    CONSTRAINT ck_diag_definition_hash CHECK (definition_hash ~ '^[a-f0-9]{64}$'),
    CONSTRAINT ck_diag_snapshot_object CHECK (jsonb_typeof(snapshot)='object')
);

-- PostgreSQL, not the caller, computes both fingerprints; the historical
-- snapshot cannot be altered or deleted by application-role SQL.
CREATE FUNCTION fin_diag.sign_assessment()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE trusted_hash CHAR(64);
BEGIN
    SELECT p.snapshot_hash INTO trusted_hash
    FROM fin_fact.fact_set_publication p
    WHERE p.fact_set_revision_id=NEW.fact_set_revision_id
      AND p.organization_id=NEW.organization_id;
    IF trusted_hash IS NULL THEN RAISE EXCEPTION 'DIAG_INPUT_NOT_PUBLISHED'; END IF;
    NEW.input_fingerprint=encode(sha256(convert_to(jsonb_build_array(
        trusted_hash,NEW.evaluation_month,NEW.rule_set_code,NEW.rule_set_version,
        NEW.engine_version,NEW.definition_hash)::text,'UTF8')),'hex');
    NEW.snapshot_hash=encode(sha256(convert_to(NEW.snapshot::text,'UTF8')),'hex');
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_diag_snapshot_sign BEFORE INSERT
ON fin_diag.assessment_run FOR EACH ROW
EXECUTE FUNCTION fin_diag.sign_assessment();

CREATE TRIGGER trg_diag_snapshot_immutable BEFORE UPDATE OR DELETE
ON fin_diag.assessment_run FOR EACH ROW
EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

CREATE INDEX idx_diag_context_month
ON fin_diag.assessment_run(organization_id,evaluation_month DESC,fact_set_revision_id);
