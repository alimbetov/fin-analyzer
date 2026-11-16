-- CAT-A3.1: append-only independent financial methodology review ledger.
-- The migration creates no approvals. Its existence never unlocks ACTUAL runs.
-- Review events must be created by a separately authorized back-office identity.
CREATE TABLE fin_calc.method_s2_review (
    id UUID PRIMARY KEY,
    method_code VARCHAR(128) NOT NULL
        CHECK (method_code ~ '^[A-Z][A-Z0-9_-]{1,127}(/[A-Z][A-Z0-9_]{1,127})?$'),
    method_version VARCHAR(64) NOT NULL CHECK (length(btrim(method_version))>0),
    form_code VARCHAR(128) NOT NULL CHECK (form_code ~ '^[A-Z][A-Z0-9-]{0,127}$'),
    form_version VARCHAR(64) NOT NULL CHECK (length(btrim(form_version))>0),
    reporting_basis VARCHAR(24) NOT NULL
        CHECK (reporting_basis IN ('MANAGEMENT','SEPARATE','CONSOLIDATED')),
    semantic_contract_sha CHAR(64) NOT NULL
        CHECK (semantic_contract_sha ~ '^[0-9a-f]{64}$'),
    golden_cases_sha CHAR(64) NOT NULL
        CHECK (golden_cases_sha ~ '^[0-9a-f]{64}$'),
    review_evidence_sha CHAR(64) NOT NULL
        CHECK (review_evidence_sha ~ '^[0-9a-f]{64}$'),
    submitted_by VARCHAR(200) NOT NULL CHECK (length(btrim(submitted_by))>0),
    reviewed_by VARCHAR(200) NOT NULL CHECK (length(btrim(reviewed_by))>0),
    review_decision VARCHAR(12) NOT NULL CHECK (review_decision IN ('APPROVED','REJECTED')),
    review_reason VARCHAR(2000) NOT NULL CHECK (length(btrim(review_reason))>0),
    effective_from TIMESTAMPTZ NOT NULL,
    effective_until TIMESTAMPTZ NOT NULL,
    reviewed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (submitted_by <> reviewed_by),
    CHECK (effective_until > effective_from),
    CONSTRAINT uq_method_review_identity UNIQUE (
        id,method_code,method_version,form_code,form_version)
);

-- One approval per version/scope. Changes require a new version with a new S2 review.
CREATE UNIQUE INDEX uq_s2_approved_method_variant ON fin_calc.method_s2_review(
    method_code,method_version,form_code,form_version,reporting_basis)
    WHERE review_decision='APPROVED';
CREATE INDEX idx_method_s2_lookup ON fin_calc.method_s2_review(
    method_code,method_version,form_code,form_version,reporting_basis,effective_from,effective_until);

CREATE TRIGGER trg_method_s2_review_immutable BEFORE UPDATE OR DELETE
ON fin_calc.method_s2_review FOR EACH ROW
EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

CREATE TABLE fin_calc.method_s2_revocation (
    id UUID PRIMARY KEY,
    review_id UUID NOT NULL UNIQUE REFERENCES fin_calc.method_s2_review(id),
    revoked_by VARCHAR(200) NOT NULL CHECK (length(btrim(revoked_by))>0),
    reason VARCHAR(2000) NOT NULL CHECK (length(btrim(reason))>0),
    evidence_sha CHAR(64) NOT NULL CHECK (evidence_sha ~ '^[0-9a-f]{64}$'),
    revoked_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TRIGGER trg_method_s2_revocation_immutable BEFORE UPDATE OR DELETE
ON fin_calc.method_s2_revocation FOR EACH ROW
EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

-- Prevent revocation of an absent/denied review and backdated revocations.
CREATE FUNCTION fin_calc.validate_s2_revocation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_decision VARCHAR(12);
DECLARE v_reviewed_at TIMESTAMPTZ;
BEGIN
    SELECT review_decision,reviewed_at INTO v_decision,v_reviewed_at
      FROM fin_calc.method_s2_review WHERE id=NEW.review_id FOR UPDATE;
    IF v_decision IS NULL OR v_decision <> 'APPROVED' THEN
       RAISE EXCEPTION 'S2_REVOCATION_REQUIRES_APPROVED_REVIEW';
    END IF;
    IF NEW.revoked_at < v_reviewed_at THEN
       RAISE EXCEPTION 'S2_REVOCATION_BEFORE_REVIEW';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_method_s2_revocation_validate BEFORE INSERT
ON fin_calc.method_s2_revocation FOR EACH ROW
EXECUTE FUNCTION fin_calc.validate_s2_revocation();

-- No bootstrap approvals, no runtime auto-approval, no client-facing write route.
