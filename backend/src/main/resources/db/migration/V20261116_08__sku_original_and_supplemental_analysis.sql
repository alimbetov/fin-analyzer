-- FIN CON-R1.1: sealed source bytes and supplemental analysis for late SKU reviews.
-- NEVER edit V20261116_07 after applying Flyway. This migration upgrades it.

-- Existing immutable base event contract supports one supplemental event per
-- fact-set revision. This pilot permits at most one APPROVED SKU certificate
-- for the same revision, so the event key remains unique and deterministic.
ALTER TABLE fin_exec.fact_publication_outbox
    DROP CONSTRAINT IF EXISTS fact_publication_outbox_event_type_check;
ALTER TABLE fin_exec.fact_publication_outbox
    ADD CONSTRAINT ck_fin_exec_event_type_v2 CHECK (
        event_type IN ('FACT_SET_PUBLISHED','SKU_POPULATION_APPROVED'));

-- Preserve original bytes for independent review, with server-computed SHA256.
-- The bytes are never changed after publication; old test fixtures must now
-- provide the original record to gain a new approval.
CREATE TABLE fin_con.sku_source_original (
    population_id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    original_bytes BYTEA NOT NULL,
    sha256 CHAR(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    media_type VARCHAR(80) NOT NULL DEFAULT 'application/json'
        CHECK(media_type='application/json'),
    original_length INTEGER NOT NULL CHECK(original_length BETWEEN 2 AND 2097152),
    uploaded_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    FOREIGN KEY(population_id,organization_id)
        REFERENCES fin_con.sku_population_submission(id,organization_id),
    CONSTRAINT ck_sku_bytes_length CHECK (octet_length(original_bytes)=original_length)
);
CREATE FUNCTION fin_con.verify_sku_original()
RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE p fin_con.sku_population_submission%ROWTYPE;
BEGIN
    SELECT * INTO p FROM fin_con.sku_population_submission
    WHERE id=NEW.population_id AND organization_id=NEW.organization_id
    FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'SKU_ORIGINAL_POPULATION_NOT_FOUND'; END IF;
    IF EXISTS (
        SELECT 1 FROM fin_con.sku_population_review
         WHERE population_id=NEW.population_id
    ) THEN RAISE EXCEPTION 'SKU_ORIGINAL_ALREADY_REVIEWED'; END IF;
    IF NEW.sha256<>p.source_document_hash OR
       NEW.sha256<>encode(sha256(NEW.original_bytes),'hex') THEN
       RAISE EXCEPTION 'SKU_ORIGINAL_SHA256_MISMATCH'; END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_sku_original_verify BEFORE INSERT
ON fin_con.sku_source_original FOR EACH ROW
EXECUTE FUNCTION fin_con.verify_sku_original();
CREATE TRIGGER trg_sku_original_immutable BEFORE UPDATE OR DELETE
ON fin_con.sku_source_original FOR EACH ROW
EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

-- Original source bytes are required before a population may be approved.
CREATE FUNCTION fin_con.review_original_required()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.decision='APPROVED' AND NOT EXISTS (
    SELECT 1 FROM fin_con.sku_source_original orig
     JOIN fin_con.sku_population_submission p
       ON p.id=orig.population_id AND p.organization_id=orig.organization_id
     WHERE orig.population_id=NEW.population_id
       AND orig.organization_id=NEW.organization_id
       AND orig.sha256=p.source_document_hash
       AND orig.sha256=encode(sha256(orig.original_bytes),'hex')
  ) THEN RAISE EXCEPTION 'SKU_REVIEW_REQUIRES_SEALED_ORIGINAL'; END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER trg_a_sku_review_original BEFORE INSERT
ON fin_con.sku_population_review FOR EACH ROW
EXECUTE FUNCTION fin_con.review_original_required();

-- Replace late-review rejection with a no-loss model. Both review and
-- SingleExecutor.planOne() lock the same outbox event row, so whichever wins
-- first determines whether to emit supplemental analysis. Never mutate CORE.
CREATE OR REPLACE FUNCTION fin_con.verify_sku_population_review()
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

    -- Synchronize with SingleExecutor.planOne(): it locks the same immutable
    -- FACT_SET_PUBLISHED outbox row FOR UPDATE SKIP LOCKED.
    -- This prevents a concurrent approval from being silently omitted in the DAG.
    PERFORM 1 FROM fin_exec.fact_publication_outbox event
     WHERE event.organization_id=p.organization_id
       AND event.fact_set_revision_id=p.fact_set_revision_id
       AND event.event_type='FACT_SET_PUBLISHED'
     FOR UPDATE;
    IF NOT FOUND THEN RAISE EXCEPTION 'SKU_PUBLICATION_EVENT_NOT_FOUND'; END IF;
    -- A second approved ledger would make snapshot selection ambiguous.
    IF EXISTS (
       SELECT 1 FROM fin_con.sku_population_review earlier
       JOIN fin_con.sku_population_submission existing
         ON existing.id=earlier.population_id
       WHERE earlier.decision='APPROVED'
         AND existing.organization_id=p.organization_id
         AND existing.fact_set_revision_id=p.fact_set_revision_id
    ) THEN RAISE EXCEPTION 'SKU_ONE_APPROVED_LEDGER_PER_REVISION'; END IF;

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


-- Exactly one reanalysis event is emitted IF original outbox planning has
-- already committed. Otherwise the original event will select both methods.
CREATE FUNCTION fin_con.queue_supplemental_analysis()
RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.decision<>'APPROVED' THEN RETURN NEW; END IF;
  INSERT INTO fin_exec.fact_publication_outbox(
      event_id,organization_id,economic_entity_id,business_scope_id,
      fact_set_revision_id,event_type,reporting_basis,data_space,
      snapshot_hash,current_version)
  SELECT gen_random_uuid(),o.organization_id,o.economic_entity_id,
         o.business_scope_id,o.fact_set_revision_id,
         'SKU_POPULATION_APPROVED',o.reporting_basis,o.data_space,
         o.snapshot_hash,o.current_version
    FROM fin_exec.fact_publication_outbox o
    JOIN fin_exec.outbox_delivery delivered ON delivered.event_id=o.event_id
   WHERE o.organization_id=NEW.organization_id
     AND o.fact_set_revision_id=(
       SELECT p.fact_set_revision_id FROM fin_con.sku_population_submission p
       WHERE p.id=NEW.population_id AND p.organization_id=NEW.organization_id)
     AND o.event_type='FACT_SET_PUBLISHED';
  RETURN NEW;
END;
$$;
CREATE TRIGGER trg_sku_review_reanalyze AFTER INSERT
ON fin_con.sku_population_review FOR EACH ROW
EXECUTE FUNCTION fin_con.queue_supplemental_analysis();
