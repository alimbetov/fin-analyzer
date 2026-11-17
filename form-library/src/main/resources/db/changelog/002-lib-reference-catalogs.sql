--liquibase formatted sql
--changeset fin-form:002 splitStatements:true
-- New standalone catalog tables. No changes to original FIN fact/methodological schema.
CREATE TABLE fin_lib.lib_currency (
 code VARCHAR(3) PRIMARY KEY,
 iso_numeric VARCHAR(3) NOT NULL,
 minor_units INT NOT NULL CHECK(minor_units BETWEEN 0 AND 3),
 status VARCHAR(32) NOT NULL CHECK(status IN ('ACTIVE','INACTIVE')),
 CONSTRAINT uq_lib_currency_numeric UNIQUE(iso_numeric)
);
CREATE TABLE fin_lib.lib_currency_translation (
 id UUID PRIMARY KEY,
 currency_code VARCHAR(3) NOT NULL,
 locale VARCHAR(12) NOT NULL CHECK(locale IN ('ru','kk','en','zh-Hans')),
 title VARCHAR(255),
 description VARCHAR(1000),
 review_status VARCHAR(48) NOT NULL,
 CONSTRAINT fk_lib_currency_tr FOREIGN KEY(currency_code) REFERENCES fin_lib.lib_currency(code),
 CONSTRAINT uq_lib_currency_locale UNIQUE(currency_code,locale)
);
CREATE TABLE fin_lib.lib_period_type (
 code VARCHAR(8) PRIMARY KEY,
 sort_order INT NOT NULL UNIQUE CHECK(sort_order>0),
 iso_calendar VARCHAR(32) NOT NULL,
 status VARCHAR(32) NOT NULL,
 CONSTRAINT ck_lib_period_code CHECK(code IN ('D','W','DEC','M','Q','H','Y'))
);
CREATE TABLE fin_lib.lib_period_type_translation (
 id UUID PRIMARY KEY,
 period_type VARCHAR(8) NOT NULL,
 locale VARCHAR(12) NOT NULL CHECK(locale IN ('ru','kk','en','zh-Hans')),
 title VARCHAR(255) NOT NULL,
 description VARCHAR(1000) NOT NULL,
 CONSTRAINT fk_lib_period_tr FOREIGN KEY(period_type) REFERENCES fin_lib.lib_period_type(code),
 CONSTRAINT uq_lib_period_locale UNIQUE(period_type,locale)
);
CREATE TABLE fin_lib.lib_form_translation (
 id UUID PRIMARY KEY,
 form_version_id UUID NOT NULL,
 locale VARCHAR(12) NOT NULL CHECK(locale IN ('ru','kk','en','zh-Hans')),
 title VARCHAR(255),
 description VARCHAR(1500),
 review_status VARCHAR(48) NOT NULL,
 CONSTRAINT fk_lib_form_tr FOREIGN KEY(form_version_id) REFERENCES fin_lib.lib_form_version(id),
 CONSTRAINT uq_lib_form_locale UNIQUE(form_version_id,locale)
);
CREATE TABLE fin_lib.lib_metric_definition (
 id UUID PRIMARY KEY,
 metric_code VARCHAR(160) NOT NULL UNIQUE,
 metric_kind VARCHAR(32) NOT NULL CHECK(metric_kind IN ('SOURCE_FIELD','ANALYTICAL_OUTPUT')),
 form_code VARCHAR(128),
 source_field_code VARCHAR(128),
 unit_policy VARCHAR(64) NOT NULL,
 qualification_state VARCHAR(48) NOT NULL,
 CONSTRAINT ck_lib_metric_source CHECK (
  (metric_kind='SOURCE_FIELD' AND form_code IS NOT NULL AND source_field_code IS NOT NULL)
  OR (metric_kind='ANALYTICAL_OUTPUT' AND form_code IS NULL AND source_field_code IS NULL)
 ),
 CONSTRAINT fk_lib_metric_form FOREIGN KEY(form_code) REFERENCES fin_lib.lib_form_definition(form_code)
);
CREATE TABLE fin_lib.lib_metric_translation (
 id UUID PRIMARY KEY,
 metric_id UUID NOT NULL,
 locale VARCHAR(12) NOT NULL CHECK(locale IN ('ru','kk','en','zh-Hans')),
 title VARCHAR(255),
 description VARCHAR(1000),
 review_status VARCHAR(48) NOT NULL,
 CONSTRAINT fk_lib_metric_tr FOREIGN KEY(metric_id) REFERENCES fin_lib.lib_metric_definition(id),
 CONSTRAINT uq_lib_metric_locale UNIQUE(metric_id,locale)
);
ALTER TABLE fin_intake.lib_form_instance ADD COLUMN period_start DATE;
ALTER TABLE fin_intake.lib_form_instance ADD COLUMN period_end_exclusive DATE;
ALTER TABLE fin_intake.lib_form_instance ADD CONSTRAINT fk_lib_instance_currency
  FOREIGN KEY(currency) REFERENCES fin_lib.lib_currency(code);
ALTER TABLE fin_intake.lib_form_instance ADD CONSTRAINT fk_lib_instance_period_type
  FOREIGN KEY(period_type) REFERENCES fin_lib.lib_period_type(code);
-- Existing F02 seed can have tri-language labels; do not re-issue approved semantics.
ALTER TABLE fin_lib.lib_form_field_translation ADD COLUMN review_status VARCHAR(48) NOT NULL DEFAULT 'LEGACY_UNREVIEWED';
