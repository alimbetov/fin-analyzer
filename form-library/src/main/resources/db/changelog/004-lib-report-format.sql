--liquibase formatted sql
--changeset fin-form:004 splitStatements:true
-- Presentation-only, versioned per metric. Existing immutable data revisions remain intact.
ALTER TABLE fin_lib.lib_report_definition ADD COLUMN format_version INT NOT NULL DEFAULT 1;
ALTER TABLE fin_lib.lib_report_definition ADD CONSTRAINT ck_lib_report_format_version CHECK(format_version > 0);
CREATE TABLE fin_lib.lib_report_metric_format (
 id UUID PRIMARY KEY,
 report_id UUID NOT NULL,
 metric_id UUID NOT NULL,
 format_version INT NOT NULL CHECK(format_version > 0),
 format_kind VARCHAR(32) NOT NULL CHECK(format_kind IN ('MONEY','DECIMAL','INTEGER','PERCENT_POINTS','RATIO_PERCENT')),
 decimal_places INT NOT NULL CHECK(decimal_places BETWEEN 0 AND 12),
 scale_divisor INT NOT NULL CHECK(scale_divisor IN (1,1000,1000000)),
 rounding_mode VARCHAR(16) NOT NULL CHECK(rounding_mode IN ('HALF_EVEN','HALF_UP','DOWN')),
 grouping_enabled BOOLEAN NOT NULL,
 negative_style VARCHAR(16) NOT NULL CHECK(negative_style IN ('MINUS','PARENTHESES')),
 currency_display VARCHAR(16) NOT NULL CHECK(currency_display IN ('NONE','CODE')),
 CONSTRAINT fk_lib_metric_format_report FOREIGN KEY(report_id) REFERENCES fin_lib.lib_report_definition(id),
 CONSTRAINT fk_lib_metric_format_metric FOREIGN KEY(metric_id) REFERENCES fin_lib.lib_metric_definition(id),
 CONSTRAINT uq_lib_report_metric_format UNIQUE(report_id,format_version,metric_id)
);
CREATE INDEX idx_lib_report_format_version ON fin_lib.lib_report_metric_format(report_id,format_version);
