-- Verified metric semantics are required before mapping stored facts to
-- time-series operators. No automatic deployment-time promotion to QUALIFIED.
CREATE TABLE fin_calc.metric_contract (
    metric_code VARCHAR(128) NOT NULL,
    metric_version VARCHAR(64) NOT NULL,
    measure_kind VARCHAR(16) NOT NULL
        CHECK (measure_kind IN ('FLOW','STOCK','LEVEL')),
    unit_code VARCHAR(32) NOT NULL,
    qualification VARCHAR(16) NOT NULL
        CHECK (qualification IN ('PILOT','QUALIFIED')),
    method_ref VARCHAR(128) NOT NULL,
    PRIMARY KEY (metric_code,metric_version),
    CONSTRAINT ck_contract_metric_code CHECK (
        metric_code ~ '^[A-Z][A-Z0-9_]{1,127}$'),
    CONSTRAINT ck_contract_unit_code CHECK (
        unit_code ~ '^[A-Z][A-Z0-9_]{0,31}$'),
    CONSTRAINT ck_contract_method CHECK (length(btrim(method_ref)) > 0)
);
CREATE TRIGGER trg_metric_contract_immutable BEFORE UPDATE OR DELETE
ON fin_calc.metric_contract FOR EACH ROW
EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

-- A report version must pin the actual column/operator definition, not only a label.
ALTER TABLE fin_calc.report_run
    ADD COLUMN definition_hash CHAR(64) NOT NULL DEFAULT repeat('0',64);

CREATE OR REPLACE FUNCTION fin_calc.set_report_fingerprint()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_hash CHAR(64);
BEGIN
    SELECT snapshot_hash INTO v_hash FROM fin_fact.fact_set_publication
    WHERE fact_set_revision_id=NEW.fact_set_revision_id
      AND organization_id=NEW.organization_id;
    IF v_hash IS NULL THEN RAISE EXCEPTION 'UNPUBLISHED_INPUTS'; END IF;
    NEW.input_fingerprint=encode(sha256(convert_to(
        jsonb_build_array(v_hash,NEW.report_code,NEW.report_version,
            NEW.method_version,NEW.definition_hash)::text,
        'UTF8')),'hex');
    RETURN NEW;
END;
$$;
