-- W1.2.A: editor identity must be derived from an authenticated backend
-- principal. A legacy/untrusted authored_by free-text is NOT authority.
CREATE TABLE fin_calc.htap_mapping_authorship (
  organization_id UUID NOT NULL,
  mapping_id UUID NOT NULL,
  author_subject VARCHAR(200) NOT NULL CHECK(length(btrim(author_subject))>0),
  attested_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  PRIMARY KEY(organization_id,mapping_id),
  FOREIGN KEY(organization_id,mapping_id)
    REFERENCES fin_calc.htap_mapping_candidate_version(organization_id,mapping_id)
);
CREATE TRIGGER trg_htap_mapping_authorship_immutable
BEFORE UPDATE OR DELETE ON fin_calc.htap_mapping_authorship FOR EACH ROW
EXECUTE FUNCTION fin_fact.reject_immutable_mutation();

-- Enforce attested creation before editor review; old rows with plain
-- authored_by never become activatable merely because they exist.
CREATE OR REPLACE FUNCTION fin_calc.htap_mapping_review_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE v_author TEXT;
DECLARE v_claimed TEXT;
BEGIN
  SELECT a.author_subject,c.authored_by INTO v_author,v_claimed
    FROM fin_calc.htap_mapping_candidate_version c
    JOIN fin_calc.htap_mapping_authorship a
      ON a.organization_id=c.organization_id AND a.mapping_id=c.mapping_id
   WHERE c.organization_id=NEW.organization_id AND c.mapping_id=NEW.mapping_id;
  IF v_author IS NULL OR v_author<>v_claimed
     OR v_author=NEW.reviewer_subject THEN
     RAISE EXCEPTION 'FIN_HTAP_MAPPING_AUTHENTICATED_SOD_REQUIRED';
  END IF;
  RETURN NEW;
END;
$$;
COMMENT ON TABLE fin_calc.htap_mapping_authorship IS
 'Internal service-recorded author identity; DB write role restriction and signed JWT enforcement are mandatory for production.';
