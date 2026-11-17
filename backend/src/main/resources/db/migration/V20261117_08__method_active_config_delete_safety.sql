-- FIN HTAP W0 / FIN-MR-R2.2 safety preparation.
-- Historical method_active_config allowed DELETE without inserting an
-- activation/audit event (the V20261117_02 triggers cover INSERT/UPDATE only).
-- A silent delete would erase the active pointer while preserving no
-- explanation of the deactivation. Fail closed until a separately reviewed
-- immutable, CAS-guarded and authenticated revocation ledger is introduced.
-- This migration is additive and never mutates historical reviews/configs.
CREATE FUNCTION fin_calc.reject_unaudited_method_deactivation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'FIN_METHOD_ACTIVE_CONFIG_DELETE_REQUIRES_AUDITED_REVOCATION'
        USING ERRCODE = '23514';
END;
$$;

CREATE TRIGGER trg_fin_method_active_no_unaudited_delete
BEFORE DELETE ON fin_calc.method_active_config
FOR EACH ROW EXECUTE FUNCTION fin_calc.reject_unaudited_method_deactivation();
