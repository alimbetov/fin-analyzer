-- FIN-BE-HARDENING-R2
-- V1 rows remain readable. Only V2 *published* fact sets are trusted calculation sources.
CREATE SCHEMA IF NOT EXISTS fin_calc;

CREATE TABLE fin_fact.approved_source_revision (
    organization_id UUID NOT NULL,
    source_revision_id UUID NOT NULL,
    form_code VARCHAR(128) NOT NULL,
    form_version VARCHAR(64) NOT NULL,
    approved_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (organization_id, source_revision_id),
    CONSTRAINT ck_approved_source_form CHECK (form_code ~ '^[A-Z][A-Z0-9-]{1,127}$'),
    CONSTRAINT ck_approved_source_version CHECK (length(btrim(form_version)) > 0)
);
CREATE TRIGGER trg_approved_source_immutable BEFORE UPDATE OR DELETE
ON fin_fact.approved_source_revision FOR EACH ROW
EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

-- Canonical identity comes from stored assertion coordinates, not the client.
-- jsonb_build_array canonicalizes dimension-object key order.
CREATE FUNCTION fin_fact.fact_identity_hash(p_fact_id UUID)
RETURNS TEXT LANGUAGE SQL STABLE AS $$
    SELECT encode(sha256(convert_to(jsonb_build_array(
        organization_id,economic_entity_id,business_scope_id,
        metric_code,metric_version,time_kind,start_inclusive,end_exclusive,
        reporting_basis,unit_code,currency_code,dimensions
    )::text,'UTF8')),'hex')
    FROM fin_fact.fact_assertion WHERE id=p_fact_id
$$;

CREATE TABLE fin_fact.fact_set_publication (
    fact_set_revision_id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    economic_entity_id UUID NOT NULL,
    business_scope_id UUID NOT NULL,
    reporting_basis VARCHAR(24) NOT NULL,
    data_space VARCHAR(16) NOT NULL,
    snapshot_hash CHAR(64) NOT NULL,
    published_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_published_data_space CHECK (
        data_space IN ('ACTUAL','PLAN','FORECAST','SCENARIO','SYNTHETIC')),
    CONSTRAINT ck_published_basis CHECK (
        reporting_basis IN ('SEPARATE','CONSOLIDATED','MANAGEMENT')),
    CONSTRAINT fk_publication_fact_set_tenant FOREIGN KEY
        (fact_set_revision_id,organization_id)
        REFERENCES fin_fact.fact_set_revision(id,organization_id),
    CONSTRAINT uq_published_scope UNIQUE
        (fact_set_revision_id,organization_id,economic_entity_id,
         business_scope_id,reporting_basis,data_space)
);

CREATE FUNCTION fin_fact.reject_selection_after_publication()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    -- Same lock as publication: protects against races with concurrent insert.
    PERFORM 1 FROM fin_fact.fact_set_revision
        WHERE id=NEW.fact_set_revision_id AND organization_id=NEW.organization_id
        FOR UPDATE;
    IF EXISTS (SELECT 1 FROM fin_fact.fact_set_publication
               WHERE fact_set_revision_id=NEW.fact_set_revision_id) THEN
        RAISE EXCEPTION 'FACT_SET_ALREADY_PUBLISHED';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_selection_sealed BEFORE INSERT
ON fin_fact.fact_selection FOR EACH ROW
EXECUTE FUNCTION fin_fact.reject_selection_after_publication();

CREATE FUNCTION fin_fact.validate_publication()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
    v_parent UUID;
    v_count BIGINT;
    v_hash TEXT;
