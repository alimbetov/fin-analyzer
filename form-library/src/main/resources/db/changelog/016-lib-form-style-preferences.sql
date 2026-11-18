--liquibase formatted sql
--changeset fin-form:016 splitStatements:true
-- R17.P: UI preferences only. Financial snapshots and fin_intake are untouched.
CREATE SCHEMA IF NOT EXISTS fin_ui;

CREATE TABLE fin_ui.lib_style_definition (
 style_code VARCHAR(32) PRIMARY KEY,
 display_name VARCHAR(100) NOT NULL
);
INSERT INTO fin_ui.lib_style_definition(style_code,display_name) VALUES ('CLASSIC','Classic');
INSERT INTO fin_ui.lib_style_definition(style_code,display_name) VALUES ('EXCEL','Excel');
INSERT INTO fin_ui.lib_style_definition(style_code,display_name) VALUES ('DASHBOARD','Dashboard');

CREATE TABLE fin_ui.lib_user_style_default (
 user_id UUID PRIMARY KEY,
 style_code VARCHAR(32) NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 CONSTRAINT fk_ui_default_style FOREIGN KEY(style_code)
   REFERENCES fin_ui.lib_style_definition(style_code)
);
CREATE TABLE fin_ui.lib_user_form_style_preference (
 user_id UUID NOT NULL,
 form_code VARCHAR(128) NOT NULL,
 style_code VARCHAR(32) NOT NULL,
 updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
 PRIMARY KEY(user_id,form_code),
 CONSTRAINT fk_ui_form_preference_form FOREIGN KEY(form_code)
   REFERENCES fin_lib.lib_form_definition(form_code),
 CONSTRAINT fk_ui_form_preference_style FOREIGN KEY(style_code)
   REFERENCES fin_ui.lib_style_definition(style_code)
);
CREATE INDEX idx_ui_form_preference_form ON fin_ui.lib_user_form_style_preference(form_code);
