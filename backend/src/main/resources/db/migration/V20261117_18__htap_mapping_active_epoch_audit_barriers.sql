-- W1.2.A: prevent unaudited active-pointer delete/scope moves and missing
-- catalog-epoch/activation journal evidence, including direct SQL writes.
-- A controlled REVOKE/DEACTIVATE protocol is a separate future design.
CREATE FUNCTION fin_calc.htap_mapping_active_write_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='DELETE' THEN
    RAISE EXCEPTION 'FIN_HTAP_MAPPING_ACTIVE_DELETE_FORBIDDEN';
  END IF;
  IF TG_OP='UPDATE' AND
     (NEW.organization_id<>OLD.organization_id
      OR NEW.form_code<>OLD.form_code
      OR NEW.form_version<>OLD.form_version
      OR NEW.catalog_epoch<=OLD.catalog_epoch) THEN
    RAISE EXCEPTION 'FIN_HTAP_MAPPING_ACTIVE_SCOPE_OR_EPOCH_INVALID';
  END IF;
  IF NOT EXISTS (
    SELECT 1 FROM fin_calc.htap_mapping_review r
    WHERE r.organization_id=NEW.organization_id
      AND r.mapping_id=NEW.mapping_id
      AND r.decision='APPROVED') THEN
    RAISE EXCEPTION 'FIN_HTAP_MAPPING_REVIEW_REQUIRED';
  END IF;
  RETURN NEW;
END
$$;
CREATE TRIGGER trg_htap_mapping_active_write_guard
BEFORE INSERT OR UPDATE OR DELETE ON fin_calc.htap_mapping_active
FOR EACH ROW EXECUTE FUNCTION fin_calc.htap_mapping_active_write_guard();

CREATE FUNCTION fin_calc.htap_mapping_active_audit_commit_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM fin_calc.htap_mapping_activation_journal j
    WHERE j.organization_id=NEW.organization_id
      AND j.form_code=NEW.form_code AND j.form_version=NEW.form_version
      AND j.mapping_id=NEW.mapping_id
      AND j.mapping_sha256=NEW.mapping_sha256
      AND j.catalog_epoch=NEW.catalog_epoch) THEN
    RAISE EXCEPTION 'FIN_HTAP_MAPPING_ACTIVE_AUDIT_MISSING';
  END IF;
  RETURN NULL;
END
$$;
CREATE CONSTRAINT TRIGGER trg_htap_mapping_active_audit_commit
AFTER INSERT OR UPDATE ON fin_calc.htap_mapping_active
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
EXECUTE FUNCTION fin_calc.htap_mapping_active_audit_commit_guard();

CREATE FUNCTION fin_calc.htap_catalog_epoch_write_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP='DELETE' THEN
    RAISE EXCEPTION 'FIN_HTAP_CATALOG_EPOCH_DELETE_FORBIDDEN';
  END IF;
  IF TG_OP='INSERT' THEN
    IF NEW.catalog_epoch<>1 THEN
      RAISE EXCEPTION 'FIN_HTAP_CATALOG_EPOCH_INITIAL_INVALID';
    END IF;
  ELSIF NEW.organization_id<>OLD.organization_id
        OR NEW.catalog_epoch<OLD.catalog_epoch
        OR NEW.catalog_epoch>OLD.catalog_epoch+1 THEN
    RAISE EXCEPTION 'FIN_HTAP_CATALOG_EPOCH_NONMONOTONIC';
  END IF;
  RETURN NEW;
END
$$;
CREATE TRIGGER trg_htap_catalog_epoch_write_guard
BEFORE INSERT OR UPDATE OR DELETE ON fin_calc.htap_catalog_epoch
FOR EACH ROW EXECUTE FUNCTION fin_calc.htap_catalog_epoch_write_guard();

CREATE FUNCTION fin_calc.htap_catalog_epoch_audit_commit_guard()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE last_epoch BIGINT;
BEGIN
  SELECT max(catalog_epoch) INTO last_epoch
    FROM fin_calc.htap_mapping_activation_journal
   WHERE organization_id=NEW.organization_id;
  IF last_epoch IS DISTINCT FROM NEW.catalog_epoch THEN
    RAISE EXCEPTION 'FIN_HTAP_CATALOG_EPOCH_AUDIT_MISSING';
  END IF;
  RETURN NULL;
END
$$;
CREATE CONSTRAINT TRIGGER trg_htap_catalog_epoch_audit_commit
AFTER INSERT OR UPDATE ON fin_calc.htap_catalog_epoch
DEFERRABLE INITIALLY DEFERRED FOR EACH ROW
EXECUTE FUNCTION fin_calc.htap_catalog_epoch_audit_commit_guard();
