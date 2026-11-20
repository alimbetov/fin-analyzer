--liquibase formatted sql
--changeset fin-a05:058a dbms:postgresql splitStatements:true
-- A05-R1.0: a legal agreement reference is scoped by creditor,
-- organization and project; loanBusinessKey alone does NOT prevent
-- two C05 instances from registering the same external loan agreement.
-- Additive hardening only; immutable migration 056 is not rewritten.
CREATE UNIQUE INDEX uq_a05_loan_agreement_scope
  ON fin_intake.a05_loan_identity(
    organization_id,project_id,creditor_ref,agreement_ref);
