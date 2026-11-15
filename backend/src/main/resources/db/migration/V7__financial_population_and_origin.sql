-- FIN R4.1/R4.2: independent source-population controls and pilot field lineage.
-- This schema does NOT certify source-file ingestion or authorize ACTUAL endpoints.
CREATE TABLE fin_fact.pilot_field_mapping (
    form_code VARCHAR(128) NOT NULL,
    form_version VARCHAR(64) NOT NULL,
    source_field VARCHAR(128) NOT NULL,
    metric_code VARCHAR(128) NOT NULL,
    metric_version VARCHAR(64) NOT NULL,
    required_time_kind VARCHAR(16) NOT NULL CHECK (required_time_kind IN ('AS_OF','INTERVAL')),
    PRIMARY KEY(form_code,form_version,source_field),
    UNIQUE(form_code,form_version,source_field,metric_code,metric_version)
);
INSERT INTO fin_fact.pilot_field_mapping VALUES
    ('F01','FIN-PILOT-V1','assets','ASSETS','1.0.0','AS_OF'),
    ('F01','FIN-PILOT-V1','liabilities','LIABILITIES','1.0.0','AS_OF'),
    ('F01','FIN-PILOT-V1','equity','EQUITY','1.0.0','AS_OF'),
    ('F01','FIN-PILOT-V1','cash','CASH_END','1.0.0','AS_OF'),
    ('F01','FIN-PILOT-V1','receivables','AR_END','1.0.0','AS_OF'),
    ('F02','FIN-PILOT-V1','netRevenue','REV_NET','1.0.0','INTERVAL'),
    ('F02','FIN-PILOT-V1','cogs','COGS','1.0.0','INTERVAL'),
    ('F02','FIN-PILOT-V1','operatingExpenses','OPEX','1.0.0','INTERVAL'),
    ('F03','FIN-PILOT-V1','operatingCash','CFO','1.0.0','INTERVAL'),
    ('F03','FIN-PILOT-V1','investingCash','CFI','1.0.0','INTERVAL'),
    ('F03','FIN-PILOT-V1','financingCash','CFF','1.0.0','INTERVAL'),
    ('DF-CSH-01','FIN-PILOT-V1','receipt','CASH_IN','1.0.0','INTERVAL'),
    ('DF-CSH-02','FIN-PILOT-V1','payment','CASH_OUT','1.0.0','INTERVAL');
CREATE TRIGGER trg_pilot_field_mapping_immutable BEFORE UPDATE OR DELETE
ON fin_fact.pilot_field_mapping FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

CREATE TABLE fin_fact.fact_origin (
    fact_assertion_id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    source_revision_id UUID NOT NULL,
    source_row_id VARCHAR(200) NOT NULL CHECK(length(btrim(source_row_id))>0),
    source_field VARCHAR(128) NOT NULL CHECK(length(btrim(source_field))>0),
    PRIMARY KEY (fact_assertion_id),
    UNIQUE (organization_id,source_revision_id,source_row_id,source_field),
    FOREIGN KEY (fact_assertion_id,organization_id)
        REFERENCES fin_fact.fact_assertion(id,organization_id),
    FOREIGN KEY (organization_id,source_revision_id)
        REFERENCES fin_fact.approved_source_revision(organization_id,source_revision_id)
);
CREATE FUNCTION fin_fact.verify_origin()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE a fin_fact.fact_assertion%ROWTYPE;
DECLARE s fin_fact.approved_source_revision%ROWTYPE;
BEGIN
    SELECT * INTO a FROM fin_fact.fact_assertion WHERE id=NEW.fact_assertion_id
         AND organization_id=NEW.organization_id;
    IF NOT FOUND OR a.source_revision_id<>NEW.source_revision_id THEN
        RAISE EXCEPTION 'ORIGIN_SOURCE_OR_TENANT_MISMATCH';
    END IF;
    SELECT * INTO s FROM fin_fact.approved_source_revision
     WHERE organization_id=NEW.organization_id AND source_revision_id=NEW.source_revision_id;
    IF NOT FOUND OR NOT EXISTS (
        SELECT 1 FROM fin_fact.pilot_field_mapping m
        WHERE m.form_code=s.form_code AND m.form_version=s.form_version
          AND m.source_field=NEW.source_field AND m.metric_code=a.metric_code
          AND m.metric_version=a.metric_version AND m.required_time_kind=a.time_kind
    ) THEN RAISE EXCEPTION 'ORIGIN_UNQUALIFIED_FIELD_MAPPING'; END IF;
    IF EXISTS (
        SELECT 1 FROM fin_fact.source_population_control c
        WHERE c.organization_id=NEW.organization_id AND c.source_revision_id=NEW.source_revision_id
          AND c.metric_code=a.metric_code AND c.time_start=a.start_inclusive
          AND c.time_end=a.end_exclusive
    ) THEN RAISE EXCEPTION 'SOURCE_POPULATION_ALREADY_SEALED'; END IF;
    RETURN NEW;
