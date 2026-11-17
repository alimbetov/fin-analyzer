--liquibase formatted sql
--changeset fin-form:005 splitStatements:true
-- Input policies have their own immutable version and are not presentation formats.
ALTER TABLE fin_lib.lib_report_definition ADD COLUMN validation_version INT NOT NULL DEFAULT 1;
ALTER TABLE fin_lib.lib_report_definition ADD CONSTRAINT ck_lib_report_validation_version CHECK(validation_version > 0);

CREATE TABLE fin_lib.lib_report_metric_validation (
 id UUID PRIMARY KEY,
 report_id UUID NOT NULL,
 metric_id UUID NOT NULL,
 validation_version INT NOT NULL CHECK(validation_version > 0),
 allow_missing BOOLEAN NOT NULL,
 allow_not_applicable BOOLEAN NOT NULL,
 sign_rule VARCHAR(32) NOT NULL CHECK(sign_rule IN
  ('ANY','NON_NEGATIVE','POSITIVE','NON_POSITIVE','NEGATIVE','ZERO_ONLY','NON_ZERO')),
 min_value VARCHAR(96),
 max_value VARCHAR(96),
 max_precision INT NOT NULL CHECK(max_precision BETWEEN 1 AND 38),
 max_scale INT NOT NULL CHECK(max_scale BETWEEN 0 AND 12),
 CONSTRAINT fk_lib_validation_report FOREIGN KEY(report_id) REFERENCES fin_lib.lib_report_definition(id),
 CONSTRAINT fk_lib_validation_metric FOREIGN KEY(metric_id) REFERENCES fin_lib.lib_metric_definition(id),
 CONSTRAINT uq_lib_report_validation_rule UNIQUE(report_id,validation_version,metric_id)
);
CREATE INDEX idx_lib_report_validation_version
 ON fin_lib.lib_report_metric_validation(report_id,validation_version);

ALTER TABLE fin_intake.lib_report_revision ADD COLUMN validation_version INT NOT NULL DEFAULT 1;
ALTER TABLE fin_intake.lib_report_revision ADD COLUMN validation_sha256 VARCHAR(64);
