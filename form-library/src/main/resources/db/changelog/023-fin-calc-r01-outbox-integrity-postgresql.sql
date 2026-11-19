--liquibase formatted sql
--changeset fin-calc:023a dbms:postgresql splitStatements:true
-- CALC-R0.1 durability: delivery metadata can change; identity/payload cannot.
-- Retain poison messages with retry metadata and operator-visible quarantine.
-- No new approval path, no producer/source S2 claim, no modification of 001-022.
ALTER TABLE fin_calc.calc_outbox_event
  ADD COLUMN failure_count INT NOT NULL DEFAULT 0
    CHECK (failure_count >= 0),
  ADD COLUMN quarantined_at TIMESTAMPTZ;

CREATE INDEX idx_calc_outbox_eligible_r01
  ON fin_calc.calc_outbox_event(next_attempt_at, created_at, id)
  WHERE processed_at IS NULL AND quarantined_at IS NULL
    AND producer='FACT1B_F02_UAT' AND event_type='FACTSET_QUALIFIED';

--changeset fin-calc:023b dbms:postgresql splitStatements:false
CREATE FUNCTION fin_calc.reject_outbox_identity_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF ROW(NEW.id, NEW.organization_id, NEW.scope_key, NEW.producer,
         NEW.producer_event_id, NEW.event_type, NEW.payload_jsonb, NEW.created_at)
       IS DISTINCT FROM
     ROW(OLD.id, OLD.organization_id, OLD.scope_key, OLD.producer,
         OLD.producer_event_id, OLD.event_type, OLD.payload_jsonb, OLD.created_at)
  THEN
    RAISE EXCEPTION 'FIN_CALC_OUTBOX_IDENTITY_IMMUTABLE' USING ERRCODE='23514';
  END IF;
  RETURN NEW;
END;
$$;

--changeset fin-calc:023c dbms:postgresql splitStatements:true
CREATE TRIGGER trg_calc_outbox_identity_immutable
 BEFORE UPDATE ON fin_calc.calc_outbox_event
 FOR EACH ROW EXECUTE FUNCTION fin_calc.reject_outbox_identity_mutation();
CREATE TRIGGER trg_calc_outbox_no_delete
 BEFORE DELETE ON fin_calc.calc_outbox_event
 FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_calc_outbox_no_truncate
 BEFORE TRUNCATE ON fin_calc.calc_outbox_event
 FOR EACH STATEMENT EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
