-- FIN P0-B: exact raw XLSX evidence with immutable content.
-- The byte digest is computed by PostgreSQL to detect client/application tampering.
CREATE TABLE fin_intake.raw_xlsx (
  submission_id UUID PRIMARY KEY,
  organization_id UUID NOT NULL,
  raw_bytes BYTEA NOT NULL CHECK(octet_length(raw_bytes) BETWEEN 100 AND 2000000),
  raw_sha CHAR(64) NOT NULL CHECK(raw_sha ~ '^[a-f0-9]{64}$'),
  FOREIGN KEY(submission_id,organization_id)
    REFERENCES fin_intake.submission(id,organization_id)
);
CREATE FUNCTION fin_intake.verify_raw_xlsx()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE s fin_intake.submission%ROWTYPE;
BEGIN
 SELECT * INTO s FROM fin_intake.submission
 WHERE id=NEW.submission_id AND organization_id=NEW.organization_id FOR UPDATE;
 IF NOT FOUND OR s.intake_kind<>'XLSX'
    OR s.source_document_sha<>encode(sha256(NEW.raw_bytes),'hex')
    OR NEW.raw_sha<>encode(sha256(NEW.raw_bytes),'hex')
    THEN RAISE EXCEPTION 'INTAKE_XLSX_DIGEST_OR_SOURCE_MISMATCH'; END IF;
 RETURN NEW;
END; $$;
CREATE TRIGGER trg_raw_xlsx_verify BEFORE INSERT ON fin_intake.raw_xlsx
 FOR EACH ROW EXECUTE FUNCTION fin_intake.verify_raw_xlsx();
CREATE TRIGGER trg_raw_xlsx_immutable BEFORE UPDATE OR DELETE ON fin_intake.raw_xlsx
 FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
