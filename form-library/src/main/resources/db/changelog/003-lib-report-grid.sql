--liquibase formatted sql
--changeset fin-form:003 splitStatements:true
-- Universal report-table generator: display axes never identify a financial observation.
CREATE TABLE fin_lib.lib_report_definition (
 id UUID PRIMARY KEY,
 organization_id UUID NOT NULL,
 report_code VARCHAR(96) NOT NULL,
 title VARCHAR(255) NOT NULL,
 currency_code VARCHAR(3) NOT NULL,
 period_type VARCHAR(8) NOT NULL,
 time_axis VARCHAR(8) NOT NULL CHECK (time_axis IN ('ROWS','COLUMNS')),
 layout_version INT NOT NULL DEFAULT 1 CHECK (layout_version > 0),
 latest_revision_no INT NOT NULL DEFAULT 0 CHECK (latest_revision_no >= 0),
 current_revision_id UUID,
 row_version BIGINT NOT NULL DEFAULT 0,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT fk_lib_report_currency FOREIGN KEY(currency_code) REFERENCES fin_lib.lib_currency(code),
 CONSTRAINT fk_lib_report_period_type FOREIGN KEY(period_type) REFERENCES fin_lib.lib_period_type(code),
 CONSTRAINT uq_lib_report_code UNIQUE(organization_id,report_code)
);
CREATE TABLE fin_lib.lib_report_metric (
 id UUID PRIMARY KEY,
 report_id UUID NOT NULL,
 metric_id UUID NOT NULL,
 display_order INT NOT NULL CHECK (display_order > 0),
 CONSTRAINT fk_lib_report_metric_report FOREIGN KEY(report_id) REFERENCES fin_lib.lib_report_definition(id),
 CONSTRAINT fk_lib_report_metric_catalog FOREIGN KEY(metric_id) REFERENCES fin_lib.lib_metric_definition(id),
 CONSTRAINT uq_lib_report_metric_id UNIQUE(report_id,metric_id),
 CONSTRAINT uq_lib_report_metric_order UNIQUE(report_id,display_order)
);
CREATE TABLE fin_lib.lib_report_period (
 id UUID PRIMARY KEY,
 report_id UUID NOT NULL,
 period_code VARCHAR(32) NOT NULL,
 start_inclusive DATE NOT NULL,
 end_exclusive DATE NOT NULL,
 display_order INT NOT NULL CHECK (display_order > 0),
 CONSTRAINT fk_lib_report_period FOREIGN KEY(report_id) REFERENCES fin_lib.lib_report_definition(id),
 CONSTRAINT uq_lib_report_period_code UNIQUE(report_id,period_code),
 CONSTRAINT uq_lib_report_period_order UNIQUE(report_id,display_order),
 CONSTRAINT ck_lib_report_period_bounds CHECK(start_inclusive < end_exclusive)
);
CREATE TABLE fin_intake.lib_report_revision (
 id UUID PRIMARY KEY,
 report_id UUID NOT NULL,
 revision_no INT NOT NULL CHECK(revision_no > 0),
 canonical_content_sha256 VARCHAR(64) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL,
 created_by VARCHAR(255) NOT NULL,
 change_reason VARCHAR(255) NOT NULL,
 source_kind VARCHAR(32) NOT NULL CHECK (source_kind IN ('MANUAL','CLIPBOARD','API')),
 qualification_state VARCHAR(48) NOT NULL DEFAULT 'UNQUALIFIED_DRAFT',
 CONSTRAINT fk_lib_report_revision_report FOREIGN KEY(report_id) REFERENCES fin_lib.lib_report_definition(id),
 CONSTRAINT uq_lib_report_revision UNIQUE(report_id,revision_no)
);
ALTER TABLE fin_lib.lib_report_definition ADD CONSTRAINT fk_lib_report_current_revision
 FOREIGN KEY(current_revision_id) REFERENCES fin_intake.lib_report_revision(id);
CREATE TABLE fin_intake.lib_report_revision_cell (
 id UUID PRIMARY KEY,
 revision_id UUID NOT NULL,
 metric_id UUID NOT NULL,
 period_code VARCHAR(32) NOT NULL,
 value_state VARCHAR(24) NOT NULL CHECK(value_state IN ('PRESENT','NOT_APPLICABLE')),
 decimal_value VARCHAR(96),
 CONSTRAINT fk_lib_report_cell_revision FOREIGN KEY(revision_id) REFERENCES fin_intake.lib_report_revision(id),
 CONSTRAINT fk_lib_report_cell_metric FOREIGN KEY(metric_id) REFERENCES fin_lib.lib_metric_definition(id),
 CONSTRAINT uq_lib_report_cell UNIQUE(revision_id,metric_id,period_code),
 CONSTRAINT ck_lib_report_cell_value CHECK(
   (value_state='PRESENT' AND decimal_value IS NOT NULL)
   OR (value_state='NOT_APPLICABLE' AND decimal_value IS NULL))
);
CREATE INDEX idx_lib_report_cell_lookup ON fin_intake.lib_report_revision_cell(revision_id,metric_id,period_code);
CREATE TABLE fin_intake.lib_report_command_receipt (
 id UUID PRIMARY KEY,
 report_id UUID NOT NULL,
 idempotency_key VARCHAR(200) NOT NULL,
 request_sha256 VARCHAR(64) NOT NULL,
 revision_id UUID NOT NULL,
 CONSTRAINT fk_lib_report_receipt_report FOREIGN KEY(report_id) REFERENCES fin_lib.lib_report_definition(id),
 CONSTRAINT fk_lib_report_receipt_revision FOREIGN KEY(revision_id) REFERENCES fin_intake.lib_report_revision(id),
 CONSTRAINT uq_lib_report_idempotency UNIQUE(report_id,idempotency_key)
);
