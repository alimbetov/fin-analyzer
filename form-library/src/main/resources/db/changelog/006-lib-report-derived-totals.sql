--liquibase formatted sql
--changeset fin-form:006 splitStatements:true
-- Derived totals are presentation-only, versioned and never part of immutable input revisions.
ALTER TABLE fin_lib.lib_report_definition ADD COLUMN derived_version INT NOT NULL DEFAULT 1;
ALTER TABLE fin_lib.lib_report_definition ADD CONSTRAINT ck_lib_report_derived_version CHECK(derived_version > 0);
CREATE TABLE fin_lib.lib_report_derived_rule (
 id UUID PRIMARY KEY,
 report_id UUID NOT NULL,
 metric_id UUID NOT NULL,
 derived_version INT NOT NULL CHECK(derived_version > 0),
 operator VARCHAR(24) NOT NULL CHECK(operator IN ('SUM','LAST_VALUE')),
 visibility VARCHAR(24) NOT NULL CHECK(visibility='OUTPUT_ONLY'),
 completeness_policy VARCHAR(32) NOT NULL CHECK(completeness_policy='REQUIRE_ALL_PRESENT'),
 review_state VARCHAR(32) NOT NULL CHECK(review_state='UNQUALIFIED_DRAFT'),
 CONSTRAINT fk_lib_derived_report FOREIGN KEY(report_id) REFERENCES fin_lib.lib_report_definition(id),
 CONSTRAINT fk_lib_derived_metric FOREIGN KEY(metric_id) REFERENCES fin_lib.lib_metric_definition(id),
 CONSTRAINT uq_lib_derived_rule UNIQUE(report_id,derived_version,metric_id)
);
CREATE INDEX idx_lib_derived_lookup ON fin_lib.lib_report_derived_rule(report_id,derived_version);
