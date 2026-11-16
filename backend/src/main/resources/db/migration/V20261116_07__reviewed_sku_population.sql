-- FIN CON-R1: separately reviewed SKU population for concentration analysis.
-- This is a controlled server-side evidence registry, NOT an authorization API,
-- an external invoice signature, or automatic qualification of the 60 families.
CREATE SCHEMA IF NOT EXISTS fin_con;

CREATE TABLE fin_con.sku_population_submission (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    economic_entity_id UUID NOT NULL,
    business_scope_id UUID NOT NULL,
    fact_set_revision_id UUID NOT NULL,
    published_snapshot_hash CHAR(64) NOT NULL CHECK (published_snapshot_hash ~ '^[0-9a-f]{64}$'),
    reporting_basis VARCHAR(24) NOT NULL CHECK (reporting_basis IN ('MANAGEMENT','SEPARATE','CONSOLIDATED')),
    data_space VARCHAR(20) NOT NULL CHECK (data_space='ACTUAL'),
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    source_document_hash CHAR(64) NOT NULL CHECK (source_document_hash ~ '^[0-9a-f]{64}$'),
    source_system VARCHAR(128) NOT NULL CHECK (length(btrim(source_system))>0),
    submitted_by VARCHAR(200) NOT NULL CHECK (length(btrim(submitted_by))>0),
    expected_line_count INTEGER NOT NULL CHECK (expected_line_count BETWEEN 2 AND 10000),
    expected_net_total NUMERIC(38,12) NOT NULL CHECK (expected_net_total>0),
    coverage_policy VARCHAR(64) NOT NULL DEFAULT 'SKU_PERIOD_GROSS_RETURNS_CANCEL_V1'
        CHECK (coverage_policy='SKU_PERIOD_GROSS_RETURNS_CANCEL_V1'),
    submitted_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_sku_revision_scope_period UNIQUE
        (organization_id,fact_set_revision_id,period_start,period_end),
    CONSTRAINT uq_sku_submission_tenant UNIQUE (id,organization_id),
    CONSTRAINT fk_sku_published_snapshot FOREIGN KEY (fact_set_revision_id,organization_id)
        REFERENCES fin_fact.fact_set_publication(fact_set_revision_id,organization_id),
    CONSTRAINT ck_sku_calendar_month CHECK (
        period_start=date_trunc('month',period_start)::date
        AND period_end=(period_start+interval '1 month')::date)
);
CREATE TRIGGER trg_sku_submission_immutable BEFORE UPDATE OR DELETE
ON fin_con.sku_population_submission FOR EACH ROW
EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

