--liquibase formatted sql
--changeset fin-form:018 dbms:postgresql splitStatements:true
-- Profiles are append-only once created. 015 defines the shared reject function.
CREATE TRIGGER r19_import_profile_immutable BEFORE UPDATE OR DELETE
 ON fin_lib.lib_import_profile_version FOR EACH ROW
 EXECUTE FUNCTION fin_lib.r16_reject_contract_mutation();
