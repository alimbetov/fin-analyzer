-- FIN-MR-R2.2 preparation: prevent changing the identity of an active
-- method pointer via UPDATE. V20261117_02 only checked pointer_version and
-- the target config moderation, not immutable (method_code,variant_code).
-- Rebinding a pointer to another family/variant can otherwise erase the old
-- activation without a corresponding deactivation event.
-- Remediation is additive and deliberately does not implement a new permit.
CREATE FUNCTION fin_calc.reject_active_config_scope_update()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.method_code IS DISTINCT FROM OLD.method_code OR
       NEW.variant_code IS DISTINCT FROM OLD.variant_code THEN
        RAISE EXCEPTION 'FIN_METHOD_ACTIVE_CONFIG_SCOPE_IMMUTABLE'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

-- PostgreSQL invokes same-kind row triggers in name order; this guard runs
-- before the old CAS checker, so the exact scope violation is observable.
CREATE TRIGGER trg_a_fin_method_active_scope_immutable
BEFORE UPDATE ON fin_calc.method_active_config
FOR EACH ROW EXECUTE FUNCTION fin_calc.reject_active_config_scope_update();