BEGIN
    SELECT parent_revision_id INTO v_parent
    FROM fin_fact.fact_set_revision
    WHERE id=NEW.fact_set_revision_id AND organization_id=NEW.organization_id
    FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'UNKNOWN_FACT_SET'; END IF;

    IF v_parent IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM fin_fact.fact_set_publication p
        WHERE p.fact_set_revision_id=v_parent
          AND p.organization_id=NEW.organization_id
          AND p.economic_entity_id=NEW.economic_entity_id
          AND p.business_scope_id=NEW.business_scope_id
          AND p.reporting_basis=NEW.reporting_basis
          AND p.data_space=NEW.data_space
    ) THEN
        RAISE EXCEPTION 'PARENT_NOT_PUBLISHED_WITH_SAME_CONTEXT';
    END IF;

    SELECT count(*), encode(sha256(convert_to(string_agg(
        s.fact_key_hash || ':' || s.fact_assertion_id::text,
        '|' ORDER BY s.fact_key_hash),'UTF8')),'hex')
    INTO v_count, v_hash
    FROM fin_fact.fact_selection s
    WHERE s.organization_id=NEW.organization_id
      AND s.fact_set_revision_id=NEW.fact_set_revision_id;

    IF v_count=0 THEN RAISE EXCEPTION 'EMPTY_FACT_SET'; END IF;

    IF EXISTS (
        SELECT 1
        FROM fin_fact.fact_selection s
        JOIN fin_fact.fact_assertion a ON a.id=s.fact_assertion_id
        LEFT JOIN fin_fact.approved_source_revision sr
          ON sr.organization_id=s.organization_id
         AND sr.source_revision_id=a.source_revision_id
        WHERE s.organization_id=NEW.organization_id
          AND s.fact_set_revision_id=NEW.fact_set_revision_id
          AND (
            sr.source_revision_id IS NULL
            OR s.fact_key_hash IS DISTINCT FROM fin_fact.fact_identity_hash(a.id)
            OR a.economic_entity_id <> NEW.economic_entity_id
            OR a.business_scope_id <> NEW.business_scope_id
            OR a.reporting_basis <> NEW.reporting_basis
            OR (NEW.data_space='ACTUAL' AND a.value_state='ESTIMATED')
          )
    ) THEN RAISE EXCEPTION 'UNVERIFIED_FACT_SELECTION_OR_SOURCE'; END IF;

    NEW.snapshot_hash=v_hash;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_check_publication BEFORE INSERT
ON fin_fact.fact_set_publication FOR EACH ROW
EXECUTE FUNCTION fin_fact.validate_publication();
CREATE TRIGGER trg_publication_immutable BEFORE UPDATE OR DELETE
ON fin_fact.fact_set_publication FOR EACH ROW
EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

-- Mutable pointer is NOT financial history: it identifies the current immutable snapshot.
CREATE TABLE fin_fact.current_fact_set (
    organization_id UUID NOT NULL,
    economic_entity_id UUID NOT NULL,
    business_scope_id UUID NOT NULL,
    reporting_basis VARCHAR(24) NOT NULL,
    data_space VARCHAR(16) NOT NULL,
    fact_set_revision_id UUID NOT NULL,
    version BIGINT NOT NULL DEFAULT 1 CHECK (version>0),
    PRIMARY KEY (organization_id,economic_entity_id,business_scope_id,
                 reporting_basis,data_space),
    CONSTRAINT fk_current_published_context FOREIGN KEY
        (fact_set_revision_id,organization_id,economic_entity_id,
         business_scope_id,reporting_basis,data_space)
        REFERENCES fin_fact.fact_set_publication(
            fact_set_revision_id,organization_id,economic_entity_id,
            business_scope_id,reporting_basis,data_space)
);

CREATE TABLE fin_calc.report_run (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    fact_set_revision_id UUID NOT NULL,
    report_code VARCHAR(128) NOT NULL,
    report_version VARCHAR(64) NOT NULL,
    method_version VARCHAR(64) NOT NULL,
    input_fingerprint CHAR(64) NOT NULL,
    expected_cell_count INTEGER NOT NULL CHECK (expected_cell_count > 0),
    calculated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_report_run_semantics UNIQUE
        (organization_id,fact_set_revision_id,report_code,report_version,method_version),
    CONSTRAINT uq_report_run_tenant UNIQUE (id,organization_id),
    CONSTRAINT fk_run_published_tenant FOREIGN KEY
        (fact_set_revision_id,organization_id)
        REFERENCES fin_fact.fact_set_publication(fact_set_revision_id,organization_id),
    CONSTRAINT ck_report_code CHECK (report_code ~ '^[A-Z][A-Z0-9_]{1,127}$')
);

CREATE FUNCTION fin_calc.set_report_fingerprint()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_hash CHAR(64);
BEGIN
    SELECT snapshot_hash INTO v_hash FROM fin_fact.fact_set_publication
    WHERE fact_set_revision_id=NEW.fact_set_revision_id
      AND organization_id=NEW.organization_id;
    IF v_hash IS NULL THEN RAISE EXCEPTION 'UNPUBLISHED_INPUTS'; END IF;
    NEW.input_fingerprint=encode(sha256(convert_to(
        jsonb_build_array(v_hash,NEW.report_code,NEW.report_version,NEW.method_version)::text,
        'UTF8')),'hex');
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_report_fingerprint BEFORE INSERT
ON fin_calc.report_run FOR EACH ROW EXECUTE FUNCTION fin_calc.set_report_fingerprint();

