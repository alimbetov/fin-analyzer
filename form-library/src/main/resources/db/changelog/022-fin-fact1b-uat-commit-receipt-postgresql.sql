--liquibase formatted sql
--changeset fin-fact:022a dbms:postgresql splitStatements:true
-- FACT-1.B: additive TEST_ONLY provenance/decision receipt.
-- Production IAM/S2 is NOT enabled, historical 001-021 are unchanged.
ALTER TABLE fin_fact.fact_set ADD COLUMN initiated_by_user_id UUID;

CREATE TABLE fin_fact.factset_qualification_receipt (
 id UUID PRIMARY KEY,
 issuer_id VARCHAR(128) NOT NULL,
 decision_id UUID NOT NULL,
 factset_id UUID NOT NULL UNIQUE,
 organization_id UUID NOT NULL,
 project_id UUID NOT NULL,
 initiated_by_user_id UUID NOT NULL,
 scope_key CHAR(64) NOT NULL,
 candidate_factset_sha256 CHAR(64) NOT NULL,
 source_set_sha256 CHAR(64) NOT NULL,
 binding_sha256 CHAR(64) NOT NULL,
 qualification_epoch BIGINT NOT NULL CHECK (qualification_epoch>0),
 qualification_mode VARCHAR(32) NOT NULL CHECK (qualification_mode='TEST_QUALIFIED'),
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 CONSTRAINT uq_factset_qualification_decision UNIQUE (issuer_id,decision_id),
 CONSTRAINT fk_factset_receipt_exact_scope FOREIGN KEY
   (factset_id,organization_id,scope_key)
   REFERENCES fin_fact.fact_set(id,organization_id,scope_key)
);
CREATE INDEX idx_factset_receipt_project_user
 ON fin_fact.factset_qualification_receipt(project_id,initiated_by_user_id,created_at);

--changeset fin-fact:022b dbms:postgresql splitStatements:true
CREATE TRIGGER trg_factset_receipt_append_only
 BEFORE UPDATE OR DELETE ON fin_fact.factset_qualification_receipt
 FOR EACH ROW EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
CREATE TRIGGER trg_factset_receipt_no_truncate
 BEFORE TRUNCATE ON fin_fact.factset_qualification_receipt
 FOR EACH STATEMENT EXECUTE FUNCTION fin_fact.reject_immutable_mutation();