CREATE TABLE fin_con.sku_sales_line (
    population_id UUID NOT NULL,
    organization_id UUID NOT NULL,
    source_line_ref VARCHAR(128) NOT NULL CHECK (source_line_ref ~ '^[A-Za-z0-9_-]{1,128}$'),
    sku_code VARCHAR(128) NOT NULL CHECK (sku_code ~ '^[A-Za-z0-9_-]{1,128}$'),
    event_kind VARCHAR(16) NOT NULL CHECK (event_kind IN ('SALE','RETURN','CANCELLATION')),
    original_sale_ref VARCHAR(128),
    sale_date DATE NOT NULL,
    currency CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    gross_amount NUMERIC(38,12) NOT NULL CHECK (gross_amount>0),
    source_document_hash CHAR(64) NOT NULL CHECK (source_document_hash ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY(population_id,source_line_ref),
    FOREIGN KEY (population_id,organization_id)
        REFERENCES fin_con.sku_population_submission(id,organization_id),
    CONSTRAINT ck_sku_sale_original CHECK (
        (event_kind='SALE' AND original_sale_ref IS NULL)
        OR (event_kind IN ('RETURN','CANCELLATION')
            AND original_sale_ref IS NOT NULL AND original_sale_ref<>source_line_ref))
);
CREATE INDEX idx_sku_sales_population_sku
ON fin_con.sku_sales_line(population_id,sku_code,event_kind);
CREATE TRIGGER trg_sku_line_immutable BEFORE UPDATE OR DELETE
ON fin_con.sku_sales_line FOR EACH ROW
EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

CREATE FUNCTION fin_con.check_sku_line_before_insert()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p fin_con.sku_population_submission%ROWTYPE;
BEGIN
    SELECT * INTO p FROM fin_con.sku_population_submission
    WHERE id=NEW.population_id AND organization_id=NEW.organization_id
    FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'SKU_POPULATION_NOT_FOUND_OR_FOREIGN'; END IF;
    IF EXISTS (SELECT 1 FROM fin_con.sku_population_review
               WHERE population_id=NEW.population_id)
       THEN RAISE EXCEPTION 'SKU_POPULATION_ALREADY_REVIEWED'; END IF;
    IF NEW.sale_date<p.period_start OR NEW.sale_date>=p.period_end
       OR NEW.currency<>p.currency
       OR NEW.source_document_hash<>p.source_document_hash
       THEN RAISE EXCEPTION 'SKU_LINE_DATE_CURRENCY_OR_SOURCE_MISMATCH'; END IF;
    RETURN NEW;
END;
$$;

CREATE TABLE fin_con.sku_population_review (
    population_id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    decision VARCHAR(12) NOT NULL CHECK (decision IN ('APPROVED','REJECTED')),
    reviewed_by VARCHAR(200) NOT NULL CHECK (length(btrim(reviewed_by))>0),
    review_reason VARCHAR(1000) NOT NULL CHECK (length(btrim(review_reason))>0),
    population_fingerprint CHAR(64) NOT NULL DEFAULT repeat('0',64)
        CHECK (population_fingerprint ~ '^[0-9a-f]{64}$'),
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY(population_id,organization_id)
        REFERENCES fin_con.sku_population_submission(id,organization_id)
);
CREATE TRIGGER trg_sku_line_seal BEFORE INSERT ON fin_con.sku_sales_line
FOR EACH ROW EXECUTE FUNCTION fin_con.check_sku_line_before_insert();
CREATE TRIGGER trg_sku_review_immutable BEFORE UPDATE OR DELETE
ON fin_con.sku_population_review FOR EACH ROW
EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

CREATE FUNCTION fin_con.verify_sku_population_review()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p fin_con.sku_population_submission%ROWTYPE;
DECLARE v_count BIGINT;
DECLARE v_net NUMERIC(38,12);
DECLARE v_f02_count BIGINT;
DECLARE v_f02_total NUMERIC(38,12);
DECLARE v_document TEXT;
BEGIN
    SELECT * INTO p FROM fin_con.sku_population_submission
     WHERE id=NEW.population_id AND organization_id=NEW.organization_id
     FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'SKU_POPULATION_NOT_FOUND_OR_FOREIGN'; END IF;
    IF p.submitted_by=NEW.reviewed_by THEN RAISE EXCEPTION 'SKU_SELF_REVIEW_FORBIDDEN'; END IF;
    IF NOT EXISTS (
       SELECT 1 FROM fin_fact.fact_set_publication f
       WHERE f.fact_set_revision_id=p.fact_set_revision_id
         AND f.organization_id=p.organization_id
         AND f.economic_entity_id=p.economic_entity_id
         AND f.business_scope_id=p.business_scope_id
         AND f.reporting_basis=p.reporting_basis
         AND f.data_space=p.data_space
         AND f.snapshot_hash=p.published_snapshot_hash
    ) THEN RAISE EXCEPTION 'SKU_FACT_SNAPSHOT_CONTEXT_MISMATCH'; END IF;
    IF NEW.decision='REJECTED' THEN RETURN NEW; END IF;

    SELECT count(*),
           COALESCE(sum(CASE WHEN event_kind='SALE'
                    THEN gross_amount ELSE -gross_amount END),0)
      INTO v_count,v_net
      FROM fin_con.sku_sales_line
     WHERE population_id=p.id AND organization_id=p.organization_id;
    IF v_count<>p.expected_line_count OR v_net<>p.expected_net_total
        THEN RAISE EXCEPTION 'SKU_POPULATION_TOTAL_OR_COUNT_MISMATCH'; END IF;
    IF (SELECT count(DISTINCT sku_code) FROM fin_con.sku_sales_line
        WHERE population_id=p.id AND event_kind='SALE')<2
        THEN RAISE EXCEPTION 'SKU_POPULATION_REQUIRES_TWO_SKUS'; END IF;

    -- Every reversal references a sale in THIS sealed month, for the same SKU.
    IF EXISTS (
        SELECT 1 FROM fin_con.sku_sales_line x
        LEFT JOIN fin_con.sku_sales_line sale
          ON sale.population_id=x.population_id
         AND sale.source_line_ref=x.original_sale_ref
         AND sale.event_kind='SALE'
         AND sale.sku_code=x.sku_code
        WHERE x.population_id=p.id AND x.event_kind<>'SALE'
          AND sale.source_line_ref IS NULL
    ) THEN RAISE EXCEPTION 'SKU_REVERSAL_WITHOUT_ORIGINAL_SALE'; END IF;

    -- Returns can be partial, but never exceed original sale value.
    -- A cancellation is a full reversal and cannot coexist with a return.
    IF EXISTS (
      SELECT 1 FROM fin_con.sku_sales_line sale
      LEFT JOIN LATERAL (
        SELECT COALESCE(sum(CASE WHEN x.event_kind='RETURN'
                        THEN x.gross_amount ELSE 0 END),0) AS refunds,
               COALESCE(sum(CASE WHEN x.event_kind='CANCELLATION'
                        THEN x.gross_amount ELSE 0 END),0) AS cancelled,
               count(*) FILTER (WHERE x.event_kind='CANCELLATION') AS cancellations
        FROM fin_con.sku_sales_line x
        WHERE x.population_id=sale.population_id
          AND x.original_sale_ref=sale.source_line_ref
      ) adjustments ON true
      WHERE sale.population_id=p.id AND sale.event_kind='SALE'
        AND (adjustments.refunds>sale.gross_amount
          OR adjustments.cancellations>1
          OR (adjustments.cancellations=1
              AND (adjustments.cancelled<>sale.gross_amount OR adjustments.refunds<>0)))
    ) THEN RAISE EXCEPTION 'SKU_REVERSAL_EXCEEDS_OR_CONFLICTS_WITH_SALE'; END IF;

    -- Only a genuinely reviewed F02 net revenue from the pinned immutable
    -- financial revision qualifies as the independent accounting denominator.
    SELECT count(*),max(a.numeric_value)
      INTO v_f02_count,v_f02_total
      FROM fin_fact.fact_selection fs
      JOIN fin_fact.fact_assertion a
        ON a.organization_id=fs.organization_id AND a.id=fs.fact_assertion_id
      JOIN fin_fact.fact_origin origin ON origin.fact_assertion_id=a.id
        AND origin.organization_id=a.organization_id
      JOIN fin_fact.approved_source_revision sr
        ON sr.organization_id=origin.organization_id
       AND sr.source_revision_id=origin.source_revision_id
      JOIN fin_fact.approved_source_intake intake
        ON intake.organization_id=sr.organization_id
       AND intake.source_revision_id=sr.source_revision_id
      JOIN fin_intake.review fr
        ON fr.organization_id=sr.organization_id
       AND fr.submission_id=sr.source_revision_id AND fr.decision='APPROVED'
      JOIN fin_calc.metric_contract mc ON mc.metric_code=a.metric_code
       AND mc.metric_version=a.metric_version
      WHERE fs.organization_id=p.organization_id
        AND fs.fact_set_revision_id=p.fact_set_revision_id
        AND a.economic_entity_id=p.economic_entity_id
        AND a.business_scope_id=p.business_scope_id
        AND a.reporting_basis=p.reporting_basis
        AND a.metric_code='REV_NET' AND a.time_kind='INTERVAL'
        AND a.start_inclusive=p.period_start AND a.end_exclusive=p.period_end
        AND a.currency_code=p.currency AND a.unit_code='MONEY'
        AND a.value_state IN ('PRESENT','EXPLICIT_ZERO')
        AND a.dimensions='{}'::jsonb
        AND sr.form_code='F02' AND origin.source_field='netRevenue'
        AND mc.measure_kind='FLOW' AND mc.unit_code='MONEY'
        AND mc.qualification='QUALIFIED';
    IF v_f02_count<>1 OR v_f02_total<>v_net
        THEN RAISE EXCEPTION 'SKU_NOT_RECONCILED_TO_APPROVED_F02'; END IF;

    -- Preserve a repeatable evidence fingerprint; source bytes are not
    -- automatically authenticated by this document-hash declaration.
    SELECT string_agg(
      concat_ws('|',source_line_ref,sku_code,event_kind,
           coalesce(original_sale_ref,''),sale_date::text,
           currency,gross_amount::text,source_document_hash),'\n'
      ORDER BY source_line_ref) INTO v_document
    FROM fin_con.sku_sales_line WHERE population_id=p.id;
    NEW.population_fingerprint=encode(sha256(convert_to(
      concat_ws('|',p.organization_id::text,p.fact_set_revision_id::text,
          p.published_snapshot_hash,p.period_start::text,p.period_end::text,
          p.currency,p.source_document_hash,p.coverage_policy,
          v_document), 'UTF8')),'hex');
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_sku_review_verify BEFORE INSERT
ON fin_con.sku_population_review FOR EACH ROW
EXECUTE FUNCTION fin_con.verify_sku_population_review();