CREATE TABLE fin_calc.report_cell (
    report_run_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    column_code VARCHAR(128) NOT NULL,
    display_order INTEGER NOT NULL CHECK (display_order > 0),
    translation_key VARCHAR(200) NOT NULL,
    status VARCHAR(24) NOT NULL,
    numeric_value NUMERIC(38,12),
    reason VARCHAR(200),
    unit_code VARCHAR(32) NOT NULL,
    currency_code VARCHAR(3),
    method_version VARCHAR(64) NOT NULL,
    PRIMARY KEY (report_run_id,column_code),
    CONSTRAINT uq_cell_order UNIQUE(report_run_id,display_order),
    CONSTRAINT fk_cell_run_tenant FOREIGN KEY
        (report_run_id,organization_id)
        REFERENCES fin_calc.report_run(id,organization_id),
    CONSTRAINT ck_cell_value CHECK (
        (status='CALCULATED' AND numeric_value IS NOT NULL AND reason IS NULL) OR
        (status='ESTIMATED' AND numeric_value IS NOT NULL AND reason IS NOT NULL) OR
        (status IN ('INCOMPLETE','NOT_COMPUTABLE') AND numeric_value IS NULL AND reason IS NOT NULL)
    ),
    CONSTRAINT ck_cell_finite CHECK (
        numeric_value IS NULL OR numeric_value::text NOT IN ('NaN','Infinity','-Infinity')),
    CONSTRAINT ck_cell_currency CHECK (
        (unit_code='MONEY' AND currency_code ~ '^[A-Z]{3}$') OR
        (unit_code <> 'MONEY' AND currency_code IS NULL))
);

CREATE TABLE fin_calc.report_publication (
    report_run_id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    cells_hash CHAR(64) NOT NULL,
    finalized_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_finalized_run_tenant FOREIGN KEY (report_run_id,organization_id)
        REFERENCES fin_calc.report_run(id,organization_id)
);

CREATE FUNCTION fin_calc.reject_cell_after_publication()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM fin_calc.report_run
        WHERE id=NEW.report_run_id AND organization_id=NEW.organization_id FOR UPDATE;
    IF EXISTS (SELECT 1 FROM fin_calc.report_publication
               WHERE report_run_id=NEW.report_run_id) THEN
        RAISE EXCEPTION 'REPORT_ALREADY_FINALIZED';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_cell_sealed BEFORE INSERT
ON fin_calc.report_cell FOR EACH ROW
EXECUTE FUNCTION fin_calc.reject_cell_after_publication();

CREATE FUNCTION fin_calc.validate_report_publication()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_expected INTEGER;
DECLARE v_actual INTEGER;
DECLARE v_hash TEXT;
BEGIN
    SELECT expected_cell_count INTO v_expected FROM fin_calc.report_run
    WHERE id=NEW.report_run_id AND organization_id=NEW.organization_id FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'UNKNOWN_REPORT_RUN'; END IF;
    SELECT count(*), encode(sha256(convert_to(string_agg(
        jsonb_build_array(column_code,display_order,translation_key,status,
            numeric_value,reason,unit_code,currency_code,method_version)::text,
        '|' ORDER BY display_order),'UTF8')),'hex')
    INTO v_actual,v_hash
    FROM fin_calc.report_cell
    WHERE report_run_id=NEW.report_run_id AND organization_id=NEW.organization_id;
    IF v_actual<>v_expected THEN RAISE EXCEPTION 'REPORT_CELL_COUNT_MISMATCH'; END IF;
    NEW.cells_hash=v_hash;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_report_validate BEFORE INSERT
ON fin_calc.report_publication FOR EACH ROW
EXECUTE FUNCTION fin_calc.validate_report_publication();

CREATE TRIGGER trg_report_run_immutable BEFORE UPDATE OR DELETE
ON fin_calc.report_run FOR EACH ROW
EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_report_cell_immutable BEFORE UPDATE OR DELETE
ON fin_calc.report_cell FOR EACH ROW
EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_report_publication_immutable BEFORE UPDATE OR DELETE
ON fin_calc.report_publication FOR EACH ROW
EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

CREATE INDEX idx_report_run_factset ON fin_calc.report_run(
    organization_id,fact_set_revision_id,report_code);
CREATE INDEX idx_report_cell_order ON fin_calc.report_cell(
    report_run_id,display_order);