END;
$$;
-- Control table is created before trigger execution, migration is transactional.
CREATE TABLE fin_fact.source_population_control (
    organization_id UUID NOT NULL,
    source_revision_id UUID NOT NULL,
    metric_code VARCHAR(128) NOT NULL,
    time_start DATE NOT NULL,
    time_end DATE NOT NULL,
    expected_rows INTEGER NOT NULL CHECK (expected_rows>0),
    expected_total NUMERIC(38,12) NOT NULL,
    source_document_hash CHAR(64) NOT NULL CHECK(source_document_hash ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY (organization_id,source_revision_id,metric_code,time_start,time_end),
    FOREIGN KEY (organization_id,source_revision_id)
        REFERENCES fin_fact.approved_source_revision(organization_id,source_revision_id),
    CHECK(time_end>time_start)
);
CREATE TRIGGER trg_fact_origin_verify BEFORE INSERT
ON fin_fact.fact_origin FOR EACH ROW EXECUTE FUNCTION fin_fact.verify_origin();
CREATE TRIGGER trg_fact_origin_immutable BEFORE UPDATE OR DELETE
ON fin_fact.fact_origin FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

CREATE FUNCTION fin_fact.verify_source_population_control()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE actual_rows BIGINT;
DECLARE actual_total NUMERIC;
BEGIN
    SELECT count(*),coalesce(sum(a.numeric_value),0)
      INTO actual_rows,actual_total
      FROM fin_fact.fact_origin o
      JOIN fin_fact.fact_assertion a ON a.id=o.fact_assertion_id
       AND a.organization_id=o.organization_id
     WHERE o.organization_id=NEW.organization_id
       AND o.source_revision_id=NEW.source_revision_id
       AND a.metric_code=NEW.metric_code
       AND a.start_inclusive=NEW.time_start AND a.end_exclusive=NEW.time_end
       AND a.value_state IN ('PRESENT','EXPLICIT_ZERO');
    IF actual_rows<>NEW.expected_rows OR actual_total<>NEW.expected_total THEN
        RAISE EXCEPTION 'SOURCE_POPULATION_COUNT_OR_TOTAL_MISMATCH';
    END IF;
    IF EXISTS (
        SELECT 1 FROM fin_fact.fact_origin o
        JOIN fin_fact.fact_assertion a ON a.id=o.fact_assertion_id
        WHERE o.organization_id=NEW.organization_id
        AND o.source_revision_id=NEW.source_revision_id
        AND a.metric_code=NEW.metric_code
        AND a.start_inclusive=NEW.time_start AND a.end_exclusive=NEW.time_end
        AND a.value_state NOT IN ('PRESENT','EXPLICIT_ZERO')
    ) THEN RAISE EXCEPTION 'SOURCE_POPULATION_UNAPPROVED_VALUES'; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_source_population_control_verify BEFORE INSERT
ON fin_fact.source_population_control FOR EACH ROW
EXECUTE FUNCTION fin_fact.verify_source_population_control();
CREATE TRIGGER trg_source_population_control_immutable BEFORE UPDATE OR DELETE
ON fin_fact.source_population_control FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE INDEX idx_fact_origin_source ON fin_fact.fact_origin(organization_id,source_revision_id);
