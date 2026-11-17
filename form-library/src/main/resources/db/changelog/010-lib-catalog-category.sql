--liquibase formatted sql
--changeset fin-form:010 splitStatements:true
-- R14: persist navigation categories in fin_lib; never store financial facts here.
CREATE TABLE fin_lib.lib_catalog_category (
  code VARCHAR(96) PRIMARY KEY,
  parent_code VARCHAR(96),
  category_type VARCHAR(24) NOT NULL
    CHECK (category_type IN ('ROOT','DOMAIN','SECTOR','PURPOSE')),
  sort_order INT NOT NULL CHECK (sort_order > 0),
  lifecycle_status VARCHAR(32) NOT NULL DEFAULT 'DRAFT_UNREVIEWED'
    CHECK (lifecycle_status IN ('DRAFT_UNREVIEWED','ACTIVE','ARCHIVED')),
  CONSTRAINT fk_lib_catalog_category_parent FOREIGN KEY(parent_code)
    REFERENCES fin_lib.lib_catalog_category(code),
  CONSTRAINT ck_lib_catalog_parent CHECK ((category_type='ROOT' AND parent_code IS NULL)
    OR (category_type<>'ROOT' AND parent_code IS NOT NULL))
);
CREATE INDEX idx_lib_catalog_category_parent
  ON fin_lib.lib_catalog_category(parent_code,sort_order,code);

CREATE TABLE fin_lib.lib_catalog_category_translation (
  id UUID PRIMARY KEY,
  category_code VARCHAR(96) NOT NULL,
  locale VARCHAR(16) NOT NULL CHECK(locale IN ('ru','kk','en','zh-Hans')),
  title VARCHAR(255) NOT NULL,
  purpose VARCHAR(1000) NOT NULL,
  description VARCHAR(1500) NOT NULL,
  review_status VARCHAR(32) NOT NULL DEFAULT 'DRAFT_UNREVIEWED'
    CHECK(review_status IN ('DRAFT_UNREVIEWED','PENDING_TRANSLATION','APPROVED')),
  CONSTRAINT fk_lib_catalog_translation_category FOREIGN KEY(category_code)
    REFERENCES fin_lib.lib_catalog_category(code),
  CONSTRAINT uq_lib_catalog_category_locale UNIQUE(category_code,locale)
);
CREATE INDEX idx_lib_catalog_translation_locale
  ON fin_lib.lib_catalog_category_translation(locale,category_code);

CREATE TABLE fin_lib.lib_catalog_category_form (
  category_code VARCHAR(96) NOT NULL,
  form_id UUID NOT NULL,
  association_role VARCHAR(32) NOT NULL
    CHECK(association_role IN ('PRIMARY','DOMAIN','SECTOR','CORE','SUPPORTING','EVIDENCE')),
  sort_order INT NOT NULL CHECK(sort_order > 0),
  PRIMARY KEY(category_code,form_id),
  CONSTRAINT fk_lib_catalog_form_category FOREIGN KEY(category_code)
    REFERENCES fin_lib.lib_catalog_category(code),
  CONSTRAINT fk_lib_catalog_form_definition FOREIGN KEY(form_id)
    REFERENCES fin_lib.lib_form_definition(id)
);
CREATE INDEX idx_lib_catalog_category_form_form
  ON fin_lib.lib_catalog_category_form(form_id,category_code);

-- A complete, immutable seed input fingerprint prevents silently mixing catalog
-- editions after a partial boot or a different application binary is deployed.
CREATE TABLE fin_lib.lib_catalog_seed_manifest (
  catalog_code VARCHAR(48) PRIMARY KEY,
  source_sha256 VARCHAR(64) NOT NULL,
  seeded_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX idx_lib_form_instance_project
  ON fin_intake.lib_form_instance(organization_id,project_id,form_id,period_type,period_code);
CREATE INDEX idx_lib_revision_by_instance
  ON fin_intake.lib_form_revision(instance_id,revision_no);
