-- FIN-BE-R1: immutable server-generated catalog Excel drafts for 166 codes.
-- This store deliberately has NO foreign key to approved sources, FactSet or
-- analysis outbox and can never publish ACTUAL by insertion alone.
-- Only additive migration; previous Flyway versions must not be modified.

CREATE TABLE fin_intake.catalog_xlsx_draft (
    id UUID PRIMARY KEY,
    organization_id UUID NOT NULL,
    economic_entity_id UUID NOT NULL,
    business_scope_id UUID NOT NULL,
    project_code VARCHAR(50) NOT NULL
        CHECK (project_code ~ '^[A-Za-z0-9_-]{1,50}$'),
    form_code VARCHAR(64) NOT NULL
        CHECK (form_code ~ '^[A-Z][A-Z0-9-]{0,63}$'),
    capture_mode VARCHAR(32) NOT NULL
        CHECK (capture_mode IN ('TYPED_DRAFT','SCHEMA_NOT_APPROVED','EVIDENCE_ONLY')),
    reporting_basis VARCHAR(24) NOT NULL
        CHECK (reporting_basis IN ('MANAGEMENT','SEPARATE','CONSOLIDATED')),
    period_type VARCHAR(16) NOT NULL
        CHECK (period_type IN ('WEEK','MONTH','QUARTER','HALF_YEAR','YEAR','AS_OF')),
    period_code VARCHAR(20) NOT NULL,
    coverage VARCHAR(24) NOT NULL
        CHECK (coverage IN ('PERIOD_ONLY','YEAR_TO_DATE','LIFE_TO_DATE','AS_OF')),
    currency CHAR(3) NOT NULL
        CHECK (currency ~ '^[A-Z]{3}$'),
    amount_scale INTEGER NOT NULL CHECK (amount_scale IN (1,1000,1000000)),
    locale VARCHAR(8) NOT NULL CHECK (locale IN ('ru','kk','en')),
    schema_fingerprint CHAR(64) NOT NULL
        CHECK (schema_fingerprint ~ '^[0-9a-f]{64}$'),
    original_xlsx BYTEA NOT NULL
        CHECK (octet_length(original_xlsx) BETWEEN 100 AND 2000000),
    original_sha CHAR(64) NOT NULL
        CHECK (original_sha ~ '^[0-9a-f]{64}$'
            AND original_sha=encode(sha256(original_xlsx),'hex')),
    normalized_rows JSONB NOT NULL
        CHECK (jsonb_typeof(normalized_rows)='array'
            AND jsonb_array_length(normalized_rows) BETWEEN 1 AND 200),
    normalized_sha CHAR(64) NOT NULL
        CHECK (normalized_sha ~ '^[0-9a-f]{64}$'),
    document_original BYTEA,
    document_sha CHAR(64),
    document_media_type VARCHAR(80),
    state VARCHAR(24) NOT NULL DEFAULT 'UNQUALIFIED_DRAFT'
        CHECK (state='UNQUALIFIED_DRAFT'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ck_fin_catalog_xlsx_document_presence CHECK (
        (capture_mode='EVIDENCE_ONLY' AND document_original IS NOT NULL
         AND document_sha IS NOT NULL AND document_media_type='application/pdf')
        OR (capture_mode<>'EVIDENCE_ONLY' AND document_original IS NULL
            AND document_sha IS NULL AND document_media_type IS NULL)
    ),
    CONSTRAINT ck_fin_catalog_xlsx_document_digest CHECK (
        document_original IS NULL OR (
            octet_length(document_original) BETWEEN 16 AND 2000000
            AND document_sha ~ '^[0-9a-f]{64}$'
            AND document_sha=encode(sha256(document_original),'hex')
        )
    )
);

CREATE INDEX idx_fin_catalog_xlsx_draft_source
  ON fin_intake.catalog_xlsx_draft
  (organization_id,project_code,form_code,period_code,created_at DESC);

CREATE TRIGGER trg_fin_catalog_xlsx_draft_immutable
BEFORE UPDATE OR DELETE ON fin_intake.catalog_xlsx_draft
FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
