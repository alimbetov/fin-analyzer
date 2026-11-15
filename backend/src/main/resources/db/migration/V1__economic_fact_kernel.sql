CREATE SCHEMA IF NOT EXISTS fin_fact;

CREATE TABLE fin_fact.fact_assertion (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    economic_entity_id UUID NOT NULL,
    business_scope_id UUID NOT NULL,
    metric_code VARCHAR(128) NOT NULL,
    metric_version VARCHAR(64) NOT NULL,
    time_kind VARCHAR(16) NOT NULL,
    start_inclusive DATE NOT NULL,
    end_exclusive DATE NOT NULL,
    reporting_basis VARCHAR(24) NOT NULL,
    unit_code VARCHAR(32) NOT NULL,
    currency_code VARCHAR(3),
    dimension_hash VARCHAR(64) NOT NULL,
    dimensions JSONB NOT NULL DEFAULT '{}'::jsonb,
    value_state VARCHAR(24) NOT NULL,
    numeric_value NUMERIC(38,12),
    estimate_method_ref VARCHAR(200),
    source_revision_id UUID NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_fact_period CHECK (end_exclusive > start_inclusive),
    CONSTRAINT ck_fact_asof CHECK (time_kind <> 'AS_OF' OR end_exclusive = start_inclusive + 1),
    CONSTRAINT ck_fact_time_kind CHECK (time_kind IN ('AS_OF','INTERVAL')),
    CONSTRAINT ck_fact_basis CHECK (reporting_basis IN ('SEPARATE','CONSOLIDATED','MANAGEMENT')),
    CONSTRAINT ck_fact_currency CHECK (currency_code IS NULL OR currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_fact_value CHECK (
        (value_state IN ('PRESENT','EXPLICIT_ZERO','ESTIMATED') AND numeric_value IS NOT NULL)
        OR (value_state IN ('MISSING','NOT_APPLICABLE') AND numeric_value IS NULL)
    ),
    CONSTRAINT ck_fact_zero CHECK (value_state <> 'EXPLICIT_ZERO' OR numeric_value = 0),
    CONSTRAINT ck_fact_estimate CHECK (
        (value_state = 'ESTIMATED' AND estimate_method_ref IS NOT NULL)
        OR (value_state <> 'ESTIMATED' AND estimate_method_ref IS NULL)
    ),
    CONSTRAINT ck_fact_finite CHECK (numeric_value IS NULL OR numeric_value::text NOT IN ('NaN','Infinity','-Infinity')),
    CONSTRAINT uq_fact_source_key UNIQUE NULLS NOT DISTINCT (
        organization_id, source_revision_id, economic_entity_id, business_scope_id,
        metric_code, metric_version, time_kind, start_inclusive, end_exclusive,
        reporting_basis, unit_code, currency_code, dimension_hash
    ),
    CONSTRAINT uq_fact_id_org UNIQUE (id, organization_id)
);

CREATE INDEX idx_fact_entity_metric_period
    ON fin_fact.fact_assertion (organization_id, economic_entity_id, metric_code, start_inclusive, end_exclusive);

CREATE TABLE fin_fact.fact_set_revision (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    parent_revision_id UUID,
    status VARCHAR(16) NOT NULL CHECK (status = 'APPROVED'),
    approved_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_factset_id_org UNIQUE (id, organization_id),
    CONSTRAINT fk_factset_parent_tenant FOREIGN KEY (parent_revision_id, organization_id)
        REFERENCES fin_fact.fact_set_revision(id, organization_id)
);

CREATE TABLE fin_fact.fact_selection (
    organization_id UUID NOT NULL,
    fact_set_revision_id UUID NOT NULL,
    fact_key_hash VARCHAR(64) NOT NULL,
    fact_assertion_id UUID NOT NULL,
    PRIMARY KEY (fact_set_revision_id, fact_key_hash),
    CONSTRAINT fk_selection_factset_tenant FOREIGN KEY (fact_set_revision_id, organization_id)
        REFERENCES fin_fact.fact_set_revision(id, organization_id),
    CONSTRAINT fk_selection_assertion_tenant FOREIGN KEY (fact_assertion_id, organization_id)
        REFERENCES fin_fact.fact_assertion(id, organization_id)
);

-- Snapshots and assertions are append-only: restatements create new IDs.
CREATE FUNCTION fin_fact.reject_immutable_mutation()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'FIN immutable record: create a new revision instead';
END;
$$;

CREATE TRIGGER trg_fact_immutable BEFORE UPDATE OR DELETE
    ON fin_fact.fact_assertion FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_factset_immutable BEFORE UPDATE OR DELETE
    ON fin_fact.fact_set_revision FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_selection_immutable BEFORE UPDATE OR DELETE
    ON fin_fact.fact_selection FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
